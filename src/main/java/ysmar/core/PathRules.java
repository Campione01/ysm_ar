package ysmar.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What a path may look like that comes from outside and is looked up below a folder: a model id (it may come from a
 * server) or a file named in the manifest of a model folder. Written with '/'. Everything that could leave the
 * folder or name something other than a plain file or folder is a problem.
 */
public final class PathRules {
    public enum Problem { CHARACTER, NOT_PLAIN, DEVICE }

    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5",
            "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private PathRules() {
    }

    /** What is wrong with the path, or null when it may be looked up. */
    public static Problem problem(String path) {
        for (int index = 0; index < path.length(); index++) {
            char letter = path.charAt(index);
            if (letter < ' ' || letter == '\\' || letter == ':' || letter == '*' || letter == '?' || letter == '"'
                    || letter == '<' || letter == '>' || letter == '|') {
                return Problem.CHARACTER;
            }
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.startsWith(" ")) {
                return Problem.NOT_PLAIN;
            }
            int dot = part.indexOf('.');
            if (DEVICES.contains((dot < 0 ? part : part.substring(0, dot)).toLowerCase(Locale.ROOT))) {
                return Problem.DEVICE;
            }
        }
        return null;
    }

    /** The path below the folder; null when it has a problem or the result would still lie outside the folder. */
    public static Path resolve(Path folder, String path) {
        if (path == null || path.isEmpty() || problem(path) != null) {
            return null;
        }
        try {
            Path base = folder.toAbsolutePath().normalize();
            Path file = base;
            for (String part : path.split("/", -1)) {
                file = file.resolve(part);
            }
            file = file.normalize();
            return file.startsWith(base) && !file.equals(base) ? file : null;
        } catch (RuntimeException unusable) {
            return null;
        }
    }
}
