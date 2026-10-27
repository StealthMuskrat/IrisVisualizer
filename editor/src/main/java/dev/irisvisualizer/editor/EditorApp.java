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
    private String riverCatalogDimension;
    private final RiverIdLists.CatalogCache riverIdCatalogs = new RiverIdLists.CatalogCache();
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
                    drawDimensionPalettes(document);
                    drawSurfaceLayerFallback(document);
                    drawDimensionHeights(document);
                    drawRootStyles(document, true);
                    drawCaveProfile(document);
                    drawHydrology(document);
                    drawRiverPolicy(document);
                    drawHydrologyEnvelopes();
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
        drawRiverPolicy(document);
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
        ImGui.textWrapped(target.strictBounds() ? "This density style requires positive cellular zoom even when cellular frequency is zero."
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
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
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
        drawBiomeDerivatives(document);
        drawSurfaceLayerFallback(document);
        drawBiomeLayers(document);
        drawBiomeSlabWall(document);
        drawRootStyles(document, false);
        drawCaveProfile(document);
        drawRiverPolicy(document);
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
            for (String field : document.fields()) if (!BiomeFields.EDITABLE.contains(field) && !NoiseStyleFields.BIOME_FIELDS.contains(field) && !BiomeChildren.EDITABLE.contains(field) && !Terrain3DFields.OBJECT_FIELDS.contains(field) && !CaveProfileFields.OBJECT_FIELDS.contains(field) && !RiverPolicyFields.OBJECT_FIELDS.contains(field) && !SurfaceLayerFallbacks.OBJECT_FIELDS.contains(field) && !BiomeLayers.LIST_FIELDS.contains(field) && !BiomeDerivatives.EDITABLE.contains(field) && !BiomeSlabWall.OBJECT_FIELDS.contains(field) && !field.equals("generators")) ImGui.textWrapped(field);
    }
    private void drawBiomeSlabWall(LosslessJson document) {
        if (!ImGui.collapsingHeader("Slab and wall materials")) return;
        ImGui.textWrapped("Slab/wall placement also depends on dimension post-processing settings and native terrain conditions. Wall palettes are used by cave and subterrain material paths too. Editing here does not change dimension flags.");
        for (String field : BiomeSlabWall.OBJECT_FIELDS) {
            if (!ImGui.treeNode(field)) continue;
            ImGui.textWrapped(BiomeSlabWall.description(field));LosslessJson palette=null;boolean supported=true;
            try { palette=BiomeSlabWall.object(document,field); }
            catch (IllegalArgumentException unsupported) { supported=false;ImGui.textWrapped("Stored material is a snippet or unsupported value (read-only)."); }
            if (supported && palette==null) {
                ImGui.textWrapped("Absent: the source uses an empty palette, placing no material. Create that same default explicitly before editing.");
                if (ImGui.button("Create empty palette (source default)")) {
                    try { commitProfileChange(BiomeSlabWall.prefix(field),BiomeSlabWall.create(selected,workspace.read(selected),field),null); }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else if (supported) {
                ImGui.textWrapped("An empty block list selects no material. In an existing object, missing entries inherit GRASS_BLOCK until explicitly initialized. Thickness values remain read-only here.");
                if (field.equals("slab") && ImGui.treeNode("Slab slope condition")) {
                    drawSlopeCondition(palette,BiomeSlabWall.prefix(field),
                            () -> workspace.replace(selected,BiomeSlabWall.createSlope(selected,workspace.read(selected),field)),
                            (minimum,maximum) -> workspace.replace(selected,BiomeSlabWall.setSlope(selected,workspace.read(selected),field,minimum,maximum)));
                    ImGui.treePop();
                }
                drawGeneralBlockPalette(palette,GeneralBlockPalettes.Target.slabWall(field));
                if (ImGui.treeNode("Other material data (read-only)")) { for (String path : BiomeSlabWall.readOnlyPaths(document,field)) ImGui.textWrapped(path);ImGui.treePop(); }
            }
            ImGui.treePop();
        }
    }
    private void drawBiomeDerivatives(LosslessJson document) {
        if (!ImGui.collapsingHeader("Biome derivatives and scatter")) return;
        ImGui.textWrapped("These are biome registry keys, separate from pack biome file references. Enter nonblank text; registry existence and physical biome mapping require Iris. Stored keys are preserved exactly. Native structure selection can constrain Minecraft derivatives for sea and shore roles.");
        for (String field : BiomeDerivatives.SCALARS) {
            ImGui.pushID(field);ImGui.textWrapped(BiomeDerivatives.description(field));
            try {
                String stored=BiomeDerivatives.storedKey(document,field),key=BiomeDerivatives.prefix(field)+"value";
                if (stored==null) ImGui.textWrapped(field.equals("derivative") ? "Absent: Iris default "+BiomeDerivatives.defaultKey() : "Absent/null: inherit derivative. Failed native registry lookup can also fall back to derivative.");
                String baseline=stored==null ? field.equals("derivative") ? BiomeDerivatives.defaultKey() : "" : stored;
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(baseline,Math.max(4096,baseline.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(field,input);drafts.update(key,baseline,input.get());
                if (ImGui.button("Apply registry key")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDerivatives.set(selected,workspace.read(selected),field,input.get())));
                if (field.equals("vanillaDerivative")) {
                    ImGui.sameLine();if (ImGui.button("Inherit derivative")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDerivatives.inherit(selected,workspace.read(selected))));
                }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored derivative has an unsupported type (read-only)."); }
            ImGui.popID();
        }
        for (String field : BiomeDerivatives.LISTS) if (ImGui.treeNode(field)) { drawBiomeScatter(document,field);ImGui.treePop(); }
        ImGui.textWrapped("The biomeStyle noise controls under Distribution styles select scatter variants. A single entry does not need random selection. An empty sky list follows the ground selection; an empty ground list follows derivative.");
    }
    private void drawBiomeScatter(LosslessJson document,String field) {
        ImGui.textWrapped(BiomeDerivatives.description(field));List<String> entries;
        try { entries=BiomeDerivatives.entries(document,field); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored scatter list is not a string array (read-only).");return; }
        if (entries==null) {
            ImGui.textWrapped(field.equals("biomeScatter") ? "Absent: use derivative." : "Absent: use the ground biome selection.");
            if (ImGui.button("Enable empty scatter list (Iris default)")) {
                try { commitProfileChange(BiomeDerivatives.prefix(field),BiomeDerivatives.enable(selected,workspace.read(selected),field),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=BiomeDerivatives.prefix(field)+"new";ImString newValue=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));
        ImGui.inputText("New registry key",newValue);drafts.update(newKey,"",newValue.get());
        if (ImGui.button("Add scatter key")) { changeBiomeScatter(field,RiverProfiles.Action.ADD,-1,newValue.get(),newKey);return; }
        if (entries.isEmpty()) ImGui.textWrapped(field.equals("biomeScatter") ? "Empty: use derivative." : "Empty: use the ground biome selection.");
        for (int index=0;index<entries.size();index++) {
            ImGui.pushID(index);int entryIndex=index;String stored=entries.get(index),key=BiomeDerivatives.prefix(field)+index+".value";
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Registry key",input);drafts.update(key,stored,input.get());
            if (ImGui.button("Apply key")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDerivatives.edit(selected,workspace.read(selected),field,entryIndex,input.get())));
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Move down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            ImGui.popID();if (action!=null) { changeBiomeScatter(field,action,index,null,null);return; }
        }
    }
    private void changeBiomeScatter(String field,RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(BiomeDerivatives.prefix(field),BiomeDerivatives.change(selected,workspace.read(selected),field,action,index,value),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawBiomeLayers(LosslessJson document) {
        if (!ImGui.collapsingHeader("Biome material layers")) return;
        for (String list : BiomeLayers.LIST_FIELDS) if (ImGui.treeNode(list)) { drawBiomeLayerList(document,list);ImGui.treePop(); }
    }
    private void drawBiomeLayerList(LosslessJson document,String list) {
        if (list.equals("caveCeilingLayers")) ImGui.textWrapped("Independent ceiling layers, indexed upward from the ceiling. Their thickness generators use this list's entries.");
        else ImGui.textWrapped(BiomeLayers.description(list));
        List<LosslessJson> layers;
        try { layers=BiomeLayers.entries(document,list); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored layer list contains snippets or unsupported entries (read-only).");return; }
        if (layers==null) {
            ImGui.textWrapped(list.equals("layers") ? "The absent surface list uses one default GRASS_BLOCK layer." : "The absent list is empty.");
            if (ImGui.button("Enable layer list (source defaults)")) {
                try { commitProfileChange(BiomeLayers.listPrefix(list),BiomeLayers.enable(selected,workspace.read(selected),list),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        ImGui.textWrapped("Add uses the layer class defaults, including GRASS_BLOCK. Lists may be empty. Slope gates affect surface layers; sea/ceiling slope settings stay read-only here.");
        if (ImGui.button("Add default layer (GRASS_BLOCK)")) { changeBiomeLayer(list,RiverProfiles.Action.ADD,-1);return; }
        for (int index=0;index<layers.size();index++) {
            if (!ImGui.treeNode("Layer "+index)) continue;
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate layer")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove layer")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);
            if (ImGui.button("Move layer up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==layers.size()-1);
            if (ImGui.button("Move layer down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeBiomeLayer(list,action,index);return; }
            var layer=layers.get(index);drawLayerThickness(layer,list,index);
            if (list.equals("layers") && ImGui.treeNode("Surface slope condition")) { drawLayerSlope(layer,list,index);ImGui.treePop(); }
            else if (!list.equals("layers") && layer.token("slopeCondition")!=null) ImGui.textWrapped("slopeCondition (read-only in this layer context)");
            if (ImGui.treeNode("Layer block palette")) { drawGeneralBlockPalette(layer,GeneralBlockPalettes.Target.layer(list,index));ImGui.treePop(); }
            for (String item : layer.fields()) if (!BiomeLayers.EDITABLE.contains(item)) ImGui.textWrapped(item+" (read-only)");
            ImGui.treePop();
        }
        if (ImGui.treeNode("Other layer data (read-only)")) { for (String path : BiomeLayers.readOnlyPaths(document,list)) ImGui.textWrapped(path);ImGui.treePop(); }
    }
    private void drawLayerThickness(LosslessJson layer,String list,int index) {
        String min=layer.token("minHeight"),max=layer.token("maxHeight");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored thickness has an unsupported type (read-only).");return; }
        String minKey=BiomeLayers.layerPrefix(list,index)+"minHeight",maxKey=BiomeLayers.layerPrefix(list,index)+"maxHeight",storedMin=min==null ? BiomeLayers.defaultHeight("minHeight") : min,storedMax=max==null ? BiomeLayers.defaultHeight("maxHeight") : max;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.textWrapped("Thickness: exact integers; minimum 0-2032, maximum 1-2032. The editor requires minimum <= maximum and applies both together.");
        ImGui.inputText(min==null ? "Minimum thickness (Iris default)" : "Minimum thickness",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum thickness (Iris default)" : "Maximum thickness",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply layer thickness")) {
            try { workspace.replace(selected,BiomeLayers.setThickness(selected,workspace.read(selected),list,index,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    @FunctionalInterface
    private interface SlopeWriter { void apply(String minimum,String maximum) throws Exception; }
    private void drawLayerSlope(LosslessJson layer,String list,int index) {
        drawSlopeCondition(layer,BiomeLayers.layerPrefix(list,index),
                () -> workspace.replace(selected,BiomeLayers.createSlope(selected,workspace.read(selected),list,index)),
                (minimum,maximum) -> workspace.replace(selected,BiomeLayers.setSlope(selected,workspace.read(selected),list,index,minimum,maximum)));
    }
    private void drawSlopeCondition(LosslessJson layer,String prefix,InspectorDrafts.Change create,SlopeWriter writer) {
        LosslessJson slope;
        try { slope=BiomeLayers.slope(layer); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored slope condition is a snippet or unsupported value (read-only).");return; }
        ImGui.textWrapped("Slope bounds are finite from 0 to 1024. Minimum 0 with maximum >= 10 disables slope gating; the source defaults are 0 to 10. No terrain slopes are sampled here.");
        if (slope==null) {
            if (ImGui.button("Create slope condition (source defaults)")) applyRoutingExtra(create);
            return;
        }
        String min=slope.token("minimumSlope"),max=slope.token("maximumSlope");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored slope bound has an unsupported type (read-only).");return; }
        String minKey=prefix+"slopeCondition.minimumSlope",maxKey=prefix+"slopeCondition.maximumSlope",storedMin=min==null ? BiomeLayers.slopeDefault("minimumSlope") : min,storedMax=max==null ? BiomeLayers.slopeDefault("maximumSlope") : max;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(min==null ? "Minimum slope (Iris default)" : "Minimum slope",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum slope (Iris default)" : "Maximum slope",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply slope pair")) {
            try { writer.apply(minimum.get(),maximum.get());drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
        for (String item : slope.fields()) if (!BiomeLayers.SLOPE_FIELDS.contains(item)) ImGui.textWrapped(item+" (read-only)");
    }
    private void changeBiomeLayer(String list,RiverProfiles.Action action,int index) {
        try { commitProfileChange(BiomeLayers.listPrefix(list),BiomeLayers.change(selected,workspace.read(selected),list,action,index),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawSurfaceLayerFallback(LosslessJson document) {
        if (!ImGui.collapsingHeader("Surface layer fallback")) return;
        boolean biome=BiomeFields.biome(selected);String token=document.token("surfaceLayerFallback"),current;
        ImGui.textWrapped(SurfaceLayerFallbacks.description(selected));
        try { current=token==null || token.equals("null") && biome ? null : document.stringToken("surfaceLayerFallback"); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored fallback has an unsupported type (read-only).");return; }
        if (current==null && !biome) current=SurfaceLayerFallbacks.dimensionDefault();
        boolean inherit=biome && current==null;
        if (ImGui.beginCombo("Fallback",inherit ? "Inherit dimension" : current.length()>128 ? "Unknown stored option" : current+(token==null ? " (Iris default)" : ""))) {
            if (biome && ImGui.selectable("Inherit dimension",inherit)) applyRoutingExtra(() -> workspace.replace(selected,SurfaceLayerFallbacks.set(selected,workspace.read(selected),null)));
            for (String choice : SurfaceLayerFallbacks.choices()) {
                if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SurfaceLayerFallbacks.set(selected,workspace.read(selected),choice)));
                if (ImGui.isItemHovered()) ImGui.setTooltip(SurfaceLayerFallbacks.choiceHelp(choice));
            }
            ImGui.endCombo();
        }
        if (current!=null && !SurfaceLayerFallbacks.choices().contains(current)) ImGui.textWrapped("Unknown stored fallback is retained until you choose a listed option.");
    }
    private void drawDimensionPalettes(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension block palettes")) return;
        for (String field : DimensionPalettes.OBJECT_FIELDS) if (ImGui.treeNode(field)) { drawDimensionPalette(document,field);ImGui.treePop(); }
    }
    private void drawDimensionPalette(LosslessJson document,String field) {
        ImGui.textWrapped(DimensionPalettes.description(field));
        ImGui.textWrapped("General palettes use at least one block and weights 1-"+DimensionPalettes.maximumWeight()+". Block names are checked for nonblank text only; registry, state and block-role validation remain pending. Advanced block data is read-only.");
        LosslessJson palette;
        try { palette=DimensionPalettes.object(document,field); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette is a snippet or unsupported value (read-only).");return; }
        if (palette==null) {
            if (ImGui.button("Create palette ("+DimensionPalettes.defaultBlock(field)+")")) {
                try { commitProfileChange(DimensionPalettes.listPrefix(field),DimensionPalettes.create(selected,workspace.read(selected),field),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        drawGeneralBlockPalette(palette,GeneralBlockPalettes.Target.dimension(field));
    }
    private void drawGeneralBlockPalette(LosslessJson palette,GeneralBlockPalettes.Target target) {
        ImGui.textWrapped("General palettes use weights 1-"+DimensionPalettes.maximumWeight()+". Block names are checked for nonblank text only; registry/state/block-role validation remains pending. Advanced block data stays read-only.");
        if (ImGui.treeNode("Palette noise")) {
            String token=palette.token("zoom"),key=target.noisePrefix()+"zoom";
            ImGui.textWrapped(target.zoomConstraints());
            if (token==null) {
                if (ImGui.button("Enable palette zoom ("+target.zoomDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,target.enableZoom(selected,workspace.read(selected))));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored palette zoom has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Palette zoom",input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply palette zoom")) commitRiverOverride(key,() -> workspace.replace(selected,target.setZoom(selected,workspace.read(selected),input.get())));
            }
            if (ImGui.treeNode("Palette generator style")) { drawStyleFields(palette,target.styleTarget());ImGui.treePop(); }
            ImGui.treePop();
        }
        for (String item : palette.fields()) if (!target.layer() && !target.slabWall() && !RiverMaterialFields.PALETTE_FIELDS.contains(item)) ImGui.textWrapped(item+" (read-only)");
        List<LosslessJson> entries;
        try { entries=DimensionPalettes.entries(palette); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette entries contain snippets or unsupported values (read-only).");return; }
        if (entries==null) {
            ImGui.textWrapped("Missing entries in an existing palette inherit the class default "+target.defaultBlock()+".");
            if (ImGui.button("Enable class block list ("+target.defaultBlock()+")")) {
                try { commitProfileChange(target.listPrefix(),target.enableEntries(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=target.listPrefix()+"new.block";
        ImString newBlock=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New block",newBlock);drafts.update(newKey,"",newBlock.get());
        if (ImGui.button("Add block")) { changeGeneralBlockPalette(target,RiverProfiles.Action.ADD,-1,newBlock.get(),newKey);return; }
        if (entries.isEmpty()) ImGui.textWrapped(target.slabWall() ? "Empty palette: no material is selected. Add a block to author a material palette." : "This list is empty. Add a block to repair the required list.");
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Block "+index)) continue;
            int entryIndex=index;var entry=entries.get(index);RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(entries.size()<=target.minimumEntries());
            if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
            ImGui.endDisabled();ImGui.beginDisabled(index==0);
            if (ImGui.button("Move up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);
            if (ImGui.button("Move down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeGeneralBlockPalette(target,action,index,null,null);return; }
            try {
                String stored=entry.token("block")==null ? "" : entry.stringToken("block"),key=target.listPrefix()+index+".block";
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(entry.token("block")==null ? "Required block (missing)" : "Block",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,target.setBlock(selected,workspace.read(selected),entryIndex,input.get())));
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block is not a string (read-only)."); }
            String token=entry.token("weight"),key=target.listPrefix()+index+".weight";
            if (token==null) {
                if (ImGui.button("Enable weight ("+DimensionPalettes.weightDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,target.enableWeight(selected,workspace.read(selected),entryIndex)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored weight has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Weight (1-"+DimensionPalettes.maximumWeight()+")",input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply weight")) commitRiverOverride(key,() -> workspace.replace(selected,target.setWeight(selected,workspace.read(selected),entryIndex,input.get())));
            }
            for (String item : entry.fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(item)) ImGui.textWrapped(item+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeGeneralBlockPalette(GeneralBlockPalettes.Target target,RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(target.listPrefix(),target.change(selected,workspace.read(selected),action,index,value),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDimensionHeights(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension height")) return;
        ImGui.textWrapped(DimensionHeights.description());ImGui.textWrapped(DimensionHeights.constraints());
        ImGui.textWrapped("Current logicalHeight: "+DimensionHeights.logicalLabel(document)+" (read-only). Borrowed dimension types and live-world height compatibility are not resolved here.");
        LosslessJson range;
        try { range=DimensionHeights.object(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored dimension height is a snippet or unsupported value (read-only).");return; }
        if (range==null) ImGui.textWrapped("Create the source default pair or supply custom bounds, for example when logicalHeight needs a larger span.");
        String min=range==null ? null : range.token("min"),max=range==null ? null : range.token("max");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) ImGui.textWrapped("Stored dimension bound has an unsupported type (read-only).");
        else {
            String minKey=DimensionHeights.draftPrefix()+"min",maxKey=DimensionHeights.draftPrefix()+"max",storedMin=min==null ? DimensionHeights.defaultBound("min",range==null) : min,storedMax=max==null ? DimensionHeights.defaultBound("max",range==null) : max;
            ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText(range==null ? "Minimum dimension Y (source default)" : min==null ? "Minimum dimension Y (range class default)" : "Minimum dimension Y",minimum);drafts.update(minKey,storedMin,minimum.get());
            ImGui.inputText(range==null ? "Maximum dimension Y (source default)" : max==null ? "Maximum dimension Y (range class default)" : "Maximum dimension Y",maximum);drafts.update(maxKey,storedMax,maximum.get());
            if (ImGui.button(range==null ? "Create dimension height pair" : "Apply dimension height pair")) {
                try { workspace.replace(selected,range==null ? DimensionHeights.create(selected,workspace.read(selected),minimum.get(),maximum.get()) : DimensionHeights.set(selected,workspace.read(selected),minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
        }
        if (range!=null) for (String field : range.fields()) if (!List.of("min","max").contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawHydrologyEnvelopes() {
        if (!ImGui.collapsingHeader("Deep-fluid footprint checks")) return;
        ImGui.textWrapped("Checks applied data only. These native pack checks do not validate full geometry, runtime registries, borrowed dimension types or live-world compatibility. Findings do not block Save/Export.");
        try {
            var report=HydrologyEnvelopes.inspect(workspace.read(selected));
            ImGui.text("Deep-fluid systems inspected: "+report.checked());
            if (report.checked()==0 && report.findings().isEmpty()) ImGui.textWrapped("No explicit deep-fluid systems to inspect.");
            else if (report.checked()>0 && report.findings().isEmpty()) ImGui.textWrapped("The inspected deep-fluid footprint margins are inside the applied dimension bounds.");
            for (var finding : report.findings()) ImGui.textWrapped(finding.kind()+": "+finding.path()+" — "+finding.detail());
        } catch (Exception failure) { ImGui.textWrapped("Footprint check unavailable: "+failure.getMessage()); }
    }
    private void drawHydrology(LosslessJson document) {
        if (!ImGui.collapsingHeader("Hydrology")) return;
        ImGui.textWrapped(HydrologyFields.description("hydrology"));
        LosslessJson hydro;
        try { hydro=HydrologyFields.hydrology(document); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored hydrology has an unsupported type (read-only)."); return; }
        if (hydro==null) {
            ImGui.textWrapped("Create an explicit object to edit hydrology. River planning keeps its disabled Iris default.");
            if (ImGui.button("Create hydrology")) {
                try { workspace.replace(selected,HydrologyFields.enableHydrology(selected,workspace.read(selected))); error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (ImGui.treeNode("River configuration")) { drawHydrologyRivers(hydro); ImGui.treePop(); }
        if (ImGui.treeNode("Independent deep fluids")) { drawDeepFluids(hydro);ImGui.treePop(); }
        if (ImGui.treeNode("Standing surface pools")) { drawStandingSurfacePools(hydro);ImGui.treePop(); }
        for (String field : hydro.fields()) if (!HydrologyFields.HYDROLOGY_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawDeepFluids(LosslessJson hydro) {
        ImGui.textWrapped(DeepFluids.description());
        ImGui.textWrapped("Deep-fluid IDs must be unique, avoid reserved hydrology selectors and differ from river profile IDs. Other references are preserved. See Deep-fluid footprint checks for dimension-height margins; placement validation remains pending.");
        List<LosslessJson> pools;
        try { pools=DeepFluids.entries(hydro); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored deep-fluid list contains snippets or unsupported entries (read-only).");return; }
        if (pools==null) {
            if (ImGui.button("Enable empty deep-fluid list")) {
                try { commitProfileChange(DeepFluids.draftPrefix(),DeepFluids.enable(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=DeepFluids.draftPrefix()+"new.id";
        ImString newId=values.computeIfAbsent(newKey,ignored -> new ImString("",256));
        ImGui.inputText("New deep-fluid ID",newId);drafts.update(newKey,"",newId.get());
        ImGui.beginDisabled(pools.size()>=DeepFluids.maximumSystems());
        if (ImGui.button("Add deep lava system")) { ImGui.endDisabled();changeDeepFluid(RiverProfiles.Action.ADD,-1,newId.get(),newKey);return; }
        ImGui.endDisabled();
        for (int index=0;index<pools.size();index++) {
            if (!ImGui.treeNode("Deep-fluid system "+index)) continue;
            var pool=pools.get(index);int poolIndex=index;
            String copyKey=DeepFluids.systemPrefix(index)+"copyId";
            ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",256));
            ImGui.inputText("Duplicate deep-fluid system as ID",copyId);drafts.update(copyKey,"",copyId.get());
            RiverProfiles.Action action=null;
            ImGui.beginDisabled(pools.size()>=DeepFluids.maximumSystems());
            if (ImGui.button("Duplicate deep-fluid system")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.endDisabled();ImGui.sameLine();
            if (ImGui.button("Remove deep-fluid system")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);
            if (ImGui.button("Move deep-fluid system up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==pools.size()-1);
            if (ImGui.button("Move deep-fluid system down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeDeepFluid(action,index,copyId.get(),action==RiverProfiles.Action.DUPLICATE ? copyKey : null);return; }
            try {
                String stored=pool.token("id")==null ? DeepFluids.defaultId() : pool.stringToken("id"),key=DeepFluids.systemPrefix(index)+"id";
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(256,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(pool.token("id")==null ? "Deep-fluid ID (missing; class default shown)" : "Deep-fluid ID",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply deep-fluid ID")) commitRiverOverride(key,() -> workspace.replace(selected,DeepFluids.setId(selected,workspace.read(selected),poolIndex,input.get())));
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored deep-fluid ID is not a string (read-only)."); }
            for (String field : DeepFluids.SCALARS) {
                ImGui.pushID(field);ImGui.textWrapped(DeepFluids.help(field));
                String token=pool.token(field),key=DeepFluids.systemPrefix(index)+field;
                if (DeepFluids.booleanField(field)) {
                    String effective=token==null ? DeepFluids.defaultToken(field) : token;
                    if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                    else if (ImGui.checkbox(token==null ? field+" (Iris default)" : field,effective.equals("true"))) applyRoutingExtra(() -> workspace.replace(selected,DeepFluids.setBoolean(selected,workspace.read(selected),poolIndex,field,!effective.equals("true"))));
                } else if (token==null) {
                    if (ImGui.button("Enable "+field+" ("+DeepFluids.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,DeepFluids.enableScalar(selected,workspace.read(selected),poolIndex,field)));
                } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored "+field+" has an unsupported type (read-only).");
                else {
                    ImGui.textWrapped(DeepFluids.constraints(field));
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                    ImGui.inputText(field,input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,DeepFluids.setScalar(selected,workspace.read(selected),poolIndex,field,input.get())));
                }
                ImGui.popID();
            }
            drawDeepGeometry(pool,index);
            if (ImGui.treeNode("Deep-fluid height")) { drawDeepHeight(pool,index);ImGui.treePop(); }
            if (ImGui.treeNode("Deep-fluid palette")) { drawHydrologyFluids(pool,HydrologyFluidPalettes.Target.deep(index));ImGui.treePop(); }
            for (String field : pool.fields()) if (!DeepFluids.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void drawDeepGeometry(LosslessJson object,int index) {
        ImGui.textWrapped("Apply geometry writes all six values together. Spacing must be at least 2 * (horizontal radius + channel width); depth + headroom must be less than 2 * vertical radius + 1. See Deep-fluid footprint checks for applied dimension-height margins.");
        boolean supported=true;var inputs=new java.util.LinkedHashMap<String,ImString>();
        for (String field : DeepFluids.GEOMETRY) {
            String token=object.token(field),key=DeepFluids.systemPrefix(index)+field;
            if (token!=null && !LosslessJson.numericToken(token)) { ImGui.textWrapped(field+" has an unsupported type (read-only).");supported=false;continue; }
            String stored=token==null ? DeepFluids.defaultToken(field) : token;
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.textWrapped(DeepFluids.constraints(field));ImGui.inputText(token==null ? field+" (Iris default)" : field,input);drafts.update(key,stored,input.get());inputs.put(field,input);
        }
        ImGui.beginDisabled(!supported);
        if (ImGui.button("Apply deep-fluid geometry")) {
            try {
                var next=new java.util.LinkedHashMap<String,String>();inputs.forEach((field,input) -> next.put(field,input.get()));
                workspace.replace(selected,DeepFluids.setGeometry(selected,workspace.read(selected),index,next));
                for (String field : DeepFluids.GEOMETRY) { String key=DeepFluids.systemPrefix(index)+field;drafts.applied(key);values.remove(key); }error=null;
            } catch (Exception failure) { error=failure.getMessage(); }
        }
        ImGui.endDisabled();
    }
    private void drawDeepHeight(LosslessJson object,int index) {
        LosslessJson height;
        try { height=DeepFluids.height(object); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored height is a snippet or unsupported value (read-only).");return; }
        ImGui.textWrapped("The source height default reaches below the standard dimension floor. Review heights for your target dimension and inspect the applied Deep-fluid footprint checks.");
        if (height==null) {
            ImGui.textWrapped("Absent height: "+DeepFluids.heightCreationLabel()+".");
            if (ImGui.button("Create deep-fluid height (source defaults)")) applyRoutingExtra(() -> workspace.replace(selected,DeepFluids.createHeight(selected,workspace.read(selected),index)));
            return;
        }
        String min=height.token("min"),max=height.token("max");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) ImGui.textWrapped("Stored height bound has an unsupported type (read-only).");
        else {
            String minKey=DeepFluids.systemPrefix(index)+"height.min",maxKey=DeepFluids.systemPrefix(index)+"height.max";
            String storedMin=min==null ? DeepFluids.heightDefault("min",false) : min,storedMax=max==null ? DeepFluids.heightDefault("max",false) : max;
            ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.textWrapped("Finite world-Y bounds -2048 to 2048, minimum <= maximum. Missing members of an existing height object show the range class defaults; Apply writes both bounds explicitly.");
            ImGui.inputText(min==null ? "Minimum height (range class default)" : "Minimum height",minimum);drafts.update(minKey,storedMin,minimum.get());
            ImGui.inputText(max==null ? "Maximum height (range class default)" : "Maximum height",maximum);drafts.update(maxKey,storedMax,maximum.get());
            if (ImGui.button("Apply deep-fluid heights")) {
                try { workspace.replace(selected,DeepFluids.setHeight(selected,workspace.read(selected),index,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
        }
        if (ImGui.treeNode("Height generator style")) { drawStyleFields(height,NoiseStyleFields.Target.deepHeight(index));ImGui.treePop(); }
        for (String field : height.fields()) if (!List.of("min","max","style").contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void changeDeepFluid(RiverProfiles.Action action,int index,String id,String appliedKey) {
        try { commitProfileChange(DeepFluids.draftPrefix(),DeepFluids.change(selected,workspace.read(selected),action,index,id),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawStandingSurfacePools(LosslessJson hydro) {
        ImGui.textWrapped(SurfacePools.description());
        ImGui.textWrapped("Pool IDs must be unique, avoid reserved hydrology selectors and differ from river profile IDs. Rename/removal leaves policy pool selections unchanged; review those selections after editing. Placement is not previewed.");
        List<LosslessJson> pools;
        try { pools=SurfacePools.entries(hydro); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored pool list contains snippets or unsupported entries (read-only).");return; }
        if (pools==null) {
            if (ImGui.button("Enable empty surface pool list")) {
                try { commitProfileChange(SurfacePools.draftPrefix(),SurfacePools.enable(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=SurfacePools.draftPrefix()+"new.id";
        ImString newId=values.computeIfAbsent(newKey,ignored -> new ImString("",256));
        ImGui.inputText("New pool ID",newId);drafts.update(newKey,"",newId.get());
        ImGui.beginDisabled(pools.size()>=SurfacePools.maximumPools());
        if (ImGui.button("Add lava pool")) { ImGui.endDisabled();changeSurfacePool(RiverProfiles.Action.ADD,-1,newId.get(),newKey);return; }
        ImGui.endDisabled();
        for (int index=0;index<pools.size();index++) {
            if (!ImGui.treeNode("Standing pool "+index)) continue;
            var pool=pools.get(index);int poolIndex=index;
            String copyKey=SurfacePools.poolPrefix(index)+"copyId";
            ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",256));
            ImGui.inputText("Duplicate pool as ID",copyId);drafts.update(copyKey,"",copyId.get());
            RiverProfiles.Action action=null;
            ImGui.beginDisabled(pools.size()>=SurfacePools.maximumPools());
            if (ImGui.button("Duplicate pool")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.endDisabled();ImGui.sameLine();
            if (ImGui.button("Remove pool")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);
            if (ImGui.button("Move pool up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==pools.size()-1);
            if (ImGui.button("Move pool down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeSurfacePool(action,index,copyId.get(),action==RiverProfiles.Action.DUPLICATE ? copyKey : null);return; }
            try {
                String stored=pool.token("id")==null ? SurfacePools.defaultId() : pool.stringToken("id"),key=SurfacePools.poolPrefix(index)+"id";
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(256,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(pool.token("id")==null ? "Pool ID (missing; class default shown)" : "Pool ID",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply pool ID")) commitRiverOverride(key,() -> workspace.replace(selected,SurfacePools.setId(selected,workspace.read(selected),poolIndex,input.get())));
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored pool ID is not a string (read-only)."); }
            for (String field : SurfacePools.SCALARS) {
                ImGui.pushID(field);ImGui.textWrapped(SurfacePools.help(field));
                String token=pool.token(field),key=SurfacePools.poolPrefix(index)+field;
                if (token==null) {
                    if (ImGui.button("Enable "+field+" ("+SurfacePools.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,SurfacePools.enableScalar(selected,workspace.read(selected),poolIndex,field)));
                } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored "+field+" has an unsupported type (read-only).");
                else {
                    ImGui.textWrapped(SurfacePools.constraints(field));
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                    ImGui.inputText(field,input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,SurfacePools.setScalar(selected,workspace.read(selected),poolIndex,field,input.get())));
                }
                ImGui.popID();
            }
            drawSurfacePoolRadii(pool,index);
            try {
                String current=pool.token("biome")==null ? "" : pool.stringToken("biome");
                if (ImGui.beginCombo("Pool bed/rim biome",current.isEmpty() ? "Keep surrounding biome" : current.length()>128 ? "Stored biome" : current)) {
                    if (ImGui.selectable("Keep surrounding biome",current.isEmpty())) applyRoutingExtra(() -> workspace.replace(selected,SurfacePools.setBiome(selected,workspace.read(selected),poolIndex,"",workspace)));
                    for (String key : ResourceTypes.referenceKeys(workspace,"biomes")) if (ImGui.selectable(key,key.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SurfacePools.setBiome(selected,workspace.read(selected),poolIndex,key,workspace)));
                    ImGui.endCombo();
                }
                if (!current.isEmpty() && !ResourceTypes.referenceKeys(workspace,"biomes").contains(current)) ImGui.textWrapped("Stored biome is not an exact in-pack resource; it stays unchanged until you choose a replacement.");
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored biome is not a string (read-only)."); }
            if (ImGui.treeNode("Pool fluid palette")) { drawHydrologyFluids(pool,new HydrologyFluidPalettes.Target(true,index));ImGui.treePop(); }
            for (String field : pool.fields()) if (!SurfacePools.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void drawSurfacePoolRadii(LosslessJson pool,int index) {
        String min=pool.token("minimumRadius"),max=pool.token("maximumRadius");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored pool radius has an unsupported type (read-only).");return; }
        String minKey=SurfacePools.poolPrefix(index)+"minimumRadius",maxKey=SurfacePools.poolPrefix(index)+"maximumRadius";
        String storedMin=min==null ? SurfacePools.defaultToken("minimumRadius") : min,storedMax=max==null ? SurfacePools.defaultToken("maximumRadius") : max;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.textWrapped("Pool radii: exact integers 2-16; minimum must not exceed maximum. Apply writes the pair together.");
        ImGui.inputText(min==null ? "Minimum pool radius (Iris default)" : "Minimum pool radius",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum pool radius (Iris default)" : "Maximum pool radius",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply pool radii")) {
            try { workspace.replace(selected,SurfacePools.setRadii(selected,workspace.read(selected),index,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    private void changeSurfacePool(RiverProfiles.Action action,int index,String id,String appliedKey) {
        try { commitProfileChange(SurfacePools.draftPrefix(),SurfacePools.change(selected,workspace.read(selected),action,index,id),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawHydrologyRivers(LosslessJson hydro) {
        ImGui.textWrapped(HydrologyFields.description("rivers"));
        LosslessJson rivers;
        try { rivers=HydrologyFields.rivers(hydro); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored river configuration has an unsupported type (read-only)."); return; }
        if (rivers==null) {
            if (ImGui.button("Create river configuration (disabled)")) {
                try { workspace.replace(selected,HydrologyFields.enableRivers(selected,workspace.read(selected))); error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String token=rivers.token("enabled"), effective=token==null ? "false" : token;
        ImGui.textWrapped(HydrologyFields.enabledDescription());
        if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored enabled value is not boolean (read-only).");
        else if (ImGui.checkbox(token==null ? "enabled (Iris default)" : "enabled",effective.equals("true"))) {
            try { workspace.replace(selected,HydrologyFields.setEnabled(selected,workspace.read(selected),!effective.equals("true"))); error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
        if (ImGui.treeNode("River profiles")) { drawRiverProfiles(rivers); ImGui.treePop(); }
        if (ImGui.treeNode("Drainage routing settings")) { drawHydrologyRouting(rivers); ImGui.treePop(); }
        if (ImGui.treeNode("River geometry")) { drawRiverGeometry(rivers); ImGui.treePop(); }
        if (ImGui.treeNode("Surface river settings")) { drawSurfaceRiver(rivers); ImGui.treePop(); }
        if (ImGui.treeNode("Underground river settings")) { drawUndergroundRiver(rivers); ImGui.treePop(); }
        if (ImGui.treeNode("Coastal and inland grotto settings")) { drawGrottoSettings(rivers); ImGui.treePop(); }
        for (String field : rivers.fields()) if (!HydrologyFields.RIVER_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRiverProfiles(LosslessJson rivers) {
        ImGui.textWrapped(RiverProfiles.description());
        ImGui.textWrapped("IDs use 1-64 lowercase letters, digits, _ or - and start with a letter or digit. Renaming/removing profiles leaves policy ID selections unchanged; review those selections after editing. Creating profiles leaves river planning unchanged.");
        List<LosslessJson> profiles;
        try { profiles=RiverProfiles.entries(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored profiles contain snippets or unsupported entries (read-only)."); return; }
        if (profiles==null) {
            ImGui.textWrapped("Uses the implicit default profile with water. Enable writes an explicit default ID and water palette.");
            if (ImGui.button("Enable default river profile")) {
                try { commitProfileChange(RiverProfiles.draftPrefix(),RiverProfiles.enable(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (profiles.isEmpty()) ImGui.textWrapped("The explicit profile list is empty. Add a profile to repair the source-required list.");
        String newKey=RiverProfiles.draftPrefix()+"new.id";
        ImString newId=values.computeIfAbsent(newKey,ignored -> new ImString("",256));
        ImGui.inputText("New profile ID",newId);drafts.update(newKey,"",newId.get());
        ImGui.beginDisabled(profiles.size()>=RiverProfiles.maximumProfiles());
        if (ImGui.button("Add water profile")) { ImGui.endDisabled();changeProfile(RiverProfiles.Action.ADD,-1,newId.get(),newKey);return; }
        ImGui.endDisabled();
        for (int index=0;index<profiles.size();index++) {
            if (!ImGui.treeNode("Profile "+index)) continue;
            int profileIndex=index;var profile=profiles.get(index);
            String copyKey=RiverProfiles.profilePrefix(index)+"copyId";
            ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",256));
            ImGui.inputText("Duplicate as ID",copyId);drafts.update(copyKey,"",copyId.get());
            RiverProfiles.Action action=null;
            ImGui.beginDisabled(profiles.size()>=RiverProfiles.maximumProfiles());
            if (ImGui.button("Duplicate profile")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(profiles.size()<=RiverProfiles.minimumProfiles());
            if (ImGui.button("Remove profile")) action=RiverProfiles.Action.REMOVE;
            ImGui.endDisabled();ImGui.beginDisabled(index==0);
            if (ImGui.button("Move profile up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==profiles.size()-1);
            if (ImGui.button("Move profile down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeProfile(action,index,copyId.get(),action==RiverProfiles.Action.DUPLICATE ? copyKey : null);return; }
            try {
                String stored=profile.token("id")==null ? RiverProfiles.defaultId() : profile.stringToken("id"),key=RiverProfiles.profilePrefix(index)+"id";
                ImString id=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(256,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(profile.token("id")==null ? "ID (missing; class default shown)" : "ID",id);drafts.update(key,stored,id.get());
                if (ImGui.button("Apply profile ID")) commitRiverOverride(key,() -> workspace.replace(selected,RiverProfiles.setId(selected,workspace.read(selected),profileIndex,id.get())));
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored profile ID is not a string (read-only)."); }
            if (ImGui.treeNode("Fluid palette")) { drawHydrologyFluids(profile,new HydrologyFluidPalettes.Target(false,index));ImGui.treePop(); }
            for (String field : profile.fields()) if (!RiverProfiles.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void drawHydrologyFluids(LosslessJson profile,HydrologyFluidPalettes.Target target) {
        LosslessJson palette;
        try { palette=RiverProfiles.palette(profile); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored fluid palette is a snippet or unsupported value (read-only).");return; }
        if (palette==null) {
            ImGui.textWrapped("Absent palette inherits "+target.defaultFluid()+"; the native pack validator requires an explicit palette.");
            if (ImGui.button("Create "+target.defaultFluid()+" palette")) {
                try { commitProfileChange(target.fluidPrefix(),target.createPalette(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (ImGui.treeNode("Fluid palette noise")) { drawFluidPaletteNoise(palette,target);ImGui.treePop(); }
        for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
        ImGui.textWrapped("1-64 weighted fluid entries; weights 1-64. Minecraft entries permit water, lava and bubble_column. Custom names pass the native name filter but still require runtime fluid/registry validation. Advanced block data stays read-only.");
        List<LosslessJson> fluids;
        try { fluids=RiverProfiles.fluids(palette); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored fluid list contains snippets or unsupported entries (read-only).");return; }
        if (fluids==null) {
            ImGui.textWrapped("This explicit palette is missing entries. Its palette class default is STONE; Initialize writes "+target.defaultFluid()+" explicitly.");
            if (ImGui.button("Initialize "+target.defaultFluid()+" entries")) {
                try { commitProfileChange(target.fluidPrefix(),target.initialize(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=target.fluidPrefix()+"new.block";
        ImString newFluid=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));
        ImGui.inputText("New fluid block",newFluid);drafts.update(newKey,"",newFluid.get());
        ImGui.beginDisabled(fluids.size()>=RiverProfiles.maximumFluids());
        if (ImGui.button("Add fluid")) { ImGui.endDisabled();changeHydrologyFluid(target,RiverProfiles.Action.ADD,-1,newFluid.get(),newKey);return; }
        ImGui.endDisabled();
        if (fluids.isEmpty()) ImGui.textWrapped("The fluid list is empty. Add a fluid to repair it.");
        for (int index=0;index<fluids.size();index++) {
            if (!ImGui.treeNode("Fluid "+index)) continue;
            int fluidIndex=index;var entry=fluids.get(index);RiverProfiles.Action action=null;
            ImGui.beginDisabled(fluids.size()>=RiverProfiles.maximumFluids());
            if (ImGui.button("Duplicate fluid")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(fluids.size()<=1);
            if (ImGui.button("Remove fluid")) action=RiverProfiles.Action.REMOVE;
            ImGui.endDisabled();ImGui.beginDisabled(index==0);
            if (ImGui.button("Move fluid up")) action=RiverProfiles.Action.UP;
            ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==fluids.size()-1);
            if (ImGui.button("Move fluid down")) action=RiverProfiles.Action.DOWN;
            ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeHydrologyFluid(target,action,index,null,null);return; }
            try {
                String block=entry.token("block")==null ? "" : entry.stringToken("block"),key=target.fluidPrefix()+index+".block";
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(block,Math.max(4096,block.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(entry.token("block")==null ? "Required fluid (missing)" : "Fluid block",input);drafts.update(key,block,input.get());
                if (ImGui.button("Apply fluid")) commitRiverOverride(key,() -> workspace.replace(selected,target.setFluid(selected,workspace.read(selected),fluidIndex,input.get())));
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored fluid block is not a string (read-only)."); }
            String weight=entry.token("weight"),weightKey=target.fluidPrefix()+index+".weight";
            if (weight==null) {
                if (ImGui.button("Enable fluid weight (1)")) applyRoutingExtra(() -> workspace.replace(selected,target.enableWeight(selected,workspace.read(selected),fluidIndex)));
            } else if (!LosslessJson.numericToken(weight)) ImGui.textWrapped("Stored fluid weight has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(weightKey,ignored -> new ImString(weight,Math.max(128,weight.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Weight (1-64)",input);drafts.update(weightKey,weight,input.get());
                if (ImGui.button("Apply weight")) commitRiverOverride(weightKey,() -> workspace.replace(selected,target.setWeight(selected,workspace.read(selected),fluidIndex,input.get())));
            }
            for (String field : entry.fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void drawFluidPaletteNoise(LosslessJson palette,HydrologyFluidPalettes.Target target) {
        String token=palette.token("zoom"),key=target.noisePrefix()+"zoom";
        ImGui.textWrapped(RiverPaletteFields.zoomConstraints());
        if (token==null) {
            if (ImGui.button("Enable fluid palette zoom (5)")) applyRoutingExtra(() -> workspace.replace(selected,target.enableZoom(selected,workspace.read(selected))));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored palette zoom has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Fluid palette zoom",input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply fluid palette zoom")) commitRiverOverride(key,() -> workspace.replace(selected,target.setZoom(selected,workspace.read(selected),input.get())));
        }
        if (ImGui.treeNode("Fluid generator style")) { drawStyleFields(palette,target.styleTarget());ImGui.treePop(); }
    }
    private void changeProfile(RiverProfiles.Action action,int index,String id,String appliedKey) {
        try { commitProfileChange(RiverProfiles.draftPrefix(),RiverProfiles.change(selected,workspace.read(selected),action,index,id),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void changeHydrologyFluid(HydrologyFluidPalettes.Target target,RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(target.fluidPrefix(),target.change(selected,workspace.read(selected),action,index,value),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void commitProfileChange(String prefix,byte[] next,String appliedKey) throws java.io.IOException {
        if (drafts.transitionUnder(prefix,() -> {
            boolean other=values.keySet().stream().anyMatch(key -> key.startsWith(prefix) && !key.equals(appliedKey) && drafts.contains(key));
            return !other || JOptionPane.showConfirmDialog(null,"This changes list positions and discards other unapplied text in this list. Continue?","Unapplied text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION;
        },() -> workspace.replace(selected,next))) { values.keySet().removeIf(key -> key.startsWith(prefix));error=null; }
    }
    private void drawGrottoSettings(LosslessJson rivers) {
        ImGui.textWrapped(GrottoFields.description("grottos"));
        LosslessJson grottos;
        try { grottos=GrottoFields.grottos(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored grotto settings have an unsupported type (read-only)."); return; }
        if (grottos==null) {
            if (ImGui.button("Create grotto settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.enableGrottos(selected,workspace.read(selected))));
            return;
        }
        for (String section : GrottoFields.SECTIONS) {
            if (ImGui.treeNode(section+" grotto")) { drawGrottoSection(grottos,section); ImGui.treePop(); }
        }
        for (String field : grottos.fields()) if (!GrottoFields.SECTIONS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawGrottoSection(LosslessJson grottos,String section) {
        ImGui.textWrapped(GrottoFields.description(section));
        LosslessJson object;
        try { object=GrottoFields.section(grottos,section); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored grotto section has an unsupported type (read-only)."); return; }
        if (object==null) {
            if (ImGui.button("Create "+section+" (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.enableSection(selected,workspace.read(selected),section)));
            return;
        }
        for (String field : GrottoFields.fields(section)) {
            String token=object.token(field),key=GrottoFields.draftPrefix(section)+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(GrottoFields.help(section,field));
            boolean pairedRadius=false;
            if (section.equals("coastal") && field.equals("horizontalRadius")) {
                try { pairedRadius=SeaCaveFields.object(object)!=null; }
                catch (IllegalArgumentException mismatch) { /* Unsupported sea-cave data remains read-only. */ }
            }
            if (pairedRadius) drawSeaCaveSpacingPair(object);
            else if (GrottoFields.enumField(section,field)) {
                if (token==null) {
                    if (ImGui.button("Enable ("+GrottoFields.defaultToken(section,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.enableScalar(selected,workspace.read(selected),section,field)));
                } else {
                    String current=null;
                    try { current=object.stringToken(field); }
                    catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored choice is not a string (read-only)."); }
                    if (current!=null) {
                        if (!GrottoFields.choices().contains(current)) ImGui.textWrapped("Unrecognized stored choice. Choose a listed option to replace it.");
                        if (ImGui.beginCombo("Pool level",current.length()>128 ? "Unrecognized choice" : current)) {
                            for (String choice : GrottoFields.choices()) {
                                if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.setEnum(selected,workspace.read(selected),section,field,choice)));
                                if (ImGui.isItemHovered()) ImGui.setTooltip(GrottoFields.choiceHelp(choice));
                            }
                            ImGui.endCombo();
                        }
                    }
                }
            } else if (GrottoFields.booleanField(section,field)) {
                String effective=token==null ? GrottoFields.defaultToken(section,field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.setBoolean(selected,workspace.read(selected),section,field,!effective.equals("true"))));
            } else if (!GrottoFields.nullable(section,field) && token==null) {
                if (ImGui.button("Enable ("+GrottoFields.defaultToken(section,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,GrottoFields.enableScalar(selected,workspace.read(selected),section,field)));
            } else {
                boolean fallback=GrottoFields.nullable(section,field) && (token==null || token.equals("null"));
                if (!fallback && !LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
                else {
                    ImGui.textWrapped(GrottoFields.constraints(section,field));
                    if (fallback) ImGui.textWrapped(GrottoFields.fallbackHelp());
                    String stored=fallback ? "" : token;
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                    ImGui.inputText("Value",input); drafts.update(key,stored,input.get());
                    if (ImGui.button(GrottoFields.nullable(section,field) ? "Set override" : "Apply")) {
                        try { workspace.replace(selected,GrottoFields.setText(selected,workspace.read(selected),section,field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                        catch (Exception failure) { error=failure.getMessage(); }
                    }
                    if (GrottoFields.nullable(section,field)) {
                        ImGui.sameLine();
                        if (ImGui.button("Use Iris fallback")) {
                            try { workspace.replace(selected,GrottoFields.reset(selected,workspace.read(selected),section,field)); drafts.applied(key); values.remove(key); error=null; }
                            catch (Exception failure) { error=failure.getMessage(); }
                        }
                    }
                }
            }
            ImGui.popID();
        }
        if (section.equals("coastal") && ImGui.treeNode("Sea caves")) { drawSeaCaves(object); ImGui.treePop(); }
        for (String field : object.fields()) if (!GrottoFields.fields(section).contains(field)
                && !(section.equals("coastal") && GrottoFields.COASTAL_OBJECT_FIELDS.contains(field))) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawSeaCaveSpacingPair(LosslessJson coastal) {
        var caves=SeaCaveFields.object(coastal);
        String radius=coastal.token("horizontalRadius"),spacing=caves.token("minimumSpacing");
        if (radius!=null && !LosslessJson.numericToken(radius) || spacing!=null && !LosslessJson.numericToken(spacing)) {
            ImGui.textWrapped("Stored radius or spacing has an unsupported type (read-only)."); return;
        }
        String radiusKey=GrottoFields.draftPrefix("coastal")+"horizontalRadius",spacingKey=SeaCaveFields.draftPrefix()+"minimumSpacing";
        String storedRadius=radius==null ? GrottoFields.defaultToken("coastal","horizontalRadius") : radius,storedSpacing=spacing==null ? SeaCaveFields.defaultToken("minimumSpacing") : spacing;
        ImString radiusInput=values.computeIfAbsent(radiusKey,ignored -> new ImString(storedRadius,Math.max(128,storedRadius.getBytes(StandardCharsets.UTF_8).length+1)));
        ImString spacingInput=values.computeIfAbsent(spacingKey,ignored -> new ImString(storedSpacing,Math.max(128,storedSpacing.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.textWrapped("Sea-cave spacing must be at least twice the coastal radius. Apply both values together.");
        ImGui.inputText(radius==null ? "Coastal radius (Iris default)" : "Coastal radius",radiusInput); drafts.update(radiusKey,storedRadius,radiusInput.get());
        ImGui.inputText(spacing==null ? "Sea-cave spacing (Iris default)" : "Sea-cave spacing",spacingInput); drafts.update(spacingKey,storedSpacing,spacingInput.get());
        if (ImGui.button("Apply radius and spacing")) {
            try {
                workspace.replace(selected,SeaCaveFields.setSpacingAndRadius(selected,workspace.read(selected),radiusInput.get(),spacingInput.get()));
                drafts.applied(radiusKey); drafts.applied(spacingKey); values.remove(radiusKey); values.remove(spacingKey); error=null;
            } catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    private void drawSeaCaves(LosslessJson coastal) {
        ImGui.textWrapped(SeaCaveFields.description());
        ImGui.textWrapped("Sea caves also require the coastal grotto to be enabled. These controls leave its enable flag unchanged.");
        LosslessJson caves;
        try { caves=SeaCaveFields.object(coastal); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored sea-cave settings have an unsupported type (read-only)."); return; }
        if (caves==null) {
            String key=SeaCaveFields.creationDraftKey(),stored=SeaCaveFields.defaultToken("minimumSpacing");
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,128));
            ImGui.textWrapped("Initial spacing must be at least twice the stored coastal radius. Other sea-cave settings keep their Iris defaults.");
            try { ImGui.text("Required spacing here: "+(SeaCaveFields.coastalRadius(coastal)*2)); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped(mismatch.getMessage()); }
            ImGui.inputText("Initial minimum spacing",input); drafts.update(key,stored,input.get());
            if (ImGui.button("Create sea-cave settings")) {
                try { workspace.replace(selected,SeaCaveFields.enableObject(selected,workspace.read(selected),input.get())); drafts.applied(key); values.remove(key); error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        for (String field : SeaCaveFields.EDITABLE) {
            String token=caves.token(field),key=SeaCaveFields.draftPrefix()+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(SeaCaveFields.help(field));
            if (field.equals("minimumSpacing")) ImGui.textWrapped("Edit minimum spacing with coastal horizontalRadius above.");
            else if (SeaCaveFields.booleanField(field)) {
                String effective=token==null ? SeaCaveFields.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,SeaCaveFields.setEnabled(selected,workspace.read(selected),!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+SeaCaveFields.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,SeaCaveFields.enable(selected,workspace.read(selected),field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(SeaCaveFields.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,SeaCaveFields.setText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        for (String field : caves.fields()) if (!SeaCaveFields.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawUndergroundRiver(LosslessJson rivers) {
        ImGui.textWrapped(UndergroundRiverFields.description("underground"));
        LosslessJson underground;
        try { underground=UndergroundRiverFields.underground(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored underground settings have an unsupported type (read-only)."); return; }
        if (underground==null) {
            ImGui.textWrapped("Creating settings leaves river planning unchanged. The underground source budget keeps its enabled Iris default.");
            if (ImGui.button("Create underground settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,UndergroundRiverFields.enableUnderground(selected,workspace.read(selected))));
            return;
        }
        drawUndergroundScalars(underground,false);
        if (ImGui.treeNode("Underground sources")) {
            ImGui.textWrapped(UndergroundRiverFields.description("sources"));
            try {
                var sources=UndergroundRiverFields.sources(underground);
                if (sources==null) {
                    if (ImGui.button("Create source settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,UndergroundRiverFields.enableSources(selected,workspace.read(selected))));
                } else {
                    drawUndergroundScalars(sources,true);
                    for (String field : sources.fields()) if (!UndergroundRiverFields.SOURCE_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
                }
            } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored sources have an unsupported type (read-only)."); }
            ImGui.treePop();
        }
        for (String field : RiverChannelRanges.UNDERGROUND_FIELDS) {
            if (ImGui.treeNode(field+" range")) { drawChannelRange(underground,RiverChannelRanges.undergroundKey(field)); ImGui.treePop(); }
        }
        if (ImGui.treeNode("Underground bed material")) { drawRiverMaterial(rivers,"underground.bedMaterial"); ImGui.treePop(); }
        for (String field : underground.fields()) if (!UndergroundRiverFields.ROOT_FIELDS.contains(field) && !RiverMaterialFields.UNDERGROUND_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawUndergroundScalars(LosslessJson object,boolean sources) {
        for (String field : UndergroundRiverFields.fields(sources)) {
            String token=object.token(field), key=UndergroundRiverFields.draftPrefix(sources)+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(UndergroundRiverFields.help(sources,field));
            if (UndergroundRiverFields.booleanField(sources,field)) {
                String effective=token==null ? UndergroundRiverFields.defaultToken(sources,field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,UndergroundRiverFields.setBoolean(selected,workspace.read(selected),sources,field,!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+UndergroundRiverFields.defaultToken(sources,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,UndergroundRiverFields.enableScalar(selected,workspace.read(selected),sources,field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(UndergroundRiverFields.constraints(sources,field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,UndergroundRiverFields.setText(selected,workspace.read(selected),sources,field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
    }
    private void drawSurfaceRiver(LosslessJson rivers) {
        ImGui.textWrapped(SurfaceRiverFields.description("surface"));
        LosslessJson surface;
        try { surface=SurfaceRiverFields.surface(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored surface-river settings have an unsupported type (read-only)."); return; }
        if (surface==null) {
            ImGui.textWrapped("Create settings to edit the surface source budget. River planning keeps its current state.");
            if (ImGui.button("Create surface settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.enableSurface(selected,workspace.read(selected))));
            return;
        }
        drawSurfaceRiverScalars(surface,"");
        for (String section : SurfaceRiverFields.sections()) {
            if (ImGui.treeNode(section+" settings")) {
                ImGui.textWrapped(SurfaceRiverFields.description(section));
                LosslessJson object=null; boolean supported=true;
                try { object=SurfaceRiverFields.section(surface,section); }
                catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored section has an unsupported type (read-only)."); supported=false; }
                if (supported) {
                    if (object==null) {
                        if (ImGui.button("Create "+section+" (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.enableSection(selected,workspace.read(selected),section)));
                    } else {
                        drawSurfaceRiverScalars(object,section);
                        if (section.equals("banks") && ImGui.treeNode("Excavation limits")) { drawSurfaceExcavation(surface); ImGui.treePop(); }
                        if (section.equals("banks")) for (String material : RiverMaterialFields.BANK_FIELDS) {
                            if (ImGui.treeNode(material)) { drawRiverMaterial(rivers,"surface.banks."+material); ImGui.treePop(); }
                        }
                        if (section.equals("bed")) for (String material : RiverMaterialFields.BED_FIELDS) {
                            if (ImGui.treeNode(material)) { drawRiverMaterial(rivers,"surface.bed."+material); ImGui.treePop(); }
                        }
                        if (section.equals("channel")) for (String field : RiverChannelRanges.OBJECT_FIELDS) {
                            if (ImGui.treeNode(field+" range")) { drawChannelRange(object,field); ImGui.treePop(); }
                        }
                        for (String field : object.fields()) if (!SurfaceRiverFields.fields(section).contains(field)
                                && !(section.equals("banks") && SurfaceRiverFields.EXCAVATION_OBJECT_FIELDS.contains(field))
                                && !(section.equals("banks") && RiverMaterialFields.BANK_FIELDS.contains(field))
                                && !(section.equals("bed") && RiverMaterialFields.BED_FIELDS.contains(field))
                                && !(section.equals("channel") && RiverChannelRanges.OBJECT_FIELDS.contains(field))) ImGui.textWrapped(field+" (read-only)");
                    }
                }
                ImGui.treePop();
            }
        }
        if (ImGui.treeNode("Endpoint ponds")) { drawSurfacePonds(surface); ImGui.treePop(); }
        for (String field : surface.fields()) if (!SurfaceRiverFields.ROOT_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRiverMaterial(LosslessJson rivers,String target) {
        ImGui.pushID(target);
        try {
            boolean padding=target.equals(RiverMaterialFields.PADDING);
            LosslessJson object;
            try { object=RiverMaterialFields.object(rivers,target); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored material/palette is a snippet or unsupported value (read-only)."); return; }
            if (object==null) {
                ImGui.textWrapped("Absent Iris defaults: "+RiverMaterialFields.creationDefaults(target));
                if (ImGui.button(padding ? "Create padding palette" : "Create material settings")) {
                    try {
                        if (padding) commitMaterialList(target,RiverMaterialFields.createPalette(selected,workspace.read(selected),target),null);
                        else workspace.replace(selected,RiverMaterialFields.createMaterial(selected,workspace.read(selected),target));
                        error=null;
                    } catch (Exception failure) { error=failure.getMessage(); }
                }
                return;
            }
            if (!padding) {
                String token=object.token("enabled"),effective=token==null ? RiverMaterialFields.defaultToken("enabled") : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored enabled value is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Paint material (Iris default)" : "Paint material",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,RiverMaterialFields.setEnabled(selected,workspace.read(selected),target,!effective.equals("true"))));
                drawRiverMaterialNumber(object,target,-1,"depth");
                for (String field : object.fields()) if (!RiverMaterialFields.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            }
            LosslessJson palette;
            try { palette=RiverMaterialFields.palette(object,target); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored palette is a snippet or unsupported value (read-only)."); return; }
            ImGui.textWrapped("Weighted blocks: at least one entry; weights 1-64. Advanced block data is read-only. Runtime block registry, state and solidity validation is pending.");
            if (palette==null) {
                ImGui.textWrapped("The absent palette uses STONE. Creation writes an explicit block list.");
                if (ImGui.button("Create material palette (STONE)")) {
                    try { commitMaterialList(target,RiverMaterialFields.createPalette(selected,workspace.read(selected),target),null); }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
                return;
            }
            if (ImGui.treeNode("Palette noise")) {
                drawPaletteNoise(palette,target);
                ImGui.treePop();
            }
            for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) ImGui.textWrapped("palette."+field+" (read-only)");
            List<LosslessJson> entries;
            try { entries=RiverMaterialFields.entries(palette); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Block list contains snippets or unsupported entries (read-only)."); return; }
            if (entries==null) {
                ImGui.textWrapped("Missing entries use the palette class default STONE; the native pack validator requires an explicit list.");
                if (ImGui.button("Enable block list (STONE)")) {
                    try { commitMaterialList(target,RiverMaterialFields.enableEntries(selected,workspace.read(selected),target),null); }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
                return;
            }
            String newKey=RiverMaterialFields.listDraftPrefix(target)+"new.block";
            ImString newBlock=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));
            ImGui.inputText("New Minecraft block",newBlock); drafts.update(newKey,"",newBlock.get());
            if (ImGui.button("Add block")) { changeMaterialEntry(target,RiverMaterialFields.Action.ADD,-1,newBlock.get(),newKey); return; }
            if (entries.isEmpty()) ImGui.textWrapped("This palette is empty. Add a block to repair the required list.");
            for (int index=0;index<entries.size();index++) {
                if (!ImGui.treeNode("Block "+index)) continue;
                RiverMaterialFields.Action action=null;
                if (ImGui.button("Duplicate")) action=RiverMaterialFields.Action.DUPLICATE;
                ImGui.sameLine(); ImGui.beginDisabled(entries.size()<=1);
                if (ImGui.button("Remove")) action=RiverMaterialFields.Action.REMOVE;
                ImGui.endDisabled(); ImGui.beginDisabled(index==0);
                if (ImGui.button("Move up")) action=RiverMaterialFields.Action.UP;
                ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index==entries.size()-1);
                if (ImGui.button("Move down")) action=RiverMaterialFields.Action.DOWN;
                ImGui.endDisabled();
                if (action!=null) { ImGui.treePop(); changeMaterialEntry(target,action,index,null,null); return; }
                var entry=entries.get(index); int selectedIndex=index;
                try {
                    String block=entry.token("block")==null ? "" : entry.stringToken("block");
                    String key=RiverMaterialFields.listDraftPrefix(target)+index+".block";
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(block,Math.max(4096,block.getBytes(StandardCharsets.UTF_8).length+1)));
                    ImGui.inputText(entry.token("block")==null ? "Required block (missing)" : "Block",input); drafts.update(key,block,input.get());
                    if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,RiverMaterialFields.setBlock(selected,workspace.read(selected),target,selectedIndex,input.get())));
                } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored block name is not a string (read-only)."); }
                drawRiverMaterialNumber(entry,target,index,"weight");
                for (String field : entry.fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
        } finally { ImGui.popID(); }
    }
    private void drawPaletteNoise(LosslessJson palette,String target) {
        ImGui.textWrapped(RiverPaletteFields.description("zoom"));
        ImGui.textWrapped(RiverPaletteFields.zoomConstraints());
        String token=palette.token("zoom"),key=RiverPaletteFields.draftPrefix(target)+"zoom";
        if (token==null) {
            if (ImGui.button("Enable palette zoom ("+RiverPaletteFields.zoomDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,RiverPaletteFields.enableZoom(selected,workspace.read(selected),target)));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored palette zoom has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Palette zoom",input); drafts.update(key,token,input.get());
            if (ImGui.button("Apply palette zoom")) commitRiverOverride(key,() -> workspace.replace(selected,RiverPaletteFields.setZoom(selected,workspace.read(selected),target,input.get())));
        }
        if (ImGui.treeNode("Palette generator style")) { drawStyleFields(palette,NoiseStyleFields.Target.palette(target)); ImGui.treePop(); }
    }
    private void drawRiverMaterialNumber(LosslessJson object,String target,int index,String field) {
        String token=object.token(field),key=(index<0 ? RiverMaterialFields.draftPrefix(target) : RiverMaterialFields.listDraftPrefix(target)+index+".")+field;
        ImGui.pushID(key);
        try {
            if (token==null) {
                if (ImGui.button("Enable "+field+" ("+RiverMaterialFields.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,index<0 ? RiverMaterialFields.enableDepth(selected,workspace.read(selected),target) : RiverMaterialFields.enableWeight(selected,workspace.read(selected),target,index)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped(field+" has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(field+(index<0 ? " (1-8)" : " (1-64)"),input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,index<0 ? RiverMaterialFields.setDepth(selected,workspace.read(selected),target,input.get()) : RiverMaterialFields.setWeight(selected,workspace.read(selected),target,index,input.get())));
            }
        } finally { ImGui.popID(); }
    }
    private void changeMaterialEntry(String target,RiverMaterialFields.Action action,int index,String block,String appliedKey) {
        try { commitMaterialList(target,RiverMaterialFields.change(selected,workspace.read(selected),target,action,index,block),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void commitMaterialList(String target,byte[] next,String appliedKey) throws java.io.IOException {
        String prefix=RiverMaterialFields.listDraftPrefix(target);
        if (drafts.transitionUnder(prefix,() -> {
            boolean other=values.keySet().stream().anyMatch(key -> key.startsWith(prefix) && !key.equals(appliedKey) && drafts.contains(key));
            return !other || JOptionPane.showConfirmDialog(null,"This changes palette list positions and discards other unapplied text in this palette. Continue?","Unapplied text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION;
        },() -> workspace.replace(selected,next))) {
            values.keySet().removeIf(key -> key.startsWith(prefix)); error=null;
        }
    }
    private void drawChannelRange(LosslessJson channel,String field) {
        ImGui.textWrapped(RiverChannelRanges.description(field));
        LosslessJson range;
        try { range=RiverChannelRanges.object(channel,field); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored range is a snippet or unsupported value (read-only)."); return; }
        if (range==null) {
            ImGui.textWrapped("Absent defaults: "+RiverChannelRanges.contextBound(field,"min")+" to "+RiverChannelRanges.contextBound(field,"max")
                    +", "+RiverChannelRanges.contextStyle(field)+", zoom "+RiverChannelRanges.contextZoom(field)+".");
            if (ImGui.button("Create range with these defaults")) applyRoutingExtra(() -> workspace.replace(selected,RiverChannelRanges.enable(selected,workspace.read(selected),field)));
            return;
        }
        String min=range.token("min"),max=range.token("max");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) ImGui.textWrapped("Stored bounds have unsupported types (read-only).");
        else {
            String minKey=RiverChannelRanges.draftPrefix(field)+"min",maxKey=RiverChannelRanges.draftPrefix(field)+"max";
            String storedMin=min==null ? RiverChannelRanges.classBound("min") : min,storedMax=max==null ? RiverChannelRanges.classBound("max") : max;
            ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1)));
            ImString maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.textWrapped("Finite bounds; minimum must be at most maximum. Absent members of an existing range use class defaults. Physical river limits are not validated here.");
            ImGui.inputText(min==null ? "Minimum (Iris default)" : "Minimum",minimum); drafts.update(minKey,storedMin,minimum.get());
            ImGui.inputText(max==null ? "Maximum (Iris default)" : "Maximum",maximum); drafts.update(maxKey,storedMax,maximum.get());
            if (ImGui.button("Apply bounds")) {
                try {
                    workspace.replace(selected,RiverChannelRanges.editBounds(selected,workspace.read(selected),field,minimum.get(),maximum.get()));
                    drafts.applied(minKey); drafts.applied(maxKey); values.remove(minKey); values.remove(maxKey); error=null;
                } catch (Exception failure) { error=failure.getMessage(); }
            }
        }
        if (ImGui.treeNode("Range style")) { drawStyleFields(range,RiverChannelRanges.styleTarget(field)); ImGui.treePop(); }
        for (String member : range.fields()) if (!RiverChannelRanges.RANGE_FIELDS.contains(member)) ImGui.textWrapped(member+" (read-only)");
    }
    private void drawSurfacePonds(LosslessJson surface) {
        ImGui.textWrapped(SurfacePondFields.description("ponds"));
        LosslessJson ponds;
        try { ponds=SurfacePondFields.ponds(surface); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored pond settings have an unsupported type (read-only)."); return; }
        if (ponds==null) {
            if (ImGui.button("Create pond settings (endpoint defaults)")) applyRoutingExtra(() -> workspace.replace(selected,SurfacePondFields.enableContainer(selected,workspace.read(selected))));
            return;
        }
        for (String context : SurfacePondFields.CONTEXTS) {
            if (ImGui.treeNode(context+" pond")) { drawSurfacePond(ponds,context); ImGui.treePop(); }
        }
        for (String field : ponds.fields()) if (!SurfacePondFields.CONTEXTS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawSurfacePond(LosslessJson ponds,String context) {
        ImGui.textWrapped(SurfacePondFields.description(context));
        LosslessJson pond;
        try { pond=SurfacePondFields.pond(ponds,context); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored pond has an unsupported type (read-only)."); return; }
        if (pond==null) {
            ImGui.textWrapped("Absent endpoint defaults: enabled="+SurfacePondFields.contextDefault(context,"enabled")
                    +", radius "+SurfacePondFields.contextDefault(context,"minimumRadius")+" to "+SurfacePondFields.contextDefault(context,"maximumRadius")
                    +", depth "+SurfacePondFields.contextDefault(context,"depth")+".");
            if (ImGui.button("Create "+context+" pond with these defaults")) applyRoutingExtra(() -> workspace.replace(selected,SurfacePondFields.enablePond(selected,workspace.read(selected),context)));
            return;
        }
        ImGui.textWrapped("Absent members in an existing pond use its class defaults: radius "+SurfacePondFields.defaultToken("minimumRadius")
                +" to "+SurfacePondFields.defaultToken("maximumRadius")+", depth "+SurfacePondFields.defaultToken("depth")+".");
        for (String field : SurfacePondFields.EDITABLE) {
            String token=pond.token(field), key=SurfacePondFields.draftPrefix(context)+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(SurfacePondFields.help(field));
            if (field.equals("enabled")) {
                String effective=token==null ? SurfacePondFields.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,SurfacePondFields.setEnabled(selected,workspace.read(selected),context,!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+SurfacePondFields.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,SurfacePondFields.enableScalar(selected,workspace.read(selected),context,field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(SurfacePondFields.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,SurfacePondFields.setText(selected,workspace.read(selected),context,field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        for (String field : pond.fields()) if (!SurfacePondFields.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawSurfaceExcavation(LosslessJson surface) {
        String section="banks.excavation";
        ImGui.textWrapped(SurfaceRiverFields.description(section));
        LosslessJson excavation;
        try { excavation=SurfaceRiverFields.section(surface,section); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored excavation has an unsupported type (read-only)."); return; }
        if (excavation==null) {
            if (ImGui.button("Create excavation limits (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.enableSection(selected,workspace.read(selected),section)));
            return;
        }
        drawSurfaceRiverScalars(excavation,section);
        for (String field : excavation.fields()) if (!SurfaceRiverFields.EXCAVATION_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawSurfaceRiverScalars(LosslessJson object,String section) {
        for (String field : SurfaceRiverFields.fields(section)) {
            String token=object.token(field), key=SurfaceRiverFields.draftPrefix(section)+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(SurfaceRiverFields.help(section,field));
            if (SurfaceRiverFields.enumField(section,field)) {
                if (token==null) {
                    if (ImGui.button("Enable ("+SurfaceRiverFields.defaultToken(section,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.enableScalar(selected,workspace.read(selected),section,field)));
                } else {
                    String current=null;
                    try { current=object.stringToken(field); }
                    catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored choice is not a string (read-only)."); }
                    if (current!=null) {
                        if (!SurfaceRiverFields.choices(section,field).contains(current)) ImGui.textWrapped("Unrecognized stored choice. Choose a listed option to replace it.");
                        if (ImGui.beginCombo("Choice",current.length()>128 ? "Unrecognized choice" : current)) {
                            for (String choice : SurfaceRiverFields.choices(section,field)) {
                                if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.setEnum(selected,workspace.read(selected),section,field,choice)));
                                if (ImGui.isItemHovered()) ImGui.setTooltip(SurfaceRiverFields.choiceHelp(section,field,choice));
                            }
                            ImGui.endCombo();
                        }
                    }
                }
            } else if (SurfaceRiverFields.booleanField(section,field)) {
                String effective=token==null ? SurfaceRiverFields.defaultToken(section,field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.setBoolean(selected,workspace.read(selected),section,field,!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+SurfaceRiverFields.defaultToken(section,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,SurfaceRiverFields.enableScalar(selected,workspace.read(selected),section,field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(SurfaceRiverFields.constraints(section,field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,SurfaceRiverFields.setText(selected,workspace.read(selected),section,field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
    }
    private void drawRiverGeometry(LosslessJson rivers) {
        ImGui.textWrapped(RiverGeometryFields.description("geometry"));
        LosslessJson geometry;
        try { geometry=RiverGeometryFields.geometry(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored geometry has an unsupported type (read-only)."); return; }
        if (geometry==null) {
            if (ImGui.button("Create geometry (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,RiverGeometryFields.enableGeometry(selected,workspace.read(selected))));
            return;
        }
        for (String section : RiverGeometryFields.sections()) {
            if (ImGui.treeNode(section+" settings")) { drawRiverGeometrySection(geometry,section); ImGui.treePop(); }
        }
        if (ImGui.treeNode("Bank shelves (banks3D)")) { drawRiverBanks(geometry); ImGui.treePop(); }
        for (String field : geometry.fields()) if (!RiverGeometryFields.sections().contains(field) && !RiverBankFields.OBJECT_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRiverBanks(LosslessJson geometry) {
        ImGui.textWrapped(RiverBankFields.description("banks3D"));
        LosslessJson bank;
        try { bank=RiverBankFields.object(geometry); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored bank geometry has an unsupported type (read-only)."); return; }
        if (bank==null) {
            ImGui.textWrapped("Absent bank settings use Iris defaults. Creating settings leaves river planning unchanged.");
            if (ImGui.button("Create bank settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,RiverBankFields.enableObject(selected,workspace.read(selected))));
            return;
        }
        for (String field : RiverBankFields.EDITABLE) {
            String token=bank.token(field), key=RiverBankFields.draftPrefix()+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(RiverBankFields.description(field));
            if (RiverBankFields.booleanField(field)) {
                String effective=token==null ? RiverBankFields.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Enabled (Iris default)" : "Enabled",effective.equals("true")))
                    applyRoutingExtra(() -> workspace.replace(selected,RiverBankFields.setEnabled(selected,workspace.read(selected),!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+RiverBankFields.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,RiverBankFields.enable(selected,workspace.read(selected),field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(RiverBankFields.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,RiverBankFields.setText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        if (ImGui.treeNode("Bank density style")) { drawStyleFields(bank,NoiseStyleFields.Target.bank()); ImGui.treePop(); }
        for (String field : bank.fields()) if (!RiverBankFields.EDITABLE.contains(field) && !RiverBankFields.STYLE_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRiverGeometrySection(LosslessJson geometry, String section) {
        ImGui.textWrapped(RiverGeometryFields.description(section));
        LosslessJson object;
        try { object=RiverGeometryFields.section(geometry,section); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored geometry section has an unsupported type (read-only)."); return; }
        if (object==null) {
            if (ImGui.button("Create "+section+" (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,RiverGeometryFields.enableSection(selected,workspace.read(selected),section)));
            return;
        }
        for (String field : RiverGeometryFields.fields(section)) {
            String token=object.token(field), key=RiverGeometryFields.draftPrefix(section)+field;
            boolean nullable=RiverGeometryFields.nullable(section,field), fallback=nullable && (token==null || token.equals("null"));
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(RiverGeometryFields.help(section,field));
            if (!nullable && token==null) {
                if (ImGui.button("Enable ("+RiverGeometryFields.defaultToken(section,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,RiverGeometryFields.enableScalar(selected,workspace.read(selected),section,field)));
            } else if (!fallback && !LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(RiverGeometryFields.constraints(section,field));
                if (fallback) ImGui.textWrapped("Uses the channel default; the effective value is not resolved here.");
                String stored=fallback ? "" : token;
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,stored,input.get());
                if (ImGui.button(nullable ? "Set override" : "Apply")) {
                    try { workspace.replace(selected,RiverGeometryFields.setText(selected,workspace.read(selected),section,field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
                if (nullable) {
                    ImGui.sameLine();
                    if (ImGui.button("Use channel default")) {
                        try { workspace.replace(selected,RiverGeometryFields.reset(selected,workspace.read(selected),section,field)); drafts.applied(key); values.remove(key); error=null; }
                        catch (Exception failure) { error=failure.getMessage(); }
                    }
                }
            }
            ImGui.popID();
        }
        for (String field : object.fields()) if (!RiverGeometryFields.fields(section).contains(field)) {
            ImGui.textWrapped(field+" (read-only)");
            String reason=RiverGeometryFields.readOnlyReason(section,field);
            if (!reason.isEmpty()) ImGui.textWrapped(reason);
        }
    }
    private void drawHydrologyRouting(LosslessJson rivers) {
        ImGui.textWrapped(HydrologyFields.description("routing"));
        LosslessJson route;
        try { route=HydrologyFields.routing(rivers); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored routing settings have an unsupported type (read-only)."); return; }
        if (route==null) {
            if (ImGui.button("Create routing settings (Iris defaults)")) {
                try { workspace.replace(selected,HydrologyFields.enableRouting(selected,workspace.read(selected))); error=null; }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        for (String field : HydrologyFields.ROUTING_FIELDS) {
            String token=route.token(field), key=HydrologyFields.draftPrefix()+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(HydrologyFields.routingHelp(field));
            if (HydrologyFields.routingBoolean(field)) {
                String effective=token==null ? HydrologyFields.routingDefault(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Value (Iris default)" : "Value",effective.equals("true"))) {
                    try { workspace.replace(selected,HydrologyFields.setRoutingBoolean(selected,workspace.read(selected),field,!effective.equals("true"))); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else if (token==null) {
                if (ImGui.button("Enable ("+HydrologyFields.routingDefault(field)+")")) {
                    try { workspace.replace(selected,HydrologyFields.enableScalar(selected,workspace.read(selected),field)); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value is not numeric (read-only).");
            else {
                ImGui.textWrapped(HydrologyFields.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,HydrologyFields.setRoutingText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        if (ImGui.treeNode("Regional drainage")) { drawRegionalRouting(route); ImGui.treePop(); }
        if (ImGui.treeNode("Inland outlets")) { drawInlandOutlets(route); ImGui.treePop(); }
        for (String field : route.fields()) if (!HydrologyFields.ROUTING_FIELDS.contains(field) && !RoutingExtras.OBJECT_FIELDS.contains(field) && !RoutingExtras.LIST_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRegionalRouting(LosslessJson route) {
        ImGui.textWrapped(RoutingExtras.regionalDescription());
        LosslessJson region;
        try { region=RoutingExtras.regional(route); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored regional settings have an unsupported type (read-only)."); return; }
        if (region==null) {
            if (ImGui.button("Create regional settings (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.enableRegional(selected,workspace.read(selected))));
            return;
        }
        for (String field : RoutingExtras.REGIONAL_FIELDS) {
            String token=region.token(field), key=RoutingExtras.regionalPrefix()+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(RoutingExtras.help(field));
            if (RoutingExtras.booleanField(field)) {
                String effective=token==null ? RoutingExtras.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle is not boolean (read-only).");
                else if (ImGui.checkbox(token==null ? "Value (Iris default)" : "Value",effective.equals("true"))) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.setBoolean(selected,workspace.read(selected),field,!effective.equals("true"))));
            } else if (token==null) {
                if (ImGui.button("Enable ("+RoutingExtras.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.enableScalar(selected,workspace.read(selected),field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value is not numeric (read-only).");
            else if (!RoutingExtras.options(field).isEmpty()) {
                ImGui.textWrapped(RoutingExtras.constraints(field));
                if (ImGui.beginCombo("Value",token.length()>128 ? "Stored number" : token)) {
                    for (String choice : RoutingExtras.options(field)) if (ImGui.selectable(choice,RoutingExtras.optionMatches(token,choice))) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.setText(selected,workspace.read(selected),field,choice)));
                    ImGui.endCombo();
                }
            } else {
                ImGui.textWrapped(RoutingExtras.constraints(field));
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,RoutingExtras.setText(selected,workspace.read(selected),field,input.get())); drafts.applied(key); values.remove(key); error=null; }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
        for (String field : region.fields()) if (!RoutingExtras.REGIONAL_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawInlandOutlets(LosslessJson route) {
        ImGui.textWrapped(RoutingExtras.outletsDescription());
        if (route.token("inlandOutlets")==null) {
            ImGui.textWrapped("Absent list uses the empty Iris default.");
            if (ImGui.button("Enable empty inland outlet list")) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.enableOutlets(selected,workspace.read(selected))));
            return;
        }
        List<String> entries;
        try { entries=route.stringArrayPath("inlandOutlets"); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored outlet list is not a string array (read-only)."); return; }
        for (int index=0;index<entries.size();index++) {
            int item=index; String current=entries.get(index);
            ImGui.pushID(index);
            if (!RoutingExtras.outletChoices().contains(current)) ImGui.textWrapped("Unrecognized stored kind is retained until replaced or removed.");
            if (ImGui.beginCombo("Kind",current.length()>128 ? "Long stored kind" : current)) {
                for (String choice : RoutingExtras.outletChoices()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.editOutlet(selected,workspace.read(selected),item,choice)));
                ImGui.endCombo();
            }
            boolean remove=ImGui.button("Remove"); ImGui.sameLine();
            RoutingExtras.Action action=null;
            if (ImGui.button("Duplicate")) action=RoutingExtras.Action.DUPLICATE;
            ImGui.beginDisabled(index==0); if (ImGui.button("Move up")) action=RoutingExtras.Action.UP;
            ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index==entries.size()-1);
            if (ImGui.button("Move down")) action=RoutingExtras.Action.DOWN;
            ImGui.endDisabled(); ImGui.popID();
            if (remove || action!=null) {
                RoutingExtras.Action chosen=action;
                if (remove) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.editOutlet(selected,workspace.read(selected),item,null)));
                else applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.changeOutlet(selected,workspace.read(selected),item,chosen)));
                return;
            }
        }
        for (String choice : RoutingExtras.outletChoices()) if (ImGui.button("Add "+choice)) applyRoutingExtra(() -> workspace.replace(selected,RoutingExtras.editOutlet(selected,workspace.read(selected),entries.size(),choice)));
    }
    private void applyRoutingExtra(InspectorDrafts.Change action) {
        try { action.run(); error=null; }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawRiverPolicy(LosslessJson document) {
        if (!ImGui.collapsingHeader("River policy")) return;
        ImGui.textWrapped(RiverPolicyFields.description(selected));
        LosslessJson policy;
        try { policy = RiverPolicyFields.object(selected,document); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored river policy has an unsupported type (read-only)."); return; }
        if (policy == null) {
            ImGui.textWrapped("Create an explicit policy to edit nullable overrides. Creating it writes only an empty object.");
            if (ImGui.button("Create river policy")) {
                try { workspace.replace(selected,RiverPolicyFields.enablePolicy(selected,workspace.read(selected))); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        ImGui.textWrapped("Absent and null values inherit. Effective hydrology settings and policy hierarchy are not resolved here.");
        for (String field : RiverPolicyFields.EDITABLE) {
            String token = policy.token(field), key = RiverPolicyFields.draftPrefix(ResourceTypes.base(selected))+field;
            boolean inherited = token == null || token.equals("null");
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(RiverPolicyFields.help(field));
            if (RiverPolicyFields.booleanField(field)) {
                if (!inherited && !token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored override is not boolean or null (read-only).");
                else if (ImGui.beginCombo("Value",inherited ? "Inherit" : token)) {
                    if (ImGui.selectable("Inherit",inherited)) commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.setBoolean(selected,workspace.read(selected),field,null)));
                    for (String choice : List.of("true","false")) if (ImGui.selectable(choice,choice.equals(token)))
                        commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.setBoolean(selected,workspace.read(selected),field,Boolean.valueOf(choice))));
                    ImGui.endCombo();
                }
            } else if (RiverPolicyFields.enumField(field)) {
                try {
                    String current = inherited ? null : policy.stringToken(field);
                    if (current != null && !RiverPolicyFields.choices(field).contains(current)) ImGui.textWrapped("Unrecognized stored mode. Choose a listed mode or Inherit to replace it.");
                    if (ImGui.beginCombo("Mode",current == null ? "Inherit" : current.length()>128 ? "Long stored mode" : current)) {
                        if (ImGui.selectable("Inherit",inherited)) commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.setMode(selected,workspace.read(selected),field,null)));
                        for (String choice : RiverPolicyFields.choices(field)) {
                            if (ImGui.selectable(choice,choice.equals(current))) commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.setMode(selected,workspace.read(selected),field,choice)));
                            if (ImGui.isItemHovered()) ImGui.setTooltip(RiverPolicyFields.choiceDescription(field,choice));
                        }
                        ImGui.endCombo();
                    }
                } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored override is not a string or null (read-only)."); }
            } else if (!RiverPolicyFields.numericStored(token)) ImGui.textWrapped("Stored override is not numeric or null (read-only).");
            else {
                ImGui.textWrapped(RiverPolicyFields.constraints(field));
                if (inherited) ImGui.textWrapped("Inherit: enter a value to override.");
                String stored = inherited ? "" : token;
                ImString input = values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Override",input); drafts.update(key,stored,input.get());
                if (ImGui.button("Set override")) commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.setText(selected,workspace.read(selected),field,input.get())));
                ImGui.sameLine();
                if (ImGui.button("Inherit")) commitRiverOverride(key,() -> workspace.replace(selected,RiverPolicyFields.reset(selected,workspace.read(selected),field)));
            }
            ImGui.popID();
        }
        drawRiverLists(policy,RiverBiomeLists.EDITABLE,false);
        if (ImGui.treeNode("Dimension profile/pool selections")) {
            drawRiverCatalogDimension();
            drawRiverLists(policy,RiverIdLists.EDITABLE,true);
            ImGui.treePop();
        }
        if (ImGui.treeNode("Other river settings (read-only)")) {
            for (String field : policy.fields()) if (!RiverPolicyFields.EDITABLE.contains(field) && !RiverBiomeLists.EDITABLE.contains(field) && !RiverIdLists.EDITABLE.contains(field)) ImGui.textWrapped(field);
            ImGui.treePop();
        }
    }
    private void drawRiverCatalogDimension() {
        if (DimensionFields.dimension(selected)) ImGui.textWrapped("ID catalog: " + selected);
        else if (ImGui.beginCombo("Catalog dimension",riverCatalogDimension==null ? "Choose dimension" : riverCatalogDimension)) {
            for (String dimension : RiverIdLists.dimensions(workspace)) if (ImGui.selectable(dimension,dimension.equals(riverCatalogDimension))) riverCatalogDimension=dimension;
            ImGui.endCombo();
        }
        ImGui.textWrapped("The lookup dimension supplies available ID strings. Policy inheritance remains unresolved.");
    }
    private void drawRiverLists(LosslessJson policy, List<String> fields, boolean dimensionIds) {
        for (String field : fields) {
            if (!ImGui.treeNode(field + " selection")) continue;
            ImGui.textWrapped(dimensionIds ? RiverIdLists.description(field) : RiverBiomeLists.description(field));
            RiverIdLists.Catalog catalog = dimensionIds ? riverIdCatalogs.inspect(workspace,selected,riverCatalogDimension,field) : null;
            if (catalog!=null) ImGui.textWrapped(catalog.detail());
            boolean assignable = !dimensionIds || catalog.status()==RiverIdLists.Status.READY && !catalog.ids().isEmpty();
            String token = policy.token(field), prefix = dimensionIds ? RiverIdLists.draftPrefix(ResourceTypes.base(selected),field) : RiverBiomeLists.draftPrefix(ResourceTypes.base(selected),field);
            if (token == null || token.equals("null")) {
                ImGui.textWrapped("Inherit: the effective selection is not resolved here.");
                if (ImGui.button("Override with empty selection")) setRiverListState(field,false);
                ImGui.treePop(); continue;
            }
            List<String> entries;
            try { entries = policy.stringArrayPath(field); }
            catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored selection is not a string array (read-only)."); ImGui.treePop(); continue; }
            ImGui.textWrapped(entries.isEmpty() ? dimensionIds ? RiverIdLists.emptyLabel(field) : "Explicit empty selection: clears inherited biomes."
                    : dimensionIds ? "Choose an ID from this dimension catalog. Other hydrology settings are not validated." : "Exact in-pack biome keys only; aliases and target contents are unchecked.");
            boolean inherit = ImGui.button("Inherit"); ImGui.sameLine();
            boolean clear = ImGui.button("Clear selection");
            if (inherit || clear) { ImGui.treePop(); setRiverListState(field,inherit); return; }
            boolean changed = false;
            for (int index=0;index<entries.size();index++) {
                ImGui.pushID(index);
                String current = entries.get(index), key = prefix+index;
                if (dimensionIds && catalog.status()==RiverIdLists.Status.READY && !catalog.ids().contains(current)) ImGui.textWrapped("Stored ID is outside this literal catalog; it is retained until explicitly changed.");
                if (current.length()>4096) ImGui.textWrapped("Long existing entry (read-only).");
                else {
                    ImString input = values.computeIfAbsent(key,ignored -> new ImString(current,16_384));
                    ImGui.inputText("Key",input); drafts.update(key,current,input.get());
                    riverReferencePicker(dimensionIds ? "Available IDs" : "Available biomes",input,key,current,catalog);
                    ImGui.beginDisabled(!assignable);
                    if (ImGui.button("Apply")) editRiverList(field,index,input.get(),false,key);
                    ImGui.endDisabled();
                }
                boolean remove = ImGui.button("Remove");
                RiverBiomeLists.Action action = null;
                ImGui.sameLine(); if (ImGui.button("Duplicate")) action = RiverBiomeLists.Action.DUPLICATE;
                ImGui.beginDisabled(index==0); if (ImGui.button("Move up")) action = RiverBiomeLists.Action.UP;
                ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index==entries.size()-1);
                if (ImGui.button("Move down")) action = RiverBiomeLists.Action.DOWN;
                ImGui.endDisabled(); ImGui.popID();
                if (remove || action != null) {
                    if (remove) editRiverList(field,index,null,true,null); else changeRiverList(field,index,action);
                    changed = true; break;
                }
            }
            if (!changed) {
                String key = prefix+"new";
                ImString input = values.computeIfAbsent(key,ignored -> new ImString(16_384));
                ImGui.inputText(dimensionIds ? "New ID" : "New key",input); drafts.update(key,"",input.get());
                riverReferencePicker(dimensionIds ? "New catalog ID" : "New biome",input,key,"",catalog);
                ImGui.beginDisabled(!assignable);
                if (ImGui.button("Add")) editRiverList(field,entries.size(),input.get(),true,key);
                ImGui.endDisabled();
            }
            ImGui.treePop();
        }
    }
    private void riverReferencePicker(String label, ImString input, String key, String stored, RiverIdLists.Catalog catalog) {
        if (ImGui.beginCombo(label,"Choose resource")) {
            for (String choice : catalog==null ? RiverBiomeLists.choices(workspace) : catalog.ids()) if (ImGui.selectable(choice)) { input.set(choice); drafts.update(key,stored,choice); }
            ImGui.endCombo();
        }
    }
    private void setRiverListState(String field, boolean inherit) {
        try { commitRiverList(field,RiverIdLists.EDITABLE.contains(field) ? RiverIdLists.setInherited(selected,workspace.read(selected),field,inherit) : RiverBiomeLists.setInherited(selected,workspace.read(selected),field,inherit),true,null); }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private void editRiverList(String field, int index, String key, boolean structural, String appliedKey) {
        try { commitRiverList(field,RiverIdLists.EDITABLE.contains(field) ? RiverIdLists.edit(selected,workspace.read(selected),field,index,key,workspace,riverCatalogDimension) : RiverBiomeLists.edit(selected,workspace.read(selected),field,index,key,workspace),structural,appliedKey); }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private void changeRiverList(String field, int index, RiverBiomeLists.Action action) {
        try { commitRiverList(field,RiverIdLists.EDITABLE.contains(field) ? RiverIdLists.change(selected,workspace.read(selected),field,index,action) : RiverBiomeLists.change(selected,workspace.read(selected),field,index,action),true,null); }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private void commitRiverList(String field, byte[] next, boolean structural, String appliedKey) throws java.io.IOException {
        if (!structural) { workspace.replace(selected,next); drafts.applied(appliedKey); values.remove(appliedKey); error = null; return; }
        String prefix = RiverIdLists.EDITABLE.contains(field) ? RiverIdLists.draftPrefix(ResourceTypes.base(selected),field) : RiverBiomeLists.draftPrefix(ResourceTypes.base(selected),field);
        if (drafts.transitionUnder(prefix,() -> {
            boolean other = values.keySet().stream().anyMatch(key -> key.startsWith(prefix) && !key.equals(appliedKey) && drafts.contains(key));
            return !other || JOptionPane.showConfirmDialog(null,"This changes the selection or list positions and discards other unapplied text in this selection. Continue?","Unapplied text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION;
        },() -> workspace.replace(selected,next))) {
            values.keySet().removeIf(key -> key.startsWith(prefix)); error = null;
        }
    }
    private void commitRiverOverride(String key, InspectorDrafts.Change action) {
        try { action.run(); drafts.applied(key); values.remove(key); error = null; }
        catch (Exception failure) { error = failure.getMessage(); }
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
        if (ImGui.treeNode("Density threshold")) {
            drawCaveThreshold(profile);
            ImGui.treePop();
        }
        if (ImGui.treeNode("Cave density modules")) {
            drawCaveModules(profile);
            ImGui.treePop();
        }
        for (String field:NoiseStyleFields.CAVE_FIELDS) {
            if (ImGui.treeNode(field+" settings")) {
                drawStyleFields(profile,NoiseStyleFields.Target.cave(ResourceTypes.base(selected),field));
                ImGui.treePop();
            }
        }
        if (ImGui.treeNode("Other cave settings (read-only)")) {
            for (String field:profile.fields()) if (!CaveProfileFields.EDITABLE.contains(field) && !CavePlacementFields.EDITABLE.contains(field) && !CaveThresholdFields.OBJECT_FIELDS.contains(field) && !CaveModules.LIST_FIELDS.contains(field) && !NoiseStyleFields.CAVE_FIELDS.contains(field)) ImGui.textWrapped(field);
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
        drawCaveBoundInputs(range, false);
        for (String member : range.fields()) if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) ImGui.textWrapped(member + " (read-only)");
    }
    private void drawCaveThreshold(LosslessJson profile) {
        ImGui.textWrapped(CaveThresholdFields.description());
        LosslessJson range;
        try { range = CaveThresholdFields.object(profile); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored threshold is a snippet or unsupported value (read-only)."); return; }
        if (range == null) {
            String bounds = CaveThresholdFields.defaultBound("min", true) + ".." + CaveThresholdFields.defaultBound("max", true);
            ImGui.textWrapped("Absent threshold uses " + bounds + " with " + CaveThresholdFields.defaultStyle(true) + ". Enable it explicitly before editing.");
            if (ImGui.button("Enable density threshold")) {
                try { workspace.replace(selected, CaveThresholdFields.enable(selected, workspace.read(selected))); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        drawCaveBoundInputs(range, true);
        if (ImGui.treeNode("Threshold style")) {
            drawStyleFields(range, NoiseStyleFields.Target.threshold(ResourceTypes.base(selected)));
            ImGui.treePop();
        }
        for (String member : range.fields()) if (!CaveThresholdFields.EDITABLE.contains(member)) ImGui.textWrapped(member + " (read-only)");
    }
    private void drawCaveBoundInputs(LosslessJson range, boolean threshold) {
        drawCaveBoundInputs(range, threshold, -1);
    }
    private void drawCaveBoundInputs(LosslessJson range, boolean threshold, int moduleIndex) {
        boolean module = moduleIndex >= 0;
        String context = ResourceTypes.base(selected);
        String prefix = module ? CaveModules.draftPrefix(context,moduleIndex)+"verticalRange." : threshold ? CaveThresholdFields.draftPrefix(context) : CavePlacementFields.draftPrefix(context);
        for (String member : CavePlacementFields.RANGE_MEMBERS) {
            String token = range.token(member);
            try { if (token != null) new java.math.BigDecimal(token); }
            catch (NumberFormatException mismatch) { ImGui.textWrapped("Stored " + member + " is not numeric (read-only)."); return; }
        }
        for (String member : CavePlacementFields.RANGE_MEMBERS) {
            String token = range.token(member), effective = token;
            if (effective == null) effective = module ? CaveModules.rangeDefault(member,false) : threshold ? CaveThresholdFields.defaultBound(member, false) : CavePlacementFields.rangeDefault(member, false);
            String key = prefix + member;
            String stored = effective;
            ImString input = values.computeIfAbsent(key, ignored -> new ImString(stored, Math.max(128, stored.getBytes(StandardCharsets.UTF_8).length + 1)));
            ImGui.inputText(member + (token == null ? " (Iris default)" : "") + "##" + key, input);
            drafts.update(key, effective, input.get());
        }
        ImGui.textWrapped("Apply writes both bounds, filling absent members. Use finite numbers with minimum at most maximum.");
        if (ImGui.button(module ? "Apply module bounds" : threshold ? "Apply threshold bounds" : "Apply vertical bounds")) {
            try {
                byte[] original = workspace.read(selected);
                String min = values.get(prefix + "min").get(), max = values.get(prefix + "max").get();
                workspace.replace(selected, module ? CaveModules.editRange(selected,original,moduleIndex,min,max) : threshold ? CaveThresholdFields.editRange(selected, original, min, max) : CavePlacementFields.editRange(selected, original, min, max));
                for (String member : CavePlacementFields.RANGE_MEMBERS) { drafts.applied(prefix + member); values.remove(prefix + member); }
                error = null;
            } catch (Exception failure) { error = failure.getMessage(); }
        }
    }
    private void drawCaveModules(LosslessJson profile) {
        ImGui.textWrapped(CaveModules.description());
        List<LosslessJson> modules;
        try { modules = CaveModules.entries(profile); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Module list contains snippets or unsupported entries (read-only)."); return; }
        ImGui.textWrapped("Add creates an empty module using Iris defaults. Absent and empty lists retain the empty default. Removing entries respects the source minimum of " + CaveModules.minimumEntries() + ".");
        if (ImGui.button("Add default cave module")) { changeCaveModule(CaveModules.Action.ADD,-1); return; }
        for (int index = 0; index < modules.size(); index++) {
            if (!ImGui.treeNode("Module " + index)) continue;
            CaveModules.Action action = null;
            if (ImGui.button("Duplicate")) action = CaveModules.Action.DUPLICATE;
            ImGui.sameLine(); ImGui.beginDisabled(modules.size() <= CaveModules.minimumEntries());
            if (ImGui.button("Remove")) action = CaveModules.Action.REMOVE;
            ImGui.endDisabled(); ImGui.beginDisabled(index == 0);
            if (ImGui.button("Move up")) action = CaveModules.Action.UP;
            ImGui.endDisabled(); ImGui.sameLine(); ImGui.beginDisabled(index == modules.size()-1);
            if (ImGui.button("Move down")) action = CaveModules.Action.DOWN;
            ImGui.endDisabled();
            if (action != null) { ImGui.treePop(); changeCaveModule(action,index); return; }
            var module = modules.get(index);
            drawCaveModuleScalars(module,index);
            if (ImGui.treeNode("Module vertical bounds")) { drawCaveModuleRange(module,index); ImGui.treePop(); }
            if (ImGui.treeNode("Module style")) { drawStyleFields(module,NoiseStyleFields.Target.module(ResourceTypes.base(selected),index)); ImGui.treePop(); }
            for (String field : module.fields()) if (!CaveModules.EDITABLE.contains(field) && !CaveModules.OBJECT_FIELDS.contains(field)) ImGui.textWrapped(field + " (read-only)");
            ImGui.treePop();
        }
    }
    private void drawCaveModuleScalars(LosslessJson module, int index) {
        for (String field : CaveModules.EDITABLE) {
            String token = module.token(field), key = CaveModules.draftPrefix(ResourceTypes.base(selected),index)+field;
            ImGui.pushID(key); ImGui.text(field);
            if (ImGui.isItemHovered()) ImGui.setTooltip(CaveModules.help(field));
            if (CaveModules.booleanField(field)) {
                String effective = token == null ? CaveModules.defaultToken(field) : token;
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle has an unsupported type (read-only).");
                else if (ImGui.checkbox(token == null ? "Value (Iris default)" : "Value",effective.equals("true"))) {
                    try { workspace.replace(selected,CaveModules.editBoolean(selected,workspace.read(selected),index,field,!effective.equals("true"))); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else if (token == null) {
                if (ImGui.button("Enable (" + CaveModules.defaultToken(field) + ")")) {
                    try { workspace.replace(selected,CaveModules.enableScalar(selected,workspace.read(selected),index,field)); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped(CaveModules.constraints(field));
                ImString input = values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Value",input); drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) {
                    try { workspace.replace(selected,CaveModules.editText(selected,workspace.read(selected),index,field,input.get())); drafts.applied(key); values.remove(key); error = null; }
                    catch (Exception failure) { error = failure.getMessage(); }
                }
            }
            ImGui.popID();
        }
    }
    private void drawCaveModuleRange(LosslessJson module, int index) {
        ImGui.textWrapped(CaveModules.help("verticalRange"));
        LosslessJson range;
        try { range = CaveModules.range(module); }
        catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored module range is a snippet or unsupported value (read-only)."); return; }
        if (range == null) {
            String bounds = CaveModules.rangeDefault("min",true)+".."+CaveModules.rangeDefault("max",true);
            ImGui.textWrapped("Absent module range uses " + bounds + ". Enable it explicitly before editing.");
            if (ImGui.button("Enable module bounds (" + bounds + ")")) {
                try { workspace.replace(selected,CaveModules.enableRange(selected,workspace.read(selected),index)); error = null; }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            return;
        }
        drawCaveBoundInputs(range,false,index);
        for (String member : range.fields()) if (!CavePlacementFields.RANGE_MEMBERS.contains(member)) ImGui.textWrapped(member + " (read-only)");
    }
    private void changeCaveModule(CaveModules.Action action, int index) {
        try {
            byte[] next = CaveModules.change(selected,workspace.read(selected),action,index);
            String prefix = CaveModules.draftPrefix(ResourceTypes.base(selected));
            if (drafts.transitionUnder(prefix,() -> discardArrayDrafts("caveModules",null),() -> workspace.replace(selected,next))) {
                values.keySet().removeIf(key -> key.startsWith(prefix)); error = null;
            }
        } catch (Exception failure) { error = failure.getMessage(); }
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
        if (committed) { values.clear(); riverCatalogDimension=null; }
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
                if (DimensionFields.EDITABLE.isEmpty() || BiomeFields.EDITABLE.isEmpty() || GeneratorFields.EDITABLE.isEmpty() || RegionFields.EDITABLE.isEmpty() || NoiseFields.EDITABLE.isEmpty() || NoiseStyleFields.choices().isEmpty() || BiomeGeneratorLinks.minimumEntries() != 1 || BiomeGeneratorLinks.defaultGenerator() == null || BiomeChildren.description().isEmpty() || Terrain3DFields.EDITABLE.isEmpty() || CaveProfileFields.EDITABLE.isEmpty() || CavePlacementFields.choices("defaultObjectAnchor").isEmpty() || RiverPolicyFields.choices("placement").isEmpty() || RiverPolicyFields.choices("routing").isEmpty() || RiverBiomeLists.EDITABLE.isEmpty() || RiverIdLists.EDITABLE.isEmpty() || HydrologyFields.ROUTING_FIELDS.isEmpty() || RoutingExtras.outletChoices().isEmpty() || RiverGeometryFields.GEOMETRY_FIELDS.isEmpty() || SurfaceRiverFields.SECTIONS.isEmpty() || SurfacePondFields.CONTEXTS.isEmpty() || UndergroundRiverFields.ROOT_SCALARS.isEmpty() || GrottoFields.SECTIONS.isEmpty() || SeaCaveFields.EDITABLE.isEmpty() || RiverMaterialFields.defaultToken("weight")==null || RiverPaletteFields.zoomDefault()==null || RiverProfiles.maximumProfiles()!=64 || SurfacePools.maximumPools()!=64 || DeepFluids.maximumSystems()!=64 || DimensionHeights.defaultBound("min",true)==null || DimensionPalettes.maximumWeight()!=1000 || SurfaceLayerFallbacks.choices().isEmpty() || BiomeLayers.defaultBlock()==null || BiomeDerivatives.defaultKey()==null || BiomeSlabWall.classBlock()==null || InterpolationFields.choices().isEmpty() || CompositeNoise.minimumEntries() != 1 || FieldCoverage.fields("dimension").isEmpty()) throw new IllegalStateException("Missing field policy");
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
