package redsmods.flashplus.mixin.client.Window;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImInt;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redsmods.flashplus.FlashplusClient;

import java.nio.file.Path;

import static redsmods.flashplus.FlashplusClient.*;

@Mixin(value = StartExportWindow.class, remap = false)
public class StartExportWindowMixin {
    @Unique
    private static final ImInt flashPlus$lightingInterval = new ImInt(lightingIntervalTicks);
    @Unique
    private static final ImInt flashPlus$lightingMultiplier = new ImInt(lightingMultiplier);
    @Unique
    private static String flashPlus$lastCameraDirectory = "C:\\tmp";

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Limgui/moulberry90/ImGui;dummy(FF)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private static void renderFlashplusOptions(CallbackInfo ci) {
        ImGuiHelper.separatorWithText("Flashplus Camera Path");

        if (ImGui.radioButton("Flashback Keyframes", !useImportedCameraPath)) {
            useImportedCameraPath = false;
        }
        ImGui.sameLine();
        if (ImGui.radioButton("Recorded Camera Path", useImportedCameraPath)) {
            useImportedCameraPath = true;
        }

        if (useImportedCameraPath) {
            if (ImGui.button("Browse Recorded Camera JSON...")) {
                AsyncFileDialogs.openFileDialog(flashPlus$lastCameraDirectory, "Recorded Camera JSON", "json")
                        .thenAccept(selectedPath -> {
                    if (selectedPath != null && !selectedPath.isBlank()) {
                        Path selectedFile = Path.of(selectedPath).toAbsolutePath().normalize();
                        Path parentDirectory = selectedFile.getParent();
                        if (parentDirectory != null) {
                            flashPlus$lastCameraDirectory = parentDirectory.toString();
                        }
                        Minecraft.getInstance().execute(() -> FlashplusClient.loadImportedCameraJson(selectedFile));
                    }
                });
            }

            if (importedLiveMotionData != null) {
                ImGui.textColored(0.2f, 1.0f, 0.2f, 1.0f, importedCameraStatus);
                ImGui.textWrapped("File: " + importedCameraJsonPath);
            } else {
                ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, importedCameraStatus);
            }

            if (ImGui.checkbox("Override FOV from Recording", importedCameraOverrideFov)) {
                importedCameraOverrideFov = !importedCameraOverrideFov;
            }
        }

        ImGuiHelper.separatorWithText("Flashplus Export Options");

        if (ImGui.checkbox("Camera Track", cjson)) {
            cjson = !cjson;
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Entity Track", etjson)) {
            etjson = !etjson;
        }

        if (ImGui.checkbox("Use Quaternion", useQuaternion)) {
            useQuaternion = !useQuaternion;
        }

        // Lighting Export Option
        if (ImGui.checkbox("Export Lighting", exportLightingSh)) {
            exportLightingSh = !exportLightingSh;
        }
        ImGuiHelper.tooltip("Export world lighting data (Sun direction, color, intensity, sky ambient) to Lighting.json for Blender");

        if (exportLightingSh) {
            flashPlus$lightingInterval.set(lightingIntervalTicks);
            if (ImGui.inputInt("Lighting Interval (ticks)", flashPlus$lightingInterval, 5, 20)) {
                lightingIntervalTicks = Math.max(1, flashPlus$lightingInterval.get());
            }
            ImGuiHelper.tooltip("Interval in ticks between lighting captures (e.g. 20 ticks = 1 sec at 20tps)");

            flashPlus$lightingMultiplier.set(lightingMultiplier);
            if (ImGui.inputInt("Lighting Multiplier", flashPlus$lightingMultiplier, 1, 5)) {
                lightingMultiplier = Math.max(1, flashPlus$lightingMultiplier.get());
            }
            ImGuiHelper.tooltip("Intensity multiplier for Blender Sun & Ambient light (default 1)");
        }
    }
}
