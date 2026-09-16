package redsmods.flashplus.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redsmods.flashplus.live.LiveMotionTrackingRecorder;

/** Runs once per rendered frame, after Minecraft has prepared the active camera. */
@Mixin(GameRenderer.class)
public class LiveMotionTrackingRenderMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void flashPlus$captureLiveCamera(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        LiveMotionTrackingRecorder.get().captureRenderFrame();
    }
}
