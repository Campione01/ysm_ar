package ysmar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The settings of config/ysm_ar.properties, a UTF-8 text file (with or without a byte order mark). A file that is
 * there but cannot be read or is not UTF-8 switches the mod off for that load: what its enabled line says is not
 * known. A file that is missing and cannot be written leaves the defaults in place.
 */
public final class YsmArConfig {
    public static final String FILE_NAME = "ysm_ar.properties";
    private static final String MODEL_KEY = "standalone.model";
    private static final String LATE_READ_KEY = "takeover.late_read";

    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static volatile YsmArConfig current = new YsmArConfig(new Properties(), "", null, false);

    public final boolean enabled;
    public final boolean clientMeshes;
    public final boolean reverseMirrored;
    public final boolean arVersionCheck;
    /** Bake with the UV rules of the version the file was exported with (as 2.6.5 shows it), not always those of 29. */
    public final boolean bakeFileRules;
    public final String standaloneModel;
    public final String standaloneTexture;
    public final String standaloneEntity;
    public final boolean standaloneWave;
    /** NaN: take the scales of the model. */
    public final float standaloneScale;
    public final int statsIntervalSeconds;
    /** The take-over of models drawn by Yes Steve Model 2.6.5. */
    public final boolean takeoverEnabled;
    /** Model ids that always stay with the renderer of Yes Steve Model. */
    public final Set<String> takeoverDeny;
    /** Nanoseconds per frame for building bone meshes ahead of a model's first draw; 0: no limit. */
    public final long prewarmNanos;
    /** Test aid, pixels; not 0: our copy is drawn that far to the right and 2.6.5 keeps its own draw. */
    public final int takeoverSideBySide;
    /**
     * Models that use the Molang function bone_pivot_abs: false leaves them to Yes Steve Model, whose own draw
     * computes the values of that function; true takes them over and writes those values from our poses.
     */
    public final boolean takeoverProvidePivotAbs;
    /**
     * Bone values and pose of a taken-over model are read when Yes Steve Model itself reads them (the scale call
     * of its pre-render step), not at the visibility test before it. Whether the hook for that exists is decided
     * when the game starts; false at run time draws at the visibility test all the same.
     */
    public final boolean takeoverLateRead;
    /** A local file stands in for a model of Yes Steve Model only when its content passes the identity guards too. */
    public final boolean takeoverIdentityStrict;
    /** Bones named ysmGlow... of a taken-over model are drawn at full light, as Yes Steve Model 2.6.5 shows them. */
    public final boolean takeoverLegacyGlow;
    /**
     * Bone values of Yes Steve Model that are not finite numbers: false leaves a model that has one to Yes Steve
     * Model; true hands them to Extract, which leaves such a bone and what hangs below it out.
     */
    public final boolean takeoverPruneNonFinite;
    /** What was wrong with the file, or null. */
    public final String problem;

    private static final String TEMPLATE = """
            # ysm_ar: Yes Steve Model player models drawn through the compute path of Accelerated Rendering.
            # Re-read with /ysm_ar reload. Lines that start with # are comments.

            # false switches the whole mod off.
            enabled=true

            # How Accelerated Rendering keeps the bone meshes: server (uploaded once) or client (copied on every draw).
            mesh_type=server

            # Bones whose final transform mirrors (negative determinant): as_baked draws them as the official YSM
            # renderer does, which shows such a bone inside out; reversed keeps their outer faces visible.
            mirrored_bones=as_baked

            # true: work only with the Accelerated Rendering build this mod was written against (1.0.14-1.21.1-alpha).
            ar_version_check=true

            # How texture coordinates are rounded to texels when a model is baked: file applies the rules of the YSM
            # version the model file was exported with, which is what Yes Steve Model 2.6.5 shows; official applies
            # the newest rules to every file, as the open-source loader does (up to one texel row off on some quads).
            bake.uv_rules=file

            # Stand-alone test front-end: draws one .ysm model in place of every entity of one type.
            # Absolute path of the model file, written as it is (both / and \\ work); empty switches the front-end off.
            standalone.model=
            # Texture key of the model; empty takes the default texture of the model.
            standalone.texture=
            # Entity type that is replaced.
            standalone.entity=minecraft:husk
            # wave (a few bones swing) or rest.
            standalone.animation=wave
            # One scale for width and height; empty takes the scales of the model.
            standalone.scale=

            # Above 0: one log line every so many seconds with what was submitted.
            stats.interval_seconds=0

            # Take-over of the models Yes Steve Model 2.6.5 draws (players, Touhou Little Maid maids): their bodies go
            # through Accelerated Rendering, everything else stays with Yes Steve Model. false leaves every model to it.
            takeover.enabled=true
            # Model ids (as /ysm model set takes them) that always stay with Yes Steve Model, separated by ;
            takeover.deny=
            # Models whose animations use the Molang function bone_pivot_abs: exclude leaves them to Yes Steve
            # Model (its own draw computes the values of that function); provide takes them over as well and
            # writes those values into Yes Steve Model's array from the poses this mod computes.
            takeover.pivot_abs=exclude
            # true: bone values and pose of a taken-over model are read as late as Yes Steve Model reads them itself,
            # so that what another mod changes just before the draw is in the picture. false: read at the visibility
            # test, as before 0.2.2. The hook this needs is put in place when the game starts: a change from false to
            # true takes a restart.
            takeover.late_read=true
            # strict: a local model file is only used when its content agrees with what Yes Steve Model has loaded
            # (texture size, locator bone chains, bone pivots and rest rotations, file unchanged); names: bone names
            # and texture name alone decide, as before 0.2.2. /ysm_ar status shows the result per model.
            takeover.identity=strict
            # true: bones whose name starts with ysmGlow (eye highlights and the like) are drawn at full light whatever
            # the light around the entity, as Yes Steve Model 2.6.5 shows them; false: at the light of the entity.
            takeover.legacy_glow=true
            # A model whose animation gives a bone a value that is no finite number (a division by zero, say):
            # refuse leaves the model to Yes Steve Model while it has such a value; prune takes it over and leaves
            # that bone and the bones below it out, which is what the open-source renderer does with such a bone.
            # /ysm_ar status names the bone and the value in the line of the model ("attributes refused").
            takeover.non_finite=refuse

            # Milliseconds per frame for building the bone meshes of a model before its first draw; until all are
            # built the model is drawn the old way. 0 builds everything in one frame.
            prewarm.ms_per_frame=2

            # For picture comparisons only. Not 0: the model drawn by this mod is moved that many pixels to the right
            # on screen and Yes Steve Model keeps drawing its own, so that one frame shows both (no shader pack).
            takeover.debug_side_by_side=0
            """;

    private YsmArConfig(Properties values, String model, String problem, boolean unreadable) {
        StringBuilder notes = new StringBuilder(problem == null ? "" : problem);
        enabled = flag(values, "enabled", true, notes) && !unreadable;
        clientMeshes = choice(values, "mesh_type", "server", "client", notes);
        reverseMirrored = choice(values, "mirrored_bones", "as_baked", "reversed", notes);
        arVersionCheck = flag(values, "ar_version_check", true, notes);
        bakeFileRules = choice(values, "bake.uv_rules", "official", "file", "file", notes);
        standaloneModel = model.trim();
        standaloneTexture = values.getProperty("standalone.texture", "").trim();
        standaloneEntity = values.getProperty("standalone.entity", "minecraft:husk").trim();
        standaloneWave = choice(values, "standalone.animation", "rest", "wave", "wave", notes);
        float scale = Float.NaN;
        String scaleText = values.getProperty("standalone.scale", "").trim();
        if (!scaleText.isEmpty()) {
            try {
                scale = Float.parseFloat(scaleText);
            } catch (NumberFormatException ignored) {
                scale = Float.NaN;
            }
            if (!(scale > 0.0f) || !Float.isFinite(scale)) {
                scale = Float.NaN;
                note(notes, "standalone.scale is not a positive number");
            }
        }
        standaloneScale = scale;
        int interval = 0;
        String intervalText = values.getProperty("stats.interval_seconds", "0").trim();
        try {
            interval = Math.max(0, Integer.parseInt(intervalText));
        } catch (NumberFormatException ignored) {
            note(notes, "stats.interval_seconds is not a whole number");
        }
        statsIntervalSeconds = interval;
        takeoverEnabled = flag(values, "takeover.enabled", true, notes);
        Set<String> deny = new LinkedHashSet<>();
        for (String id : values.getProperty("takeover.deny", "").split(";")) {
            if (!id.isBlank()) {
                deny.add(id.trim());
            }
        }
        takeoverDeny = Collections.unmodifiableSet(deny);
        float prewarm = 2.0f;
        String prewarmText = values.getProperty("prewarm.ms_per_frame", "2").trim();
        try {
            prewarm = Float.parseFloat(prewarmText);
        } catch (NumberFormatException ignored) {
            prewarm = Float.NaN;
        }
        if (!(prewarm >= 0.0f) || prewarm > 1000.0f) {
            prewarm = 2.0f;
            note(notes, "prewarm.ms_per_frame is not a number from 0 to 1000");
        }
        prewarmNanos = Math.round(prewarm * 1_000_000.0);
        int sideBySide = 0;
        try {
            sideBySide = Integer.parseInt(values.getProperty("takeover.debug_side_by_side", "0").trim());
        } catch (NumberFormatException ignored) {
            note(notes, "takeover.debug_side_by_side is not a whole number");
        }
        takeoverSideBySide = sideBySide;
        takeoverProvidePivotAbs = choice(values, "takeover.pivot_abs", "exclude", "provide", notes);
        takeoverLateRead = flag(values, LATE_READ_KEY, true, notes);
        takeoverIdentityStrict = choice(values, "takeover.identity", "names", "strict", "strict", notes);
        takeoverLegacyGlow = flag(values, "takeover.legacy_glow", true, notes);
        takeoverPruneNonFinite = choice(values, "takeover.non_finite", "refuse", "prune", notes);
        this.problem = notes.length() == 0 ? null : notes.toString();
    }

    public static YsmArConfig current() {
        return current;
    }

    /** Reads the file, writing it with the defaults first when it does not exist. Never throws. */
    public static YsmArConfig load(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME);
        Properties values = new Properties();
        String model = "";
        String problem = null;
        boolean unreadable = false;
        boolean present = false;
        try {
            present = Files.exists(file);
            if (!present) {
                Files.createDirectories(configDirectory);
                Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            }
            String[] path = {""};
            values = parse(Files.readAllBytes(file), path);
            model = path[0];
        } catch (Exception failure) {
            values = new Properties();
            model = "";
            unreadable = present;
            String what = failure instanceof CharacterCodingException ? "is not UTF-8 text (save it as UTF-8)"
                    : "cannot be read (" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")";
            problem = file + " " + what + (present ? "; the mod stays switched off until /ysm_ar reload reads it" : "; defaults are used");
        }
        YsmArConfig loaded = new YsmArConfig(values, model, problem, unreadable);
        current = loaded;
        if (loaded.problem != null) {
            LOGGER.warn("ysm_ar: {}", loaded.problem);
        }
        return loaded;
    }

    /**
     * What takeover.late_read says before the game has started, for the mixin plugin: the same file, read the same
     * way, but nothing is written, logged or kept. A file that is missing or cannot be read gives the default.
     */
    public static boolean lateReadAtStartup(Path configDirectory) {
        try {
            Path file = configDirectory.resolve(FILE_NAME);
            if (!Files.isRegularFile(file)) {
                return true;
            }
            return flag(parse(Files.readAllBytes(file), new String[1]), LATE_READ_KEY, true, new StringBuilder());
        } catch (Exception | LinkageError unreadable) {
            return true;
        }
    }

    /** The keys and values of the file; the model path, which is taken as written, goes to model[0]. */
    private static Properties parse(byte[] bytes, String[] model) throws java.io.IOException {
        // The model path is taken as written: in a properties file a backslash would be an escape character.
        StringBuilder others = new StringBuilder();
        for (String line : decode(bytes).split("\\R", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith(MODEL_KEY)) {
                String rest = stripped.substring(MODEL_KEY.length()).stripLeading();
                if (rest.startsWith("=") || rest.startsWith(":")) {
                    model[0] = unquote(rest.substring(1).trim());
                    continue;
                }
            }
            others.append(line).append('\n');
        }
        Properties values = new Properties();
        values.load(new StringReader(others.toString()));
        return values;
    }

    /** Strict UTF-8, without the byte order mark some editors put in front. */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        int start = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
    }

    /** A path as "Copy as path" of the Windows Explorer gives it comes in double quotes. */
    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1).trim() : value;
    }

    public String describe() {
        return "enabled=" + enabled + " mesh_type=" + (clientMeshes ? "client" : "server")
                + " mirrored_bones=" + (reverseMirrored ? "reversed" : "as_baked")
                + " ar_version_check=" + arVersionCheck
                + " bake.uv_rules=" + (bakeFileRules ? "file" : "official")
                + " standalone.model=" + (standaloneModel.isEmpty() ? "(off)" : standaloneModel)
                + " standalone.texture=" + (standaloneTexture.isEmpty() ? "(default)" : "(set)")
                + " standalone.entity=" + standaloneEntity
                + " standalone.animation=" + (standaloneWave ? "wave" : "rest")
                + " standalone.scale=" + (Float.isNaN(standaloneScale) ? "(model)" : String.valueOf(standaloneScale))
                + " stats.interval_seconds=" + statsIntervalSeconds
                + " takeover.enabled=" + takeoverEnabled
                + " takeover.deny=" + (takeoverDeny.isEmpty() ? "(none)" : takeoverDeny.size() + " id(s)")
                + " takeover.pivot_abs=" + (takeoverProvidePivotAbs ? "provide" : "exclude")
                + " takeover.late_read=" + takeoverLateRead
                + " takeover.identity=" + (takeoverIdentityStrict ? "strict" : "names")
                + " takeover.legacy_glow=" + takeoverLegacyGlow
                + " takeover.non_finite=" + (takeoverPruneNonFinite ? "prune" : "refuse")
                + " prewarm.ms_per_frame=" + (prewarmNanos == 0 ? "0 (no limit)" : String.valueOf(prewarmNanos / 1e6))
                + (takeoverSideBySide == 0 ? "" : " takeover.debug_side_by_side=" + takeoverSideBySide);
    }

    private static boolean flag(Properties values, String key, boolean fallback, StringBuilder notes) {
        return choice(values, key, "false", "true", fallback ? "true" : "false", notes);
    }

    private static boolean choice(Properties values, String key, String off, String on, StringBuilder notes) {
        return choice(values, key, off, on, off, notes);
    }

    /** True for the value `on`, false for `off`; anything else is noted and gives the fallback. */
    private static boolean choice(Properties values, String key, String off, String on, String fallback, StringBuilder notes) {
        String value = values.getProperty(key, fallback).trim().toLowerCase(Locale.ROOT);
        if (value.equals(on)) {
            return true;
        }
        if (value.equals(off)) {
            return false;
        }
        note(notes, key + " is neither " + off + " nor " + on);
        return fallback.equals(on);
    }

    private static void note(StringBuilder notes, String text) {
        if (notes.length() > 0) {
            notes.append("; ");
        }
        notes.append(text);
    }
}
