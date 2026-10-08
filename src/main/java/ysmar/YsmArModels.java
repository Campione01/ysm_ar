package ysmar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.core.ModelData;
import ysmar.core.ModelFolder;
import ysmar.core.ModelLoader;
import ysmar.core.NativeLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The model cache. request() never waits for a model: it returns the entry at once and one daemon thread loads
 * the models one after the other. An entry is identified by canonical path, size, modification time and texture key.
 * For a model folder these are the path of the folder and the size and time of the file that makes it a model; the
 * other files a model of it was read from are kept with the entry and looked at whenever the folder is stamped. A
 * folder whose files have changed gets a new generation: its next request reads it again, and a model of it that is
 * in use stays as it is until then, as the model of a changed file does.
 * Why a file or folder was refused is remembered for what it was then: until it changes or the models are cleared,
 * a request for it reads nothing, under whatever texture name it comes, and the refusal is logged once in a session.
 * A model that could not be read at all (a file held by another program, access denied, memory short) is no such
 * refusal: the loader tries a file it could not open a few more times, and when the model fails all the same, that
 * is remembered for a few seconds only; the next request after them reads again.
 * A model that no entity has shown for a while can be dropped (dropIdle): the next request for it reads it again.
 * request() and clear() belong to the render thread; from another thread they are refused (see RenderThread).
 */
public final class YsmArModels {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final long RECHECK_NANOS = 2_000_000_000L;
    /**
     * A file that could not be opened is tried again after these pauses, counted from the attempt before; other
     * models are loaded in between. How long a model that was not read is answered for from memory afterwards.
     */
    private static final long[] RETRY_PAUSES_NANOS = {1_000_000_000L, 2_000_000_000L, 4_000_000_000L};
    private static final long NOT_READ_NANOS = 10_000_000_000L;
    /** Why a model failed that the loader thread was reading when it ended. */
    static final String LOADER_ENDED = "the loader thread ended while it read this model";
    /** Entries that failed stay listed with their reason, but only the newest of them. */
    public static final int MAX_FAILED = 32;
    /** Refusals that are remembered, and refusals whose line was logged: of each the ones asked for last. */
    private static final int MAX_REMEMBERED = 256;
    private static final int MAX_TOLD = 1024;

    private record Lookup(String path, String texture) {
    }

    private record Key(String canonicalPath, long size, long modified, int generation, String texture) {
    }

    /**
     * A regular file as the file system showed it at one moment: its real path, size and modification time. For a
     * model folder these are those of the file that makes it a model, and generation tells how often the other files
     * its models were read from were found changed.
     */
    public record FileStamp(String realPath, long size, long modified, int generation) {
    }

    private static final class Slot {
        Key key;
        ModelEntry entry;
        long checkedAt;
    }

    /** A file or folder as it was when it was read: what a Key holds without the texture. */
    private record Content(String canonicalPath, long size, long modified, int generation) {
    }

    /** everyTexture: refused before its texture keys were known, so whatever texture is asked for; else for this one. */
    private record Refused(Content content, boolean everyTexture, String texture) {
    }

    /**
     * opened: for a folder, what was read up to the refusal; a change of any of it is a change of the folder.
     * notReadAt: for a model that was not read (see ModelEntry.notRead), when it failed; such a failure is forgotten
     * after NOT_READ_NANOS. Long.MIN_VALUE for a refusal of the content, which stays.
     */
    private record Reason(String text, List<ModelFolder.Opened> opened, long notReadAt) {
        boolean notRead() {
            return notReadAt != Long.MIN_VALUE;
        }
    }

    /** A model whose file could not be opened, waiting for its next attempt. */
    private record Deferred(ModelEntry entry, int attempts, long first, long due) {
    }

    /** A refusal as it is logged once: without the generation, which starts again when the models are cleared. */
    private record Told(String path, long size, long modified, List<ModelFolder.Opened> opened, boolean everyTexture, String texture, String reason) {
    }

    @SuppressWarnings("serial")
    private static <K, V> Map<K, V> newestOf(int limit) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }

    private static final Object LOCK = new Object();
    private static final Map<Lookup, Slot> LOOKUPS = new HashMap<>();
    private static final Map<Key, ModelEntry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, Integer> GENERATIONS = new HashMap<>();
    private static final Map<Refused, Reason> REFUSALS = newestOf(MAX_REMEMBERED);
    /** Kept over clear(): the lines of a session, not of a reload. */
    private static final Map<Told, Boolean> TOLD = newestOf(MAX_TOLD);
    private static final LinkedBlockingQueue<ModelEntry> QUEUE = new LinkedBlockingQueue<>();
    /** Guarded by the lock: a loader thread that takes the place of one that ended finds them. */
    private static final List<Deferred> DEFERRED = new ArrayList<>();
    private static final AtomicLong PUBLISHED = new AtomicLong();
    private static final AtomicLong FILE_CHECKS = new AtomicLong();
    private static Thread worker;
    /** Guarded by the lock: models dropped as idle since the start or since the models were cleared. */
    private static long idleDrops;
    private static long[] retryPauses = RETRY_PAUSES_NANOS;
    private static long notReadNanos = NOT_READ_NANOS;
    private static volatile Path gameDirectory = Path.of("").toAbsolutePath();

    private YsmArModels() {
    }

    /** Where the native library is extracted to (below it, in ysm_ar/native). */
    public static void gameDirectory(Path directory) {
        gameDirectory = directory.toAbsolutePath();
    }

    public static ModelEntry request(Path file, String textureKeyOrNull) {
        return request(file, textureKeyOrNull, false);
    }

    /**
     * keepPixels: keep the decoded texture in the entry, for a caller that uploads it itself. Off the render thread
     * the answer is an entry that has failed with that reason and belongs to no cache.
     */
    public static ModelEntry request(Path file, String textureKeyOrNull, boolean keepPixels) {
        return request(file, textureKeyOrNull, keepPixels, null);
    }

    /**
     * Reads the stamp of the file from the file system; an exception when it is not a regular file that can be read.
     * A folder is stamped as a model folder, unless it is named like a model file.
     */
    public static FileStamp stamp(Path file) throws java.io.IOException {
        FILE_CHECKS.incrementAndGet();
        Path real = file.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            if (ModelFolder.namedLikeFile(String.valueOf(real.getFileName())) || ModelFolder.namedLikeFile(String.valueOf(file.getFileName()))) {
                throw new ModelFolder.NamedLikeFile();
            }
            return folderStamp(real);
        }
        if (!attributes.isRegularFile()) {
            throw new java.io.IOException("not a file");
        }
        return new FileStamp(real.toString(), attributes.size(), attributes.lastModifiedTime().toMillis(), 0);
    }

    /**
     * The stamp of a model folder, given by its real path: that of the file that makes it a model; an exception
     * for a folder without one. When a model of the folder was read from files which are no longer what they were,
     * the folder gets a new generation: a request with this stamp reads it again. No model is dropped here: one
     * that is drawn shows what Yes Steve Model still has, and goes when its own request comes again.
     */
    public static FileStamp folderStamp(Path realFolder) throws java.io.IOException {
        Path marker = ModelFolder.marker(realFolder);
        if (marker == null) {
            throw new java.io.IOException("no model folder");
        }
        BasicFileAttributes attributes = Files.readAttributes(marker, BasicFileAttributes.class);
        String path = realFolder.toString();
        Map<ModelFolder.Opened, Boolean> looked = new HashMap<>();
        int generation;
        synchronized (LOCK) {
            generation = GENERATIONS.getOrDefault(path, 0);
            boolean unchanged = true;
            for (Map.Entry<Key, ModelEntry> known : ENTRIES.entrySet()) {
                ModelEntry entry = known.getValue();
                if (!known.getKey().canonicalPath().equals(path) || known.getKey().generation() != generation
                        || entry.state() == ModelEntry.State.LOADING) {
                    continue;
                }
                for (ModelFolder.Opened file : entry.opened()) {
                    // Models of one folder were read from the same files: each is looked at once.
                    unchanged &= looked.computeIfAbsent(file, each -> {
                        FILE_CHECKS.incrementAndGet();
                        return ModelFolder.unchanged(realFolder, each);
                    });
                }
            }
            // A refusal is remembered longer than its entry is listed: what it was read from counts as well.
            for (Map.Entry<Refused, Reason> known : REFUSALS.entrySet()) {
                Content content = known.getKey().content();
                if (!content.canonicalPath().equals(path) || content.generation() != generation) {
                    continue;
                }
                for (ModelFolder.Opened read : known.getValue().opened()) {
                    unchanged &= looked.computeIfAbsent(read, each -> {
                        FILE_CHECKS.incrementAndGet();
                        return ModelFolder.unchanged(realFolder, each);
                    });
                }
            }
            if (!unchanged) {
                GENERATIONS.put(path, ++generation);
            }
        }
        return new FileStamp(path, attributes.size(), attributes.lastModifiedTime().toMillis(), generation);
    }

    /** How often this class has asked the file system about a model file since the start. */
    public static long fileChecks() {
        return FILE_CHECKS.get();
    }

    /** checked: the stamp of the file when the caller has read it a moment ago, which saves reading it here; else null. */
    public static ModelEntry request(Path file, String textureKeyOrNull, boolean keepPixels, FileStamp checked) {
        String texture = textureKeyOrNull == null || textureKeyOrNull.isEmpty() ? null : textureKeyOrNull;
        if (!RenderThread.allows(RenderThread.Call.REQUEST)) {
            ModelEntry refused = new ModelEntry(file, texture, keepPixels);
            refused.fail("requested from a thread that is not the render thread");
            return refused;
        }
        Lookup lookup = new Lookup(file.toString(), texture);
        long now = System.nanoTime();
        // A caller that has just looked at the file is answered for what it saw, not for what was there two seconds ago.
        Key seen = checked == null ? null : new Key(checked.realPath(), checked.size(), checked.modified(), checked.generation(), texture);
        synchronized (LOCK) {
            Slot slot = LOOKUPS.get(lookup);
            if (slot != null && now - slot.checkedAt < RECHECK_NANOS && !slot.entry.isDiscarded() && (seen == null || seen.equals(slot.key))
                    && (!keepPixels || slot.entry.keepsPixels())) {
                if (slot.entry.state() == ModelEntry.State.LOADING) {
                    startWorker();
                }
                slot.entry.shown();
                return slot.entry;
            }
        }
        Key key;
        String unreadable = null;
        String realPath = null;
        try {
            FileStamp stamp = checked != null ? checked : stamp(file);
            key = seen != null ? seen : new Key(stamp.realPath(), stamp.size(), stamp.modified(), stamp.generation(), texture);
            realPath = stamp.realPath();
        } catch (Exception problem) {
            key = new Key(file.toAbsolutePath().toString(), -1, -1, 0, texture);
            unreadable = problem instanceof ModelFolder.NamedLikeFile ? problem.getMessage()
                    : (Files.isDirectory(file) ? "the folder holds no model or cannot be read: " : "the file cannot be read: ")
                    + problem.getClass().getSimpleName();
        }
        synchronized (LOCK) {
            Slot slot = LOOKUPS.computeIfAbsent(lookup, ignored -> new Slot());
            ModelEntry entry = ENTRIES.get(key);
            Reason remembered = remembered(key, now);
            // A model that was not read is tried again once that is no longer remembered.
            if (entry != null && (entry.isDiscarded() || keepPixels && !entry.keepsPixels() || entry.notRead() && remembered == null)) {
                entry.discard();
                entry = null;
            }
            if (entry == null) {
                entry = new ModelEntry(file, texture, keepPixels, realPath);
                ENTRIES.put(key, entry);
                dropOldFailures(entry);
                if (remembered != null) {
                    entry.opened(remembered.opened());
                    if (remembered.notRead()) {
                        entry.failNotRead(remembered.text());
                    } else {
                        entry.fail(remembered.text());
                    }
                } else if (unreadable != null) {
                    fail(entry, unreadable, true, false);
                } else if (NativeLoader.failure() != null) {
                    entry.fail("native library: " + NativeLoader.failure());
                } else {
                    QUEUE.add(entry);
                    startWorker();
                }
            } else if (entry.state() == ModelEntry.State.LOADING) {
                startWorker();
            }
            // The file behind this lookup changed: the model of the old content is of no use any more.
            if (slot.key != null && !slot.key.equals(key)) {
                ModelEntry old = ENTRIES.remove(slot.key);
                if (old != null) {
                    old.discard();
                }
            }
            slot.key = key;
            slot.entry = entry;
            slot.checkedAt = now;
            entry.shown();
            return entry;
        }
    }

    /**
     * Called with the lock held. Why what this key stands for was refused, for every texture or for the one of the
     * key; else null. A model that was not read is forgotten here once it has been remembered long enough.
     */
    private static Reason remembered(Key key, long now) {
        Content content = new Content(key.canonicalPath(), key.size(), key.modified(), key.generation());
        for (Refused refused : new Refused[]{new Refused(content, true, null), new Refused(content, false, key.texture())}) {
            Reason reason = REFUSALS.get(refused);
            if (reason != null && reason.notRead() && now - reason.notReadAt() >= notReadNanos) {
                REFUSALS.remove(refused);
            } else if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /** Called with the lock held. Requests that can never succeed must not make the cache grow without end. */
    private static void dropOldFailures(ModelEntry newest) {
        int failed = 0;
        for (ModelEntry entry : ENTRIES.values()) {
            if (entry.state() == ModelEntry.State.FAILED) {
                failed++;
            }
        }
        for (Iterator<ModelEntry> iterator = ENTRIES.values().iterator(); failed >= MAX_FAILED && iterator.hasNext(); ) {
            ModelEntry old = iterator.next();
            if (old != newest && old.state() == ModelEntry.State.FAILED) {
                iterator.remove();
                LOOKUPS.values().removeIf(slot -> slot.entry == old);
                old.discard();
                failed--;
            }
        }
    }

    /**
     * The texture keys of the file as an earlier request for it found them; empty when no request got that far or
     * the file is not the one that was read then. Loads nothing.
     */
    public static List<String> knownTextureKeys(Path file) {
        try {
            return knownTextureKeys(stamp(file));
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** The same for a file whose stamp the caller already has; the file system is not asked. */
    public static List<String> knownTextureKeys(FileStamp stamp) {
        synchronized (LOCK) {
            for (Map.Entry<Key, ModelEntry> known : ENTRIES.entrySet()) {
                Key key = known.getKey();
                if (key.size() == stamp.size() && key.modified() == stamp.modified() && key.generation() == stamp.generation()
                        && key.canonicalPath().equals(stamp.realPath()) && !known.getValue().textureKeys().isEmpty()) {
                    return known.getValue().textureKeys();
                }
            }
        }
        return List.of();
    }

    /**
     * What an earlier request read a model folder from, when the folder is still as that request found it (the same
     * stamp); empty when no request got that far. Loads nothing and asks the file system nothing.
     */
    public static List<ModelFolder.Opened> knownOpened(FileStamp stamp) {
        synchronized (LOCK) {
            for (Map.Entry<Key, ModelEntry> known : ENTRIES.entrySet()) {
                Key key = known.getKey();
                if (key.size() == stamp.size() && key.modified() == stamp.modified() && key.generation() == stamp.generation()
                        && key.canonicalPath().equals(stamp.realPath()) && known.getValue().state() != ModelEntry.State.LOADING
                        && !known.getValue().notRead() && !known.getValue().opened().isEmpty()) {
                    return known.getValue().opened();
                }
            }
        }
        return List.of();
    }

    /**
     * Drops every model; natives of loaded models are closed, models still loading are closed when they arrive.
     * False, with nothing dropped, off the render thread: the render thread may be drawing one of them.
     */
    public static boolean clear() {
        if (!RenderThread.allows(RenderThread.Call.CLEAR)) {
            return false;
        }
        synchronized (LOCK) {
            for (ModelEntry entry : ENTRIES.values()) {
                entry.discard();
            }
            ENTRIES.clear();
            LOOKUPS.clear();
            GENERATIONS.clear();
            REFUSALS.clear();
            DEFERRED.clear();
            idleDrops = 0;
        }
        return true;
    }

    /**
     * Drops the models no entity has shown for idleNanos, with their natives; a request for one of them reads its
     * file or folder again. Meant to be called now and then, not in every frame: how long a model was not shown is
     * counted from the first call that found it not shown since the call before. Models that load or have failed
     * stay, and so does what is remembered of refusals. Returns how many models were dropped: none for an idle time
     * of zero or less, and none off the render thread, which may be drawing one of them.
     */
    public static int dropIdle(long now, long idleNanos) {
        if (idleNanos <= 0 || !RenderThread.allows(RenderThread.Call.CLEAR)) {
            return 0;
        }
        List<ModelEntry> dropped = null;
        synchronized (LOCK) {
            for (Iterator<ModelEntry> iterator = ENTRIES.values().iterator(); iterator.hasNext(); ) {
                ModelEntry entry = iterator.next();
                if (entry.state() != ModelEntry.State.READY || now - entry.idleSince(now) < idleNanos) {
                    continue;
                }
                iterator.remove();
                LOOKUPS.values().removeIf(slot -> slot.entry == entry);
                entry.discardIdle();
                if (dropped == null) {
                    dropped = new ArrayList<>();
                }
                dropped.add(entry);
                idleDrops++;
            }
        }
        if (dropped == null) {
            return 0;
        }
        for (ModelEntry entry : dropped) {
            LOGGER.info("ysm_ar: dropped {} from memory: no entity has shown it for {} s; it is read again when one does",
                    Text.clean(entry.fileName()), idleNanos / 1_000_000_000L);
        }
        return dropped.size();
    }

    /** Models that became READY since the start; one that was dropped while it loaded is not among them. */
    static long published() {
        return PUBLISHED.get();
    }

    /** The pauses between the attempts at a file that could not be opened, and how long a model that was not read is remembered. */
    static long[] readPauses() {
        return new long[]{RETRY_PAUSES_NANOS[0], RETRY_PAUSES_NANOS[1], RETRY_PAUSES_NANOS[2], NOT_READ_NANOS};
    }

    /** For a check, which cannot wait as long as a game can: other pauses; null puts the real ones back. */
    static void readPauses(long[] retry, long notRead) {
        synchronized (LOCK) {
            retryPauses = retry == null ? RETRY_PAUSES_NANOS : retry.clone();
            notReadNanos = retry == null ? NOT_READ_NANOS : notRead;
        }
    }

    /** True while there is a loader thread that has not ended. */
    static boolean loaderAlive() {
        synchronized (LOCK) {
            return worker != null && worker.isAlive();
        }
    }

    public static List<ModelEntry> entries() {
        synchronized (LOCK) {
            return new ArrayList<>(ENTRIES.values());
        }
    }

    public static String describeStates() {
        int loading = 0;
        int ready = 0;
        int failed = 0;
        for (ModelEntry entry : entries()) {
            switch (entry.state()) {
                case LOADING -> loading++;
                case READY -> ready++;
                case FAILED -> failed++;
            }
        }
        long idle;
        synchronized (LOCK) {
            idle = idleDrops;
        }
        return "ready=" + ready + " loading=" + loading + " failed=" + failed + " dropped_idle=" + idle;
    }

    /** One line per model for the status command: name of the file or folder, state, counts, sizes and timings. */
    public static List<String> describeEntries() {
        List<String> lines = new ArrayList<>();
        for (ModelEntry entry : entries()) {
            StringBuilder line = new StringBuilder(Text.clean(entry.fileName())).append(": ").append(entry.state());
            ModelData data = entry.data();
            if (entry.state() == ModelEntry.State.FAILED) {
                line.append(" (").append(entry.failure()).append(')');
            } else if (data != null) {
                line.append(String.format(Locale.ROOT,
                        " bones=%d (with geometry %d) quads baked=%d/%d/%d/%d opaque=%d translucent=%d dropped=%d vertices=%d"
                                + " texture=%dx%d keys=%d pbr=%b scale=%.3f/%.3f uv_rules=%d (file %d) load=%.0f ms (import %.0f, decode %.1f, bake %.1f, read %.1f, walk %.1f)",
                        data.boneCount, data.bonesWithGeometry, data.partitionQuads[0], data.partitionQuads[1],
                        data.partitionQuads[2], data.partitionQuads[3], data.emittedQuads[0], data.emittedQuads[1],
                        data.droppedQuads, data.emittedVertices(), data.textureWidth, data.textureHeight,
                        data.textureKeys.size(), data.hasPbr, data.widthScale, data.heightScale, data.originVersion, data.fileOriginVersion,
                        data.totalMillis,
                        data.importMillis, data.decodeMillis, data.bakeMillis, data.readMillis, data.walkMillis));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The failure holds for the file or folder as it is, whenever it is asked for. everyTexture: under whatever
     * texture name; else for the texture of this entry. notRead: the model was not read (see ModelEntry.notRead),
     * which is remembered for a short time only. It is logged the first time it is made for that content in a
     * session.
     */
    private static void fail(ModelEntry entry, String reason, boolean everyTexture, boolean notRead) {
        boolean first;
        synchronized (LOCK) {
            Key key = null;
            for (Map.Entry<Key, ModelEntry> known : ENTRIES.entrySet()) {
                if (known.getValue() == entry) {
                    key = known.getKey();
                    break;
                }
            }
            String texture = everyTexture ? null : entry.requestedTexture();
            if (key != null) {
                REFUSALS.put(new Refused(new Content(key.canonicalPath(), key.size(), key.modified(), key.generation()), everyTexture, texture),
                        new Reason(reason, entry.opened(), notRead ? System.nanoTime() : Long.MIN_VALUE));
            }
            Told told = key == null ? new Told(entry.file().toString(), -2, -2, List.of(), everyTexture, texture, reason)
                    : new Told(key.canonicalPath(), key.size(), key.modified(), entry.opened(), everyTexture, texture, reason);
            first = TOLD.put(told, Boolean.TRUE) == null;
            if (notRead) {
                entry.failNotRead(reason);
            } else {
                entry.fail(reason);
            }
        }
        if (first) {
            LOGGER.warn("ysm_ar: {} is not used: {}", Text.clean(entry.fileName()), reason);
        }
    }

    /** Called with the lock held. A loader thread that has ended is replaced: what waits in the queue is still loaded. */
    private static void startWorker() {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(YsmArModels::work, "ysm_ar model loader");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void work() {
        ModelEntry current = null;
        try {
            while (true) {
                current = null;
                ModelEntry entry;
                int attempts = 0;
                long first = 0;
                try {
                    Deferred next;
                    synchronized (LOCK) {
                        next = null;
                        for (Deferred each : DEFERRED) {
                            next = next == null || each.due() < next.due() ? each : next;
                        }
                    }
                    long wait = next == null ? 0 : next.due() - System.nanoTime();
                    entry = next == null ? QUEUE.take() : wait > 0 ? QUEUE.poll(wait, TimeUnit.NANOSECONDS) : null;
                    if (entry == null) {
                        synchronized (LOCK) {
                            if (!DEFERRED.remove(next)) {
                                continue;
                            }
                        }
                        entry = next.entry();
                        attempts = next.attempts();
                        first = next.first();
                    }
                } catch (InterruptedException interrupted) {
                    continue;
                }
                if (entry.isDiscarded()) {
                    continue;
                }
                current = entry;
                load(entry, attempts, attempts == 0 ? System.nanoTime() : first);
            }
        } finally {
            // Only an error that nothing here could handle ends this thread; what it was reading is not left loading for ever.
            if (current != null) {
                current.fail(LOADER_ENDED);
            }
        }
    }

    /** attempts: how often the files of this model could not be opened before; first: when the first of these attempts was made. */
    private static void load(ModelEntry entry, int attempts, long first) {
        try {
            if (!NativeLoader.ensureLoaded(gameDirectory)) {
                entry.fail("native library: " + NativeLoader.failure());
                return;
            }
            ModelData data = ModelLoader.load(entry.file(), entry.requestedTexture(), entry.keepsPixels(), entry::textureKeys,
                    YsmArConfig.current().bakeFileRules, entry::opened, entry.realPath());
            // The numbers are read before the model is handed over: a dropped one is closed by complete().
            int bones = data.boneCount;
            long quads = data.emittedQuads[0] + data.emittedQuads[1];
            int width = data.textureWidth;
            int height = data.textureHeight;
            long millis = Math.round(data.totalMillis);
            if (entry.complete(data)) {
                PUBLISHED.incrementAndGet();
                LOGGER.info("ysm_ar: loaded {}: {} bones, {} quads, {} vertices, texture {}x{}, {} ms",
                        Text.clean(entry.fileName()), bones, quads, 4 * quads, width, height, millis);
            }
        } catch (ModelLoader.Refusal refusal) {
            long now = System.nanoTime();
            long[] pauses;
            synchronized (LOCK) {
                pauses = retryPauses;
                if (refusal.worthAnotherTry() && attempts < pauses.length && !entry.isDiscarded()) {
                    // The model stays loading: nothing is known of it yet.
                    DEFERRED.add(new Deferred(entry, attempts + 1, first, now + pauses[attempts]));
                    return;
                }
            }
            String reason = refusal.getMessage();
            if (refusal.worthAnotherTry()) {
                reason += "; tried " + (attempts + 1) + " times in " + String.format(Locale.ROOT, "%.0f", (now - first) / 1e9) + " s";
            }
            fail(entry, reason, !refusal.isOfTheTexture(), refusal.notRead());
        } catch (Throwable problem) {
            // What went wrong is not known: before the texture keys were told it cannot have been the texture.
            fail(entry, Text.clean(NativeLoader.describe(problem)), entry.textureKeys().isEmpty(), false);
        }
    }
}
