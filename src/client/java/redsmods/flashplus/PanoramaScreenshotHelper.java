package redsmods.flashplus;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.stb.STBImageWrite;
import redsmods.flashplus.util.ExrWriter;

public class PanoramaScreenshotHelper {

    public static volatile boolean isCapturingPanorama = false;

    public static void takePanorama(Minecraft mc, String outputBase) {
        float savedXRot = mc.player.getXRot();
        float savedYRot = mc.player.getYRot();
        float savedXRotO = mc.player.xRotO;
        float savedYRotO = mc.player.yRotO;

        mc.gameRenderer.setRenderBlockOutline(false);
        isCapturingPanorama = true;

        try {
            if (Flashback.getReplayServer() != null && Flashback.getReplayServer().getEditorState() != null) {
                Flashback.getReplayServer().getEditorState().replayVisuals.overrideFov = true;
                Flashback.getReplayServer().getEditorState().replayVisuals.overrideFovAmount = 90.0f;
            }
            mc.options.fov().set(90);

            File targetDir = new File(outputBase + "_panorama");
            targetDir.mkdirs();

            // Use Minecraft's built-in panorama capture
            mc.grabPanoramixScreenshot(targetDir);

        } catch (Exception e) {
            Flashplus.LOGGER.error(String.valueOf(e));
        } finally {
            isCapturingPanorama = false;
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

    public static boolean tryConvert(String outputBase) throws IOException {
        Path outbot = Paths.get(outputBase);
        Path panoramaDir = outbot.resolveSibling(outbot.getFileName() + "_panorama");
        System.out.println(panoramaDir.toString());
        File screenshotsDir = panoramaDir.resolve("screenshots").toFile();
        System.out.println(screenshotsDir.toString());


        // Check all 6 faces exist before attempting to read
        for (int i = 0; i < 6; i++) {
            File face = new File(screenshotsDir, "panorama_" + i + ".png");
            if (!face.exists() || !face.canRead()) return false;
        }

        // Load all 6 faces
        BufferedImage[] faces = new BufferedImage[6];
        for (int i = 0; i < 6; i++) {
            File face = new File(screenshotsDir, "panorama_" + i + ".png");
            faces[i] = ImageIO.read(face);
            if (faces[i] == null) return false;
        }

        // Async convert to equirectangular
        final BufferedImage[] facesFinal = faces;
        File equirectFile = panoramaDir.resolve("equirectangular.png").toFile();
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
        return true;
    }
    private static final float[] SRGB_TO_LINEAR_LUT = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255.0f;
            if (c <= 0.04045f) {
                SRGB_TO_LINEAR_LUT[i] = c / 12.92f;
            } else {
                SRGB_TO_LINEAR_LUT[i] = (float) Math.pow((c + 0.055) / 1.055, 2.4);
            }
        }
    }

    public static BufferedImage toEquirectangular(BufferedImage[] faces, int outWidth, int outHeight) {
        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        int faceSize = faces[0].getWidth();

        // Extract face pixel arrays for fast direct memory access
        int[][] facePixels = new int[6][faceSize * faceSize];
        for (int i = 0; i < 6; i++) {
            faces[i].getRGB(0, 0, faceSize, faceSize, facePixels[i], 0, faceSize);
        }
        int[] outPixels = new int[outWidth * outHeight];

        for (int py = 0; py < outHeight; py++) {
            // Latitude theta from 0 (North Pole / Zenith / Up) to PI (South Pole / Nadir / Down)
            double theta = ((double) py + 0.5) / outHeight * Math.PI;
            double sinTheta = Math.sin(theta);
            double y = Math.cos(theta); // +Y is Up, -Y is Down
            int rowOffset = py * outWidth;

            for (int px = 0; px < outWidth; px++) {
                // Longitude phi from -PI to +PI, with center px = outWidth / 2 at phi = 0 (+Z Front)
                double phi = (((double) px + 0.5) / outWidth * 2.0 - 1.0) * Math.PI;
                double x = sinTheta * Math.sin(phi);
                double z = sinTheta * Math.cos(phi);

                double absX = Math.abs(x);
                double absY = Math.abs(y);
                double absZ = Math.abs(z);

                int face;
                double sc, tc;

                // Standard OpenGL / Minecraft CubeMap projection (matching CubeMapTexture layers):
                // Layer 0 (+X, Right): panorama_1.png
                // Layer 1 (-X, Left):  panorama_3.png
                // Layer 2 (+Y, Up):    panorama_4.png (pitch -90: Up/Zenith)
                // Layer 3 (-Y, Down):  panorama_5.png (pitch +90: Down/Nadir)
                // Layer 4 (+Z, Front): panorama_0.png
                // Layer 5 (-Z, Back):  panorama_2.png
                if (absX >= absY && absX >= absZ) {
                    if (x > 0) {
                        face = 1; // +X (Right): panorama_1.png
                        sc = -z / absX;
                        tc = -y / absX;
                    } else {
                        face = 3; // -X (Left): panorama_3.png
                        sc = z / absX;
                        tc = -y / absX;
                    }
                } else if (absY >= absX && absY >= absZ) {
                    if (y > 0) {
                        face = 4; // +Y (Up): panorama_4.png
                        sc = x / absY;
                        tc = z / absY;
                    } else {
                        face = 5; // -Y (Down): panorama_5.png
                        sc = x / absY;
                        tc = -z / absY;
                    }
                } else {
                    if (z > 0) {
                        face = 0; // +Z (Front): panorama_0.png
                        sc = x / absZ;
                        tc = -y / absZ;
                    } else {
                        face = 2; // -Z (Back): panorama_2.png
                        sc = -x / absZ;
                        tc = -y / absZ;
                    }
                }

                // Map [-1, 1] to [0, faceSize - 1]
                double u = (sc + 1.0) * 0.5;
                double v = (tc + 1.0) * 0.5;

                int sx = Math.min(faceSize - 1, Math.max(0, (int) (u * faceSize)));
                int sy = Math.min(faceSize - 1, Math.max(0, (int) (v * faceSize)));

                outPixels[rowOffset + px] = facePixels[face][sy * faceSize + sx];
            }
        }

        out.setRGB(0, 0, outWidth, outHeight, outPixels, 0, outWidth);
        return out;
    }

    public static void convertCubemapToEquirectangular(Path baseFolder, String baseName, int faceSize) {
        int outWidth = faceSize * 4;
        int outHeight = faceSize * 2;
        NativeImage equi = new NativeImage(outWidth, outHeight, false);

        // 1. Cache the 6 faces in memory
        // Standard order: +X, -X, +Y, -Y, +Z, -Z
        String[] suffixes = {"_right", "_left", "_up", "_down", "_front", "_back"};
        NativeImage[] faces = new NativeImage[6];
        try {
            for (int i = 0; i < 6; i++) {
                Path path = baseFolder.resolve(baseName + suffixes[i] + ".png");
                try (var is = Files.newInputStream(path)) {
                    faces[i] = NativeImage.read(is);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // 2. Perform Projection
        for (int v = 0; v < outHeight; v++) {
            // Normalize V to [0, 1] then to latitude [-PI/2, PI/2]
            double theta = (v / (double) outHeight) * Math.PI;

            for (int u = 0; u < outWidth; u++) {
                // Normalize U to [0, 1] then to longitude [-PI, PI]
                double phi = (u / (double) outWidth) * 2 * Math.PI;

                // Spherical to Cartesian (Standard OpenGL-style coordinates)
                double x = Math.sin(theta) * Math.cos(phi);
                double y = Math.cos(theta);
                double z = Math.sin(theta) * Math.sin(phi);

                int faceIndex;
                double uc, vc;
                double absX = Math.abs(x), absY = Math.abs(y), absZ = Math.abs(z);

                // Determine which face and calculate UV (-1 to 1 range)
                if (absX >= absY && absX >= absZ) {
                    faceIndex = x > 0 ? 0 : 1; // Right (+X) or Left (-X)
                    uc = (x > 0 ? -z : z) / absX;
                    vc = -y / absX;
                } else if (absY >= absX && absY >= absZ) {
                    faceIndex = y > 0 ? 2 : 3; // Up (+Y) or Down (-Y)
                    uc = x / absY;
                    vc = (y > 0 ? z : -z) / absY;
                } else {
                    faceIndex = z > 0 ? 4 : 5; // Front (+Z) or Back (-Z)
                    uc = (z > 0 ? x : -x) / absZ;
                    vc = -y / absZ;
                }

                // Map UV (-1 to 1) to Pixel (0 to faceSize - 1)
                int px = (int) Math.min(faceSize - 1, Math.max(0, (0.5 * (uc + 1.0) * faceSize)));
                int py = (int) Math.min(faceSize - 1, Math.max(0, (0.5 * (vc + 1.0) * faceSize)));

                // Use pixel copy to ensure color bit-order is preserved
                equi.setPixel(u, v, faces[faceIndex].getPixel(px, py));
            }
        }

        // 3. Save and Cleanup
        try {
            equi.writeToFile(baseFolder.resolve(baseName + "_panorama.png"));
            for (NativeImage img : faces) {
                if (img != null) img.close();
            }
            equi.close();
            if (FlashplusClient.deleteCubeMap) {
                for (int i = 0; i < 6; i++) {
                    Path facePath = baseFolder.resolve(baseName + suffixes[i] + ".png");
                    try {
                        boolean deleted = Files.deleteIfExists(facePath);
                        if (deleted) {
                            System.out.println("Deleted source face: " + facePath.getFileName());
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to delete " + facePath.getFileName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Captures 6 cubemap faces synchronously at the current replay tick using Minecraft's panoramic screenshot pipeline.
     */
    public static void capturePanoramaAtTick(Minecraft mc, File tempDir) {
        float savedXRot = mc.player.getXRot();
        float savedYRot = mc.player.getYRot();
        float savedXRotO = mc.player.xRotO;
        float savedYRotO = mc.player.yRotO;

        mc.gameRenderer.setRenderBlockOutline(false);
        isCapturingPanorama = true;

        com.moulberry.flashback.state.EditorState editorState = (Flashback.getReplayServer() != null) ? Flashback.getReplayServer().getEditorState() : null;
        boolean savedOverrideFov = false;
        float savedOverrideFovAmount = 70.0f;
        int savedOptionsFov = mc.options.fov().get();

        try {
            if (editorState != null) {
                savedOverrideFov = editorState.replayVisuals.overrideFov;
                savedOverrideFovAmount = editorState.replayVisuals.overrideFovAmount;
                editorState.replayVisuals.overrideFov = true;
                editorState.replayVisuals.overrideFovAmount = 90.0f;
            }
            mc.options.fov().set(90);

            tempDir.mkdirs();
            new File(tempDir, "screenshots").mkdirs();
            mc.grabPanoramixScreenshot(tempDir);

        } catch (Exception e) {
            Flashplus.LOGGER.error("[FlashPlus] Error capturing panoramic screenshot: " + e.getMessage(), e);
        } finally {
            isCapturingPanorama = false;
            mc.player.setXRot(savedXRot);
            mc.player.setYRot(savedYRot);
            mc.player.xRotO = savedXRotO;
            mc.player.yRotO = savedYRotO;
            mc.gameRenderer.setRenderBlockOutline(true);
            if (editorState != null) {
                editorState.replayVisuals.overrideFov = savedOverrideFov;
                editorState.replayVisuals.overrideFovAmount = savedOverrideFovAmount;
            }
            mc.options.fov().set(savedOptionsFov);
        }
    }

    /**
     * Reads cubemap faces from tempDir, stitches them into an equirectangular image,
     * converts to linear float radiance, and writes .exr and/or .hdr files.
     * Cleans up tempDir upon completion.
     */
    public static class CaptureResult {
        public final Map<String, Object> lightingMetadata;
        public final Map<String, Object> panoramaMetadata;

        public CaptureResult(Map<String, Object> lightingMetadata, Map<String, Object> panoramaMetadata) {
            this.lightingMetadata = lightingMetadata;
            this.panoramaMetadata = panoramaMetadata;
        }
    }

    public static Map<String, Object> extractLightingData(float[] sh) {
        Map<String, Object> light = new LinkedHashMap<>();

        // Band 0 (Ambient)
        float c0 = 0.282095f;
        float ambR = Math.max(0.0f, sh[0] * c0);
        float ambG = Math.max(0.0f, sh[9] * c0);
        float ambB = Math.max(0.0f, sh[18] * c0);
        float ambIntensity = (float) (0.2126 * ambR + 0.7152 * ambG + 0.0722 * ambB);

        // Band 1 Directional Vector (Y1=Up/Down, Y2=South/North, Y3=East/West)
        float dirRx = sh[3], dirRy = sh[1], dirRz = sh[2];
        float dirGx = sh[12], dirGy = sh[10], dirGz = sh[11];
        float dirBx = sh[21], dirBy = sh[19], dirBz = sh[20];

        float lx = (float) (0.2126 * dirRx + 0.7152 * dirGx + 0.0722 * dirBx);
        float ly = (float) (0.2126 * dirRy + 0.7152 * dirGy + 0.0722 * dirBy);
        float lz = (float) (0.2126 * dirRz + 0.7152 * dirGz + 0.0722 * dirBz);

        float dirLen = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        float[] sunDirMc = new float[]{0.0f, 1.0f, 0.0f};
        float[] sunDirBlender = new float[]{0.0f, 0.0f, 1.0f};
        if (dirLen > 1e-6f) {
            float nx = lx / dirLen;
            float ny = ly / dirLen;
            float nz = lz / dirLen;
            sunDirMc = new float[]{nx, ny, nz};
            // Minecraft: +X East, +Y Up, +Z South
            // Blender:   +X East, +Y North (-Z mc), +Z Up (+Y mc)
            sunDirBlender = new float[]{nx, -nz, ny};
        }

        float c1 = 0.488603f * (float) (2.0 * Math.PI / 3.0);
        float sunR = Math.max(0.0f, (float) Math.sqrt(dirRx * dirRx + dirRy * dirRy + dirRz * dirRz) * c1);
        float sunG = Math.max(0.0f, (float) Math.sqrt(dirGx * dirGx + dirGy * dirGy + dirGz * dirGz) * c1);
        float sunB = Math.max(0.0f, (float) Math.sqrt(dirBx * dirBx + dirBy * dirBy + dirBz * dirBz) * c1);
        float rawSunIntensity = (float) (0.2126 * sunR + 0.7152 * sunG + 0.0722 * sunB);

        // Normalize color channels to [0..1] range preserving true chromaticity
        float maxSun = Math.max(sunR, Math.max(sunG, sunB));
        float[] sunColorNorm = maxSun > 1e-6f
                ? new float[]{sunR / maxSun, sunG / maxSun, sunB / maxSun}
                : new float[]{1.0f, 1.0f, 1.0f};

        float maxAmb = Math.max(ambR, Math.max(ambG, ambB));
        float[] ambColorNorm = maxAmb > 1e-6f
                ? new float[]{ambR / maxAmb, ambG / maxAmb, ambB / maxAmb}
                : new float[]{1.0f, 1.0f, 1.0f};

        float mult = Math.max(0.1f, (float) FlashplusClient.lightingMultiplier);

        // In Blender Cycles & EEVEE, physical outdoor daylight Sun light energy is 10-25, and World Background is 1.0-1.5
        float sunEnergyBlender = rawSunIntensity * 50.0f * mult;
        float ambStrengthBlender = ambIntensity * 10.0f * mult;

        light.put("sun_direction", sunDirMc);
        light.put("sun_direction_blender", sunDirBlender);
        light.put("sun_intensity", rawSunIntensity * mult);
        light.put("sun_energy_blender", sunEnergyBlender);
        light.put("sun_color", sunColorNorm);
        light.put("ambient_intensity", ambIntensity * mult);
        light.put("ambient_strength_blender", ambStrengthBlender);
        light.put("ambient_color", ambColorNorm);

        return light;
    }

    /**
     * Reads cubemap faces from tempDir, optionally calculates Spherical Harmonics lighting,
     * optionally stitches and writes .exr and/or .hdr files.
     * Cleans up tempDir upon completion.
     */
    public static CaptureResult processCapture(
            File tempDir,
            Path outputDir,
            int tick,
            boolean doLighting,
            boolean saveExr,
            boolean saveHdr,
            int outWidth,
            int outHeight
    ) {
        return processCapture(tempDir, outputDir, tick, doLighting, saveExr, saveHdr, outWidth, outHeight, null);
    }

    public static CaptureResult processCapture(
            File tempDir,
            Path outputDir,
            int tick,
            boolean doLighting,
            boolean saveExr,
            boolean saveHdr,
            int outWidth,
            int outHeight,
            Map<String, Object> rawLevelData
    ) {
        File screenshotsDir = new File(tempDir, "screenshots");
        BufferedImage[] faces = new BufferedImage[6];
        try {
            // Wait up to 10 seconds for Minecraft's IO-Worker to finish saving all 6 faces
            for (int i = 0; i < 6; i++) {
                File face = new File(screenshotsDir, "panorama_" + i + ".png");
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    if (isPngComplete(face)) {
                        break;
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!face.exists() || !face.canRead()) {
                    Flashplus.LOGGER.warn("[FlashPlus] Missing cubemap face after waiting: " + face.getAbsolutePath());
                    return null;
                }
                faces[i] = ImageIO.read(face);
                if (faces[i] == null) {
                    Flashplus.LOGGER.warn("[FlashPlus] Could not decode face image: " + face.getAbsolutePath());
                    return null;
                }
            }
        } catch (Exception e) {
            Flashplus.LOGGER.error("[FlashPlus] Error reading cubemap faces for tick " + tick + ": " + e.getMessage(), e);
            return null;
        } finally {
            deleteRecursively(tempDir);
        }

        Map<String, Object> lightingMeta = null;
        if (doLighting) {
            float[] sh = computeSphericalHarmonics(faces);
            lightingMeta = new LinkedHashMap<>();
            lightingMeta.put("tick", tick);
            if (rawLevelData != null) {
                lightingMeta.putAll(rawLevelData);
            }
            lightingMeta.put("sh", sh);
            lightingMeta.putAll(extractLightingData(sh));
        }

        Map<String, Object> panoMeta = null;
        if ((saveExr || saveHdr) && outputDir != null) {
            panoMeta = new LinkedHashMap<>();
            panoMeta.put("tick", tick);

            BufferedImage equirect = toEquirectangular(faces, outWidth, outHeight);

            // Convert sRGB to linear float RGB using precomputed LUT
            int[] rgbPixels = equirect.getRGB(0, 0, outWidth, outHeight, null, 0, outWidth);
            float[] linearRgb = new float[outWidth * outHeight * 3];
            int idx = 0;
            for (int i = 0; i < rgbPixels.length; i++) {
                int rgb = rgbPixels[i];
                linearRgb[idx++] = SRGB_TO_LINEAR_LUT[(rgb >> 16) & 0xFF];
                linearRgb[idx++] = SRGB_TO_LINEAR_LUT[(rgb >> 8) & 0xFF];
                linearRgb[idx++] = SRGB_TO_LINEAR_LUT[rgb & 0xFF];
            }

            String baseFileName = String.format("panorama_%06d", tick);

            if (saveExr) {
                String exrFileName = baseFileName + ".exr";
                File exrFile = outputDir.resolve(exrFileName).toFile();
                try {
                    ExrWriter.writeRgbHalfExr(exrFile, outWidth, outHeight, linearRgb);
                    panoMeta.put("exr", exrFileName);
                } catch (Exception e) {
                    Flashplus.LOGGER.error("[FlashPlus] Failed to write EXR for tick " + tick + ": " + e.getMessage(), e);
                }
            }

            if (saveHdr) {
                String hdrFileName = baseFileName + ".hdr";
                File hdrFile = outputDir.resolve(hdrFileName).toFile();
                try {
                    File parent = hdrFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    boolean success = STBImageWrite.stbi_write_hdr(hdrFile.getAbsolutePath(), outWidth, outHeight, 3, linearRgb);
                    if (success) {
                        panoMeta.put("hdr", hdrFileName);
                    } else {
                        Flashplus.LOGGER.error("[FlashPlus] STBImageWrite failed to write HDR for tick " + tick);
                    }
                } catch (Exception e) {
                    Flashplus.LOGGER.error("[FlashPlus] Failed to write HDR for tick " + tick + ": " + e.getMessage(), e);
                }
            }
        }

        return new CaptureResult(lightingMeta, panoMeta);
    }

    public static Map<String, Object> processAndSavePanorama(
            File tempDir,
            Path outputDir,
            int tick,
            boolean saveExr,
            boolean saveHdr,
            int outWidth,
            int outHeight
    ) {
        CaptureResult res = processCapture(tempDir, outputDir, tick, FlashplusClient.exportLightingSh, saveExr, saveHdr, outWidth, outHeight);
        if (res == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        if (res.lightingMetadata != null) {
            result.putAll(res.lightingMetadata);
        }
        if (res.panoramaMetadata != null) {
            result.putAll(res.panoramaMetadata);
        }
        return result;
    }

    /**
     * Computes Order-2 Spherical Harmonics (27 coefficients: 9 Red, 9 Green, 9 Blue)
     * by integrating over the 6 cubemap faces with solid-angle weighting.
     */
    public static float[] computeSphericalHarmonics(BufferedImage[] faces) {
        final int N = 16;
        float[] shR = new float[9];
        float[] shG = new float[9];
        float[] shB = new float[9];
        float weightSum = 0.0f;

        for (int faceIdx = 0; faceIdx < 6; faceIdx++) {
            BufferedImage face = faces[faceIdx];
            int origW = face.getWidth();
            int origH = face.getHeight();

            for (int py = 0; py < N; py++) {
                double v = ((py + 0.5) / N) * 2.0 - 1.0;
                int srcY = Math.min(origH - 1, Math.max(0, (int) (((py + 0.5) / N) * origH)));

                for (int px = 0; px < N; px++) {
                    double u = ((px + 0.5) / N) * 2.0 - 1.0;
                    int srcX = Math.min(origW - 1, Math.max(0, (int) (((px + 0.5) / N) * origW)));

                    int rgb = face.getRGB(srcX, srcY);
                    float rLin = SRGB_TO_LINEAR_LUT[(rgb >> 16) & 0xFF];
                    float gLin = SRGB_TO_LINEAR_LUT[(rgb >> 8) & 0xFF];
                    float bLin = SRGB_TO_LINEAR_LUT[rgb & 0xFF];

                    double x, y, z;
                    switch (faceIdx) {
                        case 0: // Front (+Z)
                            x = u;   y = -v;  z = 1.0; break;
                        case 1: // Right (+X)
                            x = 1.0; y = -v;  z = -u;  break;
                        case 2: // Back (-Z)
                            x = -u;  y = -v;  z = -1.0; break;
                        case 3: // Left (-X)
                            x = -1.0; y = -v; z = u;   break;
                        case 4: // Top (+Y)
                            x = u;   y = 1.0; z = v;   break;
                        case 5: // Bottom (-Y)
                        default:
                            x = u;   y = -1.0; z = -v; break;
                    }

                    double lenSq = x * x + y * y + z * z;
                    double len = Math.sqrt(lenSq);
                    float nx = (float) (x / len);
                    float ny = (float) (y / len);
                    float nz = (float) (z / len);
                    float dw = (float) (1.0 / (len * lenSq));

                    float y0 = 0.282095f;
                    float y1 = 0.488603f * ny;
                    float y2 = 0.488603f * nz;
                    float y3 = 0.488603f * nx;
                    float y4 = 1.092548f * nx * ny;
                    float y5 = 1.092548f * ny * nz;
                    float y6 = 0.315392f * (3.0f * nz * nz - 1.0f);
                    float y7 = 1.092548f * nx * nz;
                    float y8 = 0.546274f * (nx * nx - ny * ny);

                    float[] Y = {y0, y1, y2, y3, y4, y5, y6, y7, y8};

                    for (int i = 0; i < 9; i++) {
                        float ydw = Y[i] * dw;
                        shR[i] += rLin * ydw;
                        shG[i] += gLin * ydw;
                        shB[i] += bLin * ydw;
                    }
                    weightSum += dw;
                }
            }
        }

        float norm = (float) (4.0 * Math.PI / weightSum);
        float[] result = new float[27];
        for (int i = 0; i < 9; i++) {
            result[i]      = shR[i] * norm;
            result[9 + i]  = shG[i] * norm;
            result[18 + i] = shB[i] * norm;
        }
        return result;
    }

    public static float srgbToLinear(float c) {
        if (c <= 0.04045f) {
            return c / 12.92f;
        } else {
            return (float) Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private static boolean isPngComplete(File file) {
        if (!file.exists() || file.length() < 12) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len < 12) return false;
            raf.seek(len - 12);
            byte[] tail = new byte[12];
            raf.readFully(tail);
            // Check for IEND chunk: length 0 (4 bytes), 'I','E','N','D' (4 bytes), CRC (4 bytes: 0xAE, 0x42, 0x60, 0x82)
            return tail[0] == 0 && tail[1] == 0 && tail[2] == 0 && tail[3] == 0
                    && tail[4] == 'I' && tail[5] == 'E' && tail[6] == 'N' && tail[7] == 'D'
                    && (tail[8] & 0xFF) == 0xAE && (tail[9] & 0xFF) == 0x42
                    && (tail[10] & 0xFF) == 0x60 && (tail[11] & 0xFF) == 0x82;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}