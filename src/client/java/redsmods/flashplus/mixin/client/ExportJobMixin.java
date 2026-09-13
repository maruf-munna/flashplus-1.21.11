package redsmods.flashplus.mixin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.*;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redsmods.flashplus.Flashplus;
import redsmods.flashplus.FlashplusClient;
import redsmods.flashplus.PanoramaScreenshotHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mixin(value = ExportJob.class, remap = false)
public abstract class ExportJobMixin {

	@Shadow @Final private ExportSettings settings;
	@Shadow private double currentTickDouble;

	@Unique
	private List<Map<String, Object>> flashPlus$allCameraKeyframes;

	@Unique
	private List<Map<String, Object>> flashPlus$trackedData;

	@Unique
	private List<Map<String, Object>> flashPlus$panoramaMetadataList;

	@Unique
	private List<Map<String, Object>> flashPlus$lightingMetadataList;

	@Unique
	private ExecutorService flashPlus$panoramaExecutor;

	@Unique
	private Path flashPlus$panoramaOutputDir;

	@Unique
	private Gson flashPlus$gson;

	@Unique
	private float flashPlus$previousFov;

	// ENABLE THESE
	private boolean cameraJson = true;
	private boolean entityTracking = true;
	private int flashPlus$tick = 0;
	private Integer originalFov = 70;
//	private boolean flashPlus$panoramaDone = false;

	/**
	 * Initialize data structures at the start of doExport, right after renderStartTime is set
	 */
	@Inject(
			method = "doExport",
			at = @At(
					value = "FIELD",
					target = "Lcom/moulberry/flashback/exporting/ExportJob;renderStartTime:J",
					shift = At.Shift.AFTER
			),
			remap = false
	)
	private void flashPlus$initializeDataStructures(
			VideoWriter videoWriter,
			SaveableFramebufferQueue downloader,
			CallbackInfo ci) {

		this.flashPlus$allCameraKeyframes = new ArrayList<>();
		this.flashPlus$trackedData = new ArrayList<>();
		this.flashPlus$gson = new GsonBuilder().setPrettyPrinting().create();
		this.flashPlus$tick = 0;
		this.flashPlus$previousFov = FlashplusClient.fov;

		this.flashPlus$panoramaMetadataList = Collections.synchronizedList(new ArrayList<>());
		this.flashPlus$lightingMetadataList = Collections.synchronizedList(new ArrayList<>());

		boolean isSingleTickPanorama = FlashplusClient.takePanorama && settings.endTick() == settings.startTick();
		boolean shouldCaptureAny = (FlashplusClient.exportLightingSh || FlashplusClient.panoramaExportEnabled) && !isSingleTickPanorama;

		if (shouldCaptureAny) {
			this.flashPlus$panoramaExecutor = Executors.newSingleThreadExecutor();
			Path outputPath = this.settings.output();
			Path parentFolder = outputPath.getParent();
			String fileName = outputPath.getFileName().toString();
			String nameWithoutExtension = fileName.contains(".")
					? fileName.substring(0, fileName.lastIndexOf("."))
					: fileName;

			if (FlashplusClient.panoramaExportEnabled && (FlashplusClient.exportPanoramaExr || FlashplusClient.exportPanoramaHdr)) {
				this.flashPlus$panoramaOutputDir = parentFolder.resolve(nameWithoutExtension + "_panoramas");
				try {
					Files.createDirectories(this.flashPlus$panoramaOutputDir);
				} catch (IOException e) {
					Flashplus.LOGGER.error("[FlashPlus] Failed to create panorama output directory: " + e.getMessage(), e);
				}
			} else {
				this.flashPlus$panoramaOutputDir = null;
			}
		} else {
			this.flashPlus$panoramaExecutor = null;
			this.flashPlus$panoramaOutputDir = null;
		}
	}

	/**
	 * Capture camera and entity data for each frame, right before saveable.audioBuffer is set
	 */
	@Inject(
			method = "doExport",
			at = @At(
					value = "INVOKE",
					target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V",
					shift = At.Shift.AFTER
			),
			remap = false
	)
	private void flashPlus$captureFrameData(
			VideoWriter videoWriter,
			SaveableFramebufferQueue downloader,
			CallbackInfo ci
	) {
		// Access what you need through fields or other means
		Minecraft minecraft = Minecraft.getInstance();
		ReplayServer replayServer = Flashback.getReplayServer();

		if (replayServer == null) return;

		// You can access this.currentTickDouble directly since it's a field
		double partialClientTick = this.currentTickDouble - (int)this.currentTickDouble;

		if (FlashplusClient.cjson && !(FlashplusClient.takePanorama && settings.endTick() == settings.startTick())) {
			flashPlus$captureCameraKeyframe(flashPlus$tick, partialClientTick, replayServer);
		}

		if (FlashplusClient.etjson && !(FlashplusClient.takePanorama && settings.endTick() == settings.startTick())) {
			flashPlus$captureEntityData(flashPlus$tick, partialClientTick);
		}

		boolean isSingleTickPanorama = FlashplusClient.takePanorama && settings.endTick() == settings.startTick();
		if (!isSingleTickPanorama) {
			boolean shouldCaptureLighting = FlashplusClient.exportLightingSh && (flashPlus$tick % Math.max(1, FlashplusClient.lightingIntervalTicks) == 0);
			boolean shouldCapturePanorama = FlashplusClient.panoramaExportEnabled && (flashPlus$tick % Math.max(1, FlashplusClient.panoramaIntervalTicks) == 0);

			if (shouldCaptureLighting || shouldCapturePanorama) {
				flashPlus$triggerCapture(flashPlus$tick, shouldCaptureLighting, shouldCapturePanorama);
			}
		}

		flashPlus$tick++;
	}

	@Unique
	private void flashPlus$triggerCapture(int currentTick, boolean doLighting, boolean doPanorama) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		long startNanos = System.nanoTime();

		// Record raw Minecraft environment and lighting state from render thread
		final Map<String, Object> rawLevelData = new LinkedHashMap<>();
		try {
			long gameTime = mc.level.getLevelData().getGameTime();
			long timeOfDay = (gameTime % 24000L + 24000L) % 24000L;
			rawLevelData.put("time", timeOfDay);
			rawLevelData.put("game_time", gameTime);
			rawLevelData.put("dimension", mc.level.dimension().identifier().toString());
			rawLevelData.put("rain_level", mc.level.getRainLevel(1.0f));
			rawLevelData.put("thunder_level", mc.level.getThunderLevel(1.0f));
			rawLevelData.put("sky_darken", mc.level.getSkyDarken());

			float celestialAngle = (float) timeOfDay / 24000.0f;
			rawLevelData.put("celestial_angle", celestialAngle);
			rawLevelData.put("sun_angle_deg", celestialAngle * 360.0f);
			rawLevelData.put("is_day", timeOfDay < 12000L);
			rawLevelData.put("has_skylight", mc.level.dimensionType().hasSkyLight());
			rawLevelData.put("has_ceiling", mc.level.dimensionType().hasCeiling());
			rawLevelData.put("ambient_light_level", mc.level.dimensionType().ambientLight());
		} catch (Throwable t) {
			Flashplus.LOGGER.warn("[FlashPlus] Could not read raw level environment data: " + t.getMessage());
		}

		File tempDir;
		try {
			tempDir = Files.createTempDirectory("flashplus_pano_tick_" + currentTick + "_").toFile();
		} catch (IOException e) {
			Flashplus.LOGGER.error("[FlashPlus] Failed to create temp dir for capture at tick " + currentTick, e);
			return;
		}

		// Perform cubemap capture on render thread
		PanoramaScreenshotHelper.capturePanoramaAtTick(mc, tempDir);

		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		double frameBudgetMs = 1000.0 / Math.max(1.0, this.settings.framerate());
		if (elapsedMs > frameBudgetMs) {
			Flashplus.LOGGER.warn("[FlashPlus] Cubemap capture at tick {} took {}ms (frame budget: {}ms)",
					currentTick, elapsedMs, String.format("%.2f", frameBudgetMs));
		}

		// Dispatch processing to background worker
		if (this.flashPlus$panoramaExecutor != null) {
			final Path outDir = this.flashPlus$panoramaOutputDir;
			final boolean saveExr = doPanorama && FlashplusClient.exportPanoramaExr;
			final boolean saveHdr = doPanorama && FlashplusClient.exportPanoramaHdr;
			this.flashPlus$panoramaExecutor.submit(() -> {
				try {
					PanoramaScreenshotHelper.CaptureResult res = PanoramaScreenshotHelper.processCapture(
							tempDir, outDir, currentTick, doLighting, saveExr, saveHdr, 4096, 2048, rawLevelData
					);
					if (res != null) {
						if (res.lightingMetadata != null) {
							flashPlus$lightingMetadataList.add(res.lightingMetadata);
						}
						if (res.panoramaMetadata != null) {
							flashPlus$panoramaMetadataList.add(res.panoramaMetadata);
						}
					}
				} catch (Throwable t) {
					Flashplus.LOGGER.error("[FlashPlus] Error in background capture processing for tick " + currentTick, t);
				}
			});
		}
	}

	/**
	 * Write JSON files after the main loop completes, right before submitDownloadedFrames is called the final time
	 */
	@Inject(
			method = "doExport",
			at = @At(
					value = "INVOKE",
					target = "Lcom/moulberry/flashback/exporting/ExportJob;submitDownloadedFrames(Lcom/moulberry/flashback/exporting/VideoWriter;Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;Z)V",
					ordinal = 1,
					shift = At.Shift.BEFORE
			),
			remap = false
	)
	private void flashPlus$writeJsonFiles(
			VideoWriter videoWriter,
			SaveableFramebufferQueue downloader,
			CallbackInfo ci) throws IOException {

		// Apply Gaussian smoothing to FOV
		flashPlus$applySmoothingToFov();

		// Determine output path
		Path outputPath = this.settings.output();
		String pathStr = outputPath.toAbsolutePath().toString();
		int lastDot = pathStr.lastIndexOf('.');
		String basePath = lastDot > 0 ? pathStr.substring(0, lastDot) : pathStr;

		// Write camera JSON
		if (cameraJson && !flashPlus$allCameraKeyframes.isEmpty()) {
			Path cameraJsonPath = Path.of(basePath + "CJ.json");
			try (FileWriter writer = new FileWriter(cameraJsonPath.toFile())) {
				flashPlus$gson.toJson(Map.of("keyframes", flashPlus$allCameraKeyframes), writer);
				System.out.println("[FlashPlus] Camera keyframes exported to " + cameraJsonPath);
			} catch (IOException e) {
				System.err.println("[FlashPlus] Failed to write camera keyframes:");
				e.printStackTrace();
			}
		}

		// Write entity tracking JSON
		if(entityTracking) {
			System.out.println(flashPlus$trackedData);
		}
		if (entityTracking && !FlashplusClient.trackedmodels.isEmpty() && !flashPlus$trackedData.isEmpty()) {
			Path entityJsonPath = Path.of(basePath + "ET.json");
			try (FileWriter writer = new FileWriter(entityJsonPath.toFile())) {
				flashPlus$gson.toJson(Map.of("Entities", flashPlus$trackedData), writer);
				System.out.println("[FlashPlus] Entity tracking keyframes exported to " + entityJsonPath);
			} catch (IOException e) {
				System.err.println("[FlashPlus] Failed to write entity tracking keyframes:");
				e.printStackTrace();
			}
		}

		// Wait for all capture tasks if enabled
		if (this.flashPlus$panoramaExecutor != null) {
			this.flashPlus$panoramaExecutor.shutdown();
			try {
				if (!this.flashPlus$panoramaExecutor.awaitTermination(10, TimeUnit.MINUTES)) {
					Flashplus.LOGGER.error("[FlashPlus] Timed out waiting for capture export tasks to complete");
				}
			} catch (InterruptedException e) {
				Flashplus.LOGGER.error("[FlashPlus] Interrupted while waiting for capture export tasks", e);
			}

			// Sort metadata by tick
			this.flashPlus$panoramaMetadataList.sort(Comparator.comparingInt(m -> (int) m.get("tick")));
			this.flashPlus$lightingMetadataList.sort(Comparator.comparingInt(m -> (int) m.get("tick")));

			// Write companion panoramas.json in output directory and basePath + "HDRI.json"
			if (!this.flashPlus$panoramaMetadataList.isEmpty()) {
				Map<String, Object> panoramaJsonMap = Map.of("panoramas", this.flashPlus$panoramaMetadataList);

				// 1. Inside the panoramas folder
				if (this.flashPlus$panoramaOutputDir != null) {
					Path panoramasJsonPath = this.flashPlus$panoramaOutputDir.resolve("panoramas.json");
					try (FileWriter writer = new FileWriter(panoramasJsonPath.toFile())) {
						flashPlus$gson.toJson(panoramaJsonMap, writer);
						System.out.println("[FlashPlus] Panorama metadata exported to " + panoramasJsonPath);
					} catch (IOException e) {
						System.err.println("[FlashPlus] Failed to write panorama metadata to folder:");
						e.printStackTrace();
					}
				}

				// 2. Alongside camera JSON as basePath + "HDRI.json"
				Path hdriJsonPath = Path.of(basePath + "HDRI.json");
				try (FileWriter writer = new FileWriter(hdriJsonPath.toFile())) {
					flashPlus$gson.toJson(panoramaJsonMap, writer);
					System.out.println("[FlashPlus] Panorama metadata exported to " + hdriJsonPath);
				} catch (IOException e) {
					System.err.println("[FlashPlus] Failed to write HDRI metadata:");
					e.printStackTrace();
				}
			}

			// Write lighting JSON if SH data was captured
			if (!this.flashPlus$lightingMetadataList.isEmpty()) {
				Map<String, Object> lightingJsonMap = Map.of("frames", this.flashPlus$lightingMetadataList);

				// 1. Alongside camera JSON as basePath + "Lighting.json"
				Path baseLightingJsonPath = Path.of(basePath + "Lighting.json");
				try (FileWriter writer = new FileWriter(baseLightingJsonPath.toFile())) {
					flashPlus$gson.toJson(lightingJsonMap, writer);
					System.out.println("[FlashPlus] Lighting metadata exported to " + baseLightingJsonPath);
				} catch (IOException e) {
					System.err.println("[FlashPlus] Failed to write Lighting.json alongside video:");
					e.printStackTrace();
				}

				// 2. Inside the panoramas folder if it exists: lighting.json
				if (this.flashPlus$panoramaOutputDir != null && Files.exists(this.flashPlus$panoramaOutputDir)) {
					Path lightingJsonPath = this.flashPlus$panoramaOutputDir.resolve("lighting.json");
					try (FileWriter writer = new FileWriter(lightingJsonPath.toFile())) {
						flashPlus$gson.toJson(lightingJsonMap, writer);
						System.out.println("[FlashPlus] Lighting metadata exported to " + lightingJsonPath);
					} catch (IOException e) {
						System.err.println("[FlashPlus] Failed to write lighting.json to folder:");
						e.printStackTrace();
					}
				}
			}
		}
	}

	@Unique
	private void flashPlus$captureCameraKeyframe(int tickIndex, double partialClientTick, ReplayServer replayServer) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		if (camera == null) return;

		Map<String, Object> keyframeData = new HashMap<>();
		keyframeData.put("tick", tickIndex);

		Vec3 positionVec3 = camera.position();
		keyframeData.put("position", new double[]{positionVec3.x, positionVec3.y, positionVec3.z});

		if (FlashplusClient.useQuaternion) {
			keyframeData.put("w", FlashplusClient.quaternion.w);
			keyframeData.put("x", FlashplusClient.quaternion.x);
			keyframeData.put("y", FlashplusClient.quaternion.y);
			keyframeData.put("z", FlashplusClient.quaternion.z);
		} else {
			keyframeData.put("yaw", camera.yRot());
			keyframeData.put("pitch", camera.xRot());
			keyframeData.put("roll", FlashplusClient.roll);
		}

		// grab MC fov
//		keyframeData.put("fov", FlashplusClient.getFOV());

		// Calculate FOV
		float currentOverrideFov = replayServer.getEditorState().replayVisuals.overrideFovAmount;
		float keyframeStartFov = this.flashPlus$previousFov;
		float keyframeEndFov = FlashplusClient.getFOV();

		final float EPSILON = 0.001f;
		boolean isOverrideDifferent = Math.abs(currentOverrideFov - keyframeEndFov) > EPSILON;

		float targetFov = isOverrideDifferent ? currentOverrideFov : keyframeEndFov;
		float interpolatedFov = (float) (keyframeStartFov + (targetFov - keyframeStartFov) * partialClientTick);

		keyframeData.put("fov", keyframeEndFov);
        keyframeData.put("time", Minecraft.getInstance().level.getLevelData().getGameTime() % 24000);

		flashPlus$allCameraKeyframes.add(keyframeData);

	}

	@Unique
	private void flashPlus$captureEntityData(int tickIndex, double partialClientTick) {
		if (FlashplusClient.trackedmodels.isEmpty()) return;

		Map<String, Object> keyframeData = new HashMap<>();
		keyframeData.put("tick", tickIndex);

		for (Map<String, Object> currentModelMap : FlashplusClient.trackedmodels) {
			for (Map.Entry<String, Object> entry : currentModelMap.entrySet()) {
				String key = entry.getKey();
				String[] keyParts = key.split("/");
				if (keyParts.length != 2) continue;

				String entityName = keyParts[0];
				String partName = keyParts[1];

				Map<String, Object> partData = new HashMap<>();

				Entity entity = null;
				try {
					for (Entity e : Minecraft.getInstance().level.entitiesForRendering()) {
						if (e.getUUID().equals(UUID.fromString(entityName))) {
							entity = e;
							break;
						}
					}
				} catch (IllegalArgumentException e) {
					// Invalid UUID format
					continue;
				}

				if (entity == null) continue;

				EntityRenderer<?,?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);

				if ("Eyes".equals(partName)) {
					float tick = Math.max(0.0f, Math.min(1.0f, (float) (partialClientTick + 0.001)));
					Vec3 eyePos = entity.getPosition(tick);
					partData.put("eyePosition", new double[]{
							eyePos.x,
							eyePos.y + entity.getEyeHeight(),
							eyePos.z
					});
					partData.put("eyeangle", new double[]{
							entity.getViewXRot((float) partialClientTick),
							entity.getViewYRot((float) partialClientTick),
							0.0
					});
				} else if ("BlockPosition".equals(partName)) {
					Vec3 blockPos = entity.getPosition((float) partialClientTick);
					partData.put("blockPosition", new double[]{
							blockPos.x,
							blockPos.y,
							blockPos.z
					});
					partData.put("entityrotation", new double[]{
							entity.getYRot(),
							entity.getYRot(),
							0.0
					});
				} else {
					if (renderer instanceof LivingEntityRenderer<?, ?, ?> livingRenderer) {
						ModelPart part = flashPlus$getNamedModelPart(livingRenderer.getModel(), partName);
						if (part != null) {
							partData.put("position", new double[]{part.x, part.y, part.z});
							partData.put("rotation", new double[]{part.xRot, part.yRot, part.zRot});
						}
					}
				}

				@SuppressWarnings("unchecked")
				Map<String, Object> entityData = (Map<String, Object>) keyframeData.get(entityName);
				if (entityData == null) {
					entityData = new HashMap<>();
					keyframeData.put(entityName, entityData);
				}
				entityData.put(partName, partData);
			}
		}

		if (keyframeData.size() > 1) { // More than just "tick" entry
			flashPlus$trackedData.add(keyframeData);
		}
	}

	@Unique
	private void flashPlus$applySmoothingToFov() {
		if (flashPlus$allCameraKeyframes == null || flashPlus$allCameraKeyframes.size() < 7) {
			return;
		}

		final int KERNEL_RADIUS = 3;
		final float[] GAUSSIAN_KERNEL = {0.006f, 0.061f, 0.242f, 0.383f, 0.242f, 0.061f, 0.006f};

		List<Float> originalFovs = new ArrayList<>();
		for (Map<String, Object> keyframe : flashPlus$allCameraKeyframes) {
			Object fovObj = keyframe.get("fov");
			originalFovs.add(fovObj instanceof Number ? ((Number) fovObj).floatValue() : 0.0f);
		}

		int listSize = originalFovs.size();

		for (int i = 0; i < listSize; i++) {
			if (i < KERNEL_RADIUS || i >= listSize - KERNEL_RADIUS) {
				continue;
			}

			float newFov = 0.0f;
			for (int j = -KERNEL_RADIUS; j <= KERNEL_RADIUS; j++) {
				int keyframeIndex = i + j;
				int kernelIndex = j + KERNEL_RADIUS;
				float fovAtNeighbor = originalFovs.get(keyframeIndex);
				float weight = GAUSSIAN_KERNEL[kernelIndex];
				newFov += fovAtNeighbor * weight;
			}

			flashPlus$allCameraKeyframes.get(i).put("fov", newFov);
		}
	}

	@Unique
	private static ModelPart flashPlus$getNamedModelPart(net.minecraft.client.model.EntityModel<?> model, String partName) {
		Class<?> currentClass = model.getClass();
		while (currentClass != null) {
			try {
				java.lang.reflect.Field field = currentClass.getDeclaredField(partName);
				field.setAccessible(true);
				Object part = field.get(model);
				if (part instanceof ModelPart) {
					return (ModelPart) part;
				}
				return null;
			} catch (NoSuchFieldException e) {
				currentClass = currentClass.getSuperclass();
			} catch (IllegalAccessException e) {
				System.err.println("[FlashPlus] Could not access field: " + partName);
				e.printStackTrace();
				return null;
			}
		}
		return null;
	}

	@Inject(
			method = "doExport",
			at = @At(
					value = "INVOKE",
					// This is the point right after keyframes are applied
					target = "Lcom/moulberry/flashback/state/EditorState;applyKeyframes(Lcom/moulberry/flashback/keyframe/handler/KeyframeHandler;F)V",
					shift = At.Shift.AFTER
			)
	)
	private void overrideCameraForPanorama(VideoWriter videoWriter, SaveableFramebufferQueue downloader, CallbackInfo ci) {
		// Check if this is a single-frame export (screenshot) and if it's meant to be a panorama face
		// We know it's a panorama face if the start/end ticks are identical
		// AND the initial yaw/pitch are set to our cubemap values.
		if (this.settings.startTick() == this.settings.endTick()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				// Force the player rotation (which the camera follows)
				mc.player.setYRot(this.settings.initialCameraYaw());
				mc.player.setXRot(this.settings.initialCameraPitch());

				// Also update previous rotations to prevent interpolation jitter
				mc.player.yRotO = this.settings.initialCameraYaw();
				mc.player.xRotO = this.settings.initialCameraPitch();
				EditorState editorState = EditorStateManager.getCurrent();
				if (editorState != null && editorState.replayVisuals.overrideFov) {
					editorState.replayVisuals.overrideFovAmount = 90;
				} else {
					originalFov = mc.options.fov().get();
					mc.options.fov().set(90);
				}


				// Force the GameRenderer's camera to update immediately
			mc.gameRenderer.mainCamera().setLevel(mc.level);
			mc.gameRenderer.mainCamera().update(DeltaTracker.ONE);
			}
		}
	}

	@Inject(method = "doExport", at = @At("RETURN"))
	private void onExportFinish(CallbackInfo ci) {
		// Only run if the queue is now empty and we were actually processing a panorama
		if (ExportJobQueue.count() == 0 && FlashplusClient.takePanorama) {
			ExportJobQueue.drainingQueue = false;
			Minecraft mc = Minecraft.getInstance();
			mc.options.fov().set(originalFov);

			Path outputPath = this.settings.output();
			Path folder = outputPath.getParent();
			String fileName = outputPath.getFileName().toString();

			String nameWithoutExtension = fileName.contains(".")
					? fileName.substring(0, fileName.lastIndexOf("."))
					: fileName;

			String baseName = nameWithoutExtension.contains("_")
					? nameWithoutExtension.substring(0, nameWithoutExtension.lastIndexOf("_"))
					: nameWithoutExtension;

            int size = this.settings.resolutionX();

			new Thread(() -> {
				PanoramaScreenshotHelper.convertCubemapToEquirectangular(folder, baseName, size);
				System.out.println("Panorama conversion complete!");
			}).start();
		}
	}
}