package redsmods.flashplus.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import redsmods.flashplus.PanoramaScreenshotHelper;

@Mixin(value = Camera.class, priority = 1200)
public abstract class CameraMixin {

    @Shadow
    private boolean isPanoramicMode;

    @Redirect(
        method = "setupPerspective",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"
        )
    )
    private void flashplus$enforceSquareAspectForPanorama(Projection projection, float depthNear, float depthFar, float fov, float width, float height) {
        if (this.isPanoramicMode || PanoramaScreenshotHelper.isCapturingPanorama) {
            float size = Math.max(width, height);
            projection.setupPerspective(depthNear, depthFar, 90.0f, size, size);
        } else {
            projection.setupPerspective(depthNear, depthFar, fov, width, height);
        }
    }

    @Inject(
        method = "createProjectionMatrixForCulling",
        at = @At("HEAD"),
        cancellable = true
    )
    private void flashplus$cullingMatrixForPanorama(CallbackInfoReturnable<Matrix4f> cir) {
        if (this.isPanoramicMode || PanoramaScreenshotHelper.isCapturingPanorama) {
            Matrix4f matrix = new Matrix4f();
            matrix.setPerspective((float) Math.toRadians(90.0), 1.0f, 0.05f, 1024.0f);
            cir.setReturnValue(matrix);
        }
    }
}
