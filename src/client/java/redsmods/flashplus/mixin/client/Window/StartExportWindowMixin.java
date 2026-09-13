package redsmods.flashplus.mixin.client.Window;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static redsmods.flashplus.FlashplusClient.*;

@Mixin(value = StartExportWindow.class, remap = false)
public class StartExportWindowMixin {
    @Unique
    private static final ImInt flashPlus$lightingInterval = new ImInt(lightingIntervalTicks);
    @Unique
    private static final ImInt flashPlus$lightingMultiplier = new ImInt(lightingMultiplier);
    @Unique
    private static final ImInt flashPlus$panoramaInterval = new ImInt(panoramaIntervalTicks);

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
        ImGuiHelper.separatorWithText("Flashplus Options");

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

        // Lighting Export Option (SH / 27 coefficients)
        if (ImGui.checkbox("Export Lighting (SH)", exportLightingSh)) {
            exportLightingSh = !exportLightingSh;
        }
        ImGuiHelper.tooltip("Export Spherical Harmonics lighting (Sun direction, color, intensity, sky ambient) to Lighting.json for Blender");

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

        // Panorama Export Option (HDRI)
        if (ImGui.checkbox("Export Panoramas (HDRI)", panoramaExportEnabled)) {
            panoramaExportEnabled = !panoramaExportEnabled;
        }
        ImGuiHelper.tooltip("Captures time-varying equirectangular panoramas at fixed tick intervals");

        if (panoramaExportEnabled) {
            flashPlus$panoramaInterval.set(panoramaIntervalTicks);
            if (ImGui.inputInt("Panorama Interval (ticks)", flashPlus$panoramaInterval, 100, 1000)) {
                panoramaIntervalTicks = Math.max(1, flashPlus$panoramaInterval.get());
            }
            ImGuiHelper.tooltip("Interval in ticks between panorama captures (e.g. 1200 ticks = 60s at 20tps)");

            if (ImGui.checkbox("Save EXR", exportPanoramaExr)) {
                exportPanoramaExr = !exportPanoramaExr;
            }
            ImGuiHelper.tooltip("Export 16-bit half-float OpenEXR (.exr) files");

            ImGui.sameLine();

            if (ImGui.checkbox("Save HDR", exportPanoramaHdr)) {
                exportPanoramaHdr = !exportPanoramaHdr;
            }
            ImGuiHelper.tooltip("Export Radiance RGBE (.hdr) files");
        }
    }
}