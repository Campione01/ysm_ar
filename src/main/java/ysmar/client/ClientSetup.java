package ysmar.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.PrewarmBudget;
import ysmar.RenderThread;
import ysmar.YsmAr;
import ysmar.YsmArConfig;
import ysmar.YsmArModels;
import ysmar.YsmArStats;
import ysmar.YsmArSubmitter;
import ysmar.core.NativeLoader;
import ysmar.takeover.Takeover;
import ysmar.takeover.TakeoverStats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Client start-up: settings, the event listeners, the status text. Nothing is loaded here. */
public final class ClientSetup {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");

    private static Path configDirectory;
    private static long statsDeadline;
    private static int ticksSinceSweep;

    private ClientSetup() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void init() {
        configDirectory = FMLPaths.CONFIGDIR.get();
        YsmArModels.gameDirectory(FMLPaths.GAMEDIR.get());
        YsmArConfig config = YsmArConfig.load(configDirectory);
        // Lowest priority and not for cancelled events: whoever else decided about the entity goes first.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLivingEvent.Pre.class,
                event -> Standalone.onRenderLiving((RenderLivingEvent.Pre<?, ?>) event));
        // After the test front-end: an event it cancels is not seen by the take-over.
        Takeover.init();
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, event -> PrewarmBudget.newFrame(YsmArConfig.current().prewarmNanos));
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, YsmArCommands::register);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, ClientSetup::onClientTick);
        RenderThread.rule(RenderSystem::isOnRenderThread);
        LOGGER.info("ysm_ar {}: {}", YsmAr.VERSION, config.describe());
    }

    static void reload() {
        Standalone.reset();
        Takeover.reset();
        YsmArModels.clear();
        YsmArStats.reset();
        YsmArConfig config = YsmArConfig.load(configDirectory);
        YsmArSubmitter.settingsChanged();
        statsDeadline = 0;
        LOGGER.info("ysm_ar: settings re-read, models dropped: {}", config.describe());
    }

    static List<String> status() {
        YsmArConfig config = YsmArConfig.current();
        List<String> lines = new ArrayList<>();
        lines.add("ysm_ar " + YsmAr.VERSION + ": " + config.describe());
        if (config.problem != null) {
            lines.add("settings: " + config.problem);
        }
        String failure = NativeLoader.failure();
        lines.add("native library: " + (failure != null ? "FAILED: " + failure
                : NativeLoader.isLoaded() ? "loaded from " + NativeLoader.location() : "not loaded yet (loads with the first model)"));
        lines.add("Accelerated Rendering: " + YsmArSubmitter.describeAr());
        lines.add("front-end: " + Standalone.describe());
        lines.addAll(Takeover.status());
        lines.add("models: " + YsmArModels.describeStates());
        lines.addAll(YsmArModels.describeEntries());
        lines.add("since start or reload: " + YsmArStats.total().describe());
        lines.add("calls refused off the render thread since start: " + RenderThread.describe());
        return lines;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        YsmArConfig config = YsmArConfig.current();
        // Twenty ticks are a second or more: often enough for a time that is counted in minutes.
        if (config.modelIdleNanos > 0 && ++ticksSinceSweep >= 20) {
            ticksSinceSweep = 0;
            if (YsmArSubmitter.mergesMeshes()) {
                YsmArModels.dropIdle(System.nanoTime(), config.modelIdleNanos);
            }
        }
        int interval = config.statsIntervalSeconds;
        if (interval <= 0) {
            return;
        }
        long now = System.nanoTime();
        if (statsDeadline == 0) {
            statsDeadline = now + interval * 1_000_000_000L;
        } else if (now >= statsDeadline) {
            statsDeadline = now + interval * 1_000_000_000L;
            LOGGER.info("ysm_ar stats, last {} s: {} models: {} take-over: {}", interval, YsmArStats.roll().describe(),
                    YsmArModels.describeStates(), TakeoverStats.roll().describe());
        }
    }
}
