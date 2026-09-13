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