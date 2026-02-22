package redsmods.flashplus.mixin.client.keyframes;

import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redsmods.flashplus.FlashplusClient;

@Mixin(MinecraftKeyframeHandler.class)
public class MinecraftKeyframeHandlerMixin {
    @Inject(method = "applyCameraPosition", at = @At("RETURN"))
    private void injectSaveRoll(Vector3d position, double yaw, double pitch, double roll, CallbackInfo ci) {
        if (roll > -0.01 && roll < 0.01) {
            FlashplusClient.roll = 0;
        } else {
            FlashplusClient.roll = roll;
        }
    }
}