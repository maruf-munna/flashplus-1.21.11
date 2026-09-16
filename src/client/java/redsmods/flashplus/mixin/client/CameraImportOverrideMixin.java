package redsmods.flashplus.mixin.client;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redsmods.flashplus.FlashplusClient;
import redsmods.flashplus.live.LiveMotionSampler;

@Mixin(Camera.class)
public abstract class CameraImportOverrideMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    @Final
    private Quaternionf rotation;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void flashPlus$overrideCameraWithImportedMotion(float partialTicks, CallbackInfo ci) {
        if (Flashback.isExporting() && FlashplusClient.useImportedCameraPath) {
            LiveMotionSampler.SampledPose pose = FlashplusClient.currentExportPose;
            if (pose != null) {
                Vec3 pos = pose.position();
                this.setPosition(pos.x, pos.y, pos.z);
                this.rotation.set(pose.rotation());
                this.xRot = pose.pitch();
                this.yRot = pose.yaw();
            }
        }
    }
}
