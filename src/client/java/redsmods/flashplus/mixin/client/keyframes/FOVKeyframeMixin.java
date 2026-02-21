package redsmods.flashplus.mixin.client.keyframes;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import redsmods.flashplus.FlashplusClient;

import java.util.Map;

@Mixin(FOVKeyframe.class)
public class FOVKeyframeMixin {

    @Shadow
    private float fov;

    @Inject(method = "createChange", at = @At("HEAD"))
    private void saveFovOnCreateChange(CallbackInfoReturnable<KeyframeChange> cir) {
        FlashplusClient.fov = this.fov;
    }

    @Inject(method = "createSmoothInterpolatedChange", at = @At("RETURN"))
    private void saveFovOnSmoothInterpolated(Keyframe p1, Keyframe p2, Keyframe p3,
                                             float t0, float t1, float t2, float t3,
                                             float amount, CallbackInfoReturnable<KeyframeChange> cir) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        float f0 = Utils.fovToFocalLength(this.fov);
        float f1 = Utils.fovToFocalLength(((FOVKeyframe)(Object)p1).fov);
        float f2 = Utils.fovToFocalLength(((FOVKeyframe)(Object)p2).fov);
        float f3 = Utils.fovToFocalLength(((FOVKeyframe)(Object)p3).fov);

        float focalLength = com.moulberry.flashback.spline.CatmullRom.value(f0, f1, f2, f3, time1, time2, time3, amount);
        FlashplusClient.fov = Utils.focalLengthToFov(focalLength);
    }

    @Inject(method = "createHermiteInterpolatedChange", at = @At("RETURN"))
    private void saveFovOnHermiteInterpolated(Map<Float, Keyframe> keyframes, float amount,
                                              CallbackInfoReturnable<KeyframeChange> cir) {
        float focalLength = (float) com.moulberry.flashback.spline.Hermite.value(
                com.google.common.collect.Maps.transformValues(keyframes,
                        k -> (double) Utils.fovToFocalLength(((FOVKeyframe)(Object)k).fov)),
                amount
        );
        FlashplusClient.fov = Utils.focalLengthToFov(focalLength);
    }
}