package ysmar.takeover;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * The built-in models of Yes Steve Model as its mod file holds them: plain resources below
 * assets/yes_steve_model/builtin, one folder per model id. Asked through the mod loader; nothing of them is read
 * but whether a folder is there and whether it holds the file that makes it a model. Render thread only.
 */
final class ModFileBuiltins implements Bindings.Builtins {
    private static final int MAX_KNOWN = 256;
    /** A built-in model every build of 2.6.5 has: when this one is not found as a model, the mod file cannot be asked. */
    private static final String ALWAYS_THERE = "default";

    private final HashMap<String, Integer> known = new HashMap<>();
    private Boolean usable;

    @Override
    public int kind(String modelId) {
        Integer cached = known.get(modelId);
        if (cached != null) {
            return cached;
        }
        int kind;
        try {
            if (usable == null) {
                usable = look(ALWAYS_THERE) == MODEL;
            }
            kind = usable ? look(modelId) : UNKNOWN;
        } catch (Throwable unreadable) {
            kind = UNKNOWN;
        }
        if (known.size() >= MAX_KNOWN) {
            known.clear();
        }
        known.put(modelId, kind);
        return kind;
    }

    private static int look(String modelId) {
        IModFileInfo info = ModList.get().getModFileById(YsmBuild.MOD_ID);
        if (info == null) {
            throw new IllegalStateException("no mod file");
        }
        String[] id = modelId.split("/", -1);
        String[] path = new String[id.length + 3];
        path[0] = "assets";
        path[1] = YsmBuild.MOD_ID;
        path[2] = "builtin";
        System.arraycopy(id, 0, path, 3, id.length);
        Path found = info.getFile().findResource(path);
        // The file that makes a folder a model is asked for by itself: a mod file need not list its folders.
        if (Files.isRegularFile(found.resolve("ysm.json")) || Files.isRegularFile(found.resolve("main.json"))) {
            return MODEL;
        }
        return Files.exists(found) ? FOLDER : ABSENT;
    }
}
