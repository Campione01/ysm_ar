package ysmar;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.ar.ArBridge;
import ysmar.core.NativeLoader;

/**
 * Hands a posed model to Accelerated Rendering. This class links without Accelerated Rendering; everything that
 * names its types is in ysmar.ar and is only touched after the mod list says it is installed.
 */
public final class YsmArSubmitter {
    public static final String AR_MOD_ID = "acceleratedrendering";
    public static final String AR_VERSION = "1.0.14-1.21.1-alpha";

    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final int UNKNOWN = 0;
    private static final int USABLE = 1;
    private static final int UNUSABLE = 2;
    private static final int BROKEN = 3;

    private static int arState = UNKNOWN;
    private static String arNote = "not looked at yet";

    private YsmArSubmitter() {
    }

    /**
     * Draws the model through Accelerated Rendering. attributes: 14 floats per bone in the sorted bone order of the
     * entry. Render thread only: from another thread the call is refused (see RenderThread). True only if the whole
     * model was submitted; on false nothing useful was drawn and the caller draws the model its own way.
     *
     * A call that fails because of what the caller handed in (a missing argument, a buffer source that throws) is
     * refused and counted; only a failure of the model itself takes the entry out of use.
     */
    public static boolean submit(ModelEntry entry, float[] attributes, PoseStack.Pose root, MultiBufferSource buffers,
                                 ResourceLocation texture, int light, int overlay) {
        if (!RenderThread.allows(RenderThread.Call.SUBMIT)) {
            return false;
        }
        if (entry == null || entry.data() == null) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_READY);
            return false;
        }
        if (attributes == null || root == null || buffers == null || texture == null) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            return false;
        }
        if (!arUsable()) {
            YsmArStats.reject(YsmArStats.Rejection.AR_MISSING);
            return false;
        }
        try {
            return ArBridge.submit(entry, attributes, root, buffers, texture, light, overlay);
        } catch (Throwable problem) {
            failed(entry, problem);
        }
        return false;
    }

    /**
     * The first half of submit() for a caller that decides early and draws late: everything that can refuse happens
     * here, the Extract of these attributes included. Returns what emit() takes, or null with the rejection counted.
     * Nothing is drawn yet.
     */
    public static Object prepare(ModelEntry entry, float[] attributes, MultiBufferSource buffers, ResourceLocation texture) {
        if (!RenderThread.allows(RenderThread.Call.SUBMIT)) {
            return null;
        }
        if (entry == null || entry.data() == null) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_READY);
            return null;
        }
        if (attributes == null || buffers == null || texture == null) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            return null;
        }
        if (!arUsable()) {
            YsmArStats.reject(YsmArStats.Rejection.AR_MISSING);
            return null;
        }
        try {
            return ArBridge.prepare(entry, attributes, buffers, texture);
        } catch (Throwable problem) {
            failed(entry, problem);
        }
        return null;
    }

    /** True while the model still holds the poses of this prepared submit: no other Extract of the model came since. */
    public static boolean holdsExtract(ModelEntry entry, Object prepared) {
        try {
            return prepared != null && ArBridge.holdsExtract(prepared);
        } catch (Throwable problem) {
            failed(entry, problem);
        }
        return false;
    }

    /** Extracts again for a prepared submit, with attributes of now. False: refused, and the earlier poses are gone. */
    public static boolean extractAgain(ModelEntry entry, Object prepared, float[] attributes) {
        try {
            return prepared != null && ArBridge.extractAgain(prepared, attributes);
        } catch (Throwable problem) {
            failed(entry, problem);
        }
        return false;
    }

    /** The second half: draws what prepare() or extractAgain() extracted. True only if the whole model was submitted. */
    public static boolean emit(ModelEntry entry, Object prepared, PoseStack.Pose root, int light, int overlay) {
        try {
            return prepared != null && ArBridge.emit(prepared, root, light, overlay);
        } catch (Throwable problem) {
            failed(entry, problem);
        }
        return false;
    }

    private static void failed(ModelEntry entry, Throwable problem) {
        if (problem instanceof LinkageError) {
            arState = BROKEN;
            arNote = "its classes do not link: " + NativeLoader.describe(problem);
            LOGGER.error("ysm_ar: Accelerated Rendering cannot be used, the mod stays inert: {}", arNote);
        } else {
            entry.disable("submit failed: " + NativeLoader.describe(problem));
            LOGGER.error("ysm_ar: {} is taken out of use after an error while it was submitted", Text.clean(entry.fileName()), problem);
        }
        YsmArStats.reject(YsmArStats.Rejection.EXCEPTION);
    }

    /** True while the mod is enabled and the installed Accelerated Rendering is the build this mod works with. */
    public static boolean usable() {
        return arUsable();
    }

    /** True while the mod is enabled, Accelerated Rendering is usable and its own switches allow entity acceleration. */
    public static boolean ready() {
        if (!arUsable()) {
            return false;
        }
        try {
            return ArBridge.togglesOn();
        } catch (LinkageError problem) {
            arState = BROKEN;
            arNote = "its classes do not link: " + NativeLoader.describe(problem);
            LOGGER.error("ysm_ar: Accelerated Rendering cannot be used, the mod stays inert: {}", arNote);
            return false;
        } catch (RuntimeException problem) {
            return false;
        }
    }

    /**
     * True while Accelerated Rendering is usable and is drawing the level. Screens, the HUD and model previews run
     * the same entity renderers outside of that; submit() refuses them.
     */
    public static boolean renderingLevel() {
        if (!arUsable()) {
            return false;
        }
        try {
            return ArBridge.renderingLevel();
        } catch (LinkageError problem) {
            arState = BROKEN;
            arNote = "its classes do not link: " + NativeLoader.describe(problem);
            LOGGER.error("ysm_ar: Accelerated Rendering cannot be used, the mod stays inert: {}", arNote);
            return false;
        } catch (RuntimeException problem) {
            return false;
        }
    }

    /** Looks at the mod list again after the settings were re-read. A link failure stays final. */
    public static void settingsChanged() {
        if (arState != BROKEN) {
            arState = UNKNOWN;
            arNote = "not looked at yet";
        }
    }

    public static String describeAr() {
        boolean usable = arUsable();
        if (!usable) {
            return YsmArConfig.current().enabled ? arNote : "not asked (enabled=false)";
        }
        try {
            return arNote + "; " + ArBridge.describe();
        } catch (Throwable problem) {
            return arNote + "; state unknown (" + NativeLoader.describe(problem) + ")";
        }
    }

    private static boolean arUsable() {
        YsmArConfig config = YsmArConfig.current();
        if (!config.enabled) {
            return false;
        }
        if (arState == UNKNOWN) {
            ModList mods = ModList.get();
            if (mods == null) {
                return false;
            }
            var container = mods.getModContainerById(AR_MOD_ID);
            if (container.isEmpty()) {
                arState = UNUSABLE;
                arNote = "not installed";
                LOGGER.info("ysm_ar: Accelerated Rendering is not installed, nothing is changed");
            } else {
                String version = container.get().getModInfo().getVersion().toString();
                if (config.arVersionCheck && !AR_VERSION.equals(version)) {
                    arState = UNUSABLE;
                    arNote = "version " + version + " is installed, this mod was written against " + AR_VERSION + " (ar_version_check=false lifts the check)";
                    LOGGER.warn("ysm_ar: Accelerated Rendering {}, nothing is changed", arNote);
                } else {
                    arState = USABLE;
                    arNote = "version " + version;
                }
            }
        }
        return arState == USABLE;
    }
}
