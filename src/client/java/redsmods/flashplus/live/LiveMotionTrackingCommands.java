package redsmods.flashplus.live;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.moulberry.flashback.Flashback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/** Registers commands handled entirely on the local client. */
public final class LiveMotionTrackingCommands {
    private LiveMotionTrackingCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("recordflashback")
                    .then(ClientCommands.literal("on").executes(context -> {
                        if (!context.getSource().attended()) {
                            return 0;
                        }
                        if (Flashback.RECORDER != null) {
                            context.getSource().sendError(Component.literal(
                                    "[FlashPlus] Flashback recording is already running."));
                            return 0;
                        }

                        Flashback.startRecordingReplay();
                        context.getSource().sendFeedback(Component.literal(
                                "[FlashPlus] Started Flashback recording."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        if (!context.getSource().attended()) {
                            return 0;
                        }
                        if (Flashback.RECORDER == null) {
                            context.getSource().sendError(Component.literal(
                                    "[FlashPlus] No Flashback recording is running."));
                            return 0;
                        }

                        // Use Flashback's own finish flow so its normal save dialog is shown.
                        Flashback.finishRecordingReplay();
                        context.getSource().sendFeedback(Component.literal(
                                "[FlashPlus] Finishing Flashback recording; choose where to save it."));
                        return 1;
                    }))
            );

            dispatcher.register(ClientCommands.literal("recordlivemotiontracking")
                        .then(ClientCommands.literal("on").executes(context -> {
                            if (!context.getSource().attended()) {
                                return 0;
                            }
                            LiveMotionTrackingRecorder.Result result = LiveMotionTrackingRecorder.get().start();
                            sendResult(context.getSource(), result);
                            return result.success() ? 1 : 0;
                        }))
                        .then(ClientCommands.literal("off").executes(context -> {
                            if (!context.getSource().attended()) {
                                return 0;
                            }
                            LiveMotionTrackingRecorder.Result result = LiveMotionTrackingRecorder.get().stop();
                            sendResult(context.getSource(), result);
                            return result.success() ? 1 : 0;
                        }))
                        .then(ClientCommands.literal("config")
                                .then(ClientCommands.literal("fps")
                                        .then(ClientCommands.argument("value", IntegerArgumentType.integer())
                                                .executes(context -> updateFps(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "value")))))
                                .then(ClientCommands.literal("path")
                                        .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> updatePath(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "value"))))))
            );
        });
    }

    private static int updateFps(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, int fps) {
        try {
            LiveMotionTrackingConfig.get().setFps(fps);
            source.sendFeedback(Component.literal("[FlashPlus] Live motion tracking FPS set to " + fps + "."));
            return 1;
        } catch (Exception exception) {
            source.sendError(Component.literal("[FlashPlus] " + exception.getMessage()));
            return 0;
        }
    }

    private static int updatePath(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String path) {
        try {
            LiveMotionTrackingConfig.get().setOutputDirectory(path);
            source.sendFeedback(Component.literal("[FlashPlus] Live motion tracking output path set to "
                    + LiveMotionTrackingConfig.get().getOutputDirectory()));
            return 1;
        } catch (Exception exception) {
            source.sendError(Component.literal("[FlashPlus] " + exception.getMessage()));
            return 0;
        }
    }

    private static void sendResult(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                                   LiveMotionTrackingRecorder.Result result) {
        if (result.success()) {
            source.sendFeedback(Component.literal("[FlashPlus] " + result.message()));
        } else {
            source.sendError(Component.literal("[FlashPlus] " + result.message()));
        }
    }
}
