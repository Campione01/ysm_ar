package ysmar.takeover;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import ysmar.YsmArConfig;

import java.util.List;
import java.util.Set;

/**
 * Applies the mixins of this mod, both on vanilla classes, only next to the one build of Yes Steve Model the take-over
 * is written for; without that build the vanilla classes stay untouched. The mixin on PoseStack.scale is for the
 * late read alone and is left out as well when the settings file says takeover.late_read=false at this moment.
 */
public final class TakeoverMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final String SCALE_MIXIN = ".PoseStackMixin";

    private boolean apply;
    private boolean lateRead;

    @Override
    public void onLoad(String mixinPackage) {
        String version = null;
        try {
            for (ModInfo mod : LoadingModList.get().getMods()) {
                if (mod.getModId().equals(YsmBuild.MOD_ID)) {
                    version = mod.getVersion().toString();
                }
            }
            apply = YsmBuild.VERSION.equals(version);
        } catch (Throwable problem) {
            apply = false;
            LOGGER.warn("ysm_ar: the mod list could not be read, no mixin of this mod is applied: {}", problem.toString());
            return;
        }
        boolean setting = apply && lateReadSetting();
        lateRead = setting;
        LOGGER.info("ysm_ar: mixins: LivingEntityRenderer.isBodyVisible {}, PoseStack.scale {} (Yes Steve Model: {}, needed: {})",
                apply ? "applied" : "not applied", lateRead ? "applied" : apply ? "not applied (takeover.late_read=false)" : "not applied",
                version == null ? "not installed" : version, YsmBuild.VERSION);
    }

    /** The settings file as it is before the game starts; whatever goes wrong leaves the default, true. */
    private static boolean lateReadSetting() {
        try {
            return YsmArConfig.lateReadAtStartup(FMLPaths.CONFIGDIR.get());
        } catch (Throwable problem) {
            return true;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return mixinClassName != null && mixinClassName.endsWith(SCALE_MIXIN) ? apply && lateRead : apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
