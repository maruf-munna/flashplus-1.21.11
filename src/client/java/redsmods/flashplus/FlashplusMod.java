package redsmods.flashplus;

import com.moulberry.flashback.Flashback;
import redsmods.flashplus.live.LiveMotionTrackingRecorder;

/**
 * Quiet client-side recording API for integrations with other mods.
 *
 * <p>Call these from Minecraft's client thread. The methods never send chat feedback:
 * {@code true} means the requested operation was accepted and {@code false} means it was
 * rejected or threw an error. {@link #endRecording()} uses Flashback's own finish flow, which
 * opens Flashback's normal replay save dialog.</p>
 */
public final class FlashplusMod {
    private FlashplusMod() {
    }

    /** Starts FlashPlus live camera tracking. */
    public static boolean startTrack() {
        try {
            return LiveMotionTrackingRecorder.get().start().success();
        } catch (RuntimeException exception) {
            Flashplus.LOGGER.error("[FlashPlus] Could not start live motion tracking.", exception);
            return false;
        }
    }

    /** Stops live camera tracking and writes its JSON file before returning. */
    public static boolean endTrack() {
        try {
            return LiveMotionTrackingRecorder.get().stopBlocking().success();
        } catch (RuntimeException exception) {
            Flashplus.LOGGER.error("[FlashPlus] Could not stop live motion tracking.", exception);
            return false;
        }
    }

    /** Starts Flashback's normal replay recording. */
    public static boolean startRecording() {
        try {
            if (Flashback.RECORDER != null) {
                return false;
            }

            Flashback.startRecordingReplay();
            return Flashback.RECORDER != null;
        } catch (RuntimeException exception) {
            Flashplus.LOGGER.error("[FlashPlus] Could not start Flashback recording.", exception);
            return false;
        }
    }

    /** Finishes Flashback recording and opens Flashback's normal replay save dialog. */
    public static boolean endRecording() {
        try {
            if (Flashback.RECORDER == null) {
                return false;
            }

            Flashback.finishRecordingReplay();
            return true;
        } catch (RuntimeException exception) {
            Flashplus.LOGGER.error("[FlashPlus] Could not finish Flashback recording.", exception);
            return false;
        }
    }
}
