package ysmar.takeover;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * From a model id of Yes Steve Model 2.6.5 to the file it was loaded from. For a custom model the id is the path of
 * the file below config/yes_steve_model/custom, written with '/' and with its ".ysm" ending. Ids may come from a
 * server, so everything that could leave that folder or name something other than a plain file is refused.
 */
public final class ModelFiles {
    public static final String ENDING = ".ysm";
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5",
            "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private ModelFiles() {
    }

    /** Why the id names no file of the custom folder, or null when it may be looked up. */
    public static String refusal(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "no model id";
        }
        if (!modelId.regionMatches(true, modelId.length() - ENDING.length(), ENDING, 0, ENDING.length())) {
            return "not a .ysm file (a built-in or folder model)";
        }
        for (int index = 0; index < modelId.length(); index++) {
            char letter = modelId.charAt(index);
            if (letter < ' ' || letter == '\\' || letter == ':' || letter == '*' || letter == '?' || letter == '"'
                    || letter == '<' || letter == '>' || letter == '|') {
                return "the model id has a character a file below the custom folder cannot have";
            }
        }
        for (String part : modelId.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.startsWith(" ")) {
                return "the model id is not a plain path below the custom folder";
            }
            int dot = part.indexOf('.');
            if (DEVICES.contains((dot < 0 ? part : part.substring(0, dot)).toLowerCase(Locale.ROOT))) {
                return "the model id names a device";
            }
        }
        return null;
    }

    /** The file for an id that refusal() let through; null when the result would still lie outside the folder. */
    public static Path resolve(Path customDirectory, String modelId) {
        if (refusal(modelId) != null) {
            return null;
        }
        try {
            Path base = customDirectory.toAbsolutePath().normalize();
            Path file = base;
            for (String part : modelId.split("/", -1)) {
                file = file.resolve(part);
            }
            file = file.normalize();
            return file.startsWith(base) && !file.equals(base) ? file : null;
        } catch (RuntimeException unusable) {
            return null;
        }
    }
}
