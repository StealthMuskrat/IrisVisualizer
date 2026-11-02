package dev.irisvisualizer.editor;

import imgui.ImGui;
import imgui.flag.ImGuiSliderFlags;
import imgui.type.ImString;

/** Small audited ranges get a slider plus exact entry; changes still require the caller's Apply. */
final class NumericInput {
    private NumericInput() {}

    static void draw(String label, ImString input, NumericBounds bounds) {
        if (!bounds.slider()) {
            ImGui.inputText(label, input);
            return;
        }
        ImGui.pushID(label);
        var draft = bounds.draft(input.get());
        ImGui.beginDisabled(draft.isEmpty());
        int flags = ImGuiSliderFlags.NoInput | ImGuiSliderFlags.AlwaysClamp | ImGuiSliderFlags.NoRoundToFormat;
        if (bounds.integer()) {
            int[] value = {(int) draft.orElse(bounds.minimum())};
            if (ImGui.sliderInt(label, value, (int) bounds.minimum(), (int) bounds.maximum(), "%d", flags)
                    && draft.isPresent() && value[0] != draft.getAsDouble()) input.set(Integer.toString(value[0]));
        } else {
            double[] value = {draft.orElse(bounds.minimum())};
            if (ImGui.sliderScalar(label, value, bounds.minimum(), bounds.maximum(), "%.6g", flags)
                    && draft.isPresent() && value[0] != draft.getAsDouble()) input.set(Double.toString(value[0]));
        }
        ImGui.endDisabled();
        // This field also lets an invalid or out-of-range draft be corrected without loss.
        ImGui.inputText("Exact value", input);
        ImGui.popID();
    }
}
