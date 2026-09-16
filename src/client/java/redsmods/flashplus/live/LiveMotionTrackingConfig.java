package redsmods.flashplus.live;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import redsmods.flashplus.Flashplus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent settings for the client-side live camera recorder. */
public final class LiveMotionTrackingConfig {
    public static final int DEFAULT_FPS = 60;
    public static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("C:\\tmp");
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 480;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("flashplus")
            .resolve("live-motion-tracking.json");

    private static LiveMotionTrackingConfig instance;

    private int fps;
    private Path outputDirectory;

    private LiveMotionTrackingConfig(int fps, Path outputDirectory) {
        this.fps = fps;
        this.outputDirectory = outputDirectory;
    }

    public static synchronized LiveMotionTrackingConfig load() {
        if (instance != null) {
            return instance;
        }

        int fps = DEFAULT_FPS;
        Path outputDirectory = DEFAULT_OUTPUT_DIRECTORY;

        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("fps")) {
                    fps = validateFps(json.get("fps").getAsInt());
                }
                if (json.has("output_path")) {
                    outputDirectory = normalizeDirectory(json.get("output_path").getAsString());
                }
            } catch (Exception exception) {
                Flashplus.LOGGER.warn("[FlashPlus] Could not read live motion tracking config; using defaults.", exception);
            }
        }

        instance = new LiveMotionTrackingConfig(fps, outputDirectory);
        if (!Files.exists(CONFIG_PATH)) {
            try {
                instance.save();
            } catch (IOException exception) {
                Flashplus.LOGGER.warn("[FlashPlus] Could not create live motion tracking config.", exception);
            }
        }
        return instance;
    }

    public static synchronized LiveMotionTrackingConfig get() {
        return load();
    }

    public synchronized int getFps() {
        return fps;
    }

    public synchronized Path getOutputDirectory() {
        return outputDirectory;
    }

    public synchronized void setFps(int value) throws IOException {
        fps = validateFps(value);
        save();
    }

    public synchronized void setOutputDirectory(String value) throws IOException {
        Path newDirectory = normalizeDirectory(value);
        Files.createDirectories(newDirectory);
        outputDirectory = newDirectory;
        save();
    }

    public static int validateFps(int value) {
        if (value < MIN_FPS || value > MAX_FPS) {
            throw new IllegalArgumentException("FPS must be between " + MIN_FPS + " and " + MAX_FPS + ".");
        }
        return value;
    }

    private static Path normalizeDirectory(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Output path cannot be empty.");
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private void save() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("fps", fps);
        json.addProperty("output_path", outputDirectory.toString());
        Files.writeString(CONFIG_PATH, GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
