package ysmar.core;

import com.elfmcys.ysm.natives.NativeRuntime;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Extracts the native library from this jar and loads it once. A failure is final for this JVM: it is logged
 * once and every later call reports it without trying again.
 *
 * Several game processes may extract into the same directory at the same time. The file is named after its content
 * and is only ever put in place whole, by renaming a private temporary file, and never over a file that is already
 * the library: so a file another process has verified or loaded does not go away. Whatever goes wrong with reading
 * or renaming is tried again for a while before it counts as a failure.
 *
 * Every build of the library has a file name of its own, so the files of earlier versions of this mod stay behind.
 * After a load, those that nobody has written for a week are deleted; one that another game still has loaded
 * cannot be deleted and stays. The same goes for the temporary files of extractions that ended with their process.
 */
public final class NativeLoader {
    public static final String RESOURCE = "/ysm_ar/native/ysm.dll";

    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final Object LOCK = new Object();
    private static final long EXTRACT_PATIENCE_NANOS = 15_000_000_000L;
    /** The names extract() gives: ysm-, twelve hex digits of the content hash, .dll. Nothing else is ever deleted. */
    private static final Pattern EXTRACTED_NAME = Pattern.compile("ysm-[0-9a-f]{12}\\.dll");
    /** The names of extract()'s temporary files: the name above, the process id, a hex number, .tmp. */
    private static final Pattern TEMPORARY_NAME = Pattern.compile("ysm-[0-9a-f]{12}\\.dll\\.[0-9]+-[0-9a-f]+\\.tmp");
    private static final long STALE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int MATCHES = 0;
    private static final int ABSENT = 1;
    private static final int DIFFERS = 2;
    private static volatile boolean loaded;
    private static volatile String failure;
    private static volatile String location;

    private NativeLoader() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** The reason the library is unusable, or null. */
    public static String failure() {
        return failure;
    }

    /** Where the loaded library was extracted to, or null. */
    public static String location() {
        return location;
    }

    public static boolean ensureLoaded(Path gameDirectory) {
        if (loaded) {
            return true;
        }
        synchronized (LOCK) {
            if (loaded) {
                return true;
            }
            if (failure != null) {
                return false;
            }
            try {
                Path library = extract(gameDirectory);
                load(library);
                location = library.toString();
                loaded = true;
                LOGGER.info("ysm_ar: native library loaded from {}", library);
                int removed = removeStale(library.getParent(), library.getFileName().toString(), System.currentTimeMillis());
                if (removed > 0) {
                    LOGGER.info("ysm_ar: {} extracted native libraries of other versions or left-over temporary files of an extraction,"
                            + " unused for a week, removed from {}", removed, library.getParent());
                }
            } catch (Throwable problem) {
                failure = describe(problem);
                LOGGER.error("ysm_ar: the native library is not usable, models stay on the normal path: {}", failure);
            }
            return loaded;
        }
    }

    private static Path extract(Path gameDirectory) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IllegalStateException("unsupported platform " + os + "/" + arch + " (Windows x64 only)");
        }
        byte[] bytes;
        try (InputStream stream = NativeLoader.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the jar does not contain " + RESOURCE);
            }
            bytes = stream.readAllBytes();
        }
        String digest = sha256(bytes);
        Path directory = asciiDirectory(gameDirectory);
        Files.createDirectories(directory);
        Path target = directory.resolve("ysm-" + digest.substring(0, 12) + ".dll");
        Path temporary = directory.resolve(target.getFileName() + "." + ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()) + ".tmp");
        boolean written = false;
        long deadline = System.nanoTime() + EXTRACT_PATIENCE_NANOS;
        try {
            for (int attempt = 1; ; attempt++) {
                IOException problem = null;
                try {
                    int found = inspect(target, bytes.length, digest);
                    if (found == MATCHES) {
                        return target;
                    }
                    if (!written) {
                        Files.write(temporary, bytes);
                        written = true;
                    }
                    if (found == ABSENT) {
                        // Without REPLACE_EXISTING this is one rename, and it fails when another process was first.
                        Files.move(temporary, target);
                    } else {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                    written = false;
                } catch (FileAlreadyExistsException lostTheRace) {
                    // the next look at the file tells whether it is the library
                } catch (IOException notNow) {
                    problem = notNow;
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("the library could not be put at " + target + " in " + attempt + " attempts"
                            + (problem == null ? "" : ": " + describe(problem)));
                }
                if (problem != null) {
                    pause(Math.min(200, attempt * 10));
                }
            }
        } finally {
            if (written) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // a left-over temporary file does no harm
                }
            }
        }
    }

    /**
     * Deletes the files directly in the directory that have a name extract() gives, to a library or to one of its
     * temporary files, are not the one in use and were last written more than seven days before `now`. Returns how
     * many went; never throws, and a file that cannot be looked at or deleted is passed over.
     */
    public static int removeStale(Path directory, String inUse, long now) {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                try {
                    String name = file.getFileName().toString();
                    boolean ours = EXTRACTED_NAME.matcher(name).matches() || TEMPORARY_NAME.matcher(name).matches();
                    if (!ours || name.equalsIgnoreCase(inUse)) {
                        continue;
                    }
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || now - attributes.lastModifiedTime().toMillis() <= STALE_MILLIS) {
                        continue;
                    }
                    Files.delete(file);
                    removed++;
                } catch (IOException | RuntimeException inUseElsewhere) {
                    // loaded by another game, or gone already
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // the directory is left as it is
        }
        return removed;
    }

    /** MATCHES, ABSENT or DIFFERS; a file that cannot be read right now is an IOException, which the caller retries. */
    private static int inspect(Path file, int size, String digest) throws Exception {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            return ABSENT;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("not a file: " + file);
        }
        if (attributes.size() != size) {
            return DIFFERS;
        }
        return sha256(Files.readAllBytes(file)).equals(digest) ? MATCHES : DIFFERS;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path asciiDirectory(Path gameDirectory) {
        Path inGame = gameDirectory.toAbsolutePath().normalize().resolve("ysm_ar").resolve("native");
        if (isAscii(inGame.toString())) {
            return inGame;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path fallback = Path.of(localAppData).toAbsolutePath().normalize().resolve("ysm_ar").resolve("native");
            if (isAscii(fallback.toString())) {
                return fallback;
            }
        }
        throw new IllegalStateException("neither the game directory nor %LOCALAPPDATA% is an ASCII-only path");
    }

    private static boolean isAscii(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) >= 128) {
                return false;
            }
        }
        return true;
    }

    // The library's allocator needs the loading thread to outlive every allocation, so that thread never ends.
    // System.load is called from this class: JNI_OnLoad finds the wrapper classes through its class loader.
    private static void load(Path library) throws Exception {
        CompletableFuture<Object> initialised = new CompletableFuture<>();
        CountDownLatch lifetime = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                System.load(library.toString());
                initialised.complete(NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO)));
            } catch (Throwable problem) {
                initialised.complete(problem);
                return;
            }
            while (true) {
                try {
                    lifetime.await();
                    return;
                } catch (InterruptedException ignored) {
                    // keep holding
                }
            }
        }, "ysm_ar native holder");
        holder.setDaemon(true);
        holder.start();
        Object result = initialised.get(120, TimeUnit.SECONDS);
        if (result instanceof Throwable problem) {
            throw new IllegalStateException("loading " + library + " failed: " + describe(problem));
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static String describe(Throwable problem) {
        String text = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        Throwable cause = problem.getCause();
        if (cause != null && cause != problem) {
            text += " <- " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return text;
    }
}
