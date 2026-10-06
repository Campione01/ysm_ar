package ysmar;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;

@Mod(value = YsmAr.MOD_ID, dist = Dist.CLIENT)
public final class YsmAr {
    public static final String MOD_ID = "ysm_ar";
    public static final String VERSION = "0.2.3";

    public YsmAr() {
        try {
            ysmar.client.ClientSetup.init();
        } catch (Throwable problem) {
            LogManager.getLogger(MOD_ID).error("ysm_ar: start-up failed, the mod stays inert", problem);
        }
    }
}
