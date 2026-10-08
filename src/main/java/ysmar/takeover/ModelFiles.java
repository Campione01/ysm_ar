package ysmar.takeover;

import ysmar.core.PathRules;

import java.nio.file.Path;

/**
 * From a model id of Yes Steve Model 2.6.5 to what the model was loaded from. For a custom model the id is its path
 * below config/yes_steve_model/custom, written with '/': that of a packed file with its ".ysm" ending, or that of a
 * model folder. A built-in model has an id of the same form as a folder (default, or pack/model); Yes Steve Model
 * unpacks those into the folder builtin next to custom. Ids may come from a server, so everything that could leave
 * the folder or name something other than a plain file or folder is refused.
 */
public final class ModelFiles {
    public static final String ENDING = ".ysm";

    private ModelFiles() {
    }

    /** Why the id names nothing below the custom folder, or null when it may be looked up. */
    public static String refusal(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "no model id";
        }
        PathRules.Problem problem = PathRules.problem(modelId);
        if (problem == null) {
            return null;
        }
        return switch (problem) {
            case CHARACTER -> "the model id has a character a file below the custom folder cannot have";
            case NOT_PLAIN -> "the model id is not a plain path below the custom folder";
            case DEVICE -> "the model id names a device";
        };
    }

    /** True for the id of a packed model file; every other id that refusal() lets through names a folder. */
    public static boolean packed(String modelId) {
        return modelId.regionMatches(true, modelId.length() - ENDING.length(), ENDING, 0, ENDING.length());
    }

    /** The file or folder for an id that refusal() let through; null when the result would still lie outside the folder. */
    public static Path resolve(Path customDirectory, String modelId) {
        return refusal(modelId) != null ? null : PathRules.resolve(customDirectory, modelId);
    }
}
