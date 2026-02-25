package redsmods.flashplus;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.exporting.PerfectFrames;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.PanoramicScreenshotParameters;
import org.joml.Vector3f;

import java.io.File;

public class PanoramaScreenshotHelper {

    /**
     * Takes a 6-sided panorama screenshot, waiting for a perfect frame before
     * each capture. Drop-in replacement for mc.grabPanoramixScreenshot().
     *
     * @param mc         the Minecraft instance
     * @param outputBase base path string (no extension) — a "_panorama" folder
     *                   will be created next to it containing panorama_0..5.png
     */
    public static void takePanorama(Minecraft mc, String outputBase) {
        int originalWidth  = mc.getWindow().getWidth();
        int originalHeight = mc.getWindow().getHeight();
        RenderTarget renderTarget = mc.getMainRenderTarget();

        float savedXRot  = mc.player.getXRot();
        float savedYRot  = mc.player.getYRot();
        float savedXRotO = mc.player.xRotO;
        float savedYRotO = mc.player.yRotO;

        mc.gameRenderer.setRenderBlockOutline(false);

        try {
            mc.gameRenderer.setPanoramicScreenshotParameters(
                    new PanoramicScreenshotParameters(
                            new Vector3f(mc.gameRenderer.getMainCamera().forwardVector())
                    )
            );

            mc.getWindow().setWidth(4096);
            mc.getWindow().setHeight(4096);
            renderTarget.resize(4096, 4096);

            File targetDir = new File(outputBase + "_panorama");
            targetDir.mkdirs();

            for (int i = 0; i < 6; i++) {
                // Mirror vanilla's rotation logic, relative to the player's original yaw
                switch (i) {
                    case 0 -> { mc.player.setYRot(savedYRot);                mc.player.setXRot(0.0F);    }
                    case 1 -> { mc.player.setYRot((savedYRot + 90.0F)  % 360.0F); mc.player.setXRot(0.0F);    }
                    case 2 -> { mc.player.setYRot((savedYRot + 180.0F) % 360.0F); mc.player.setXRot(0.0F);    }
                    case 3 -> { mc.player.setYRot((savedYRot - 90.0F)  % 360.0F); mc.player.setXRot(0.0F);    }
                    case 4 -> { mc.player.setYRot(savedYRot);                mc.player.setXRot(-90.0F);  }
                    case 5 -> { mc.player.setYRot(savedYRot);                mc.player.setXRot(90.0F);   }
                }

                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();

                mc.gameRenderer.updateCamera(DeltaTracker.ONE);
                mc.gameRenderer.renderLevel(DeltaTracker.ONE);

                // Wait for a fully-settled frame before capturing
                PerfectFrames.waitUntilFrameReady();

                Screenshot.grab(
                        mc.gameDirectory,
                        "panorama_" + i + ".png",
                        renderTarget,
                        4,
                        component -> {}
                );

                // Wait for the file to be flushed, then move it into the target dir
                File src = new File(mc.gameDirectory, "screenshots/panorama_" + i + ".png");
                long deadline = System.currentTimeMillis() + 10_000;
                while (!src.exists() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                src.renameTo(new File(targetDir, "panorama_" + i + ".png"));
            }

        } catch (Exception e) {
//            FlashplusClient.LOGGER.error("PanoramaScreenshotHelper: failed to capture panorama", e);
        } finally {
            // Always restore everything regardless of failure
            mc.player.setXRot(savedXRot);
            mc.player.setYRot(savedYRot);
            mc.player.xRotO = savedXRotO;
            mc.player.yRotO = savedYRotO;
            mc.gameRenderer.setRenderBlockOutline(true);
            mc.getWindow().setWidth(originalWidth);
            mc.getWindow().setHeight(originalHeight);
            renderTarget.resize(originalWidth, originalHeight);
            mc.gameRenderer.setPanoramicScreenshotParameters(null);
        }
    }
}