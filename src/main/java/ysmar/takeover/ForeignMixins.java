package ysmar.takeover;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Which mixins of other mods were applied to a class of Yes Steve Model, asked from the mod loader's list of mixin
 * configurations and from Mixin's own records through their public API (see MixinListing for how). Only told, never
 * acted on: such code can change what a render does between the visibility test and the draw, which is what the
 * late read is there for.
 */
final class ForeignMixins {
    private static final String OWN_PACKAGE = "com.elfmcys.yesstevemodel.";

    private static List<MixinListing.Config> configs;

    private ForeignMixins() {
    }

    /** The class names of the mixins that are not Yes Steve Model's own, "none (...)", or "unknown (...)" when that cannot be told. */
    static String describe(Class<?> target) {
        return MixinListing.describe(new Game(), target.getName(), OWN_PACKAGE);
    }

    private static final class Game implements MixinListing.Records {
        /** Read once: the mod list does not change while the game runs. */
        @Override
        public List<MixinListing.Config> configs() {
            if (configs == null) {
                ModList mods = ModList.get();
                if (mods == null) {
                    return null;
                }
                List<MixinListing.Config> found = new ArrayList<>();
                try {
                    mods.forEachModFile(file -> {
                        if (file instanceof ModFile modFile) {
                            List<String> listed = new ArrayList<>();
                            for (ModFileParser.MixinConfig declared : modFile.getMixinConfigs()) {
                                listed.add(declared.config());
                            }
                            for (String name : MixinListing.declared(listed, manifestAttribute(modFile))) {
                                found.add(read(modFile, name));
                            }
                        }
                    });
                } catch (RuntimeException | LinkageError unlisted) {
                    return null;
                }
                configs = found;
            }
            return configs;
        }

        @Override
        public List<String> appliedTargets(String mixinClass) {
            try {
                ClassInfo info = ClassInfo.fromCache(mixinClass);
                if (info == null) {
                    return null;
                }
                List<String> targets = new ArrayList<>();
                for (IMixinInfo applied : info.getAppliedMixins()) {
                    for (String target : applied.getTargetClasses()) {
                        targets.add(target.replace('/', '.'));
                    }
                }
                return targets;
            } catch (RuntimeException | LinkageError unknown) {
                return null;
            }
        }

        @Override
        public List<String> appliedTo(String target) {
            try {
                ClassInfo info = ClassInfo.fromCache(target);
                if (info == null) {
                    return null;
                }
                List<String> names = new ArrayList<>();
                for (IMixinInfo applied : info.getAppliedMixins()) {
                    names.add(applied.getClassName());
                }
                return names;
            } catch (RuntimeException | LinkageError unknown) {
                return null;
            }
        }
    }

    /** What the manifest of the mod file names as mixin configurations, or null. */
    private static String manifestAttribute(ModFile file) {
        try {
            return file.getSecureJar().moduleDataProvider().getManifest().getMainAttributes().getValue(MixinListing.MANIFEST_ATTRIBUTE);
        } catch (RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    /** One mixin configuration, read from the jar of its mod. */
    private static MixinListing.Config read(ModFile file, String name) {
        try (Reader reader = Files.newBufferedReader(file.findResource(name), StandardCharsets.UTF_8)) {
            return MixinListing.read(name, reader);
        } catch (Exception | LinkageError unreadable) {
            return new MixinListing.Config(name, null, null);
        }
    }
}
