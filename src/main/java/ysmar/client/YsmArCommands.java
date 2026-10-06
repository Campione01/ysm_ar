package ysmar.client;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.Text;

/** /ysm_ar status and /ysm_ar reload. Client commands run on the render thread. */
final class YsmArCommands {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");

    private YsmArCommands() {
    }

    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ysm_ar")
                .then(Commands.literal("status").executes(YsmArCommands::status))
                .then(Commands.literal("reload").executes(YsmArCommands::reload)));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        try {
            for (String line : ClientSetup.status()) {
                LOGGER.info("ysm_ar status: {}", line);
                context.getSource().sendSuccess(() -> Component.literal(Text.chat(line)), false);
            }
            return 1;
        } catch (Throwable problem) {
            return failed(context, "status", problem);
        }
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            ClientSetup.reload();
            context.getSource().sendSuccess(() -> Component.literal("ysm_ar: settings re-read, models and meshes dropped"), false);
            return 1;
        } catch (Throwable problem) {
            return failed(context, "reload", problem);
        }
    }

    private static int failed(CommandContext<CommandSourceStack> context, String what, Throwable problem) {
        LOGGER.error("ysm_ar: /ysm_ar {} failed", what, problem);
        context.getSource().sendFailure(Component.literal("ysm_ar: " + what + " failed, see the log"));
        return 0;
    }
}
