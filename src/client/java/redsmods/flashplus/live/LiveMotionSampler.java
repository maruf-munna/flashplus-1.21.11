package redsmods.flashplus.live;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Continuously evaluates and interpolates a {@link LiveMotionData} dataset at any arbitrary timestamp.
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

        // Fast sequential search with fallback to binary search
        int count = points.size();
        int lower = lastIndex;
        if (lower >= count - 1 || points.get(lower).timestamp() > seconds) {
            lower = 0;
        }

        // Advance sequentially if near
        while (lower + 1 < count && points.get(lower + 1).timestamp() <= seconds) {
            lower++;
        }

        // If sequential check didn't locate the bracket, binary search
        if (lower + 1 < count && points.get(lower).timestamp() > seconds) {
            int low = 0;
            int high = count - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (points.get(mid).timestamp() <= seconds) {
                    lower = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        }

        lastIndex = lower;
        int upper = Math.min(lower + 1, count - 1);

        LiveMotionData.LiveMotionPoint p0 = points.get(lower);
        LiveMotionData.LiveMotionPoint p1 = points.get(upper);

        double span = p1.timestamp() - p0.timestamp();
        if (span <= 0.0) {
            return toPose(p0);
        }

        float alpha = (float) Math.clamp((seconds - p0.timestamp()) / span, 0.0, 1.0);

        // SLERP quaternion
        Quaternionf rot = new Quaternionf(p0.rotation()).slerp(p1.rotation(), alpha);

        // LERP position
        Vec3 pos = p0.position().lerp(p1.position(), alpha);

        // LERP FOV & time
        float fov = (float) (p0.fov() + (p1.fov() - p0.fov()) * alpha);
        double worldTime = p0.worldTime() + (p1.worldTime() - p0.worldTime()) * alpha;

        return createPose(pos, rot, fov, worldTime);
    }

    private static SampledPose toPose(LiveMotionData.LiveMotionPoint p) {
        return createPose(p.position(), new Quaternionf(p.rotation()), p.fov(), p.worldTime());
    }

    private static SampledPose createPose(Vec3 pos, Quaternionf rot, float fov, double worldTime) {
        // Forward vector (0, 0, -1) transformed by rotation
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(rot);
        float pitch = (float) Math.toDegrees(Math.asin(-Math.clamp(forward.y, -1.0f, 1.0f)));
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));

        return new SampledPose(pos, rot, fov, worldTime, yaw, pitch);
    }

    public record SampledPose(
            Vec3 position,
            Quaternionf rotation,
            float fov,
            double worldTime,
            float yaw,
            float pitch
    ) {
    }
}
