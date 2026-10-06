package ysmar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.core.ModelData;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The model cache. request() never waits for a model: it returns the entry at once and one daemon thread loads
 * the models one after the other. An entry is identified by canonical path, size, modification time and texture key.
 * request() and clear() belong to the render thread; from another thread they are refused (see RenderThread).
 */
public final class YsmArModels {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final long RECHECK_NANOS = 2_000_000_000L;
    /** Entries that failed stay listed with their reason, but only the newest of them. */
    public static final int MAX_FAILED = 32;

    private record Lookup(String path, String texture) {
    }

    private record Key(String canonicalPath, long size, long modified, String texture) {
    }

    /** A regular file as the file system showed it at one moment: its real path, size and modification time. */
    public record FileStamp(String realPath, long size, long modified) {
    }

    private static final class Slot {
        Key key;
        ModelEntry entry;
        long checkedAt;
    }

    private static final Object LOCK = new Object();
    private static final Map<Lookup, Slot> LOOKUPS = new HashMap<>();
    private static final Map<Key, ModelEntry> ENTRIES = new LinkedHashMap<>();
    private static final LinkedBlockingQueue<ModelEntry> QUEUE = new LinkedBlockingQueue<>();
    private static final AtomicLong PUBLISHED = new AtomicLong();
    private static final AtomicLong FILE_CHECKS = new AtomicLong();
    private static Thread worker;
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

    /** Reads the stamp of the file from the file system; an exception when it is not a regular file that can be read. */
    public static FileStamp stamp(Path file) throws java.io.IOException {
        FILE_CHECKS.incrementAndGet();
        Path real = file.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new java.io.IOException("not a file");
        }
        return new FileStamp(real.toString(), attributes.size(), attributes.lastModifiedTime().toMillis());
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
        synchronized (LOCK) {
            Slot slot = LOOKUPS.get(lookup);
            if (slot != null && now - slot.checkedAt < RECHECK_NANOS && !slot.entry.isDiscarded()
                    && (!keepPixels || slot.entry.keepsPixels())) {
                return slot.entry;
            }
        }
        Key key;
        String unreadable = null;
        try {
            FileStamp stamp = checked != null ? checked : stamp(file);
            key = new Key(stamp.realPath(), stamp.size(), stamp.modified(), texture);
        } catch (Exception problem) {
            key = new Key(file.toAbsolutePath().toString(), -1, -1, texture);
            unreadable = "the file cannot be read: " + problem.getClass().getSimpleName();
        }
        synchronized (LOCK) {
            Slot slot = LOOKUPS.computeIfAbsent(lookup, ignored -> new Slot());
            ModelEntry entry = ENTRIES.get(key);
            if (entry != null && (entry.isDiscarded() || keepPixels && !entry.keepsPixels())) {
                entry.discard();
                entry = null;
            }
            if (entry == null) {
                entry = new ModelEntry(file, texture, keepPixels);
                ENTRIES.put(key, entry);
                dropOldFailures(entry);
                if (unreadable != null) {
                    fail(entry, unreadable);
                } else if (NativeLoader.failure() != null) {
                    entry.fail("native library: " + NativeLoader.failure());
                } else {
                    startWorker();
                    QUEUE.add(entry);
                }
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
            return entry;
        }
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
                if (key.size() == stamp.size() && key.modified() == stamp.modified() && key.canonicalPath().equals(stamp.realPath())
                        && !known.getValue().textureKeys().isEmpty()) {
                    return known.getValue().textureKeys();
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
        }
        return true;
    }

    /** Models that became READY since the start; one that was dropped while it loaded is not among them. */
    public static long published() {
        return PUBLISHED.get();
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
        return "ready=" + ready + " loading=" + loading + " failed=" + failed;
    }

    /** One line per model for the status command: file name, state, counts, sizes and timings. */
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

    private static void fail(ModelEntry entry, String reason) {
        entry.fail(reason);
        LOGGER.warn("ysm_ar: {} is not used: {}", Text.clean(entry.fileName()), reason);
    }

    private static void startWorker() {
        if (worker == null) {
            worker = new Thread(YsmArModels::work, "ysm_ar model loader");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void work() {
        while (true) {
            ModelEntry entry;
            try {
                entry = QUEUE.take();
            } catch (InterruptedException interrupted) {
                continue;
            }
            if (entry.isDiscarded()) {
                continue;
            }
            try {
                if (!NativeLoader.ensureLoaded(gameDirectory)) {
                    entry.fail("native library: " + NativeLoader.failure());
                    continue;
                }
                ModelData data = ModelLoader.load(entry.file(), entry.requestedTexture(), entry.keepsPixels(), entry::textureKeys,
                        YsmArConfig.current().bakeFileRules);
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
                fail(entry, refusal.getMessage());
            } catch (Throwable problem) {
                fail(entry, Text.clean(NativeLoader.describe(problem)));
            }
        }
    }
}
