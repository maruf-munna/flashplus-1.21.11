package redsmods.flashplus;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class PanoramaScreenshotHelper {

    public static void takePanorama(Minecraft mc, String outputBase) {
        float savedXRot = mc.player.getXRot();
        float savedYRot = mc.player.getYRot();
        float savedXRotO = mc.player.xRotO;
        float savedYRotO = mc.player.yRotO;

        mc.gameRenderer.setRenderBlockOutline(false);

        try {
            Flashback.getReplayServer().getEditorState().replayVisuals.overrideFov = false;

            File targetDir = new File(outputBase + "_panorama");
            targetDir.mkdirs();

            // Use Minecraft's built-in panorama capture
            mc.grabPanoramixScreenshot(targetDir);
            File screenshotsDir = new File(targetDir, "screenshots");

            // Wait for all 6 faces to finish writing
            for (int i = 0; i < 6; i++) {
                File face = new File(screenshotsDir, "panorama_" + i + ".png");
                long deadline = System.currentTimeMillis() + 10_000;
                long lastSize = -1;
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (face.exists()) {
                        long size = face.length();
                        if (size > 0 && size == lastSize) break;
                        lastSize = size;
                    }
                }
            }

            // Load all 6 faces
            BufferedImage[] faces = new BufferedImage[6];
            for (int i = 0; i < 6; i++) {
                File face = new File(screenshotsDir, "panorama_" + i + ".png");
                faces[i] = ImageIO.read(face);
                if (faces[i] == null) throw new RuntimeException("Face " + i + " failed to load");
            }

            // Async convert to equirectangular
            final BufferedImage[] facesFinal = faces;
            final File equirectFile = new File(outputBase + "_panorama_equirectangular.png");
            Thread convertThread = new Thread(() -> {
                try {
                    BufferedImage equirect = toEquirectangular(facesFinal, 8192, 4096);
                    ImageIO.write(equirect, "png", equirectFile);
                } catch (Exception e) {
                    // log if needed
                }
            });
            convertThread.setDaemon(true);
            convertThread.start();

            Flashback.getReplayServer().getEditorState().replayVisuals.overrideFov = true;

        } catch (Exception e) {
            // log if needed
        } finally {
            mc.player.setXRot(savedXRot);
            mc.player.setYRot(savedYRot);
            mc.player.xRotO = savedXRotO;
            mc.player.yRotO = savedYRotO;
            mc.gameRenderer.setRenderBlockOutline(true);
        }
    }

    /**
     * Converts 6 cubemap faces to an equirectangular (lat/long) projection.
     *
     * Face index convention (matches vanilla panorama order):
     *   0 = Front  (+Z, yaw=0)
     *   1 = Left   (-X, yaw=+90)
     *   2 = Back   (-Z, yaw=+180)
     *   3 = Right  (+X, yaw=-90)
     *   4 = Top    (+Y, pitch=-90)
     *   5 = Bottom (-Y, pitch=+90)
     */
    public static BufferedImage toEquirectangular(BufferedImage[] faces, int outWidth, int outHeight) {
        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        int faceSize = faces[0].getWidth();

        for (int py = 0; py < outHeight; py++) {
            double lat = Math.PI * (0.5 - (double) py / outHeight);
            double cosLat = Math.cos(lat);
            double sinLat = Math.sin(lat);

            for (int px = 0; px < outWidth; px++) {
                double lon = 2.0 * Math.PI * ((double) px / outWidth - 0.5);

                double dx = cosLat * Math.sin(lon);
                double dy = sinLat;
                double dz = cosLat * Math.cos(lon);

                int face;
                double u, v;

                double absX = Math.abs(dx);
                double absY = Math.abs(dy);
                double absZ = Math.abs(dz);

                if (absX >= absY && absX >= absZ) {
                    if (dx > 0) {
                        face = 3;
                        u = -dz / absX;
                        v = -dy / absX;
                    } else {
                        face = 1;
                        u = dz / absX;
                        v = -dy / absX;
                    }
                } else if (absY >= absX && absY >= absZ) {
                    if (dy > 0) {
                        face = 4;
                        u = dx / absY;
                        v = dz / absY;
                    } else {
                        face = 5;
                        u = dx / absY;
                        v = -dz / absY;
                    }
                } else {
                    if (dz > 0) {
                        face = 0;
                        u = dx / absZ;
                        v = -dy / absZ;
                    } else {
                        face = 2;
                        u = -dx / absZ;
                        v = -dy / absZ;
                    }
                }

                if (face == 1) face = 3;
                else if (face == 3) face = 1;

                int sx = (int) Math.min(faceSize - 1, Math.max(0, (u + 1.0) * 0.5 * faceSize));
                int sy = (int) Math.min(faceSize - 1, Math.max(0, (v + 1.0) * 0.5 * faceSize));

                out.setRGB(px, py, faces[face].getRGB(sx, sy));
            }
        }

        return out;
    }
}