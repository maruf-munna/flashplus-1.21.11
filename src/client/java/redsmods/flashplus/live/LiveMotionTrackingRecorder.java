package redsmods.flashplus.live;

import com.google.gson.stream.JsonWriter;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import redsmods.flashplus.Flashplus;
import redsmods.flashplus.FlashplusClient;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the camera pose during rendering and writes a constant-rate track after recording stops.
 *
 * Missing source samples are represented by repeating the last real pose.  This is deliberately
 * a zero-order hold: interpolating a gap can invent a camera orientation (especially while F5
 * switches between first- and third-person) that the game never actually rendered.
 */
public final class LiveMotionTrackingRecorder {
    private static final LiveMotionTrackingRecorder INSTANCE = new LiveMotionTrackingRecorder();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH_mm_ss");

    private boolean recording;
    private boolean finalizing;
    private long startedAtNanos;
    private long lastRawCaptureNanos;
    private long rawCaptureIntervalNanos;
    private int recordingFps;
    private Path recordingDirectory;
    private final List<RawFrame> rawFrames = new ArrayList<>();

    private LiveMotionTrackingRecorder() {
    }

    public static LiveMotionTrackingRecorder get() {
        return INSTANCE;
    }

    public synchronized Result start() {
        if (recording) {
            return Result.failure("Live motion tracking is already recording.");
        }
        if (finalizing) {
            return Result.failure("The previous recording is still being finalized.");
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Result.failure("Join a world before starting live motion tracking.");
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized()) {
            return Result.failure("The camera is not ready yet. Try again after the world has rendered.");
        }

        LiveMotionTrackingConfig config = LiveMotionTrackingConfig.get();
        recordingFps = config.getFps();
        recordingDirectory = config.getOutputDirectory();
        startedAtNanos = System.nanoTime();
        lastRawCaptureNanos = Long.MIN_VALUE;
        rawCaptureIntervalNanos = Math.max(1L, 1_000_000_000L / ((long) recordingFps * 2L));
        rawFrames.clear();
        recording = true;
        captureRawFrame(startedAtNanos, camera, minecraft);

        return Result.success("Live motion tracking started at " + recordingFps + " FPS.");
    }

    /** Called after each game render. This is deliberately not tied to Minecraft's 20 TPS game tick. */
    public synchronized void captureRenderFrame() {
        if (!recording) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastRawCaptureNanos < rawCaptureIntervalNanos) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (minecraft.level == null || camera == null || !camera.isInitialized()) {
            return;
        }
        captureRawFrame(now, camera, minecraft);
    }

    public synchronized Result stop() {
        if (!recording) {
            return Result.failure("Live motion tracking is not recording.");
        }

        Minecraft minecraft = Minecraft.getInstance();
        long stoppedAtNanos = System.nanoTime();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (minecraft.level != null && camera != null && camera.isInitialized()) {
            captureRawFrame(stoppedAtNanos, camera, minecraft);
        }

        recording = false;
        finalizing = true;
        double durationSeconds = Math.max(0.0, (stoppedAtNanos - startedAtNanos) / 1_000_000_000.0);
        List<RawFrame> framesToWrite = List.copyOf(rawFrames);
        int fpsToWrite = recordingFps;
        Path outputDirectory = recordingDirectory;
        Path outputFile = createOutputPath(outputDirectory);

        Thread.ofVirtual().name("FlashPlus-LiveMotionWriter").start(() -> finalizeRecording(
                framesToWrite, fpsToWrite, durationSeconds, outputFile));

        return Result.success("Finalizing " + framesToWrite.size() + " captured camera poses to " + outputFile + ".");
    }

    /**
     * Stops tracking and waits for the JSON write to finish. This is intended for integrations
     * that need a truthful success/failure result rather than a queued finalization result.
     */
    public synchronized Result stopBlocking() {
        if (!recording) {
            return Result.failure("Live motion tracking is not recording.");
        }

        Minecraft minecraft = Minecraft.getInstance();
        long stoppedAtNanos = System.nanoTime();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (minecraft.level != null && camera != null && camera.isInitialized()) {
            captureRawFrame(stoppedAtNanos, camera, minecraft);
        }

        recording = false;
        finalizing = true;
        double durationSeconds = Math.max(0.0, (stoppedAtNanos - startedAtNanos) / 1_000_000_000.0);
        List<RawFrame> framesToWrite = List.copyOf(rawFrames);
        int fpsToWrite = recordingFps;
        Path outputDirectory = recordingDirectory;
        Path outputFile = createOutputPath(outputDirectory);

        return finalizeRecording(framesToWrite, fpsToWrite, durationSeconds, outputFile)
                ? Result.success("Live motion tracking saved to " + outputFile + ".")
                : Result.failure("Failed to save live motion tracking; see the log for details.");
    }

    private void captureRawFrame(long capturedAtNanos, Camera camera, Minecraft minecraft) {
        Vec3 position = camera.position();
        Quaternionf rotation = new Quaternionf(camera.rotation());
        // Flashback applies roll and camera shake immediately before rendering. Its mixin has
        // already saved that final quaternion by the time this render-tail hook runs.
        if (Flashback.getReplayServer() != null && FlashplusClient.quaternion != null) {
            rotation.set(FlashplusClient.quaternion);
        }
        double elapsedSeconds = Math.max(0.0, (capturedAtNanos - startedAtNanos) / 1_000_000_000.0);
        long epochMillis = System.currentTimeMillis();

        rawFrames.add(new RawFrame(elapsedSeconds, epochMillis, position.x, position.y, position.z,
                rotation, camera.getFov()));
        lastRawCaptureNanos = capturedAtNanos;
    }

    private boolean finalizeRecording(List<RawFrame> frames, int fps, double durationSeconds, Path outputFile) {
        try {
            if (frames.isEmpty()) {
                throw new IOException("No camera poses were captured.");
            }
            Files.createDirectories(outputFile.getParent());
            Path partialFile = outputFile.resolveSibling(outputFile.getFileName() + ".part");
            writeHeldJson(partialFile, frames, fps, durationSeconds);
            try {
                Files.move(partialFile, outputFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partialFile, outputFile);
            }
            Flashplus.LOGGER.info("[FlashPlus] Live motion tracking written to {}", outputFile);
            return true;
        } catch (Exception exception) {
            Flashplus.LOGGER.error("[FlashPlus] Failed to finalize live motion tracking.", exception);
            return false;
        } finally {
            synchronized (this) {
                finalizing = false;
            }
        }
    }

    private static void writeHeldJson(Path path, List<RawFrame> rawFrames, int fps, double durationSeconds) throws IOException {
        long finalFrameIndex = Math.max(0L, (long) Math.ceil(durationSeconds * fps));
        long startEpochMillis = rawFrames.getFirst().epochMillis();

        try (Writer fileWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(fileWriter)) {
            writer.setIndent("  ");
            writer.beginObject();
            writer.name("format").value("flashplus-live-motion-tracking");
            writer.name("version").value(2);
            writer.name("fps").value(fps);
            writer.name("gap_handling").value("hold_previous_pose");
            writer.name("duration_seconds").value(durationSeconds);
            writer.name("start_epoch_millis").value(startEpochMillis);
            writer.name("keyframes").beginArray();

            int lowerIndex = 0;
            for (long frameIndex = 0; frameIndex <= finalFrameIndex; frameIndex++) {
                double timestamp = frameIndex / (double) fps;
                while (lowerIndex + 1 < rawFrames.size()
                        && rawFrames.get(lowerIndex + 1).elapsedSeconds() <= timestamp) {
                    lowerIndex++;
                }
                // Never blend the lower and upper records. If rendering stalls, every output
                // slot until the next captured pose is an exact repeat of this lower pose.
                RawFrame frame = rawFrames.get(lowerIndex);
                writeFrame(writer, frameIndex, timestamp, frame);
            }

            writer.endArray();
            writer.endObject();
        }
    }

    private static void writeFrame(JsonWriter writer, long frameIndex, double timestamp, RawFrame frame) throws IOException {
        writer.beginObject();
        writer.name("tick").value(frameIndex);
        writer.name("timestamp").value(timestamp);
        writer.name("position").beginArray().value(frame.x()).value(frame.y()).value(frame.z()).endArray();
        writer.name("w").value(frame.rotation().w);
        writer.name("x").value(frame.rotation().x);
        writer.name("y").value(frame.rotation().y);
        writer.name("z").value(frame.rotation().z);
        writer.name("fov").value(frame.fov());
        writer.name("epoch_millis").value(frame.epochMillis());
        writer.endObject();
    }

    private static Path createOutputPath(Path directory) {
        String stem = "live-motion-" + FILE_TIME.format(LocalDateTime.now());
        Path candidate = directory.resolve(stem + "CJ.json");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(stem + "-" + suffix++ + "CJ.json");
        }
        return candidate;
    }

    private record RawFrame(double elapsedSeconds, long epochMillis, double x, double y, double z,
                            Quaternionf rotation, float fov) {
    }

    public record Result(boolean success, String message) {
        static Result success(String message) {
            return new Result(true, message);
        }

        static Result failure(String message) {
            return new Result(false, message);
        }
    }
}
