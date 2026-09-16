package redsmods.flashplus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed live motion tracking dataset.
 */
public record LiveMotionData(
        String format,
        int version,
        int fps,
        double durationSeconds,
        Double startReplayTick,
        List<LiveMotionPoint> frames,
        Path filePath
) {
    public static LiveMotionData load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("File does not exist: " + path);
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                throw new IOException("Invalid JSON root: expected object in " + path.getFileName());
            }

            JsonObject root = rootElement.getAsJsonObject();
            String format = root.has("format") ? root.get("format").getAsString() : "unknown";
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            int fps = root.has("fps") ? root.get("fps").getAsInt() : 60;
            double durationSeconds = root.has("duration_seconds") ? root.get("duration_seconds").getAsDouble() : 0.0;
            Double startReplayTick = root.has("start_replay_tick") ? root.get("start_replay_tick").getAsDouble() : null;

            JsonArray keyframesArray = null;
            if (root.has("keyframes") && root.get("keyframes").isJsonArray()) {
                keyframesArray = root.getAsJsonArray("keyframes");
            } else if (root.has("frames") && root.get("frames").isJsonArray()) {
                keyframesArray = root.getAsJsonArray("frames");
            }

            if (keyframesArray == null || keyframesArray.isEmpty()) {
                throw new IOException("No keyframes found in " + path.getFileName());
            }

            List<LiveMotionPoint> points = new ArrayList<>(keyframesArray.size());
            for (JsonElement elem : keyframesArray) {
                if (!elem.isJsonObject()) continue;
                JsonObject kf = elem.getAsJsonObject();

                long tick = kf.has("tick") ? kf.get("tick").getAsLong() : points.size();
                double timestamp = kf.has("timestamp") ? kf.get("timestamp").getAsDouble() : (double) tick / (fps > 0 ? fps : 60);
                Double replayTick = kf.has("replay_tick") ? kf.get("replay_tick").getAsDouble() : null;

                double x = 0.0, y = 0.0, z = 0.0;
                if (kf.has("position") && kf.get("position").isJsonArray()) {
                    JsonArray posArr = kf.getAsJsonArray("position");
                    if (posArr.size() >= 3) {
                        x = posArr.get(0).getAsDouble();
                        y = posArr.get(1).getAsDouble();
                        z = posArr.get(2).getAsDouble();
                    }
                }

                float qw = kf.has("w") ? kf.get("w").getAsFloat() : 1.0f;
                float qx = kf.has("x") ? kf.get("x").getAsFloat() : 0.0f;
                float qy = kf.has("y") ? kf.get("y").getAsFloat() : 0.0f;
                float qz = kf.has("z") ? kf.get("z").getAsFloat() : 0.0f;

                Quaternionf rot = new Quaternionf(qx, qy, qz, qw);
                rot.normalize();

                float fov = kf.has("fov") ? kf.get("fov").getAsFloat() : 70.0f;
                double worldTime = kf.has("time") ? kf.get("time").getAsDouble() : 0.0;

                points.add(new LiveMotionPoint(
                        timestamp,
                        tick,
                        replayTick,
                        new Vec3(x, y, z),
                        rot,
                        fov,
                        worldTime
                ));
            }

            if (points.isEmpty()) {
                throw new IOException("Parsed zero valid keyframe points from " + path.getFileName());
            }

            if (durationSeconds <= 0.0 && !points.isEmpty()) {
                durationSeconds = points.getLast().timestamp();
            }

            return new LiveMotionData(
                    format,
                    version,
                    fps,
                    durationSeconds,
                    startReplayTick,
                    Collections.unmodifiableList(points),
                    path
            );
        }
    }

    public record LiveMotionPoint(
            double timestamp,
            long tick,
            Double replayTick,
            Vec3 position,
            Quaternionf rotation,
            float fov,
            double worldTime
    ) {
    }
}
