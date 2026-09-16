package redsmods.flashplus;

import net.fabricmc.api.ClientModInitializer;
import org.joml.Quaternionf;
import redsmods.flashplus.live.LiveMotionData;
import redsmods.flashplus.live.LiveMotionSampler;
import redsmods.flashplus.live.LiveMotionTrackingCommands;
import redsmods.flashplus.live.LiveMotionTrackingConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlashplusClient implements ClientModInitializer {
	public static List<Map<String, Object>> trackedmodels = new ArrayList<>();
	public static boolean cjson = true;
	public static boolean etjson = true;
	public static boolean useQuaternion = true;
	public static boolean lockRoll = false;

	// Lighting export options
	public static boolean exportLightingSh = true;
	public static int lightingIntervalTicks = 20;
	public static int lightingMultiplier = 1;

	public static float fov;
	public static double roll;
	public static Quaternionf quaternion;

	// Imported live motion tracking camera path options
	public static boolean useImportedCameraPath = false;
	public static String importedCameraJsonPath = "";
	public static LiveMotionData importedLiveMotionData = null;
	public static LiveMotionSampler importedLiveMotionSampler = null;
	public static String importedCameraStatus = "No file loaded";
	public static double importedCameraTickOffset = 0.0;
	public static boolean importedCameraOverrideFov = true;
	public static boolean importedCameraOverrideTime = false;
	public static volatile LiveMotionSampler.SampledPose currentExportPose = null;

	public static boolean loadImportedCameraJson(Path path) {
		try {
			LiveMotionData data = LiveMotionData.load(path);
			importedLiveMotionData = data;
			importedLiveMotionSampler = new LiveMotionSampler(data);
			importedCameraJsonPath = path.toAbsolutePath().toString();
			importedCameraStatus = String.format("Loaded %d frames (%.2fs @ %d FPS)",
					data.frames().size(), data.durationSeconds(), data.fps());
			Flashplus.LOGGER.info("[FlashPlus] Successfully loaded camera data: {}", importedCameraStatus);
			return true;
		} catch (Exception e) {
			importedLiveMotionData = null;
			importedLiveMotionSampler = null;
			importedCameraStatus = "Error: " + e.getMessage();
			Flashplus.LOGGER.error("[FlashPlus] Failed to load camera data from " + path, e);
			return false;
		}
	}

	public static float getFOV() {
		return fov;
	}

	@Override
	public void onInitializeClient() {
		LiveMotionTrackingConfig.load();
		LiveMotionTrackingCommands.register();
	}
}
