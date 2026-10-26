package dev.irisvisualizer.editor;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.app.WindowGlfw;
import imgui.flag.ImGuiTreeNodeFlags;
import static org.lwjgl.glfw.GLFW.*;
import java.util.List;
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
    private boolean styled, closeRequested;
    private final ImString filter = new ImString(256);
    private String indexedFilter;
    private PackWorkspace indexedWorkspace;
    private List<ResourceTree.Node> resources = List.of();
    @Override protected void preRun() {
        long handle = ((WindowGlfw) getWindow()).getHandle();
        glfwSetWindowCloseCallback(handle, window -> {
            glfwSetWindowShouldClose(window, false);
            closeRequested = true;
        });
    }
    private final InspectorDrafts drafts = new InspectorDrafts();
    private final Map<String, ImString> values = new HashMap<>();
    @Override protected void configure(Configuration config) { config.setTitle("Iris Visualizer"); }
    @Override public void process() {
        if (closeRequested) {
            closeRequested = false;
            if (replaceAllowed()) glfwSetWindowShouldClose(((WindowGlfw) getWindow()).getHandle(), true);
        }
        if (!styled) { ImGui.styleColorsLight(); styled = true; }
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                for (String action : new String[]{"New", "Open", "Save", "Import", "Export"})
                    if (ImGui.menuItem(action)) perform(action);
                ImGui.endMenu();
            }
            ImGui.text(workspace.dirty() ? "Unsaved changes" : "Saved / no applied changes");
            if (drafts.any()) ImGui.text("Unapplied text: " + drafts.count());
            ImGui.endMainMenuBar();
        }
        float width = ImGui.getIO().getDisplaySizeX(), height = ImGui.getIO().getDisplaySizeY() - 24;
        ImGui.setNextWindowPos(0, 24); ImGui.setNextWindowSize(width * .24f, height);
        ImGui.begin("Resources");
        ImGui.inputText("Search", filter);
        if (indexedWorkspace != workspace || !filter.get().equals(indexedFilter)) {
            indexedWorkspace = workspace; indexedFilter = filter.get();
            resources = ResourceTree.build(workspace.paths(), workspace.directories(), indexedFilter);
        }
        if (resources.isEmpty()) ImGui.textWrapped(workspace.paths().isEmpty() ? "No resources. Open a pack folder or import a ZIP." : "No matching resources.");
        drawResources(resources);
        ImGui.end();
        ImGui.setNextWindowPos(width * .24f, 24); ImGui.setNextWindowSize(width * .48f, height);
        ImGui.begin("Visualization");
        ImGui.textWrapped("Preview unavailable: native generation parity is not verified.");
        ImGui.beginDisabled();
        ImGui.button("2D preview"); ImGui.sameLine(); ImGui.button("3D preview");
        ImGui.endDisabled();
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
                        String token;
                        try { token = document.tokenPath(field); }
                        catch (IllegalArgumentException invalidParent) { ImGui.textWrapped(field + ": parent is not an editable object"); continue; }
                        ImGui.textDisabled("(?) " + field);
                        if (ImGui.isItemHovered()) ImGui.setTooltip(DimensionFields.description(field));
                        if (token == null) {
                            ImGui.text(field + ": absent"); ImGui.sameLine();
                            if (ImGui.button("Enable##" + field)) {
                                try { workspace.replace(selected, DimensionFields.enable(selected, workspace.read(selected), field)); error = null; }
                                catch (Exception failure) { error = failure.getMessage(); }
                            }
                            if (ImGui.isItemHovered()) ImGui.setTooltip("Insert pinned Iris default: " + DimensionFields.defaultToken(field));
                            continue;
                        }
                        if (DimensionFields.arrayField(field)) {
                            drawArray(field, document); continue;
                        }
                        if (DimensionFields.booleanField(field)) {
                            if (!token.equals("true") && !token.equals("false")) { ImGui.text(field + ": stored type mismatch (read-only)"); continue; }
                            if (ImGui.checkbox(field, token.equals("true"))) {
                                try { workspace.replace(selected, DimensionFields.editBoolean(selected, workspace.read(selected), field, !token.equals("true"))); error = null; }
                                catch (Exception failure) { error = failure.getMessage(); }
                            }
                            continue;
                        }
                        ImGui.text(field + " [" + DimensionFields.min(field) + " .. " + DimensionFields.max(field) + "]");
                        ImString input = values.computeIfAbsent(field, key -> new ImString(token, 128));
                        ImGui.inputText(field, input);
                        drafts.update(field, token, input.get());
                        ImGui.sameLine();
                        if (ImGui.button("Apply##" + field)) {
                            try { workspace.replace(selected, DimensionFields.edit(selected, workspace.read(selected), field, Double.parseDouble(input.get()))); drafts.applied(field); values.remove(field); error = null; }
                            catch (Exception failure) { error = failure.getMessage(); }
                        }
                    }
                    List<String> readOnly = DimensionFields.readOnlyPaths(document);
                    if (ImGui.collapsingHeader("Read-only fields (" + readOnly.size() + ")"))
                        for (String path : readOnly) ImGui.textWrapped(path);
                    ImGui.textWrapped("Apply text changes before saving. Toggles apply immediately.");
                } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (selected.endsWith(".json") && bytes.length <= 1_000_000) {
                if (ImGui.collapsingHeader("Original/current JSON (read-only)"))
                    ImGui.textWrapped(new String(workspace.read(selected), StandardCharsets.UTF_8));
            } else ImGui.text(bytes.length + " bytes");
        }
        if (error != null) ImGui.textWrapped(error);
        ImGui.end();
    }
    private void drawArray(String field, LosslessJson document) {
        List<String> entries;
        try { entries = document.stringArrayPath(field); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored value is not a string array (read-only)."); return; }
        ImGui.pushID(field);
        ImGui.textWrapped("Registry key syntax only; registry existence is not checked.");
        for (int index=0;index<entries.size();index++) {
            ImGui.pushID(index);
            String key = field + "#" + index;
            String current = entries.get(index);
            // Oversized existing items stay byte-preserved and removable, without truncating their input.
            if (current.length() > 4096) ImGui.textWrapped("Long existing entry (read-only).");
            else {
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(current, 16_384));
                ImGui.inputText("Key", input);
                drafts.update(key, current, input.get());
                if (ImGui.button("Apply")) applyArray(field, index, input.get());
                ImGui.sameLine();
            }
            boolean remove = ImGui.button("Remove");
            ImGui.popID();
            if (remove) { applyArray(field, index, null); break; }
        }
        String key = field + "#new";
        ImString input = values.computeIfAbsent(key, ignored -> new ImString(16_384));
        ImGui.inputText("New key", input);
        drafts.update(key, "", input.get());
        if (ImGui.button("Add")) applyArray(field, entries.size(), input.get());
        ImGui.popID();
    }
    private void applyArray(String field, int index, String value) {
        try {
            int count = new LosslessJson(workspace.read(selected)).stringArrayPath(field).size();
            boolean structural = value == null || index == count;
            if (structural && !discardArrayDrafts(field, index == count ? field + "#new" : null)) return;
            workspace.replace(selected, DimensionFields.editArray(selected, workspace.read(selected), field, index, value));
            if (structural) {
                values.keySet().removeIf(key -> key.startsWith(field + "#")); drafts.clearUnder(field + "#");
            } else { values.remove(field + "#" + index); drafts.applied(field + "#" + index); }
            error = null;
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void drawResources(List<ResourceTree.Node> nodes) {
        for (ResourceTree.Node node : nodes) {
            ImGui.pushID(node.path());
            if (node.folder()) {
                if (!filter.get().isBlank()) ImGui.setNextItemOpen(true);
                if (ImGui.treeNodeEx(node.name(), ImGuiTreeNodeFlags.SpanAvailWidth)) {
                    drawResources(node.children()); ImGui.treePop();
                }
            } else if (ImGui.selectable(node.name() + (workspace.changed(node.path()) ? " *" : ""), node.path().equals(selected))) {
                if (!node.path().equals(selected) && discardDraftsAllowed()) { selected = node.path(); values.clear(); }
            }
            ImGui.popID();
        }
    }
    private boolean discardArrayDrafts(String field, String appliedKey) {
        boolean other = values.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(field + "#")
                && !entry.getKey().equals(appliedKey) && drafts.contains(entry.getKey()));
        return !other || JOptionPane.showConfirmDialog(null, "This changes list positions and discards other unapplied text in this list. Continue?",
                "Unapplied text", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }
    private boolean discardDraftsAllowed() {
        if (!drafts.any()) return true;
        if (JOptionPane.showConfirmDialog(null, "Discard unapplied text? Apply it first to include it in saved pack data.",
                "Unapplied text", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
        drafts.clear(); values.clear(); return true;
    }
    private boolean appliedValuesOnlyAllowed() {
        return !drafts.any() || JOptionPane.showConfirmDialog(null, "Save/export applied values only? Unapplied text will stay in the inspector.",
                "Unapplied text", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }
    private boolean replaceAllowed() {
        if (!discardDraftsAllowed()) return false;
        if (!workspace.dirty()) return true;
        Object[] choices = {"Save", "Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null, "Save applied changes before continuing?", "Unsaved pack",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, choices, choices[0]);
        if (choice == 1) return true;
        if (choice != 0) return false;
        return saveWorkspace();
    }
    private boolean saveWorkspace() {
        try {
            Path path = workspace.directory() == null ? choose(true, true) : workspace.directory();
            if (path == null) return false;
            workspace.save(path); error = null; return true;
        } catch (Exception failure) {
            error = failure.getMessage();
            JOptionPane.showMessageDialog(null, error, "Save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
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
                case "Save" -> { if (!appliedValuesOnlyAllowed() || !saveWorkspace()) return; }
                case "Export" -> { if (!appliedValuesOnlyAllowed()) return; Path path = choose(false, true); if (path != null) workspace.exportZip(path); }
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
