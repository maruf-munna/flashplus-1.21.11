package redsmods.flashplus.mixin.client.keyframes;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.visuals.CameraRotation;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import redsmods.flashplus.FlashplusClient;

@Mixin(CameraRotation.class)
public class CameraRotationMixin {
    @Inject(method = "modifyViewQuaternion", at = @At("RETURN"), cancellable = true)
    private static void saveViewQuaternion(Quaternionf quaternionf, CallbackInfoReturnable<Quaternionf> cir) {
        if (Flashback.isExporting() && FlashplusClient.useImportedCameraPath && FlashplusClient.currentExportPose != null) {
            cir.setReturnValue(new Quaternionf(FlashplusClient.currentExportPose.rotation()));
        }
        FlashplusClient.quaternion = cir.getReturnValue();
    }
}