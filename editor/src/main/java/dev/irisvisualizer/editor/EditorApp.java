package dev.irisvisualizer.editor;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.app.WindowGlfw;
import imgui.flag.ImGuiTreeNodeFlags;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.system.MemoryUtil;
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
    private PackWorkspace referenceWorkspace;
    private String referencePath;
    private long referenceRevision = -1;
    private List<ReferenceDiagnostics.Link> referenceLinks = List.of();
    private PackWorkspace imageWorkspace;
    private String imagePath, imageError;
    private int imageTexture, imageWidth, imageHeight;
    private void clearImage() {
        if (imageTexture != 0) glDeleteTextures(imageTexture);
        imageTexture = 0; imageError = null;
    }
    @Override protected void disposeImGui() { clearImage(); super.disposeImGui(); }
    private void drawImageAsset() {
        if (imageWorkspace != workspace || !java.util.Objects.equals(imagePath, selected)) {
            clearImage(); imageWorkspace = workspace; imagePath = selected;
            if (AssetImage.supportedPath(selected)) {
                try {
                    if (workspace.size(selected) > AssetImage.MAX_ENCODED_BYTES) throw new IllegalArgumentException("Asset image exceeds 16 MiB encoded limit");
                    AssetImage.Decoded image = AssetImage.decode(workspace.read(selected));
                    if (image.width() > glGetInteger(GL_MAX_TEXTURE_SIZE) || image.height() > glGetInteger(GL_MAX_TEXTURE_SIZE))
                        throw new IllegalArgumentException("Image exceeds this GPU's texture size limit");
                    int previous = glGetInteger(GL_TEXTURE_BINDING_2D), alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
                    var buffer = MemoryUtil.memAlloc(image.width() * image.height() * 4);
                    try {
                        buffer.put(image.rgba()).flip(); imageTexture = glGenTextures();
                        glBindTexture(GL_TEXTURE_2D, imageTexture); glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.width(), image.height(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != image.width())
                            throw new IllegalStateException("GPU image allocation failed");
                        imageWidth = image.width(); imageHeight = image.height();
                    } finally {
                        glBindTexture(GL_TEXTURE_2D, previous); glPixelStorei(GL_UNPACK_ALIGNMENT, alignment); MemoryUtil.memFree(buffer);
                    }
                } catch (Exception failure) { clearImage(); imageError = failure.getMessage(); }
            }
        }
        if (!AssetImage.supportedPath(selected)) return;
        ImGui.separator(); ImGui.textWrapped("Read-only image asset — first frame");
        if (imageError != null) { ImGui.textWrapped(imageError); return; }
        if (imageTexture != 0) {
            ImGui.text(imageWidth + " × " + imageHeight + " pixels");
            float scale = Math.min(1f, Math.min(Math.max(1, ImGui.getContentRegionAvailX()) / imageWidth,
                    Math.max(1, ImGui.getContentRegionAvailY() - 24) / imageHeight));
            ImGui.image((long)imageTexture, imageWidth * scale, imageHeight * scale);
        }
    }
    private boolean styled, closeRequested;
    private final ImString filter = new ImString(256);
    private String indexedFilter;
    private long indexedRevision = -1;
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
            try { transition(true, () -> glfwSetWindowShouldClose(((WindowGlfw) getWindow()).getHandle(), true)); }
            catch (Exception failure) { error = failure.getMessage(); }
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
        if (indexedWorkspace != workspace || indexedRevision != workspace.resourceRevision() || !filter.get().equals(indexedFilter)) {
            indexedWorkspace = workspace; indexedRevision = workspace.resourceRevision(); indexedFilter = filter.get();
            resources = ResourceTree.build(workspace.paths(), workspace.directories(), indexedFilter);
        }
        if (resources.isEmpty()) ImGui.textWrapped(workspace.paths().isEmpty() ? "No resources. Open a pack folder or import a ZIP." : "No matching resources.");
        ImGui.textDisabled("Ctrl-click: duplicate; Ctrl-right-click folder: create; Ctrl-middle-click folder: subfolder");
        drawResources(resources);
        ImGui.end();
        ImGui.setNextWindowPos(width * .24f, 24); ImGui.setNextWindowSize(width * .48f, height);
        ImGui.begin("Visualization");
        ImGui.textWrapped("Preview unavailable: native generation parity is not verified.");
        ImGui.beginDisabled();
        ImGui.button("2D preview"); ImGui.sameLine(); ImGui.button("3D preview");
        ImGui.endDisabled();
        drawImageAsset();
        ImGui.end();
        ImGui.setNextWindowPos(width * .72f, 24); ImGui.setNextWindowSize(width * .28f, height);
        ImGui.begin("Resource inspector");
        if (selected != null) {
            ImGui.text(selected);
            ImGui.textWrapped("Unknown and unclassified fields are read-only.");
            int byteCount = workspace.size(selected);
            byte[] bytes = selected.endsWith(".json") && byteCount <= 1_000_000 ? workspace.read(selected) : null;
            if (bytes != null && DimensionFields.dimension(selected)) {
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
                        if (!DimensionFields.options(field).isEmpty()) ImGui.textWrapped("Allowed: " + DimensionFields.options(field));
                        ImString input = values.computeIfAbsent(field, key -> new ImString(token, 128));
                        ImGui.inputText(field, input);
                        drafts.update(field, token, input.get());
                        ImGui.sameLine();
                        if (ImGui.button("Apply##" + field)) {
                            try { workspace.replace(selected, DimensionFields.edit(selected, workspace.read(selected), field, Double.parseDouble(input.get()))); drafts.applied(field); values.remove(field); error = null; }
                            catch (Exception failure) { error = failure.getMessage(); }
                        }
                    }
                    drawRootStyles(document, true);
                    drawCaveProfile(document);
                    List<String> readOnly = DimensionFields.readOnlyPaths(document);
                    if (ImGui.collapsingHeader("Read-only fields (" + readOnly.size() + ")"))
                        for (String path : readOnly) ImGui.textWrapped(path);
                    ImGui.textWrapped("Apply text changes before saving. Toggles apply immediately.");
                } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && BiomeFields.biome(selected)) {
                try { drawBiome(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && GeneratorFields.generator(selected)) {
                try { drawGenerator(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && RegionFields.region(selected)) {
                try { drawRegion(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            drawCoverage();
            if (bytes != null) {
                if (ImGui.collapsingHeader("Original/current JSON (read-only)"))
                    ImGui.textWrapped(new String(workspace.read(selected), StandardCharsets.UTF_8));
            } else {
                ImGui.text(byteCount + " bytes");
                if (selected.endsWith(".json")) ImGui.textWrapped("JSON inspection is limited to 1 MB; original bytes remain saveable/exportable.");
            }
        }
        if (selected != null) drawReferences();
        if (error != null) ImGui.textWrapped(error);
        ImGui.end();
    }
    private void drawReferences() {
        String base = ResourceTypes.base(selected);
        if (base == null || !java.util.Set.of("dimensions", "regions", "biomes").contains(base)
                || !ImGui.collapsingHeader("Resource links (partial)")) return;
        if (referenceWorkspace != workspace || referenceRevision != workspace.revision() || !java.util.Objects.equals(referencePath, selected)) {
            referenceWorkspace = workspace; referenceRevision = workspace.revision(); referencePath = selected;
            referenceLinks = ReferenceDiagnostics.inspect(workspace, selected);
        }
        ImGui.textWrapped("Checks dimension regions, region biome lists, biome generators and child biomes only. Exact files are not validated; Iris aliases and other references are unchecked. Save/Export remain available.");
        if (referenceLinks.isEmpty()) ImGui.textWrapped("No explicit links in this inspected subset.");
        for (var link : referenceLinks) {
            ImGui.textWrapped(link.field() + ": " + link.key());
            String status = switch (link.state()) {
                case EXACT_FILE -> "Exact file present (contents unchecked)";
                case NO_EXACT_FILE -> "No exact file match (aliases unchecked)";
                case ABSENT_REQUIRED -> "Required reference list is absent";
                case EMPTY_REQUIRED -> "Required reference list is empty";
                case UNSUPPORTED -> "Reference inspection unavailable";
                case INVALID_KEY -> "Unsupported or unsafe resource key";
            };
            ImGui.textWrapped(status);
            if (link.state() == ReferenceDiagnostics.State.EXACT_FILE && ImGui.smallButton("Open##reference-" + link.field())) {
                try { if (transition(false, () -> { selected = link.target(); filter.set(""); })) break; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
        }
    }
    private void drawCoverage() {
        List<String> groups = FieldCoverage.groups(selected);
        if (groups.isEmpty() || !ImGui.collapsingHeader("Pinned-source field coverage (partial)")) return;
        ImGui.textWrapped("Direct declared fields in selected source classes only; inherited and other nested schemas are not inventoried. Unclassified/unimplemented fields stay read-only.");
        for (String group : groups) {
            var fields = FieldCoverage.fields(group);
            long unclassified = fields.stream().filter(field -> field.classification().equals("unclassified")).count();
            long implemented = fields.stream().filter(FieldCoverage.Field::implemented).count();
            ImGui.textWrapped(group + ": " + implemented + " implemented, " + unclassified + " unclassified / " + fields.size() + " inventoried");
            if (ImGui.treeNode("Fields##coverage-" + group)) {
                for (var field : fields) {
                    String status = field.implemented() ? "editable" : field.classification().equals("generation") ? "generation: control pending" : field.classification();
                    ImGui.textWrapped(field.name() + ": " + status);
                    if (ImGui.isItemHovered()) ImGui.setTooltip(field.reason());
                }
                ImGui.treePop();
            }
        }
    }
    private void drawRegion(LosslessJson document) {
        for (String field : RegionFields.EDITABLE) {
            String token = document.token(field), key = "region#" + field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(RegionFields.description(field));
            if (token == null) {
                if (ImGui.button("Enable (" + RegionFields.defaultToken(field) + ")")) {
                    try { workspace.replace(selected, RegionFields.enable(selected, workspace.read(selected), field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (RegionFields.arrayField(field)) drawArray(field, document);
            else {
                ImGui.textWrapped(RegionFields.constraints(field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, 128));
                ImGui.inputText("Value", input); drafts.update(key, token, input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected, RegionFields.edit(selected, workspace.read(selected), field, Double.parseDouble(input.get()))); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        drawCaveProfile(document);
        ImGui.textWrapped("Other region fields remain read-only.");
    }
    private void drawGenerator(LosslessJson document) {
        for (String field : GeneratorFields.EDITABLE) {
            String token;
            try { token = document.tokenPath(field); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped(field + ": parent is not an editable object"); continue; }
            String key = "noise#" + field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(GeneratorFields.description(field));
            if (token == null) {
                if (ImGui.button("Enable (" + GeneratorFields.defaultToken(field) + ")")) {
                    try { workspace.replace(selected, GeneratorFields.enable(selected, workspace.read(selected), field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (GeneratorFields.booleanField(field)) {
                if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored type mismatch (read-only)");
                else if (ImGui.checkbox("Enabled", token.equals("true"))) {
                    try { workspace.replace(selected, GeneratorFields.editBoolean(selected, workspace.read(selected), field, !token.equals("true"))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(GeneratorFields.constraints(field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, 128));
                ImGui.inputText("Value", input); drafts.update(key, token, input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected, GeneratorFields.editText(selected, workspace.read(selected), field, input.get())); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        if (ImGui.collapsingHeader("Interpolation method")) drawInterpolation(document);
        if (ImGui.collapsingHeader("Cliff height noise")) {
            ImGui.textWrapped("Cliff shaping is active when cliffHeightMax is positive. Enable individual fields to configure its noise.");
            String token = document.token("cliffHeightGenerator");
            try { drawNoiseFields(new LosslessJson((token == null ? "{}" : token).getBytes(StandardCharsets.UTF_8)), NoiseFields.Target.cliffHeight()); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Cliff noise is a snippet or unsupported value (read-only)."); }
        }
        if (ImGui.collapsingHeader("Composite noise entries")) drawNoise(document);
        ImGui.textWrapped("Fracture trees and other unsupported fields remain read-only.");
    }
    private void drawInterpolation(LosslessJson document) {
        ImGui.textWrapped(InterpolationFields.description());
        String current;
        try { current = document.stringPath("interpolator.function"); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored interpolation value is not an editable string/object (read-only)."); return; }
        if (current == null) {
            if (ImGui.button("Enable " + InterpolationFields.defaultValue())) {
                try { workspace.replace(selected, InterpolationFields.enable(selected, workspace.read(selected))); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        if (!InterpolationFields.choices().contains(current)) ImGui.textWrapped("Stored method is unrecognized. Choosing a listed method replaces it.");
        if (ImGui.beginCombo("Method", current.length() > 128 ? "Unrecognized method" : current)) {
            for (String choice : InterpolationFields.choices()) {
                if (ImGui.selectable(choice, choice.equals(current))) {
                    try { workspace.replace(selected, InterpolationFields.edit(selected, workspace.read(selected), choice)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(InterpolationFields.description(choice));
            }
            ImGui.endCombo();
        }
    }
    private void drawNoise(LosslessJson document) {
        List<LosslessJson> entries;
        try { entries = document.token("composite") == null ? List.of() : document.objectArray("composite"); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Composite contains snippet/non-object entries (read-only)."); return; }
        ImGui.textWrapped("Add creates an empty object using Iris defaults. Fracture trees remain read-only. List order affects generation.");
        if (ImGui.button("Add default noise entry")) { changeNoise(CompositeNoise.Action.ADD, -1); return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Noise entry " + index)) continue;
            CompositeNoise.Action action = null;
            if (ImGui.button("Duplicate")) action = CompositeNoise.Action.DUPLICATE;
            ImGui.sameLine(); ImGui.beginDisabled(entries.size() <= CompositeNoise.minimumEntries());
            if (ImGui.button("Remove")) action = CompositeNoise.Action.REMOVE;
            ImGui.endDisabled();
            ImGui.beginDisabled(index == 0);
            if (ImGui.button("Move up")) action = CompositeNoise.Action.UP;
            ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index == entries.size()-1);
            if (ImGui.button("Move down")) action = CompositeNoise.Action.DOWN;
            ImGui.endDisabled();
            if (action != null) { ImGui.treePop(); changeNoise(action, index); return; }
            drawNoiseFields(entries.get(index), NoiseFields.Target.composite(index));
            ImGui.treePop();
        }
    }
    private void drawNoiseFields(LosslessJson object, NoiseFields.Target target) {
        for (String field : NoiseFields.EDITABLE) {
            String token = object.token(field), key = target.draftPrefix() + field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(NoiseFields.description(field));
            if (token == null) {
                if (ImGui.button("Enable (" + NoiseFields.defaultToken(field) + ")")) {
                    try { workspace.replace(selected, NoiseFields.enable(selected, workspace.read(selected), target, field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (NoiseFields.booleanField(field)) {
                if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored type mismatch (read-only)");
                else if (ImGui.checkbox("Enabled", token.equals("true"))) {
                    try { workspace.replace(selected, NoiseFields.editBoolean(selected, workspace.read(selected), target, field, !token.equals("true"))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(NoiseFields.constraints(field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, 128));
                ImGui.inputText("Value", input); drafts.update(key, token, input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected, NoiseFields.editText(selected, workspace.read(selected), target, field, input.get())); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        if (ImGui.treeNode("Style settings##" + target.draftPrefix())) {
            drawStyleFields(object, NoiseStyleFields.Target.noise(target));
            ImGui.treePop();
        }
    }
    private void drawRootStyles(LosslessJson document, boolean dimension) {
        if (!ImGui.collapsingHeader("Distribution styles")) return;
        for (String field : dimension ? NoiseStyleFields.DIMENSION_FIELDS : NoiseStyleFields.BIOME_FIELDS) {
            if (ImGui.treeNode(field)) {
                drawStyleFields(document, dimension ? NoiseStyleFields.Target.dimension(field) : NoiseStyleFields.Target.biome(field));
                ImGui.treePop();
            }
        }
    }
    private void drawStyleFields(LosslessJson container, NoiseStyleFields.Target target) {
        LosslessJson style;
        try { style = NoiseStyleFields.object(container, target); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored style is a snippet or unsupported value (read-only)."); return; }
        ImGui.textWrapped(NoiseStyleFields.contextDescription(target));
        ImGui.textWrapped("Expression and image-map settings can override the base style. Those, cache and fracture settings remain read-only.");
        ImGui.textWrapped(target.terrain() ? "Terrain styles require positive cellular zoom even when cellular frequency is zero."
                : "Cellular frequency 0 disables cellularization. Set a positive cellular zoom before enabling it.");
        if (style == null) {
            ImGui.textWrapped("Absent style uses " + NoiseStyleFields.contextDefaultLabel(target) + ". Enable it explicitly before changing style settings.");
            if (ImGui.button("Enable style (" + NoiseStyleFields.contextDefaultLabel(target) + ")")) enableStyle(target, "style");
            return;
        }
        for (String field : NoiseStyleFields.EDITABLE) {
            String token = style.token(field), key = target.draftPrefix() + field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(NoiseStyleFields.description(field));
            if (token == null) {
                String value = field.equals("style") ? NoiseStyleFields.objectDefault() : NoiseStyleFields.defaultToken(field);
                if (ImGui.button("Enable (" + value + ")")) enableStyle(target, field);
            } else if (field.equals("style")) {
                String current = null;
                try { current = style.stringToken(field); }
                catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored base style is not a string (read-only)."); }
                if (current != null) {
                    if (!NoiseStyleFields.choices().contains(current)) ImGui.textWrapped("Unrecognized stored style. Choose a listed style to replace it.");
                    if (ImGui.beginCombo("Base style", current.length() > 128 ? "Unrecognized style" : current)) {
                        for (String choice : NoiseStyleFields.choices()) {
                            if (ImGui.selectable(choice, choice.equals(current))) {
                                try { workspace.replace(selected, NoiseStyleFields.editStyle(selected, workspace.read(selected), target, choice)); error = null; }
                                catch (Exception failure) { error = failure.getMessage(); }
                            }
                            if (ImGui.isItemHovered()) ImGui.setTooltip(NoiseStyleFields.description(choice));
                        }
                        ImGui.endCombo();
                    }
                }
            } else {
                ImGui.textWrapped(NoiseStyleFields.constraints(target,field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, 128));
                ImGui.inputText("Value", input); drafts.update(key, token, input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected, NoiseStyleFields.editText(selected, workspace.read(selected), target, field, input.get())); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
    }
    private void enableStyle(NoiseStyleFields.Target target, String field) {
        try { workspace.replace(selected, NoiseStyleFields.enable(selected, workspace.read(selected), target, field)); error = null; }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private void changeNoise(CompositeNoise.Action action, int index) {
        try {
            byte[] next = CompositeNoise.change(selected, workspace.read(selected), action, index);
            if (drafts.transitionUnder("composite#", () -> discardArrayDrafts("composite", null), () -> workspace.replace(selected, next))) {
                values.keySet().removeIf(key -> key.startsWith("composite#")); error = null;
            }
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void drawBiome(LosslessJson document) {
        for (String field : BiomeFields.EDITABLE) {
            String token = document.token(field), key = "biome#" + field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(BiomeFields.description(field));
            if (token == null) {
                if (ImGui.button("Enable (" + BiomeFields.defaultToken(field) + ")")) {
                    try { workspace.replace(selected, BiomeFields.enable(selected, workspace.read(selected), field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (BiomeFields.booleanField(field)) {
                if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored type mismatch (read-only)");
                else if (ImGui.checkbox("Enabled", token.equals("true"))) {
                    try { workspace.replace(selected, BiomeFields.editBoolean(selected, workspace.read(selected), field, !token.equals("true"))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(BiomeFields.constraints(field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, 128));
                ImGui.inputText("Value", input); drafts.update(key, token, input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected, BiomeFields.edit(selected, workspace.read(selected), field, Double.parseDouble(input.get()))); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        drawRootStyles(document, false);
        drawCaveProfile(document);
        if (ImGui.collapsingHeader("Child biomes")) {
            ImGui.textWrapped(BiomeChildren.description());
            if (document.token("children") == null) {
                if (ImGui.button("Enable child list")) {
                    try { workspace.replace(selected,BiomeChildren.enable(selected,workspace.read(selected))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else drawArray("children",document);
        }
        if (ImGui.collapsingHeader("3D terrain profile")) drawTerrain3D(document);
        if (ImGui.collapsingHeader("Biome generators")) drawBiomeGenerators(document);
        if (ImGui.collapsingHeader("Other biome fields (read-only)"))
            for (String field : document.fields()) if (!BiomeFields.EDITABLE.contains(field) && !NoiseStyleFields.BIOME_FIELDS.contains(field) && !BiomeChildren.EDITABLE.contains(field) && !Terrain3DFields.OBJECT_FIELDS.contains(field) && !CaveProfileFields.OBJECT_FIELDS.contains(field) && !field.equals("generators")) ImGui.textWrapped(field);
    }
    private void drawCaveProfile(LosslessJson document) {
        if (!ImGui.collapsingHeader("Cave profile")) return;
        ImGui.textWrapped(CaveProfileFields.description(selected));
        LosslessJson profile;
        try { profile=CaveProfileFields.object(document); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored cave profile is a snippet or unsupported value (read-only)."); return; }
        if (profile==null) {
            ImGui.textWrapped("Uses the Iris default profile, with profile carving disabled. Create an explicit profile to edit its settings.");
            if (ImGui.button("Create cave profile (disabled)")) {
                try { workspace.replace(selected,CaveProfileFields.enableProfile(selected,workspace.read(selected))); error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        ImGui.textWrapped("Absent fields keep Iris defaults. This inspector does not resolve which profile the generator selects.");
        for (String field:CaveProfileFields.EDITABLE) {
            String token=profile.token(field),key="caveProfile#"+ResourceTypes.base(selected)+"."+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(CaveProfileFields.help(field));
            if (CaveProfileFields.booleanField(field)) {
                String effective=token==null ? CaveProfileFields.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Value (Iris default)" : "Value",effective.equals("true"))) {
                    try { workspace.replace(selected,CaveProfileFields.editBoolean(selected,workspace.read(selected),field,!effective.equals("true"))); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else if (token==null) {
                if (ImGui.button("Enable ("+CaveProfileFields.defaultToken(field)+")")) {
                    try { workspace.replace(selected,CaveProfileFields.enable(selected,workspace.read(selected),field)); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(CaveProfileFields.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,CaveProfileFields.editText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        drawCavePlacement(profile);
        for (String field:NoiseStyleFields.CAVE_FIELDS) {
            if (ImGui.treeNode(field+" settings")) {
                drawStyleFields(profile,NoiseStyleFields.Target.cave(ResourceTypes.base(selected),field));
                ImGui.treePop();
            }
        }
        if (ImGui.treeNode("Other cave settings (read-only)")) {
            for (String field:profile.fields()) if (!CaveProfileFields.EDITABLE.contains(field) && !CavePlacementFields.EDITABLE.contains(field) && !NoiseStyleFields.CAVE_FIELDS.contains(field)) ImGui.textWrapped(field);
            ImGui.treePop();
        }
    }
    private void drawCavePlacement(LosslessJson profile) {
        if (ImGui.treeNode("Vertical bounds")) {
            drawCaveRange(profile);
            ImGui.treePop();
        }
        for (String field : CavePlacementFields.ENUM_FIELDS) {
            ImGui.pushID("cavePlacement#" + field);
            ImGui.text(field); ImGui.textWrapped(CavePlacementFields.description(field));
            try {
                String stored = CavePlacementFields.enumValue(profile, field);
                boolean absent = profile.token(field) == null;
                String label = stored == null ? CavePlacementFields.nullable(field) ? "No override (Iris default)" : CavePlacementFields.defaultValue(field) + " (Iris default)"
                        : stored.length() > 128 ? "Long stored mode" : stored;
                if (stored != null && !CavePlacementFields.choices(field).contains(stored)) ImGui.textWrapped("Unrecognized stored mode. Choose a listed mode to replace it.");
                if (ImGui.beginCombo("Mode", label)) {
                    if (CavePlacementFields.nullable(field) && ImGui.selectable("No override", stored == null)) assignCaveMode(field, null);
                    for (String choice : CavePlacementFields.choices(field)) {
                        if (ImGui.selectable(choice, choice.equals(stored))) assignCaveMode(field, choice);
                        if (ImGui.isItemHovered()) ImGui.setTooltip(CavePlacementFields.choiceDescription(field, choice));
                    }
                    ImGui.endCombo();
                }
                if (absent) ImGui.textWrapped("Absent: choosing a mode writes it explicitly.");
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored mode has an unsupported type (read-only)."); }
            ImGui.popID();
        }
    }
    private void assignCaveMode(String field, String choice) {
        try { workspace.replace(selected, CavePlacementFields.assign(selected, workspace.read(selected), field, choice)); error = null; }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private void drawCaveRange(LosslessJson profile) {
        ImGui.textWrapped(CavePlacementFields.description("verticalRange"));
        LosslessJson range;
        try { range = CavePlacementFields.range(profile); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored range is a snippet or unsupported value (read-only)."); return; }
        if (range == null) {
            String bounds = CavePlacementFields.rangeDefault("min", true) + ".." + CavePlacementFields.rangeDefault("max", true);
            ImGui.textWrapped("Absent range uses " + bounds + ". Enable it explicitly before editing.");
            if (ImGui.button("Enable vertical bounds (" + bounds + ")")) {
                try { workspace.replace(selected, CavePlacementFields.enableRange(selected, workspace.read(selected))); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        String prefix = CavePlacementFields.draftPrefix(ResourceTypes.base(selected));
        for (String member : CavePlacementFields.RANGE_MEMBERS) {
            String token = range.token(member);
            try { if (token != null) new java.math.BigDecimal(token); }
            catch (NumberFormatException mismatch) { ImGui.textWrapped("Stored " + member + " is not numeric (read-only)."); return; }
        }
        for (String member : CavePlacementFields.RANGE_MEMBERS) {
            String token = range.token(member), effective = token == null ? CavePlacementFields.rangeDefault(member, false) : token;
            String key = prefix + member;
            ImString input = values.computeIfAbsent(key, ignored -> new ImString(effective, Math.max(128, effective.getBytes(StandardCharsets.UTF_8).length + 1)));
            ImGui.inputText(member + (token == null ? " (Iris default)" : "") + "##" + key, input);
            drafts.update(key, effective, input.get());
        }
        ImGui.textWrapped("Apply writes both bounds, filling absent members. Use finite numbers with minimum at most maximum.");
        if (ImGui.button("Apply vertical bounds")) {
            try {
                workspace.replace(selected, CavePlacementFields.editRange(selected, workspace.read(selected), values.get(prefix + "min").get(), values.get(prefix + "max").get()));
                for (String member : CavePlacementFields.RANGE_MEMBERS) { drafts.applied(prefix + member); values.remove(prefix + member); }
                error = null;
            } catch (Exception failure) { error = failure.getMessage(); }
        }
        for (String member : range.fields()) if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) ImGui.textWrapped(member + " (read-only)");
    }
    private void drawTerrain3D(LosslessJson document) {
        ImGui.textWrapped(Terrain3DFields.description());
        LosslessJson profile;
        try { profile = Terrain3DFields.object(document); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored terrain profile is a snippet or unsupported value (read-only)."); return; }
        if (profile == null) {
            ImGui.textWrapped("No active profile. Enable creates an object using Iris defaults, including enabled=true.");
            if (ImGui.button("Enable terrain profile (Iris defaults)")) {
                try { workspace.replace(selected,Terrain3DFields.enableProfile(selected,workspace.read(selected))); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        ImGui.textWrapped("Absent fields keep Iris defaults. Density and fissure styles use terrain-specific bounds; snippets stay read-only.");
        for (String field : Terrain3DFields.EDITABLE) {
            String token = profile.token(field), key = "terrain3D#"+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(Terrain3DFields.description(field));
            if (Terrain3DFields.booleanField(field)) {
                String effective = token == null ? Terrain3DFields.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token == null ? "Enabled (Iris default)" : "Enabled",effective.equals("true"))) {
                    try { workspace.replace(selected,Terrain3DFields.editBoolean(selected,workspace.read(selected),field,!effective.equals("true"))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (token == null) {
                if (ImGui.button("Enable ("+Terrain3DFields.defaultToken(field)+")")) {
                    try { workspace.replace(selected,Terrain3DFields.enable(selected,workspace.read(selected),field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(Terrain3DFields.constraints(field));
                ImString input = values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,Terrain3DFields.editText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        for (String field : NoiseStyleFields.TERRAIN_FIELDS) {
            if (ImGui.treeNode(field+" settings")) {
                drawStyleFields(profile,NoiseStyleFields.Target.terrain(field));
                ImGui.treePop();
            }
        }
    }
    private void drawBiomeGenerators(LosslessJson document) {
        ImGui.textWrapped(BiomeGeneratorLinks.description());
        ImGui.textWrapped("Choose exact in-pack generator files. Aliases and target contents are not checked.");
        if (document.token("generators") == null) {
            ImGui.textWrapped("Absent list uses Iris's one default link (" + BiomeGeneratorLinks.defaultGenerator() + "). Enable it explicitly before editing.");
            if (ImGui.button("Enable default generator list")) {
                try { commitBiomeGenerators(BiomeGeneratorLinks.enable(selected, workspace.read(selected)), false); }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        List<LosslessJson> links;
        try { links = document.objectArray("generators"); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Generator list contains unsupported non-object entries (read-only)."); return; }
        for (int index=0;index<links.size();index++) {
            var link = links.get(index); String key = "generator#" + index;
            ImGui.pushID(key); ImGui.text("Link " + index);
            BiomeGeneratorLinks.Action action = null;
            if (ImGui.button("Duplicate")) action = BiomeGeneratorLinks.Action.DUPLICATE;
            ImGui.sameLine(); ImGui.beginDisabled(links.size() <= BiomeGeneratorLinks.minimumEntries());
            if (ImGui.button("Remove")) action = BiomeGeneratorLinks.Action.REMOVE;
            ImGui.endDisabled(); ImGui.beginDisabled(index == 0);
            if (ImGui.button("Move up")) action = BiomeGeneratorLinks.Action.UP;
            ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index == links.size()-1);
            if (ImGui.button("Move down")) action = BiomeGeneratorLinks.Action.DOWN;
            ImGui.endDisabled();
            if (action != null) {
                ImGui.popID();
                try { commitBiomeGenerators(BiomeGeneratorLinks.change(selected, workspace.read(selected), index, action), false); }
                catch (Exception failure) { error = failure.getMessage(); }
                return;
            }
            try {
                String current = link.stringToken("generator");
                String preview = current == null ? "Absent (default: " + BiomeGeneratorLinks.defaultGenerator() + ")" : current.length()>128 ? "Long stored reference" : current;
                if (ImGui.beginCombo("Generator", preview)) {
                    for (String choice : BiomeGeneratorLinks.choices(workspace)) {
                        if (ImGui.selectable(choice, choice.equals(current))) {
                            try { workspace.replace(selected, BiomeGeneratorLinks.assign(selected, workspace.read(selected), index, workspace, choice)); error = null; }
                            catch (Exception failure) { error = failure.getMessage(); }
                        }
                    }
                    ImGui.endCombo();
                }
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored generator reference is not a string (read-only)."); }
            String minimumToken = link.token("min"), maximumToken = link.token("max");
            String storedMin = minimumToken == null ? BiomeFields.generatorDefault("min") : minimumToken;
            String storedMax = maximumToken == null ? BiomeFields.generatorDefault("max") : maximumToken;
            String minKey = key + ".min", maxKey = key + ".max";
            ImString minimum = values.computeIfAbsent(minKey, ignored -> new ImString(storedMin, 128));
            ImString maximum = values.computeIfAbsent(maxKey, ignored -> new ImString(storedMax, 128));
            ImGui.inputText("Minimum", minimum); drafts.update(minKey, storedMin, minimum.get());
            if (ImGui.isItemHovered()) ImGui.setTooltip(BiomeFields.description("generators.min"));
            ImGui.inputText("Maximum", maximum); drafts.update(maxKey, storedMax, maximum.get());
            if (ImGui.isItemHovered()) ImGui.setTooltip(BiomeFields.description("generators.max"));
            if (ImGui.button(minimumToken == null || maximumToken == null ? "Apply/enable height range" : "Apply height range")) {
                try {
                    workspace.replace(selected, BiomeGeneratorLinks.range(selected, workspace.read(selected), index, Double.parseDouble(minimum.get()), Double.parseDouble(maximum.get())));
                    drafts.applied(minKey); drafts.applied(maxKey); values.remove(minKey); values.remove(maxKey); error = null;
                } catch (Exception failure) { error = failure.getMessage(); }
            }
            ImGui.popID(); ImGui.separator();
        }
        drawNewBiomeGenerator();
    }
    private void drawNewBiomeGenerator() {
        String prefix = "generator#new.";
        ImGui.pushID(prefix);
        ImString reference = values.computeIfAbsent(prefix+"ref", ignored -> new ImString(16_384));
        ImString minimum = values.computeIfAbsent(prefix+"min", ignored -> new ImString(BiomeFields.generatorDefault("min"),128));
        ImString maximum = values.computeIfAbsent(prefix+"max", ignored -> new ImString(BiomeFields.generatorDefault("max"),128));
        if (ImGui.beginCombo("New generator", reference.get().isEmpty() ? "Choose resource" : reference.get())) {
            for (String choice : BiomeGeneratorLinks.choices(workspace)) if (ImGui.selectable(choice,choice.equals(reference.get()))) reference.set(choice);
            ImGui.endCombo();
        }
        ImGui.inputText("New minimum",minimum); ImGui.inputText("New maximum",maximum);
        drafts.update(prefix+"ref","",reference.get());
        drafts.update(prefix+"min",BiomeFields.generatorDefault("min"),minimum.get());
        drafts.update(prefix+"max",BiomeFields.generatorDefault("max"),maximum.get());
        if (ImGui.button("Add generator link")) {
            try { commitBiomeGenerators(BiomeGeneratorLinks.add(selected,workspace.read(selected),workspace,reference.get(),Double.parseDouble(minimum.get()),Double.parseDouble(maximum.get())),true); }
            catch (Exception failure) { error = failure.getMessage(); }
        }
        ImGui.popID();
    }
    private void commitBiomeGenerators(byte[] next, boolean includesNewForm) throws java.io.IOException {
        if (drafts.transitionUnder("generator#", () -> discardListDrafts("generator",key -> includesNewForm && key.startsWith("generator#new.")), () -> workspace.replace(selected,next))) {
            values.keySet().removeIf(key -> key.startsWith("generator#")); error = null;
        }
    }
    private void drawArray(String field, LosslessJson document) {
        List<String> entries;
        try { entries = document.stringArrayPath(field); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored value is not a string array (read-only)."); return; }
        ImGui.pushID(field);
        boolean region = RegionFields.region(selected), dimensionRegions = DimensionFields.dimension(selected) && field.equals("regions");
        boolean children = BiomeFields.biome(selected) && field.equals("children");
        if (children) ImGui.textWrapped("Optional child list; empty means no child links. Iris supports cyclic relationships. Exact files only; aliases and target contents are unchecked.");
        ImGui.textWrapped(region || children ? "Exact in-pack biome keys only; loader aliases are not resolved." : dimensionRegions ? "Exact in-pack region keys only; loader aliases are not resolved." : "Registry key syntax only; registry existence is not checked.");
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
            BiomeChildren.Action action = null;
            if (children) {
                ImGui.sameLine(); if (ImGui.button("Duplicate")) action = BiomeChildren.Action.DUPLICATE;
                ImGui.beginDisabled(index == 0);
                if (ImGui.button("Move up")) action = BiomeChildren.Action.UP;
                ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index == entries.size()-1);
                if (ImGui.button("Move down")) action = BiomeChildren.Action.DOWN;
                ImGui.endDisabled();
            }
            ImGui.popID();
            if (remove) { applyArray(field, index, null); break; }
            if (action != null) { changeChildren(index,action); break; }
        }
        String key = field + "#new";
        ImString input = values.computeIfAbsent(key, ignored -> new ImString(16_384));
        ImGui.inputText("New key", input);
        drafts.update(key, "", input.get());
        if ((region || children || dimensionRegions) && ImGui.beginCombo(region || children ? "Available biomes" : "Available regions", "Choose resource")) {
            for (String reference : region || children ? RegionFields.biomeKeys(workspace) : DimensionFields.regionKeys(workspace))
                if (ImGui.selectable(reference)) { input.set(reference); drafts.update(key, "", reference); }
            ImGui.endCombo();
        }
        if (ImGui.button("Add")) applyArray(field, entries.size(), input.get());
        ImGui.popID();
    }
    private void applyArray(String field, int index, String value) {
        try {
            int count = new LosslessJson(workspace.read(selected)).stringArrayPath(field).size();
            boolean structural = value == null || index == count;
            if (structural && !discardArrayDrafts(field, index == count ? field + "#new" : null)) return;
            byte[] updated = RegionFields.region(selected)
                    ? RegionFields.editArray(selected, workspace.read(selected), field, index, value, workspace)
                    : BiomeFields.biome(selected) && field.equals("children") ? BiomeChildren.edit(selected,workspace.read(selected),index,value,workspace)
                    : DimensionFields.editArray(selected, workspace.read(selected), field, index, value, workspace);
            workspace.replace(selected, updated);
            if (structural) {
                values.keySet().removeIf(key -> key.startsWith(field + "#")); drafts.clearUnder(field + "#");
            } else { values.remove(field + "#" + index); drafts.applied(field + "#" + index); }
            error = null;
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void changeChildren(int index, BiomeChildren.Action action) {
        try {
            byte[] next = BiomeChildren.change(selected,workspace.read(selected),index,action);
            if (drafts.transitionUnder("children#", () -> discardArrayDrafts("children",null), () -> workspace.replace(selected,next))) {
                values.keySet().removeIf(key -> key.startsWith("children#")); error = null;
            }
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void drawResources(List<ResourceTree.Node> nodes) {
        for (ResourceTree.Node node : nodes) {
            ImGui.pushID(node.path());
            if (node.folder()) {
                if (!filter.get().isBlank()) ImGui.setNextItemOpen(true);
                boolean expanded = ImGui.treeNodeEx(node.name(), ImGuiTreeNodeFlags.SpanAvailWidth);
                if (ImGui.getIO().getKeyCtrl() && ImGui.isItemClicked(2)) createSubfolder(node.path());
                else if (ImGui.getIO().getKeyCtrl() && ImGui.isItemClicked(1)) createResource(node.path());
                if (expanded) { drawResources(node.children()); ImGui.treePop(); }
            } else if (ImGui.selectable(node.name() + (workspace.changed(node.path()) ? " *" : ""), node.path().equals(selected))) {
                if (ImGui.getIO().getKeyCtrl()) duplicateResource(node.path());
                else if (!node.path().equals(selected)) selectResource(node.path());
            }
            ImGui.popID();
        }
    }
    private void duplicateResource(String source) {
        try {
            String base = ResourceTypes.fileFamily(source);
            if (base == null) throw new IllegalArgumentException("Duplicate supported generation JSON, objects/*.iob or images/*.png/*.jpg/*.jpeg only");
            String originalKey = source.substring(base.length() + 1, source.length() - ResourceTypes.extension(source).length());
            String key = JOptionPane.showInputDialog(null, "New resource key (omit file extension):", originalKey + "_copy");
            if (key == null) return;
            ResourceTypes.target(source, key); // Validate before asking to discard text drafts.
            transition(false, () -> { selected = workspace.duplicate(source, key); filter.set(""); error = null; });
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void createResource(String parent) {
        try {
            ResourceCreation.base(parent);
            String name = JOptionPane.showInputDialog(null, "New resource name (omit .json):", "new_resource");
            if (name == null) return;
            String dependency = null, type = ResourceCreation.dependencyType(parent);
            if (type != null) {
                List<String> choices = ResourceCreation.choices(workspace, parent);
                if (choices.isEmpty()) throw new IllegalArgumentException("Create a " + type + " resource first; this template needs an existing reference");
                Object chosen = JOptionPane.showInputDialog(null, "Choose existing " + type + " key for the new resource:", "Resource reference",
                        JOptionPane.QUESTION_MESSAGE, null, choices.toArray(), choices.get(0));
                if (chosen == null) return;
                dependency = chosen.toString();
            }
            String chosenDependency = dependency;
            transition(false, () -> { selected = workspace.createResource(parent, name, chosenDependency); filter.set(""); error = null; });
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private void createSubfolder(String parent) {
        try {
            if (!ResourceTypes.folderFamily(parent)) throw new IllegalArgumentException("This folder is read-only for subfolder creation; dimensions must stay flat");
            String name = JOptionPane.showInputDialog(null, "New subfolder name:", "new_folder");
            if (name == null) return;
            workspace.createSubfolder(parent, name); filter.set(""); error = null;
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    private boolean transition(boolean replaceWorkspace, InspectorDrafts.Change change) throws java.io.IOException {
        boolean committed = drafts.transition(replaceWorkspace ? this::replaceAllowed : this::discardDraftsAllowed, change);
        if (committed) values.clear();
        return committed;
    }
    private void selectResource(String path) {
        try { transition(false, () -> { selected = path; }); }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private boolean discardArrayDrafts(String field, String appliedKey) {
        return discardListDrafts(field, key -> key.equals(appliedKey));
    }
    private boolean discardListDrafts(String field, java.util.function.Predicate<String> included) {
        boolean other = values.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(field + "#")
                && !included.test(entry.getKey()) && drafts.contains(entry.getKey()));
        return !other || JOptionPane.showConfirmDialog(null, "This changes list positions and discards other unapplied text in this list. Continue?",
                "Unapplied text", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }
    private boolean discardDraftsAllowed() {
        if (!drafts.any()) return true;
        if (JOptionPane.showConfirmDialog(null, "Discard unapplied text? Apply it first to include it in saved pack data.",
                "Unapplied text", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
        return true;
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
                case "New" -> {
                    String name = JOptionPane.showInputDialog(null, "Pack key (letters a-z, digits, _ or -):", "new_pack");
                    if (name == null) return;
                    PackWorkspace next = StarterPack.create(name);
                    String dimension = "dimensions/" + StarterPack.normalizeName(name) + ".json";
                    transition(true, () -> { workspace = next; selected = dimension; });
                }
                case "Open", "Import" -> {
                    Path path = choose(action.equals("Open"), false);
                    if (path != null) transition(true, () -> {
                        PackWorkspace next = action.equals("Open") ? PackWorkspace.open(path) : PackWorkspace.importZip(path);
                        workspace = next; selected = null;
                    });
                }
                case "Save" -> { if (!appliedValuesOnlyAllowed() || !saveWorkspace()) return; }
                case "Export" -> { if (!appliedValuesOnlyAllowed()) return; Path path = choose(false, true); if (path != null) {
                    boolean exists = java.nio.file.Files.exists(path);
                    if (exists && JOptionPane.showConfirmDialog(null, "Replace existing ZIP?\n" + path, "Export", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                    workspace.exportZip(path, exists);
                } }
            }
            error = null;
        } catch (Exception failure) { error = failure.getMessage(); }
    }
    public static void main(String[] args) {
        if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64") || Runtime.version().feature() != 25)
            throw new IllegalStateException("Iris Visualizer requires Windows x64 and Java 25. Install the runtime separately.");
        if (args.length == 1 && args[0].equals("--check-installation")) {
            ImGui.createContext();
            try {
                if (DimensionFields.EDITABLE.isEmpty() || BiomeFields.EDITABLE.isEmpty() || GeneratorFields.EDITABLE.isEmpty() || RegionFields.EDITABLE.isEmpty() || NoiseFields.EDITABLE.isEmpty() || NoiseStyleFields.choices().isEmpty() || BiomeGeneratorLinks.minimumEntries() != 1 || BiomeGeneratorLinks.defaultGenerator() == null || BiomeChildren.description().isEmpty() || Terrain3DFields.EDITABLE.isEmpty() || CaveProfileFields.EDITABLE.isEmpty() || CavePlacementFields.choices("defaultObjectAnchor").isEmpty() || InterpolationFields.choices().isEmpty() || CompositeNoise.minimumEntries() != 1 || FieldCoverage.fields("dimension").isEmpty()) throw new IllegalStateException("Missing field policy");
                try {
                    if (StarterPack.create("installation_check").paths().size() != 4) throw new IllegalStateException("Missing starter resources");
                } catch (java.io.IOException failure) { throw new IllegalStateException("Packaged starter unavailable", failure); }
                System.out.println("PASS: packaged ImGui context, field definitions, starter templates and GLFW " + glfwGetVersionString());
            } finally { ImGui.destroyContext(); }
            return;
        }
        launch(new EditorApp());
    }
}
