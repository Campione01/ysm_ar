package ysmar.takeover;

import java.util.List;

/**
 * Which texture of the imported file belongs to a texture name of Yes Steve Model 2.6.5. The names are the importer's
 * keys; indices cannot be used, the two sides order their textures differently.
 */
public final class TextureKeys {
    private TextureKeys() {
    }

    /** The key equal to the name; else the only key of a model with one texture; else null (no guess). */
    public static String choose(String theirName, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        if (theirName != null && keys.contains(theirName)) {
            return theirName;
        }
        return keys.size() == 1 ? keys.get(0) : null;
    }
}
