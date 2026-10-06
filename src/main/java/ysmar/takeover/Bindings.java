package ysmar.takeover;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.ModelEntry;
import ysmar.Text;
import ysmar.YsmArConfig;
import ysmar.YsmArModels;
import ysmar.core.Attributes;
import ysmar.core.ModelData;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Which of our models stands in for which model object of Yes Steve Model 2.6.5. A binding belongs to one model
 * object and one texture name; it is found by the identity of the model object, which is only held weakly. A
 * binding that cannot be made keeps its reason and is not tried again: a model reload in 2.6.5 creates new model
 * objects, and with them new bindings. Model ids and texture names come from 2.6.5 and may come from a server:
 * they are cleaned before they are written anywhere, and their number per model object is limited. What a name
 * costs is limited as well: the outcome for a model id and texture name is logged once per session, and the file
 * of a model id is not looked at again within a second. Render thread only.
 */
public final class Bindings {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final int MAX_RECORDS = 256;
    /**
     * Bindings (texture names) kept per model object. A new name takes the place of the one used longest ago, unless
     * that one was used within the last second: then all of them are in use, and the new name stays with 2.6.5.
     */
    public static final int MAX_BINDINGS = 64;
    /** Outcomes (by kind, model id and texture name) that are logged in one session; after that none is. */
    public static final int MAX_LOGGED = 256;
    private static final long IN_USE_NANOS = 1_000_000_000L;
    private static final long FILE_CHECK_NANOS = 1_000_000_000L;
    private static final int MAX_FILE_CHECKS = 8;

    public enum State { LOADING, READY, REFUSED }

    /** What 2.6.5 says about one of its model objects. All of it is read through this, by reflection in the game. */
    public interface Source {
        /** The bone names in the order of 2.6.5. */
        Bones bones(Object model) throws ReflectiveOperationException;

        /** What the identity guards compare; members that cannot be read stay unset. animated may be null. */
        ContentGuards.Theirs content(Object model, Object animated, String[] names);

        /** Width and height of the texture 2.6.5 draws with, or null when that cannot be asked. */
        int[] textureSize(Object texture);
    }

    public static final class Bones {
        public final String[] names;
        /** Why there are no names, else null. */
        public final String problem;

        public Bones(String[] names, String problem) {
            this.names = names;
            this.problem = problem;
        }
    }

    public static final class Binding {
        final String modelId;
        final String textureName;
        State state = State.LOADING;
        /** Why the model stays with 2.6.5, while REFUSED. */
        String reason;
        Path file;
        String textureKey;
        ModelEntry entry;
        boolean retried;
        /** permutation[sorted bake index] = bone index of 2.6.5. */
        int[] permutation;
        float[] attributes;
        /** The live values of the render that is armed (see LateRead.Armed). */
        float[] liveCopy;
        int bones;
        /** Bones with the glow prefix of 2.6.5 (see AttributeMap.legacyGlow), by sorted bake index; null for none. */
        boolean[] glow;
        int glowBones;
        /** The outcome of the identity guards, for the status. */
        String identity;
        long fileSize = -1;
        long fileModified = -1;
        long lastUsed;
        long lastUsedNanos;
        /** Stands for every texture name beyond the ones kept (see MAX_BINDINGS). */
        boolean overflow;
        /** The first array of this binding the attribute check refused: bone index, slot, rule and value; else null. */
        String attributeRefusal;

        long takenOver;
        long fallbacks;
        TakeoverStats.Fallback lastFallback;
        int flaggedBones;
        /** Renders in which this mod wrote the bone_pivot_abs values, and for how many bones in the last of them. */
        long pivotRenders;
        int pivotBones;
        /** Renders drawn with bone values or a pose that changed after the visibility test, and Extract calls that took. */
        long lateBones;
        long latePose;
        long secondExtracts;

        Binding(String modelId, String textureName) {
            this.modelId = modelId;
            this.textureName = textureName;
        }

        /** The glowing bones a render of now is drawn with: none while takeover.legacy_glow is off. */
        public boolean[] glow() {
            return YsmArConfig.current().takeoverLegacyGlow ? glow : null;
        }

        public State state() {
            return state;
        }

        public String reason() {
            return reason;
        }
    }

    /** What is known about one model object of 2.6.5. */
    private static final class Record {
        final WeakReference<Object> model;
        final String[] boneNames;
        final String problem;
        final ArrayList<Binding> bindings = new ArrayList<>(2);
        /** Read once, with the first binding that gets as far as the guards. */
        ContentGuards.Theirs content;
        /** The file of the model id as it was when this model object was first bound to it. */
        long fileSize = -1;
        long fileModified = -1;
        /** What the file system said about the file of a model id, and when. */
        final HashMap<String, FileCheck> files = new HashMap<>(2);
        Binding overflow;

        Record(Object model, String[] boneNames, String problem) {
            this.model = new WeakReference<>(model);
            this.boneNames = boneNames;
            this.problem = problem;
        }
    }

    /** One look at the file of a model id: why it cannot be used, or the file and its stamp. */
    private static final class FileCheck {
        long at;
        String refusal;
        Path file;
        /** Null when the id names something that is not a regular file; the model cache says so in its own words. */
        YsmArModels.FileStamp stamp;
        long size = -1;
        long modified = -1;
    }

    private final Path customDirectory;
    private Path customReal;
    private final ArrayList<Record> records = new ArrayList<>();
    private Record last;
    private long clock;
    private final LongSupplier nanos;
    /** Kept over clear(): the lines of a session, not of a reload. */
    private final HashSet<String> logged = new HashSet<>();
    private long created;
    private long fileChecks;
    private long logLines;

    public Bindings(Path customDirectory) {
        this(customDirectory, System::nanoTime);
    }

    /** nanos: the clock for "within the last second"; a check puts its own here. */
    public Bindings(Path customDirectory, LongSupplier nanos) {
        this.customDirectory = customDirectory;
        this.nanos = nanos;
    }

    /**
     * The binding for this model object and texture name, made on first sight and moved on while it loads.
     * animated and texture are what 2.6.5 animates and draws this entity with; the identity guards read them once.
     */
    public Binding find(Source source, Object model, Object animated, String modelId, String textureName, Object texture)
            throws ReflectiveOperationException {
        Record record = last;
        if (record == null || !record.model.refersTo(model)) {
            record = null;
            for (int index = records.size() - 1; index >= 0; index--) {
                if (records.get(index).model.refersTo(model)) {
                    record = records.get(index);
                    break;
                }
            }
            if (record == null) {
                sweep();
                Bones bones = source.bones(model);
                record = new Record(model, bones.names, bones.problem);
                records.add(record);
            }
            last = record;
        }
        String id = modelId == null ? "" : modelId;
        String name = textureName == null ? "" : textureName;
        Binding binding = null;
        for (int index = 0; index < record.bindings.size(); index++) {
            Binding candidate = record.bindings.get(index);
            if (candidate.textureName.equals(name) && candidate.modelId.equals(id)) {
                binding = candidate;
                break;
            }
        }
        long now = nanos.getAsLong();
        if (binding == null) {
            if (record.bindings.size() >= MAX_BINDINGS) {
                int oldest = 0;
                for (int index = 1; index < record.bindings.size(); index++) {
                    if (record.bindings.get(index).lastUsed < record.bindings.get(oldest).lastUsed) {
                        oldest = index;
                    }
                }
                if (now - record.bindings.get(oldest).lastUsedNanos < IN_USE_NANOS) {
                    return overflow(record, id);
                }
                record.bindings.remove(oldest);
            }
            binding = create(record, id, name, now);
            record.bindings.add(binding);
        }
        binding.lastUsed = ++clock;
        binding.lastUsedNanos = now;
        if (binding.state == State.LOADING) {
            advance(source, record, binding, model, animated, texture);
        } else if (binding.state == State.READY && binding.entry.data() == null) {
            // Our model was taken out of use (an error while it was drawn, or the cache was emptied).
            refuse(binding, binding.entry.failure() == null ? "our model is gone" : binding.entry.failure());
        }
        return binding;
    }

    public void clear() {
        records.clear();
        last = null;
        customReal = null;
    }

    /** Bindings made since the start, the ones made again for a name that had been dropped included. */
    public long created() {
        return created;
    }

    /** How often the file system was asked about the file of a model id, here and in the model cache. */
    public long fileChecks() {
        return fileChecks;
    }

    /** Lines logged about bindings in this session; never more than MAX_LOGGED. */
    public long logLines() {
        return logLines;
    }

    /**
     * Keeps why the attribute check refused an array of this binding (the first time only) and logs it once per
     * session. detail holds numbers only: bone indices, a slot, a value.
     */
    public void attributesRefused(Binding binding, String detail) {
        if (binding.attributeRefusal != null) {
            return;
        }
        binding.attributeRefusal = detail;
        logOnce("attributes", binding, "ysm_ar: {} [texture {}]: the bone values of Yes Steve Model were refused by the attribute check: {}",
                label(binding.modelId, "(no id)"), label(binding.textureName, "(none)"), detail);
    }

    /** How many bindings are kept for the model object. */
    public int bindingCount(Object model) {
        for (Record record : records) {
            if (record.model.refersTo(model)) {
                return record.bindings.size();
            }
        }
        return 0;
    }

    /** One line per binding: model id, texture name, file, state or reason, counts. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Record record : records) {
            boolean alive = record.model.get() != null;
            List<Binding> listed = record.bindings;
            if (record.overflow != null) {
                listed = new ArrayList<>(record.bindings);
                listed.add(record.overflow);
            }
            for (Binding binding : listed) {
                StringBuilder line = new StringBuilder("binding ").append(label(binding.modelId, "(no id)"))
                        .append(" [texture ").append(binding.overflow ? "(every name beyond the " + MAX_BINDINGS + " kept)" : label(binding.textureName, "(none)"))
                        .append("] file=")
                        .append(binding.file == null ? "-" : Text.clean(customDirectory.toAbsolutePath().normalize().relativize(binding.file).toString()))
                        .append(": ");
                switch (binding.state) {
                    case LOADING -> line.append("LOADING");
                    case REFUSED -> line.append("STAYS WITH YSM: ").append(binding.reason);
                    case READY -> {
                        line.append("READY, ").append(binding.bones).append(" bones matched");
                        if (binding.glowBones > 0) {
                            line.append(", ").append(binding.glowBones).append(" of them named ").append(AttributeMap.GLOW_PREFIX)
                                    .append(YsmArConfig.current().takeoverLegacyGlow ? "* and drawn at full light"
                                            : "* (drawn at the light of the entity: takeover.legacy_glow=false)");
                        }
                    }
                }
                if (binding.identity != null) {
                    line.append("; ").append(binding.identity);
                }
                line.append("; taken over ").append(binding.takenOver).append("x");
                if (binding.pivotRenders > 0) {
                    line.append(", bone_pivot_abs values written in ").append(binding.pivotRenders).append(" of them (last: ")
                            .append(binding.pivotBones).append(" of ").append(binding.flaggedBones).append(" flagged bones visible)");
                }
                if (binding.lateBones > 0 || binding.latePose > 0 || binding.secondExtracts > 0) {
                    line.append(", changed after the visibility test: bone values ").append(binding.lateBones).append("x, pose ")
                            .append(binding.latePose).append("x, second Extracts ").append(binding.secondExtracts);
                }
                if (binding.fallbacks > 0 && binding.lastFallback != null) {
                    line.append(", left to YSM ").append(binding.fallbacks).append("x, last: ").append(binding.lastFallback.label);
                    if (binding.lastFallback == TakeoverStats.Fallback.SLOT_11) {
                        line.append(" on ").append(binding.flaggedBones).append(" bones");
                    }
                }
                if (binding.attributeRefusal != null) {
                    line.append(", attributes refused, first: ").append(binding.attributeRefusal);
                }
                if (!alive) {
                    line.append(" (model object no longer in use)");
                }
                lines.add(line.toString());
            }
        }
        return lines;
    }

    /** The one binding of a model object that answers for every name beyond the ones kept. Nothing is loaded for it. */
    private Binding overflow(Record record, String modelId) {
        if (record.overflow == null) {
            Binding binding = new Binding(modelId, "");
            binding.overflow = true;
            binding.state = State.REFUSED;
            binding.reason = "more than " + MAX_BINDINGS + " texture names of this model are in use at once";
            record.overflow = binding;
            logOnce("overflow", binding, "ysm_ar: {}: {}; the names beyond those stay with Yes Steve Model", label(modelId, "(no id)"), binding.reason);
        }
        return record.overflow;
    }

    /**
     * The only place this class logs: one line per session for this kind of line about this model id and texture
     * name, and none at all after MAX_LOGGED lines. A binding that was dropped and is made again says nothing new.
     */
    private void logOnce(String kind, Binding binding, String format, Object... arguments) {
        String key = kind + '\n' + Text.clean(binding.modelId) + '\n' + Text.clean(binding.textureName);
        if (!logged.contains(key) && logged.size() < MAX_LOGGED) {
            logged.add(key);
            logLines++;
            LOGGER.info(format, arguments);
        }
    }

    /** What the file system says about the file of the model id; asked at most once a second per id. */
    private FileCheck fileCheck(Record record, String modelId, long now) {
        FileCheck check = record.files.get(modelId);
        if (check != null && now - check.at < FILE_CHECK_NANOS) {
            return check;
        }
        check = new FileCheck();
        check.at = now;
        Path file = ModelFiles.resolve(customDirectory, modelId);
        if (file == null) {
            check.refusal = "the model id does not stay below the custom folder";
        } else {
            fileChecks++;
            try {
                if (customReal == null) {
                    customReal = customDirectory.toRealPath();
                }
                // Links and junctions: what the id names has to be a file inside the folder itself.
                Path real = file.toRealPath();
                if (!real.startsWith(customReal)) {
                    check.refusal = "the file of the model id lies outside the custom folder";
                } else {
                    BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
                    check.file = file;
                    check.size = attributes.size();
                    check.modified = attributes.lastModifiedTime().toMillis();
                    check.stamp = attributes.isRegularFile() ? new YsmArModels.FileStamp(real.toString(), check.size, check.modified) : null;
                }
            } catch (Exception missing) {
                check.refusal = "no such file below the custom folder";
            }
        }
        if (record.files.size() >= MAX_FILE_CHECKS) {
            record.files.clear();
        }
        record.files.put(modelId, check);
        return check;
    }

    private Binding create(Record record, String modelId, String textureName, long now) {
        created++;
        Binding binding = new Binding(modelId, textureName);
        if (YsmArConfig.current().takeoverDeny.contains(modelId)) {
            return refuse(binding, "listed in takeover.deny");
        }
        String refusal = ModelFiles.refusal(modelId);
        if (refusal != null) {
            return refuse(binding, refusal);
        }
        if (record.problem != null) {
            return refuse(binding, record.problem);
        }
        FileCheck check = fileCheck(record, modelId, now);
        if (check.refusal != null) {
            return refuse(binding, check.refusal);
        }
        Path file = check.file;
        binding.fileSize = check.size;
        binding.fileModified = check.modified;
        if (record.fileSize < 0) {
            record.fileSize = binding.fileSize;
            record.fileModified = binding.fileModified;
        }
        binding.file = file;
        binding.textureKey = textureName.isEmpty() ? null : textureName;
        // A texture name the file is already known not to have asks for no load: such names are free text.
        List<String> known = check.stamp == null ? List.of() : YsmArModels.knownTextureKeys(check.stamp);
        if (!known.isEmpty()) {
            String chosen = TextureKeys.choose(textureName, known);
            if (chosen == null) {
                return refuse(binding, "texture \"" + Text.clean(textureName) + "\" is not among the " + known.size() + " texture keys of the file");
            }
            binding.textureKey = chosen;
            binding.retried = true;
        }
        long before = YsmArModels.fileChecks();
        binding.entry = YsmArModels.request(file, binding.textureKey, false, check.stamp);
        fileChecks += YsmArModels.fileChecks() - before;
        return binding;
    }

    private void advance(Source source, Record record, Binding binding, Object model, Object animated, Object texture) {
        ModelEntry entry = binding.entry;
        ModelEntry.State state = entry.state();
        if (state == ModelEntry.State.LOADING) {
            return;
        }
        List<String> keys = entry.textureKeys();
        String chosen = TextureKeys.choose(binding.textureName, keys);
        ModelData data = entry.data();
        boolean rightTexture = data != null && chosen != null && chosen.equals(data.textureKey);
        if (!rightTexture && !keys.isEmpty()) {
            if (chosen == null) {
                refuse(binding, "texture \"" + Text.clean(binding.textureName) + "\" is not among the " + keys.size() + " texture keys of the file");
                return;
            }
            if (!binding.retried && !chosen.equals(binding.textureKey)) {
                // The name is no key, but the file has one texture only: that one.
                binding.retried = true;
                binding.textureKey = chosen;
                long before = YsmArModels.fileChecks();
                binding.entry = YsmArModels.request(binding.file, chosen);
                fileChecks += YsmArModels.fileChecks() - before;
                return;
            }
        }
        if (data == null) {
            refuse(binding, entry.failure() == null ? "the model could not be loaded" : entry.failure());
            return;
        }
        if (!rightTexture) {
            refuse(binding, "the file gives no texture for \"" + Text.clean(binding.textureName) + "\"");
            return;
        }
        BonePermutation permutation = BonePermutation.build(Arrays.asList(record.boneNames), entry.boneNames(), data.emptyBoneName);
        if (permutation.failure != null) {
            refuse(binding, permutation.failure);
            return;
        }
        if (YsmArConfig.current().takeoverIdentityStrict) {
            ContentGuards.Result result = guards(source, record, binding, model, animated, texture, data, permutation.indices);
            binding.identity = result.describe();
            if (result.refusal != null) {
                refuse(binding, result.refusal);
                return;
            }
        } else {
            binding.identity = "identity by names only (takeover.identity=names)";
        }
        binding.permutation = permutation.indices;
        binding.bones = permutation.indices.length;
        binding.attributes = new float[binding.bones * Attributes.PER_BONE];
        binding.liveCopy = new float[binding.bones * AttributeMap.LIVE_FLOATS];
        binding.glow = AttributeMap.legacyGlow(data.boneNames);
        binding.glowBones = AttributeMap.count(binding.glow);
        binding.state = State.READY;
        logOnce("ready", binding, "ysm_ar: take-over binding ready: {} [texture {}], {} bones matched by name, {} of them named {}*, {} vertices; {}",
                label(binding.modelId, "(no id)"), label(binding.textureName, "(none)"), binding.bones, binding.glowBones,
                AttributeMap.GLOW_PREFIX, data.emittedVertices(), binding.identity);
    }

    private static ContentGuards.Result guards(Source source, Record record, Binding binding, Object model, Object animated, Object texture,
                                               ModelData data, int[] permutation) {
        ContentGuards.Theirs theirs = record.content;
        if (theirs == null || theirs.chains == null && animated != null) {
            ContentGuards.Theirs read;
            try {
                read = source.content(model, animated, record.boneNames);
            } catch (RuntimeException unreadable) {
                read = null;
            }
            theirs = read == null ? new ContentGuards.Theirs() : read;
            record.content = theirs;
        }
        int[] size;
        try {
            size = texture == null ? null : source.textureSize(texture);
        } catch (RuntimeException unreadable) {
            size = null;
        }
        // Size and file stamp belong to this binding, the rest to the model object.
        ContentGuards.Theirs mine = new ContentGuards.Theirs();
        mine.pivots = theirs.pivots;
        mine.rotations = theirs.rotations;
        mine.chains = theirs.chains;
        mine.textureWidth = size == null || size.length != 2 ? -1 : size[0];
        mine.textureHeight = size == null || size.length != 2 ? -1 : size[1];
        mine.fileUnchanged = record.fileSize < 0 || binding.fileSize < 0 ? null
                : record.fileSize == binding.fileSize && record.fileModified == binding.fileModified;
        return ContentGuards.evaluate(mine, data, permutation);
    }

    private Binding refuse(Binding binding, String reason) {
        binding.state = State.REFUSED;
        binding.reason = reason;
        binding.permutation = null;
        binding.attributes = null;
        binding.liveCopy = null;
        binding.glow = null;
        logOnce("stays", binding, "ysm_ar: {} [texture {}] stays with Yes Steve Model: {}", label(binding.modelId, "(no id)"),
                label(binding.textureName, "(none)"), reason);
        return binding;
    }

    private static String label(String text, String empty) {
        return text.isEmpty() ? empty : Text.clean(text);
    }

    /** Forgets model objects that are gone, and the oldest ones when there are too many. */
    private void sweep() {
        records.removeIf(record -> record.model.get() == null);
        while (records.size() >= MAX_RECORDS) {
            records.remove(0);
        }
    }

    /** Position of every name in the list, for a source that has to find bones by name. */
    public static Map<String, Integer> index(String[] names) {
        Map<String, Integer> index = new HashMap<>(names.length * 2);
        for (int position = 0; position < names.length; position++) {
            if (names[position] != null) {
                index.putIfAbsent(names[position], position);
            }
        }
        return index;
    }
}
