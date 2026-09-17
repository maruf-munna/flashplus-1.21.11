package redsmods.flashplus.live;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Evaluates a {@link LiveMotionData} dataset without creating poses that are not in the file.
 */
public final class LiveMotionSampler {
    private final LiveMotionData data;
    private int lastIndex = 0;

    public LiveMotionSampler(LiveMotionData data) {
        this.data = data;
    }

    public LiveMotionData getData() {
        return data;
    }

    public synchronized SampledPose sampleAtSeconds(double seconds) {
        List<LiveMotionData.LiveMotionPoint> points = data.frames();
        if (points.isEmpty()) {
            return null;
        }

        if (seconds <= points.getFirst().timestamp()) {
            return toPose(points.getFirst());
        }

        if (seconds >= points.getLast().timestamp()) {
            return toPose(points.getLast());
        }

        int low = 0;
        int high = points.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (points.get(middle).timestamp() <= seconds) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        lastIndex = Math.max(0, low - 1);
        return toPose(points.get(lastIndex));
    }

    /**
     * Samples against Flashback's replayed real-time clock (System.currentTimeMillis).
     *
     * The importer selects the last pose at or before the requested millisecond. This makes a
     * dropped recording frame a hold, not a blend, and never pulls a pose backwards from a
     * future frame.
     */
    public synchronized SampledPose sampleAtEpochMillis(double epochMillis) {
        if (!data.hasWallClockTiming()) {
            return null;
        }

        List<LiveMotionData.LiveMotionPoint> points = data.frames();
        if (epochMillis < points.getFirst().epochMillis() || epochMillis > points.getLast().epochMillis()) {
            return null;
        }

        int low = 0;
        int high = points.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (points.get(middle).epochMillis() <= epochMillis) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return toPose(points.get(Math.max(0, low - 1)));
    }

    private static SampledPose toPose(LiveMotionData.LiveMotionPoint p) {
        return createPose(p.position(), new Quaternionf(p.rotation()), p.fov());
    }

    private static SampledPose createPose(Vec3 pos, Quaternionf rot, float fov) {
        // Forward vector (0, 0, -1) transformed by rotation
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(rot);
        float pitch = (float) Math.toDegrees(Math.asin(-Math.clamp(forward.y, -1.0f, 1.0f)));
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));

        return new SampledPose(pos, rot, fov, yaw, pitch);
    }

    public record SampledPose(
            Vec3 position,
            Quaternionf rotation,
            float fov,
            float yaw,
            float pitch
    ) {
    }
}
