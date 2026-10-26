package dev.irisvisualizer.editor;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.type.ImString;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** First editor shell. Unknown/unclassified configuration remains read-only. */
public final class EditorApp extends Application {
    private PackWorkspace workspace = PackWorkspace.empty();
    private String selected, error;
    private boolean styled;
    private final Map<String, ImString> values = new HashMap<>();
    @Override protected void configure(Configuration config) { config.setTitle("Iris Visualizer"); }
    @Override public void process() {
        if (!styled) { ImGui.styleColorsLight(); styled = true; }
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                for (String action : new String[]{"New", "Open", "Save", "Import", "Export"})
                    if (ImGui.menuItem(action)) perform(action);
                ImGui.endMenu();
            }
            ImGui.endMainMenuBar();
        }
        float width = ImGui.getIO().getDisplaySizeX(), height = ImGui.getIO().getDisplaySizeY() - 24;
        ImGui.setNextWindowPos(0, 24); ImGui.setNextWindowSize(width * .24f, height);
        ImGui.begin("Resources");
        for (String path : workspace.paths()) if (ImGui.selectable(path, path.equals(selected))) { selected = path; values.clear(); }
        ImGui.end();
        ImGui.setNextWindowPos(width * .24f, 24); ImGui.setNextWindowSize(width * .48f, height);
        ImGui.begin("Visualization");
        if (ImGui.beginTabBar("Preview modes")) {
            if (ImGui.beginTabItem("2D")) { ImGui.textWrapped("Preview unavailable: native generation parity is not verified."); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("3D")) { ImGui.textWrapped("Preview unavailable: native generation parity is not verified."); ImGui.endTabItem(); }
            ImGui.endTabBar();
        }
        ImGui.end();
        ImGui.setNextWindowPos(width * .72f, 24); ImGui.setNextWindowSize(width * .28f, height);
        ImGui.begin("Resource inspector");
        if (selected != null) {
            ImGui.text(selected);
            ImGui.textWrapped("Unknown and unclassified fields are read-only.");
            byte[] bytes = workspace.read(selected);
            if (DimensionFields.dimension(selected)) {
                try {
                    LosslessJson document = new LosslessJson(bytes);
                    for (String field : DimensionFields.EDITABLE) {
                        String token = document.token(field);
                        if (token == null) { ImGui.text(field + ": absent (preserved)"); continue; }
                        ImString input = values.computeIfAbsent(field, key -> new ImString(token, 128));
                        ImGui.inputText(field, input);
                        ImGui.sameLine();
                        if (ImGui.button("Apply##" + field)) {
                            try { workspace.replace(selected, DimensionFields.edit(selected, bytes, field, Double.parseDouble(input.get()))); error = null; }
                            catch (Exception failure) { error = failure.getMessage(); }
                        }
                    }
                    ImGui.text("Other fields awaiting classification: " + document.fields().stream().filter(field -> !DimensionFields.EDITABLE.contains(field)).count());
                } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (selected.endsWith(".json") && bytes.length <= 1_000_000)
                ImGui.textWrapped(new String(bytes, StandardCharsets.UTF_8));
            else ImGui.text(bytes.length + " bytes");
        }
        if (error != null) ImGui.textWrapped(error);
        ImGui.end();
    }
    private boolean replaceAllowed() {
        return !workspace.dirty() || JOptionPane.showConfirmDialog(null, "Discard unsaved changes?", "Unsaved pack", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
    private Path choose(boolean folder, boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        int result = save ? chooser.showSaveDialog(null) : chooser.showOpenDialog(null);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }
    private void perform(String action) {
        try {
            switch (action) {
                case "New" -> { if (replaceAllowed()) { workspace = PackWorkspace.empty(); selected = null; values.clear(); } }
                case "Open", "Import" -> {
                    if (!replaceAllowed()) return;
                    Path path = choose(action.equals("Open"), false);
                    if (path != null) { PackWorkspace next = action.equals("Open") ? PackWorkspace.open(path) : PackWorkspace.importZip(path); workspace = next; selected = null; values.clear(); }
                }
                case "Save" -> { Path path = workspace.directory() == null ? choose(true, true) : workspace.directory(); if (path != null) workspace.save(path); }
                case "Export" -> { Path path = choose(false, true); if (path != null) workspace.exportZip(path); }
            }
            error = null;
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    public static void main(String[] args) {
        if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64") || Runtime.version().feature() != 25)
            throw new IllegalStateException("Iris Visualizer requires Windows x64 and Java 25. Install the runtime separately.");
        launch(new EditorApp());
    }
}
