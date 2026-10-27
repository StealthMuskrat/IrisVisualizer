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
import imgui.type.ImBoolean;
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
    private final BlockFallbacks.AuditCache blockFallbackAudits = new BlockFallbacks.AuditCache();
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
                    drawBlockFallbacks(document);
                    drawStaticObjects(document);
                    drawDepositVariants(document,"dimensions");
                    drawDeposits(document,"dimensions");
                    drawOres(document,"dimensions");
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
        drawRootObjects(document,FloatingObjectPlacements.REGION_ROOT);
        drawDepositVariants(document,"regions");
        drawDeposits(document,"regions");
        drawOres(document,"regions");
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
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored noise number has an unsupported type (read-only).");
            else {
                ImGui.textWrapped(NoiseFields.constraints(field));
                ImString input = values.computeIfAbsent(key, ignored -> new ImString(token, Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
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
            ImGui.textWrapped(target.optionalFloating() ? "Absent or null disables this style. Explicit enablement uses the generator-style class defaults ("+NoiseStyleFields.contextDefaultLabel(target)+")." : "Absent style uses " + NoiseStyleFields.contextDefaultLabel(target) + ". Enable it explicitly before changing style settings.");
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
        drawBiomeDecorators(document);
        drawBiomeSlabWall(document);
        drawRootObjects(document,FloatingObjectPlacements.BIOME_ROOT);
        drawDepositVariants(document,"biomes");
        drawBiomeOreHosts(document);
        drawDeposits(document,"biomes");
        drawOres(document,"biomes");
        drawFloatingBiomes(document);
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
            for (String field : document.fields()) if (!BiomeFields.EDITABLE.contains(field) && !NoiseStyleFields.BIOME_FIELDS.contains(field) && !BiomeChildren.EDITABLE.contains(field) && !Terrain3DFields.OBJECT_FIELDS.contains(field) && !CaveProfileFields.OBJECT_FIELDS.contains(field) && !RiverPolicyFields.OBJECT_FIELDS.contains(field) && !SurfaceLayerFallbacks.OBJECT_FIELDS.contains(field) && !BiomeLayers.LIST_FIELDS.contains(field) && !BiomeDerivatives.EDITABLE.contains(field) && !BiomeSlabWall.OBJECT_FIELDS.contains(field) && !FloatingBiomes.LIST_FIELDS.contains(field) && !BiomeObjectPlacements.LIST_FIELDS.contains(field) && !OreGenerators.LIST_FIELDS.contains(field) && !DepositGenerators.LIST_FIELDS.contains(field) && !DepositVariants.LIST_FIELDS.contains(field) && !BiomeOreHosts.LIST_FIELDS.contains(field) && !BiomeDecorators.LIST_FIELDS.contains(field) && !field.equals("generators")) ImGui.textWrapped(field);
    }
    private void drawFloatingBiomes(LosslessJson document) {
        if (!ImGui.collapsingHeader("Floating child biomes")) return;
        ImGui.textWrapped(FloatingBiomes.description());
        ImGui.textWrapped("This editor authors applied settings without sampling islands. Object placement supports exact asset pools and bounded native settings without sampling geometry. Edge variation is clamped by Iris according to edge width. Height fields named AboveSurface specify absolute world Y.");
        List<LosslessJson> entries;
        try { entries=FloatingBiomes.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored floating list contains snippets or unsupported entries (read-only).");return; }
        if (entries==null) {
            ImGui.textWrapped("Absent: no floating child entries.");
            if (ImGui.button("Enable empty floating list (Iris default)")) {
                try { commitProfileChange(FloatingBiomes.prefix(),FloatingBiomes.enable(selected,workspace.read(selected)),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (ImGui.button("Add default floating entry (Self)")) { changeFloatingBiome(RiverProfiles.Action.ADD,-1);return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Floating entry "+index)) continue;
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate entry")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove entry")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move entry up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Move entry down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeFloatingBiome(action,index);return; }
            int entryIndex=index;var entry=entries.get(index);drawFloatingBiomeTarget(entry,index);
            drawFloatingPair(entry,index,"minHeightAboveSurface","maxHeightAboveSurface",false);
            drawFloatingPair(entry,index,"bottomDepthMin","bottomDepthMax",false);
            drawFloatingPair(entry,index,"minAbsoluteY","maxAbsoluteY",true);
            for (String field : FloatingBiomes.SCALARS) drawFloatingScalar(entry,index,field);
            for (String field : FloatingBiomes.TOGGLES) {
                String token=entry.token(field),effective=token==null ? FloatingBiomes.defaultToken(field) : token;ImGui.pushID(field);ImGui.textWrapped(FloatingBiomes.help(field));
                if (!effective.equals("true") && !effective.equals("false")) ImGui.textWrapped("Stored toggle has an unsupported type (read-only).");
                else if (ImGui.checkbox(field+(token==null ? " (Iris default)" : ""),effective.equals("true"))) applyRoutingExtra(() -> workspace.replace(selected,FloatingBiomes.setToggle(selected,workspace.read(selected),entryIndex,field,!effective.equals("true"))));
                ImGui.popID();
            }
            for (String field : FloatingBiomes.ENUMS) drawFloatingMode(entry,index,field);
            drawFloatingLiteral(entry,index,"carving");drawFloatingLiteral(entry,index,"fluidBlock");drawFloatingFluidHeight(entry,index);
            drawFloatingNoise(entry,index);drawFloatingBottomPalette(entry,index);drawFloatingObjects(entry,index);
            if (ImGui.treeNode("Other floating data (read-only)")) {
                for (String field : FloatingBiomes.readOnlyFields(entry)) ImGui.textWrapped(field);
                for (String path : FloatingNoiseStyles.readOnlyPaths(entry,index)) ImGui.textWrapped(path);
                ImGui.treePop();
            }
            ImGui.treePop();
        }
    }
    private void drawBiomeDecorators(LosslessJson document) {
        if (!ImGui.collapsingHeader("Biome decorators")) return;ImGui.pushID("biomeDecorators");
        ImGui.textWrapped("Decoration entries are bucketed by part. Native chance gates select eligible entries, then reservoir sampling picks one. List order is preserved. Sampling, host compatibility, plants, fluids and effective placement are not evaluated; previews remain unavailable.");
        List<LosslessJson> entries;try { entries=BiomeDecorators.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored decorator list is null, mixed or unsupported (read-only).");ImGui.popID();return; }
        if (entries==null) { if (ImGui.button("Initialize empty decorator list")) { try { commitProfileChange(BiomeDecorators.listPrefix(),BiomeDecorators.enable(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID();return; }
        String newKey=BiomeDecorators.listPrefix()+"new.block",initial=BiomeDecorators.defaultBlock();ImString material=values.computeIfAbsent(newKey,ignored -> new ImString(initial,4096));ImGui.inputText("Material for new decorator",material);drafts.update(newKey,initial,material.get());
        if (ImGui.button("Add decorator with material")) { try { commitProfileChange(BiomeDecorators.listPrefix(),BiomeDecorators.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,material.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Decorator "+index)) continue;RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate decorator")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();if (ImGui.button("Remove decorator")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Decorator up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Decorator down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();try { commitProfileChange(BiomeDecorators.listPrefix(),BiomeDecorators.change(selected,workspace.read(selected),action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            var context=new BiomeDecorators.Context(index);var decorator=entries.get(index);
            try { String stored=decorator.stringToken("partOf"),part=stored==null ? BiomeDecorators.defaultToken("partOf") : stored;if (ImGui.beginCombo("Decoration part",part)) { for (String choice : BiomeDecorators.parts()) if (ImGui.selectable(choice,choice.equals(part))) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.setPart(selected,workspace.read(selected),context,choice)));ImGui.endCombo(); } }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored decoration part has an unsupported type (read-only)."); }
            for (String field : BiomeDecorators.FLAGS) {
                String token=decorator.token(field);if (token==null) { if (ImGui.button("Enable "+field+"="+BiomeDecorators.defaultToken(field))) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.enableScalar(selected,workspace.read(selected),context,field))); }
                else if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped(field+": unsupported type (read-only)");
                else { boolean current=token.equals("true");if (ImGui.checkbox(field,current)) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.setFlag(selected,workspace.read(selected),context,field,!current))); }
                if (ImGui.isItemHovered()) ImGui.setTooltip(BiomeDecorators.help(field));
            }
            for (String field : BiomeDecorators.SCALARS) drawDecoratorScalar(decorator,context,field);
            drawDecoratorPair(decorator,context,false);drawDecoratorPair(decorator,context,true);drawDecoratorForce(decorator,context);
            for (String field : BiomeDecorators.BLOCK_LISTS) if (ImGui.treeNode(field)) { drawDecoratorBlocks(decorator,context,field);ImGui.treePop(); }
            for (String field : BiomeDecorators.STYLES) if (ImGui.treeNode(field)) {
                var target=context.styleTarget(field);drawStyleFields(decorator,target);
                try { var style=NoiseStyleFields.object(decorator,target);if (style!=null) for (String child : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(child)) ImGui.textWrapped(child+" (read-only)"); } catch (IllegalArgumentException unsupported) { }
                ImGui.treePop();
            }
            for (String field : decorator.fields()) if (!BiomeDecorators.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");ImGui.treePop();
        }
        ImGui.popID();
    }
    private void drawDecoratorScalar(LosslessJson d,BiomeDecorators.Context context,String field) {
        ImGui.pushID(field);String token=d.token(field),key=context.prefix()+field;ImGui.textWrapped(BiomeDecorators.help(field));
        if (token==null) { if (ImGui.button("Enable "+field+"="+BiomeDecorators.defaultToken(field))) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.enableScalar(selected,workspace.read(selected),context,field))); }
        else if (!LosslessJson.numericToken(token)) ImGui.textWrapped(field+": unsupported type (read-only)");
        else { ImGui.textWrapped(BiomeDecorators.constraints(field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setScalar(selected,workspace.read(selected),context,field,input.get()))); }
        ImGui.popID();
    }
    private void drawDecoratorPair(LosslessJson d,BiomeDecorators.Context context,boolean slope) {
        String object=slope ? "slopeCondition" : "stack";if (!ImGui.treeNode(object)) return;LosslessJson container=d;
        if (slope) {
            try { container=BiomeLayers.slope(d); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored slope is null, a snippet or unsupported (read-only).");ImGui.treePop();return; }
            if (container==null) { if (ImGui.button("Create explicit slope condition")) { try { commitProfileChange(context.prefix()+"slopeCondition.",BiomeDecorators.enableSlope(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.treePop();return; }
        }
        ImGui.textWrapped(slope ? "Finite ordered slopes 0..1024. The native default 0..10 bypasses slope testing; forcePlace also bypasses surface slope checks." : "Exact ordered stack heights 1..2032. With scaleStack, heights are percentages of available space, capped by absoluteMaxStack; this is not sampled here.");
        String low=slope ? "minimumSlope" : "stackMin",high=slope ? "maximumSlope" : "stackMax",a=container.token(low),b=container.token(high);
        if (a!=null && !LosslessJson.numericToken(a) || b!=null && !LosslessJson.numericToken(b)) { ImGui.textWrapped("Stored pair has unsupported numeric types (read-only).");ImGui.treePop();return; }
        String first=a==null ? slope ? BiomeLayers.slopeDefault(low) : BiomeDecorators.defaultToken(low) : a,second=b==null ? slope ? BiomeLayers.slopeDefault(high) : BiomeDecorators.defaultToken(high) : b,prefix=context.prefix()+(slope ? "slopeCondition." : ""),minKey=prefix+low,maxKey=prefix+high;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(first,Math.max(128,first.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(second,Math.max(128,second.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(low,minimum);drafts.update(minKey,first,minimum.get());ImGui.inputText(high,maximum);drafts.update(maxKey,second,maximum.get());
        if (ImGui.button("Apply pair")) { try { byte[] next=slope ? BiomeDecorators.setSlope(selected,workspace.read(selected),context,minimum.get(),maximum.get()) : BiomeDecorators.setStack(selected,workspace.read(selected),context,minimum.get(),maximum.get());workspace.replace(selected,next);drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
        if (slope) for (String field : container.fields()) if (!BiomeLayers.SLOPE_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");ImGui.treePop();
    }
    private void drawDecoratorForce(LosslessJson d,BiomeDecorators.Context context) {
        if (!ImGui.treeNode("Forced surface block")) return;LosslessJson block;
        try { block=BiomeDecorators.forceBlock(d); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored forceBlock is a snippet or unsupported (read-only).");ImGui.treePop();return; }
        ImGui.textWrapped("Optional surface-block replacement. This does not toggle forcePlace. Native paths can still apply host, fluid and plant guards; effective placement is not validated.");
        if (block!=null && ImGui.button("Restore no forced surface block (null)")) { try { commitProfileChange(context.prefix()+"forceBlock.",BiomeDecorators.restoreForceBlock(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        try { String stored=block==null || block.token("block")==null ? "" : block.stringToken("block"),key=context.prefix()+"forceBlock.block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(block==null ? "Block for explicit override" : "Surface block",input);drafts.update(key,stored,input.get());if (ImGui.button("Apply forced surface block")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setForceBlock(selected,workspace.read(selected),context,input.get()))); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block text has an unsupported type (read-only)."); }
        if (block!=null) for (String field : block.fields()) if (!field.equals("block")) ImGui.textWrapped(field+" (read-only)");ImGui.treePop();
    }
    private void drawDecoratorBlocks(LosslessJson d,BiomeDecorators.Context context,String field) {
        ImGui.textWrapped(BiomeDecorators.help(field));List<LosslessJson> entries;
        try { entries=BiomeDecorators.blocks(d,field); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block list is null, mixed or unsupported (read-only).");return; }
        if (BiomeDecorators.filter(field)) ImGui.textWrapped(field.equals("whitelist") ? "Absent/null applies no whitelist. Explicit empty whitelist blocks matching surface placement unless forcePlace bypasses it." : "Absent/null/empty applies no blacklist. Native forcePlace paths can bypass host filters.");
        else ImGui.textWrapped(field.equals("topPalette") ? "Weighted top materials; an empty list falls back to the main palette." : "Weighted material entries. Keep one after population; existing empty lists can be repaired.");
        if (entries==null) { if (ImGui.button("Enable explicit block list")) { try { commitProfileChange(context.blocksPrefix(field),BiomeDecorators.enableBlocks(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }return; }
        if (BiomeDecorators.filter(field) && ImGui.button("Restore no filter (null)")) { try { commitProfileChange(context.blocksPrefix(field),BiomeDecorators.restoreFilter(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); }return; }
        String newKey=context.blocksPrefix(field)+"new.block";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New block",next);drafts.update(newKey,"",next.get());
        if (ImGui.button("Add block")) { changeDecoratorBlock(context,field,RiverProfiles.Action.ADD,-1,next.get(),newKey);return; }
        for (int index=0;index<entries.size();index++) {
            ImGui.pushID(index);int blockIndex=index;var entry=entries.get(index);String prefix=context.blocksPrefix(field)+index+".";
            try { String stored=entry.token("block")==null ? "" : entry.stringToken("block"),key=prefix+"block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Block",input);drafts.update(key,stored,input.get());if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setBlock(selected,workspace.read(selected),context,field,blockIndex,input.get()))); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block text has an unsupported type (read-only)."); }
            if (BiomeDecorators.weighted(field)) {
                String token=entry.token("weight"),key=prefix+"weight";
                if (token==null) { if (ImGui.button("Enable weight 1")) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.enableWeight(selected,workspace.read(selected),context,field,blockIndex))); }
                else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored weight has an unsupported type (read-only).");
                else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Exact weight 1..1000",input);drafts.update(key,token,input.get());if (ImGui.button("Apply weight")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setWeight(selected,workspace.read(selected),context,field,blockIndex,input.get()))); }
            }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate block")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(field.equals("palette") && entries.size()<=1);if (ImGui.button("Remove block")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Block up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Block down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            for (String member : entry.fields()) if (!member.equals("block") && !(BiomeDecorators.weighted(field) && member.equals("weight"))) ImGui.textWrapped(member+" (read-only)");ImGui.popID();if (action!=null) { changeDecoratorBlock(context,field,action,index,null,null);return; }
        }
    }
    private void changeDecoratorBlock(BiomeDecorators.Context context,String field,RiverProfiles.Action action,int index,String value,String appliedKey) { try { commitProfileChange(context.blocksPrefix(field),BiomeDecorators.changeBlock(selected,workspace.read(selected),context,field,action,index,value),appliedKey); } catch (Exception failure) { error=failure.getMessage(); } }
    private static String mappingDraft(String source,String target) { return source.length()+":"+source+target; }
    private void drawBlockFallbacks(LosslessJson document) {
        if (!ImGui.collapsingHeader("Missing-block fallbacks")) return;ImGui.pushID("blockFallbacks");
        ImGui.textWrapped(BlockFallbacks.help());
        ImGui.textWrapped("Native resolution tries the registry, then legacy renames, then one fallback target lookup. Fallback targets are not followed through other fallback rules. Source ids use base material identity. Registry readiness, block/state validity, aliases and effective content compatibility are not evaluated here.");
        ImGui.textWrapped("Iris collects fallback maps from every top-level dimension JSON in the pack. Duplicate and aliased sources can overlap across files or within a map. Native file/property iteration determines the winner; the editor does not predict it. Removing a mapping here leaves other dimension maps intact.");
        if (ImGui.treeNode("Pack-wide source overlaps")) {
            var audit=blockFallbackAudits.inspect(workspace);ImGui.text(audit.inspectedDimensions()+" dimensions inspected; "+audit.definitions()+" nonblank definitions; "+audit.overlaps().size()+" overlapping sources.");
            ImGui.textWrapped("Advisory only: normalized source identity is checked; no target resolution or runtime winner is computed. Unsupported resources are listed below. This check never blocks Save/Export.");
            for (var overlap : audit.overlaps()) if (ImGui.treeNode(overlap.normalizedSource())) { for (var item : overlap.definitions()) ImGui.textWrapped(item.path()+" | "+item.source()+" -> "+item.target());ImGui.treePop(); }
            for (var notice : audit.notices()) ImGui.textWrapped(notice.path()+": "+notice.message());ImGui.treePop();
        }
        List<LosslessJson.StringProperty> rows;
        try { rows=BlockFallbacks.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored fallback map is null, mixed, a snippet or unsupported (read-only).");ImGui.popID();return; }
        if (rows==null) {
            if (ImGui.button("Initialize empty fallback map")) { try { commitProfileChange(BlockFallbacks.prefix(),BlockFallbacks.enable(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
            ImGui.popID();return;
        }
        String newKey=BlockFallbacks.prefix()+"new.mapping";ImString source=values.computeIfAbsent(newKey+".source",ignored -> new ImString("",4096)),target=values.computeIfAbsent(newKey+".target",ignored -> new ImString("",4096));
        ImGui.inputText("New namespaced source id",source);ImGui.inputText("New fallback state",target);drafts.update(newKey,mappingDraft("",""),mappingDraft(source.get(),target.get()));
        if (ImGui.button("Add fallback mapping")) { try { commitProfileChange(BlockFallbacks.prefix(),BlockFallbacks.add(selected,workspace.read(selected),source.get(),target.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (rows.isEmpty()) ImGui.textWrapped("Empty map contributes no fallback definitions from this dimension file.");
        for (int index=0;index<rows.size();index++) {
            ImGui.pushID(index);var row=rows.get(index);String key=BlockFallbacks.prefix()+index;
            ImString from=values.computeIfAbsent(key+".source",ignored -> new ImString(row.key(),Math.max(4096,row.key().getBytes(StandardCharsets.UTF_8).length+1))),to=values.computeIfAbsent(key+".target",ignored -> new ImString(row.value(),Math.max(4096,row.value().getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Source id",from);ImGui.inputText("Fallback state",to);drafts.update(key,mappingDraft(row.key(),row.value()),mappingDraft(from.get(),to.get()));
            if (ImGui.button("Apply fallback mapping")) { try { commitProfileChange(BlockFallbacks.prefix(),BlockFallbacks.set(selected,workspace.read(selected),row.key(),from.get(),to.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            if (ImGui.button("Remove fallback mapping")) { ImGui.popID();try { commitProfileChange(BlockFallbacks.prefix(),BlockFallbacks.remove(selected,workspace.read(selected),row.key()),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawStaticObjects(LosslessJson document) {
        if (!ImGui.collapsingHeader("Fixed dimension objects")) return;
        ImGui.pushID("staticObjects");
        ImGui.textWrapped("Static objects use absolute world coordinates around their saved origin. Later entries win overlaps in newly generated chunks. Reloading does not rewrite existing chunks. Object content, transformed bounds, registry compatibility and generation are not evaluated; previews remain unavailable.");
        ImGui.textWrapped(StaticObjects.help("allObjectScaleFactor"));String rootScale=document.token("allObjectScaleFactor");
        if (rootScale!=null && !LosslessJson.numericToken(rootScale)) ImGui.textWrapped("Dimension object scale has an unsupported type (read-only).");
        else {
            String stored=rootScale==null ? "1" : rootScale,key=StaticObjects.scalePrefix()+"value";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText(rootScale==null ? "Dimension object scale (default 1)" : "Dimension object scale",input);drafts.update(key,stored,input.get());
            if (ImGui.button("Apply dimension object scale")) { try { workspace.replace(selected,StaticObjects.setDimensionScale(selected,workspace.read(selected),input.get()));drafts.applied(key);values.remove(key);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
        }
        List<LosslessJson> entries;
        try { entries=StaticObjects.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored static objects contain nulls, snippets or unsupported entries (read-only).");ImGui.popID();return; }
        if (entries==null) {
            if (ImGui.button("Initialize empty static object list")) { try { commitProfileChange(StaticObjects.listPrefix(),StaticObjects.enable(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
            ImGui.popID();return;
        }
        var choices=FloatingObjectPlacements.objectChoices(workspace);String newKey=StaticObjects.listPrefix()+"new",objectKey=newKey+".object";ImString choice=objectChoiceState(objectKey,choices);drawObjectChoice("Object for new fixed placement",choice,choices);
        ImString[] position=new ImString[3];
        for (int axis=0;axis<3;axis++) { String key=newKey+"."+StaticObjects.AXES.get(axis);position[axis]=values.computeIfAbsent(key,ignored -> new ImString("0",128));ImGui.inputText("New world "+StaticObjects.AXES.get(axis),position[axis]); }
        String initial=choices.isEmpty() ? "" : choices.getFirst();drafts.update(newKey,staticDraft(initial,"0","0","0"),staticDraft(choice.get(),position[0].get(),position[1].get(),position[2].get()));
        ImGui.textWrapped("Exact world X/Z: -29999984..29999984. "+StaticObjects.heightLabel(document)+". Seed, rotation, bore and scale defaults stay implicit on Add.");
        ImGui.beginDisabled(!choices.contains(choice.get()));
        if (ImGui.button("Add fixed object")) {
            ImGui.endDisabled();try { commitProfileChange(StaticObjects.listPrefix(),StaticObjects.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,choice.get(),position[0].get(),position[1].get(),position[2].get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return;
        }
        ImGui.endDisabled();
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Fixed object "+index)) continue;RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate fixed object")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();if (ImGui.button("Remove fixed object")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Fixed object up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Fixed object down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) {
                ImGui.treePop();try { commitProfileChange(StaticObjects.listPrefix(),StaticObjects.change(selected,workspace.read(selected),action,index,null,null,null,null,workspace),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return;
            }
            var context=new StaticObjects.Context(index);var entry=entries.get(index);String current;
            try { current=entry.stringToken("object");if (ImGui.beginCombo("Object key",current==null ? "Required object missing" : current)) { for (String key : choices) if (ImGui.selectable(key,key.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StaticObjects.setReference(selected,workspace.read(selected),context,key,workspace)));ImGui.endCombo(); } }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored object reference has an unsupported type (read-only)."); }
            drawStaticTransform(entry,context,"position");drawStaticTransform(entry,context,"rotation");
            drawStaticScale(entry,context);drawStaticSeed(entry,context);
            drawFloatingReplacements(entry,context.replacements());
            for (String flag : List.of("bore","smartBore")) {
                String token=entry.token(flag);if (token!=null && !token.equals("true") && !token.equals("false")) { ImGui.textWrapped(flag+": unsupported type (read-only)");continue; }
                boolean enabled="true".equals(token);if (token==null) { if (ImGui.button("Enable "+flag+"=false")) applyRoutingExtra(() -> workspace.replace(selected,StaticObjects.setBoolean(selected,workspace.read(selected),context,flag,false))); }
                else if (ImGui.checkbox(flag,enabled)) applyRoutingExtra(() -> workspace.replace(selected,StaticObjects.setBoolean(selected,workspace.read(selected),context,flag,!enabled)));
                if (ImGui.isItemHovered()) ImGui.setTooltip(StaticObjects.help(flag));
            }
            for (String field : entry.fields()) if (!StaticObjects.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only, preserved)");
            ImGui.treePop();
        }
        ImGui.popID();
    }
    private static String staticDraft(String... values) { StringBuilder result=new StringBuilder();for (String value : values) result.append(value.length()).append(':').append(value);return result.toString(); }
    private void drawStaticTransform(LosslessJson entry,StaticObjects.Context context,String field) {
        if (!ImGui.treeNode(field)) return;LosslessJson transform;
        try { transform=StaticObjects.object(entry,field); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored transform is null, a snippet or unsupported (read-only).");ImGui.treePop();return; }
        ImGui.textWrapped(StaticObjects.help(field));ImGui.textWrapped(field.equals("rotation") ? "Finite degrees -360..360. Absent axes default to zero; Apply writes all three together." : "Exact integers. Apply writes all three coordinates together, within the dimension's stored world height.");
        String[] stored=new String[3];ImString[] input=new ImString[3];
        for (int axis=0;axis<3;axis++) {
            String name=StaticObjects.AXES.get(axis),token=transform==null ? null : transform.token(name);
            if (token!=null && !LosslessJson.numericToken(token)) { ImGui.textWrapped("Stored "+name+" is not numeric (read-only).");ImGui.treePop();return; }
            stored[axis]=token==null ? "0" : token;String key=context.prefix()+field+"."+name;String value=stored[axis];input[axis]=values.computeIfAbsent(key,ignored -> new ImString(value,Math.max(128,value.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText(name,input[axis]);drafts.update(key,value,input[axis].get());
        }
        if (ImGui.button("Apply fixed "+field)) {
            try {
                byte[] next=field.equals("position") ? StaticObjects.setPosition(selected,workspace.read(selected),context,input[0].get(),input[1].get(),input[2].get()) : StaticObjects.setRotation(selected,workspace.read(selected),context,input[0].get(),input[1].get(),input[2].get());workspace.replace(selected,next);
                for (String axis : StaticObjects.AXES) { String key=context.prefix()+field+"."+axis;drafts.applied(key);values.remove(key); }error=null;
            } catch (Exception failure) { error=failure.getMessage(); }
        }
        if (transform!=null) for (String member : transform.fields()) if (!StaticObjects.AXES.contains(member)) ImGui.textWrapped(member+" (read-only)");ImGui.treePop();
    }
    private void drawStaticScale(LosslessJson entry,StaticObjects.Context context) {
        String token=entry.token("scale"),key=context.prefix()+"scale",stored=token==null || token.equals("null") ? "1" : token;
        if (!LosslessJson.numericToken(stored)) ImGui.textWrapped("Stored scale has an unsupported type (read-only).");
        else {
            ImGui.textWrapped(token==null || token.equals("null") ? "Scale inherits the dimension default. Apply an explicit factor to override it." : "Explicit scale overrides the dimension factor, including 1.");
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Fixed scale (0.01..50)",input);drafts.update(key,stored,input.get());
            if (ImGui.button("Apply fixed scale")) { try { workspace.replace(selected,StaticObjects.setScale(selected,workspace.read(selected),context,input.get()));drafts.applied(key);values.remove(key);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
            if (token!=null && !token.equals("null") && ImGui.button("Inherit dimension scale")) { try { commitProfileChange(context.prefix()+"scale",StaticObjects.setScale(selected,workspace.read(selected),context,null),null); } catch (Exception failure) { error=failure.getMessage(); } }
        }
        try {
            String interpolation=entry.stringToken("scaleInterpolation"),current=interpolation==null ? "NONE" : interpolation;
            if (ImGui.beginCombo("Scale interpolation",current)) { for (String choice : StaticObjects.interpolations()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StaticObjects.setInterpolation(selected,workspace.read(selected),context,choice)));ImGui.endCombo(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored interpolation has an unsupported type (read-only)."); }
    }
    private void drawStaticSeed(LosslessJson entry,StaticObjects.Context context) {
        String token=entry.token("seed");if (token!=null && !LosslessJson.numericToken(token)) { ImGui.textWrapped("Stored seed has an unsupported type (read-only).");return; }
        String stored=token==null ? "0" : token,key=context.prefix()+"seed";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Fixed signed 64-bit seed",input);drafts.update(key,stored,input.get());
        if (ImGui.button("Apply fixed seed")) { try { workspace.replace(selected,StaticObjects.setSeed(selected,workspace.read(selected),context,input.get()));drafts.applied(key);values.remove(key);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
        ImGui.textWrapped(StaticObjects.help("seed"));
    }
    private void drawDepositVariants(LosslessJson document,String base) {
        if (!ImGui.collapsingHeader("Deposit variants")) return;
        ImGui.textWrapped("Ordered deposit remaps use inclusive absolute world Y. Native placement tries the cave biome at the candidate, then its region, then the dimension; the first rule producing a remap wins. Unmatched candidates use Iris's native fallback. Deposit origin bands use engine-local Y.");
        ImGui.textWrapped("Sources match material identity. New source keys require a namespaced id without properties. Targets require nonblank text; registry, aliases and state resolution are unchecked. Unresolvable mappings are skipped by Iris. Literal key collisions are rejected; native aliases may still resolve to the same material. Previews remain unavailable.");
        List<LosslessJson> variants;
        try { variants=DepositVariants.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored variants contain nulls, snippets or unsupported entries (read-only).");return; }
        String prefix=DepositVariants.listPrefix(base);
        if (variants==null) {
            if (ImGui.button("Initialize empty variant list")) { try { commitProfileChange(prefix,DepositVariants.enable(selected,workspace.read(selected),base),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        String newKey=prefix+"new.mapping";ImString source=values.computeIfAbsent(newKey+".source",ignored -> new ImString("minecraft:iron_ore",4096)),target=values.computeIfAbsent(newKey+".target",ignored -> new ImString("minecraft:deepslate_iron_ore",4096));
        ImGui.inputText("Source for new variant",source);ImGui.inputText("Target for new variant",target);drafts.update(newKey,mappingDraft("minecraft:iron_ore","minecraft:deepslate_iron_ore"),mappingDraft(source.get(),target.get()));
        ImGui.textWrapped("A new variant keeps the native 0..0 world-Y band until you apply its heights.");
        if (ImGui.button("Add variant with mapping")) { changeDepositVariant(base,RiverProfiles.Action.ADD,-1,source.get(),target.get(),newKey);return; }
        for (int index=0;index<variants.size();index++) {
            if (!ImGui.treeNode("Variant "+index)) continue;
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate variant")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();if (ImGui.button("Remove variant")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Variant up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==variants.size()-1);if (ImGui.button("Variant down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeDepositVariant(base,action,index,null,null,null);return; }
            var context=new DepositVariants.Context(base,index);var variant=variants.get(index);drawVariantBand(variant,context);
            if (ImGui.treeNode("Material remaps")) { drawVariantMap(variant,context);ImGui.treePop(); }
            for (String field : variant.fields()) if (!DepositVariants.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeDepositVariant(String base,RiverProfiles.Action action,int index,String source,String target,String appliedKey) {
        try { commitProfileChange(DepositVariants.listPrefix(base),DepositVariants.change(selected,workspace.read(selected),base,action,index,source,target),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawVariantBand(LosslessJson variant,DepositVariants.Context context) {
        ImGui.textWrapped("Apply exact integer world-Y bounds together, from -2048 to 8192. Minimum must be at most maximum; absent values use 0. World compatibility is not evaluated.");
        String low=variant.token("minHeight"),high=variant.token("maxHeight");
        if (low!=null && !LosslessJson.numericToken(low) || high!=null && !LosslessJson.numericToken(high)) { ImGui.textWrapped("Stored height bounds have unsupported types (read-only).");return; }
        String storedLow=low==null ? DepositVariants.defaultHeight("minHeight") : low,storedHigh=high==null ? DepositVariants.defaultHeight("maxHeight") : high,minKey=context.prefix()+"minHeight",maxKey=context.prefix()+"maxHeight";
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedLow,Math.max(128,storedLow.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedHigh,Math.max(128,storedHigh.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(low==null ? "Minimum world Y (native default)" : "Minimum world Y",minimum);drafts.update(minKey,storedLow,minimum.get());ImGui.inputText(high==null ? "Maximum world Y (native default)" : "Maximum world Y",maximum);drafts.update(maxKey,storedHigh,maximum.get());
        if (ImGui.button("Apply world-Y band")) { try { workspace.replace(selected,DepositVariants.setBand(selected,workspace.read(selected),context,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
    }
    private void drawVariantMap(LosslessJson variant,DepositVariants.Context context) {
        List<LosslessJson.StringProperty> mappings;LosslessJson map;
        try { map=DepositVariants.remap(variant);mappings=map==null ? null : map.stringProperties(); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored remap is null, mixed or unsupported (read-only).");return; }
        if (map==null) {
            if (ImGui.button("Initialize empty remap object")) { try { commitProfileChange(context.mapPrefix(),DepositVariants.enableMap(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        String newKey=context.mapPrefix()+"new.mapping";ImString source=values.computeIfAbsent(newKey+".source",ignored -> new ImString("",4096)),target=values.computeIfAbsent(newKey+".target",ignored -> new ImString("",4096));ImGui.inputText("New source id",source);ImGui.inputText("New target",target);drafts.update(newKey,mappingDraft("",""),mappingDraft(source.get(),target.get()));
        if (ImGui.button("Add material remap")) { try { commitProfileChange(context.mapPrefix(),DepositVariants.addMapping(selected,workspace.read(selected),context,source.get(),target.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }return; }
        if (mappings.isEmpty()) ImGui.textWrapped("Empty map: this rule produces no replacement.");
        for (int index=0;index<mappings.size();index++) {
            ImGui.pushID(index);var mapping=mappings.get(index);String rowKey=context.mapPrefix()+index;
            ImString from=values.computeIfAbsent(rowKey+".source",ignored -> new ImString(mapping.key(),Math.max(4096,mapping.key().getBytes(StandardCharsets.UTF_8).length+1))),to=values.computeIfAbsent(rowKey+".target",ignored -> new ImString(mapping.value(),Math.max(4096,mapping.value().getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Source id",from);ImGui.inputText("Target",to);drafts.update(rowKey,mappingDraft(mapping.key(),mapping.value()),mappingDraft(from.get(),to.get()));
            if (ImGui.button("Apply mapping")) {
                try { commitProfileChange(context.mapPrefix(),DepositVariants.setMapping(selected,workspace.read(selected),context,mapping.key(),from.get(),to.get()),rowKey); } catch (Exception failure) { error=failure.getMessage(); }
            }
            if (ImGui.button("Remove mapping")) { ImGui.popID();try { commitProfileChange(context.mapPrefix(),DepositVariants.removeMapping(selected,workspace.read(selected),context,mapping.key()),null); } catch (Exception failure) { error=failure.getMessage(); }return; }
            ImGui.popID();
        }
    }
    private void drawBiomeOreHosts(LosslessJson document) {
        if (!ImGui.collapsingHeader("Biome surface ore hosts")) return;
        ImGui.textWrapped("For ore deposits at exterior terrain surfaces, this list replaces the deposit surface-host filter. Absent/null inherits deposit settings. An explicit empty list permits no exterior-surface ore hosts. Cave-air walls keep their native rules; material validity and generation are not evaluated.");
        List<String> hosts;
        try { hosts=BiomeOreHosts.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored surface hosts are mixed or unsupported (read-only).");return; }
        if (hosts==null) {
            ImGui.textWrapped("Inheriting deposit surface-host settings.");
            if (ImGui.button("Use explicit empty host list (forbid surface ore)")) changeBiomeOreHostMode(false);
            return;
        }
        if (ImGui.button("Inherit deposit surface-host settings")) { changeBiomeOreHostMode(true);return; }
        if (!hosts.isEmpty() && ImGui.button("Forbid all exterior-surface ore hosts")) { changeBiomeOreHostMode(false);return; }
        if (hosts.isEmpty()) ImGui.textWrapped("Explicit empty override: no exterior-surface ore hosts are allowed.");
        String newKey=BiomeOreHosts.prefix()+"new.host";ImString input=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New host block id",input);drafts.update(newKey,"",input.get());
        if (ImGui.button("Add host")) { changeBiomeOreHost(RiverProfiles.Action.ADD,-1,input.get(),newKey);return; }
        for (int index=0;index<hosts.size();index++) {
            ImGui.pushID(index);int row=index;String stored=hosts.get(index),key=BiomeOreHosts.prefix()+index;ImString value=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Host id",value);drafts.update(key,stored,value.get());
            if (ImGui.button("Apply host")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeOreHosts.set(selected,workspace.read(selected),row,value.get())));
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate host")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();if (ImGui.button("Remove host")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Host up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==hosts.size()-1);if (ImGui.button("Host down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            ImGui.popID();if (action!=null) { changeBiomeOreHost(action,index,null,null);return; }
        }
    }
    private void changeBiomeOreHostMode(boolean inherit) {
        try { byte[] original=workspace.read(selected);commitProfileChange(BiomeOreHosts.prefix(),inherit ? BiomeOreHosts.inherit(selected,original) : BiomeOreHosts.restrict(selected,original),null); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void changeBiomeOreHost(RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(BiomeOreHosts.prefix(),BiomeOreHosts.change(selected,workspace.read(selected),action,index,value),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDeposits(LosslessJson document,String base) {
        if (!ImGui.collapsingHeader("Deposit generators")) return;
        ImGui.textWrapped("Native clump generators combine dimension, region and biome lists in that order, using the region/biome at the chunk's center column. Reordering can change deterministic native outcomes. Single-block ore noise generators are configured separately.");
        ImGui.textWrapped("Biome multipliers, host rules, variant remaps and world context affect results. Geometry, registry compatibility and native generation are not evaluated or previewed.");
        List<LosslessJson> entries;
        try { entries=DepositGenerators.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored deposits contain nulls, snippets or unsupported entries (read-only).");return; }
        String prefix=DepositGenerators.listPrefix(base);
        if (entries==null) {
            if (ImGui.button("Initialize empty deposit list")) { try { commitProfileChange(prefix,DepositGenerators.enable(selected,workspace.read(selected),base),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        String newKey=prefix+"new.block";ImString material=values.computeIfAbsent(newKey,ignored -> new ImString("minecraft:iron_ore",4096));ImGui.inputText("Material for new deposit",material);drafts.update(newKey,"minecraft:iron_ore",material.get());
        if (ImGui.button("Add deposit with material")) { changeDeposit(base,RiverProfiles.Action.ADD,-1,material.get(),newKey);return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Deposit "+index)) continue;
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate deposit")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove deposit")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Deposit up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Deposit down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeDeposit(base,action,index,null,null);return; }
            var entry=entries.get(index);var context=new DepositGenerators.Context(base,index);
            ImGui.textWrapped("Height uses inclusive engine-local Y: 0 is the dimension bottom. Per-chunk count is sampled inclusively, then chunk/per-clump chances and native filters apply. Surface clearance also affects ABOVE_TERRAIN placement.");
            for (String pair : DepositGenerators.PAIRS) if (ImGui.treeNode(pair+" range")) { drawDepositPair(entry,context,pair);ImGui.treePop(); }
            for (String field : DepositGenerators.SCALARS) drawDepositScalar(entry,context,field);
            for (String field : DepositGenerators.ENUMS) {
                ImGui.pushID(field);ImGui.textWrapped(DepositGenerators.help(field));
                try {
                    String current=entry.token(field)==null ? DepositGenerators.defaultToken(field) : entry.stringToken(field);
                    if (ImGui.beginCombo(field,current.length()>128 ? "Unknown stored choice" : current+(entry.token(field)==null ? " (native default)" : ""))) {
                        for (String choice : DepositGenerators.choices(field)) { if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DepositGenerators.setChoice(selected,workspace.read(selected),context,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(DepositGenerators.choiceHelp(field,choice)); }
                        ImGui.endCombo();
                    }
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored enum has an unsupported type (read-only)."); }
                ImGui.popID();
            }
            String bedrock=entry.token("replaceBedrock");ImGui.textWrapped("Bedrock replacement permits bedrock through its native guard; host, scope and other world rules still apply.");
            if (bedrock==null) { if (ImGui.button("Enable bedrock replacement flag (false)")) applyRoutingExtra(() -> workspace.replace(selected,DepositGenerators.enableBedrock(selected,workspace.read(selected),context))); }
            else if (!bedrock.equals("true") && !bedrock.equals("false")) ImGui.textWrapped("Stored bedrock flag has an unsupported type (read-only).");
            else { ImBoolean flag=new ImBoolean(Boolean.parseBoolean(bedrock));if (ImGui.checkbox("Replace bedrock",flag)) applyRoutingExtra(() -> workspace.replace(selected,DepositGenerators.setBedrock(selected,workspace.read(selected),context,flag.get()))); }
            if (ImGui.treeNode("Deposit materials")) { drawDepositPalette(entry,context);ImGui.treePop(); }
            for (String field : DepositGenerators.FILTERS) if (ImGui.treeNode(field)) { drawDepositFilter(entry,context,field);ImGui.treePop(); }
            for (String field : entry.fields()) if (!DepositGenerators.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeDeposit(String base,RiverProfiles.Action action,int index,String block,String appliedKey) {
        try { commitProfileChange(DepositGenerators.listPrefix(base),DepositGenerators.change(selected,workspace.read(selected),base,action,index,block),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDepositPair(LosslessJson entry,DepositGenerators.Context context,String pair) {
        String low=DepositGenerators.pairField(pair,true),high=DepositGenerators.pairField(pair,false),min=entry.token(low),max=entry.token(high);
        ImGui.textWrapped(DepositGenerators.help(low));ImGui.textWrapped(DepositGenerators.help(high));ImGui.textWrapped(DepositGenerators.constraints(low));
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored paired bounds have unsupported types (read-only).");return; }
        String storedMin=min==null ? DepositGenerators.defaultToken(low) : min,storedMax=max==null ? DepositGenerators.defaultToken(high) : max,minKey=context.prefix()+low,maxKey=context.prefix()+high;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(min==null ? "Minimum (native default)" : "Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());ImGui.inputText(max==null ? "Maximum (native default)" : "Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply paired range")) { try { workspace.replace(selected,DepositGenerators.setPair(selected,workspace.read(selected),context,pair,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
    }
    private void drawDepositScalar(LosslessJson entry,DepositGenerators.Context context,String field) {
        ImGui.pushID(field);ImGui.textWrapped(DepositGenerators.help(field));ImGui.textWrapped(DepositGenerators.constraints(field));String token=entry.token(field);
        if (token==null) { if (ImGui.button("Enable "+field+" ("+DepositGenerators.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,DepositGenerators.enableScalar(selected,workspace.read(selected),context,field))); }
        else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored scalar has an unsupported type (read-only).");
        else {
            String key=context.prefix()+field;ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply scalar")) commitRiverOverride(key,() -> workspace.replace(selected,DepositGenerators.setScalar(selected,workspace.read(selected),context,field,input.get())));
        }
        ImGui.popID();
    }
    private void drawDepositPalette(LosslessJson entry,DepositGenerators.Context context) {
        ImGui.textWrapped("Materials are selected uniformly by entry. Stored weights are ignored by the native deposit selector; advanced block data remains read-only. Block names require nonblank text; registry/state validity is unchecked.");
        List<LosslessJson> palette;
        try { palette=DepositGenerators.palette(entry); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette is a snippet or unsupported list (read-only).");return; }
        if (palette==null) {
            if (ImGui.button("Initialize empty material list")) { try { commitProfileChange(context.palettePrefix(),DepositGenerators.enablePalette(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        if (palette.isEmpty()) ImGui.textWrapped("Empty material list: add a block to configure this generator.");
        String newKey=context.palettePrefix()+"new.block";ImString block=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New deposit block",block);drafts.update(newKey,"",block.get());
        if (ImGui.button("Add deposit block")) { changeDepositPalette(context,RiverProfiles.Action.ADD,-1,block.get(),newKey);return; }
        for (int index=0;index<palette.size();index++) {
            ImGui.pushID(index);int row=index;var value=palette.get(index);
            try {
                String stored=value.token("block")==null ? "" : value.stringToken("block"),key=context.palettePrefix()+index+".block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(value.token("block")==null ? "Missing block" : "Block",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,DepositGenerators.setBlock(selected,workspace.read(selected),context,row,input.get())));
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block has an unsupported type (read-only)."); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate block")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(palette.size()<=1);if (ImGui.button("Remove block")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Block up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==palette.size()-1);if (ImGui.button("Block down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            for (String field : value.fields()) if (!field.equals("block")) ImGui.textWrapped(field+" (read-only)");
            ImGui.popID();if (action!=null) { changeDepositPalette(context,action,index,null,null);return; }
        }
    }
    private void changeDepositPalette(DepositGenerators.Context context,RiverProfiles.Action action,int index,String block,String appliedKey) {
        try { commitProfileChange(context.palettePrefix(),DepositGenerators.changePalette(selected,workspace.read(selected),context,action,index,block),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDepositFilter(LosslessJson entry,DepositGenerators.Context context,String field) {
        ImGui.textWrapped(DepositGenerators.help(field));boolean biome=DepositGenerators.biomeFilter(field);
        ImGui.textWrapped(biome ? "Include and exclude filters match the selected Iris biome key or derivative id; exclusions win. New values need an exact in-pack biome key or a namespaced derivative id. Registry existence is unchecked." : "Literal block ids without states; registry existence is unchecked. Empty main host lists add no host restriction; empty surface lists use the main host fallback. For ore deposits, biome exterior-surface rules can replace the deposit surface filter.");
        List<String> filters;
        try { filters=DepositGenerators.filter(entry,field); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored filter is null, mixed or unsupported (read-only).");return; }
        if (filters==null) {
            if (ImGui.button("Initialize empty filter list")) { try { commitProfileChange(context.filterPrefix(field),DepositGenerators.enableFilter(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        var choices=biome ? ResourceTypes.referenceKeys(workspace,"biomes") : List.<String>of();String newKey=context.filterPrefix(field)+"new.value";
        int capacity=Math.max(4096,choices.stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length+1).max().orElse(1));ImString input=values.computeIfAbsent(newKey,ignored -> new ImString("",capacity));
        if (biome && ImGui.beginCombo("In-pack biome","Choose a key")) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(input.get()))) input.set(choice);ImGui.endCombo(); }
        ImGui.inputText("New filter",input);drafts.update(newKey,"",input.get());
        if (ImGui.button("Add filter")) { changeDepositFilter(context,field,RiverProfiles.Action.ADD,-1,input.get(),newKey);return; }
        for (int index=0;index<filters.size();index++) {
            ImGui.pushID(index);int row=index;String stored=filters.get(index),key=context.filterPrefix(field)+index;ImString value=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Filter",value);drafts.update(key,stored,value.get());
            if (ImGui.button("Apply filter")) commitRiverOverride(key,() -> workspace.replace(selected,DepositGenerators.setFilter(selected,workspace.read(selected),context,field,row,value.get(),workspace)));
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate filter")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();if (ImGui.button("Remove filter")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Filter up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==filters.size()-1);if (ImGui.button("Filter down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            ImGui.popID();if (action!=null) { changeDepositFilter(context,field,action,index,null,null);return; }
        }
    }
    private void changeDepositFilter(DepositGenerators.Context context,String field,RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(context.filterPrefix(field),DepositGenerators.changeFilter(selected,workspace.read(selected),context,field,action,index,value,workspace),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawOres(LosslessJson document,String base) {
        if (!ImGui.collapsingHeader("Ore generators")) return;
        ImGui.textWrapped("Single-block ore noise generators. Generators are tried in list order; the first material wins. Native terrain tries biome, then region, then dimension generators when earlier scopes return no ore. Deposit veins are configured separately.");
        ImGui.textWrapped("Noise, effective terrain replacement and registry/state validity are not evaluated or previewed here.");
        List<LosslessJson> entries;
        try { entries=OreGenerators.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored ores are null, snippets or unsupported entries (read-only).");return; }
        String prefix=OreGenerators.listPrefix(base);
        if (entries==null) {
            if (ImGui.button("Initialize empty ore list")) { try { commitProfileChange(prefix,OreGenerators.enable(selected,workspace.read(selected),base),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        String newKey=prefix+"new.block";ImString block=values.computeIfAbsent(newKey,ignored -> new ImString("minecraft:iron_ore",4096));ImGui.inputText("Material for new ore generator",block);drafts.update(newKey,"minecraft:iron_ore",block.get());
        if (ImGui.button("Add ore generator with material")) { changeOre(base,RiverProfiles.Action.ADD,-1,block.get(),newKey);return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Ore "+index)) continue;
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate generator")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove generator")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Generator up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Generator down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeOre(base,action,index,null,null);return; }
            var context=new OreGenerators.Context(base,index);var ore=entries.get(index);
            ImGui.textWrapped("Threshold is a noise cutoff, from 0 (disabled) to 1. Actual coverage depends on the style and native context.");
            drawOreScalar(ore,context,"threshold");drawOreScalar(ore,context,"generateSurface");
            if (ImGui.treeNode("Height band")) { drawOreRange(ore,context);ImGui.treePop(); }
            if (ImGui.treeNode("Ore chance noise")) { drawStyleFields(ore,context.styleTarget("chanceStyle"));ImGui.treePop(); }
            if (ImGui.treeNode("Ore materials")) {
                ImGui.textWrapped(OreGenerators.help("palette"));
                try {
                    var palette=OreGenerators.object(ore,"palette");
                    if (palette==null) {
                        ImGui.textWrapped("Absent palette uses an empty native palette and generates no blocks. Creation keeps that empty default. An existing explicit {} palette uses the material class default STONE.");
                        if (ImGui.button("Create explicit empty ore palette")) { try { commitProfileChange(context.prefix("palette"),OreGenerators.createPalette(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }
                    } else drawGeneralBlockPalette(palette,context.paletteTarget());
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored ore palette is a snippet or unsupported value (read-only)."); }
                ImGui.treePop();
            }
            for (String field : ore.fields()) if (!OreGenerators.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeOre(String base,RiverProfiles.Action action,int index,String block,String appliedKey) {
        try { commitProfileChange(OreGenerators.listPrefix(base),OreGenerators.change(selected,workspace.read(selected),base,action,index,block),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawOreScalar(LosslessJson ore,OreGenerators.Context context,String field) {
        ImGui.pushID(field);ImGui.textWrapped(OreGenerators.help(field));String token=ore.token(field);
        if (field.equals("generateSurface")) ImGui.textWrapped("True selects the surface-pass list; false selects the underground-pass list. The surface pass runs before layers and fluid. Native terrain and host rules determine the resulting blocks.");
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+OreGenerators.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,OreGenerators.enableScalar(selected,workspace.read(selected),context,field)));
        } else if (field.equals("generateSurface")) {
            if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored flag has an unsupported type (read-only).");
            else { ImBoolean flag=new ImBoolean(Boolean.parseBoolean(token));if (ImGui.checkbox("Surface pass",flag)) applyRoutingExtra(() -> workspace.replace(selected,OreGenerators.setSurface(selected,workspace.read(selected),context,flag.get()))); }
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored threshold has an unsupported type (read-only).");
        else {
            String key=context.prefix()+field;ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Threshold",input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply threshold")) commitRiverOverride(key,() -> workspace.replace(selected,OreGenerators.setThreshold(selected,workspace.read(selected),context,input.get())));
        }
        ImGui.popID();
    }
    private void drawOreRange(LosslessJson ore,OreGenerators.Context context) {
        ImGui.textWrapped("Inclusive engine-local Y: 0 is the dimension bottom. Editor bounds are -8192 to 8192; Iris does not annotate range limits. Apply both bounds together. World-height compatibility is not evaluated.");
        LosslessJson range;
        try { range=OreGenerators.object(ore,"range"); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored range is a snippet or unsupported value (read-only).");return; }
        if (range==null) {
            ImGui.textWrapped("Absent band uses native ore defaults 30 to 80. An existing {} range uses class defaults 16 to 32.");
            if (ImGui.button("Create explicit ore band (30 to 80)")) { try { commitProfileChange(context.prefix("range"),OreGenerators.createRange(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }
            return;
        }
        String min=range.token("min"),max=range.token("max");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored bounds have unsupported types (read-only).");return; }
        String storedMin=min==null ? OreGenerators.rangeDefault("min",true) : min,storedMax=max==null ? OreGenerators.rangeDefault("max",true) : max;
        String minKey=context.prefix("range")+"min",maxKey=context.prefix("range")+"max";
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(min==null ? "Minimum (class default)" : "Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());ImGui.inputText(max==null ? "Maximum (class default)" : "Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply height band")) { try { workspace.replace(selected,OreGenerators.setRange(selected,workspace.read(selected),context,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
        for (String field : range.fields()) if (!List.of("min","max").contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawRootObjects(LosslessJson document,int owner) {
        if (!ImGui.collapsingHeader(owner==FloatingObjectPlacements.REGION_ROOT ? "Region object placements" : "Biome object placements")) return;
        ImGui.textWrapped("Select exact .iob keys from this pack. Native object loading, collision checks and generation are unavailable here.");
        ImGui.textWrapped("Carving support selects surface/cave passes. Imports can remap ordinary modes to FAST_MIN_STILT; CENTER_HEIGHT cave placements can inherit the active cave profile mode. Dimension settings also affect surface support. No effective placement is evaluated.");
        if (owner==FloatingObjectPlacements.REGION_ROOT) ImGui.textWrapped(RegionObjectPlacements.caveHelp());
        drawFloatingObjectList(document,owner,"objects");
    }
    private void drawFloatingObjects(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Floating object placements")) return;
        ImGui.textWrapped("Select exact object keys from .iob files in this pack. File contents, compatibility and native placement are not resolved here. Top/bottom override modes determine which lists participate; editing a list leaves inheritance settings unchanged. Anchored placements remap some modes and replace translation/rotation in Iris.");
        for (String list : FloatingObjectPlacements.LIST_FIELDS) if (ImGui.treeNode(list)) { drawFloatingObjectList(entry,index,list);ImGui.treePop(); }
        ImGui.treePop();
    }
    private ImString objectChoiceState(String key,List<String> choices) {
        int capacity=Math.max(4096,choices.stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length+1).max().orElse(1));
        return values.computeIfAbsent(key,ignored -> new ImString(choices.isEmpty() ? "" : choices.getFirst(),capacity));
    }
    private void drawObjectChoice(String label,ImString state,List<String> choices) {
        String current=state.get();
        if (ImGui.beginCombo(label,current.isEmpty() ? "Select an object" : current.length()>128 ? "Selected object" : current)) {
            for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) state.set(choice);
            ImGui.endCombo();
        }
    }
    private void drawFloatingObjectList(LosslessJson entry,int index,String list) {
        ImGui.textWrapped(FloatingObjectPlacements.listHelp(index,list));List<LosslessJson> placements;
        try { placements=FloatingObjectPlacements.placements(entry,list); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored list contains snippets or unsupported placements (read-only).");return; }
        if (placements==null) {
            if (ImGui.button("Initialize empty placement list")) {
                try { commitProfileChange(FloatingObjectPlacements.listPrefix(index,list),FloatingObjectPlacements.enable(selected,workspace.read(selected),index,list),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        var choices=FloatingObjectPlacements.objectChoices(workspace);String prefix=FloatingObjectPlacements.listPrefix(index,list);
        ImString object=objectChoiceState(prefix+"new.object",choices),mode=values.computeIfAbsent(prefix+"new.mode",ignored -> new ImString(FloatingObjectPlacements.creationMode(list),128));
        drawObjectChoice("Object for new placement",object,choices);
        if (ImGui.beginCombo("Mode for new placement",mode.get())) {
            for (String choice : FloatingObjectPlacements.modes()) { if (ImGui.selectable(choice,choice.equals(mode.get()))) mode.set(choice);if (ImGui.isItemHovered()) ImGui.setTooltip(FloatingObjectPlacements.modeHelp(choice)); }
            ImGui.endCombo();
        }
        if (choices.isEmpty()) ImGui.textWrapped("No exact .iob keys are available. Import an object before adding a placement.");
        ImGui.beginDisabled(!choices.contains(object.get()));
        if (ImGui.button("Add placement with selected object/mode")) { ImGui.endDisabled();changeFloatingObject(index,list,RiverProfiles.Action.ADD,-1,object.get(),mode.get());return; }
        ImGui.endDisabled();
        for (int placementIndex=0;placementIndex<placements.size();placementIndex++) {
            if (!ImGui.treeNode("Placement "+placementIndex)) continue;
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate placement")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove placement")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(placementIndex==0);if (ImGui.button("Move placement up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(placementIndex==placements.size()-1);if (ImGui.button("Move placement down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeFloatingObject(index,list,action,placementIndex,null,null);return; }
            var placement=placements.get(placementIndex);
            drawFloatingPlacementMode(placement,index,list,placementIndex);
            if (FloatingObjectPlacements.root(index) && list.equals("objects")) drawRootObjectAnchors(placement,index,placementIndex);
            for (String field : FloatingObjectPlacements.SCALARS) drawFloatingPlacementScalar(placement,index,list,placementIndex,field);
            drawFloatingPlacementSettings(placement,new FloatingPlacementSettings.Context(index,list,placementIndex));
            drawFloatingPlacementConstraints(placement,new FloatingPlacementConstraints.Context(index,list,placementIndex));
            drawFloatingPlacementEffects(placement,new FloatingPlacementEffects.Context(index,list,placementIndex));
            drawFloatingDeformation(placement,new FloatingPlacementDeformation.Context(index,list,placementIndex));
            drawFloatingReplacements(placement,new FloatingObjectReplacements.Context(index,list,placementIndex));
            drawFloatingOverrides(placement,new FloatingPlacementOverrides.Context(index,list,placementIndex));
            for (String pool : FloatingObjectPlacements.POOLS) if (ImGui.treeNode(pool)) { drawFloatingObjectPool(placement,index,list,placementIndex,pool,choices);ImGui.treePop(); }
            if (FloatingObjectPlacements.configured(list) && ImGui.treeNode("Translation")) { drawFloatingTranslation(placement,index,list,placementIndex);ImGui.treePop(); }
            if (FloatingObjectPlacements.configured(list) && ImGui.treeNode("Rotation")) { drawFloatingRotation(placement,new FloatingObjectRotations.Context(index,list,placementIndex));ImGui.treePop(); }
            if (ImGui.treeNode("Other placement data (read-only)")) { for (String field : FloatingObjectPlacements.readOnlyFields(placement,list)) ImGui.textWrapped(field);ImGui.treePop(); }
            ImGui.treePop();
        }
    }
    private void drawRootObjectAnchors(LosslessJson placement,int owner,int index) {
        if (!ImGui.treeNode("Cave anchors and bottom alignment")) return;
        ImGui.textWrapped("Cave anchors apply when carvingSupport includes carving. PROFILE_DEFAULT uses the active cave profile anchor, falling back to FLOOR. CEILING_HANG forces ceiling anchors. Anchor search, burial and containment can reject a placement.");
        String token=placement.token("caveAnchorMode");
        try {
            String current=token==null ? "Absent: "+BiomeObjectPlacements.defaultAnchor() : placement.stringToken("caveAnchorMode");
            if (ImGui.beginCombo("Cave anchor",current.length()>128 ? "Unknown stored anchor" : current)) {
                for (String choice : BiomeObjectPlacements.anchors()) {
                    if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,BiomeObjectPlacements.setAnchor(selected,workspace.read(selected),owner,index,choice)));
                    if (ImGui.isItemHovered()) ImGui.setTooltip(BiomeObjectPlacements.anchorHelp(choice));
                }
                ImGui.endCombo();
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored cave anchor has an unsupported type (read-only)."); }
        ImGui.textWrapped(BiomeObjectPlacements.bottomHelp());String bottom=placement.token("bottom");
        if (bottom==null) {
            if (ImGui.button("Enable bottom alignment (false)")) applyRoutingExtra(() -> workspace.replace(selected,BiomeObjectPlacements.enableBottom(selected,workspace.read(selected),owner,index)));
        } else if (!bottom.equals("true") && !bottom.equals("false")) ImGui.textWrapped("Stored bottom flag has an unsupported type (read-only).");
        else { ImBoolean value=new ImBoolean(Boolean.parseBoolean(bottom));if (ImGui.checkbox("Bottom alignment",value)) applyRoutingExtra(() -> workspace.replace(selected,BiomeObjectPlacements.setBottom(selected,workspace.read(selected),owner,index,value.get()))); }
        ImGui.treePop();
    }
    private void changeFloatingObject(int entry,String list,RiverProfiles.Action action,int placement,String object,String mode) {
        try { commitProfileChange(FloatingObjectPlacements.listPrefix(entry,list),FloatingObjectPlacements.change(selected,workspace.read(selected),entry,list,action,placement,object,mode,workspace),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingPlacementScalar(LosslessJson placement,int entry,String list,int index,String field) {
        ImGui.pushID(field);
        ImGui.textWrapped(field.equals("chance") ? (list.equals("objects") ? "Native surface and cave passes test chance before the density loop. Surface tests add +/-0.005 jitter; cave tests use the stored chance. A successful test permits the density attempts, not guaranteed placements." : "Floating placement tests chance per attempt with native jitter of +/-0.005. Density controls attempts, or densityStyle when configured.") : FloatingObjectPlacements.help(field));
        ImGui.textWrapped(FloatingObjectPlacements.constraints(field));
        if (field.equals("density") && placement.token("densityStyle")!=null && !placement.token("densityStyle").equals("null")) ImGui.textWrapped("A resolved non-null densityStyle overrides fixed density. Open Density range to author it or restore fixed density.");
        String token=placement.token(field),key=FloatingObjectPlacements.placementPrefix(entry,list,index)+field;
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+FloatingObjectPlacements.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectPlacements.enableScalar(selected,workspace.read(selected),entry,list,index,field)));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored scalar has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingObjectPlacements.setScalar(selected,workspace.read(selected),entry,list,index,field,input.get())));
        }
        ImGui.popID();
    }
    private void drawFloatingPlacementConstraints(LosslessJson placement,FloatingPlacementConstraints.Context context) {
        if (ImGui.treeNode("Coordinate warp")) {
            drawStyleFields(placement,context.warpTarget());
            LosslessJson warp=null;
            try { warp=NoiseStyleFields.object(placement,context.warpTarget()); } catch (IllegalArgumentException unsupported) { /* The style inspector explains unsupported parents. */ }
            if (warp!=null) {
                ImGui.textWrapped(FloatingPlacementConstraints.multiplierConstraints());String token=warp.token("multiplier"),key=context.prefix("warp")+"multiplier";
                if (token==null) {
                    if (ImGui.button("Enable warp multiplier ("+FloatingPlacementConstraints.multiplierDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementConstraints.enableMultiplier(selected,workspace.read(selected),context)));
                } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored multiplier has an unsupported type (read-only).");
                else {
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Warp multiplier",input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply warp multiplier")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementConstraints.setMultiplier(selected,workspace.read(selected),context,input.get())));
                }
            }
            ImGui.treePop();
        }
        if (FloatingObjectPlacements.configured(context.list()) && ImGui.treeNode("Placement guards")) {
            ImGui.textWrapped("These settings retain native mode-dependent behavior. Force placement can bypass clamp, slope and surface-support guards; FLOATING skips carving anchors and surface support. Support also depends on water/from-bottom settings. Effective placement is not validated or previewed.");
            ImGui.textWrapped("Slope-facing rotation requires native terrain context and replaces Y maximum/interval with a terrain direction plus the Y minimum.");
            for (String field : FloatingPlacementConstraints.FLAGS) {
                ImGui.pushID(field);ImGui.textWrapped(FloatingPlacementConstraints.help(field));String token=placement.token(field);
                if (token==null) {
                    if (ImGui.button("Enable "+field+" ("+FloatingPlacementConstraints.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementConstraints.enableFlag(selected,workspace.read(selected),context,field)));
                } else if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored flag has an unsupported type (read-only).");
                else { ImBoolean value=new ImBoolean(Boolean.parseBoolean(token));if (ImGui.checkbox(field,value)) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementConstraints.setFlag(selected,workspace.read(selected),context,field,value.get()))); }
                ImGui.popID();
            }
            for (String field : FloatingPlacementConstraints.SCALARS) {
                ImGui.pushID(field);ImGui.textWrapped(FloatingPlacementConstraints.help(field));ImGui.textWrapped(FloatingPlacementConstraints.constraints(field));String token=placement.token(field),key=context.prefix(field)+"value";
                if (token==null) {
                    if (ImGui.button("Enable "+field+" ("+FloatingPlacementConstraints.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementConstraints.enableScalar(selected,workspace.read(selected),context,field)));
                } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored scalar has an unsupported type (read-only).");
                else {
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementConstraints.setScalar(selected,workspace.read(selected),context,field,input.get())));
                }
                ImGui.popID();
            }
            String current=null;
            try { current=placement.token("carvingSupport")==null ? FloatingPlacementConstraints.defaultToken("carvingSupport") : placement.stringToken("carvingSupport"); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored carving mode has an unsupported type (read-only)."); }
            if (current!=null && ImGui.beginCombo("Carving support",current.length()>128 ? "Unknown stored mode" : current+(placement.token("carvingSupport")==null ? " (Iris default)" : ""))) {
                for (String choice : FloatingPlacementConstraints.carvingModes()) {
                    if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementConstraints.setCarving(selected,workspace.read(selected),context,choice)));
                    if (ImGui.isItemHovered()) ImGui.setTooltip(FloatingPlacementConstraints.carvingHelp(choice));
                }
                ImGui.endCombo();
            }
            for (String field : FloatingPlacementConstraints.OBJECT_FIELDS) if (ImGui.treeNode(field)) { drawFloatingConstraintBounds(placement,context,field);ImGui.treePop(); }
            ImGui.treePop();
        }
        var readOnly=FloatingPlacementConstraints.readOnlyPaths(placement,context);
        if (!readOnly.isEmpty() && ImGui.treeNode("Other guard/warp data (read-only)")) { for (String path : readOnly) ImGui.textWrapped(path);ImGui.treePop(); }
    }
    private void drawFloatingConstraintBounds(LosslessJson placement,FloatingPlacementConstraints.Context context,String field) {
        ImGui.textWrapped(FloatingPlacementConstraints.help(field));LosslessJson object;
        try { object=FloatingPlacementConstraints.object(placement,field); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored bound object is a snippet or unsupported value (read-only).");return; }
        if (object==null) {
            ImGui.textWrapped(field.equals("clamp") ? "Absent clamp uses -2048 to 2048. Creation retains those defaults." : "Absent slope uses 0 to 10, which bypasses native slope filtering. Creation retains those defaults.");
            if (ImGui.button("Create bound object with Iris defaults")) {
                try { commitProfileChange(context.prefix(field),FloatingPlacementConstraints.create(selected,workspace.read(selected),context,field),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        var members=field.equals("clamp") ? FloatingPlacementConstraints.CLAMP_FIELDS : FloatingPlacementConstraints.SLOPE_FIELDS;String minField=members.getFirst(),maxField=members.getLast(),min=object.token(minField),max=object.token(maxField);
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored bounds have unsupported types (read-only).");return; }
        ImGui.textWrapped(FloatingPlacementConstraints.boundConstraints(field,minField));
        ImGui.textWrapped("Apply both bounds together. The editor requires minimum at most maximum.");
        if (field.equals("slopeCondition")) ImGui.textWrapped("Slope minimum 0 with maximum at least 10 bypasses the native slope filter.");
        String storedMin=min==null ? FloatingPlacementConstraints.boundDefault(field,minField) : min,storedMax=max==null ? FloatingPlacementConstraints.boundDefault(field,maxField) : max;
        String minKey=context.prefix(field)+minField,maxKey=context.prefix(field)+maxField;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1)));
        ImString maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText(min==null ? "Minimum (Iris default)" : "Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum (Iris default)" : "Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply bounds")) {
            try { workspace.replace(selected,FloatingPlacementConstraints.setBounds(selected,workspace.read(selected),context,field,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    private void drawFloatingPlacementEffects(LosslessJson placement,FloatingPlacementEffects.Context context) {
        if (!ImGui.treeNode("Excavation and water")) return;
        ImGui.textWrapped("Native modes, terrain and existing blocks determine these effects. Editing extensions does not enable excavation. Object geometry and placement are not previewed.");
        for (String field : FloatingPlacementEffects.FLAGS) if (FloatingPlacementEffects.editable(context.list(),field)) {
            ImGui.pushID(field);ImGui.text(field);ImGui.textWrapped(FloatingPlacementEffects.contextHelp(context.list(),field));
            String token=placement.token(field);
            if (token==null) {
                if (ImGui.button("Enable stored "+field+" ("+FloatingPlacementEffects.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementEffects.enableFlag(selected,workspace.read(selected),context,field)));
            } else if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored flag has an unsupported type (read-only).");
            else { ImBoolean value=new ImBoolean(Boolean.parseBoolean(token));if (ImGui.checkbox(field,value)) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementEffects.setFlag(selected,workspace.read(selected),context,field,value.get()))); }
            ImGui.popID();
        }
        for (String field : FloatingPlacementEffects.SCALARS) {
            ImGui.pushID(field);ImGui.text(field);ImGui.textWrapped(FloatingPlacementEffects.contextHelp(context.list(),field));ImGui.textWrapped(FloatingPlacementEffects.constraints(field));
            String token=placement.token(field),key=context.prefix()+field;
            if (token==null) {
                if (ImGui.button("Enable "+field+" ("+FloatingPlacementEffects.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementEffects.enableScalar(selected,workspace.read(selected),context,field)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored extension has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply extension")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementEffects.setScalar(selected,workspace.read(selected),context,field,input.get())));
            }
            ImGui.popID();
        }
        ImGui.treePop();
    }
    private void drawFloatingDeformation(LosslessJson placement,FloatingPlacementDeformation.Context context) {
        for (String object : FloatingPlacementDeformation.OBJECT_FIELDS) {
            if (!ImGui.treeNode(object)) continue;
            ImGui.textWrapped(FloatingPlacementDeformation.help(object));
            ImGui.textWrapped(object.equals("stiltSettings") ? "Used by native stilt modes. Anchored collections remap classic stilt modes; their classic ranges stay read-only here. The material palette can override native stilt materials." : "Used by VACUUM modes when native terrain context is available. Radius 0 chooses the mode default (12, HIGH 20, FAST 8); FAST uses a two-block sampling step. Organic jitter and wave controls apply only to their matching modes.");
            ImGui.textWrapped("Authoring settings leaves placement mode unchanged. Deformation is not previewed or validated.");LosslessJson settings;
            try { settings=FloatingPlacementDeformation.object(placement,object); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored settings are a snippet or unsupported value (read-only).");ImGui.treePop();continue; }
            if (settings==null) {
                ImGui.textWrapped("Absent/null uses native fallback defaults. Creating an empty settings object retains class defaults.");
                if (ImGui.button("Create explicit settings")) changeFloatingDeformation(context,object,true);
                ImGui.treePop();continue;
            }
            if (ImGui.button("Restore implicit settings (null)")) { changeFloatingDeformation(context,object,false);ImGui.treePop();continue; }
            for (String field : FloatingPlacementDeformation.scalars(object)) if (FloatingPlacementDeformation.editable(context.list(),object,field)) {
                ImGui.pushID(field);ImGui.text(field);
                if (ImGui.isItemHovered()) ImGui.setTooltip(object.equals("vacuumSettings") && field.equals("waveScale") ? "Direct multiplier on world X/Z fed to native wave noise; the editor does not estimate wavelength." : FloatingPlacementDeformation.help(object,field));
                ImGui.textWrapped(FloatingPlacementDeformation.constraints(object,field));String token=settings.token(field),key=context.prefix(object)+field;
                if (token==null) {
                    if (ImGui.button("Enable ("+FloatingPlacementDeformation.defaultToken(object,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementDeformation.enableScalar(selected,workspace.read(selected),context,object,field)));
                } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored scalar has an unsupported type (read-only).");
                else {
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Value",input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementDeformation.setScalar(selected,workspace.read(selected),context,object,field,input.get())));
                }
                ImGui.popID();
            }
            if (object.equals("stiltSettings") && ImGui.treeNode("Material exclusions")) { drawFloatingStiltExclusions(settings,context);ImGui.treePop(); }
            if (object.equals("stiltSettings") && ImGui.treeNode("Stilt material palette")) { drawFloatingObjectPalette(placement,new FloatingObjectPalettes.Context(context.entry(),context.list(),context.placement(),-1));ImGui.treePop(); }
            for (String path : FloatingPlacementDeformation.readOnlyPaths(placement,context)) if (path.startsWith(object+".")) ImGui.textWrapped(path+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeFloatingDeformation(FloatingPlacementDeformation.Context context,String object,boolean enable) {
        try { byte[] current=workspace.read(selected);commitProfileChange(context.prefix(object),enable ? FloatingPlacementDeformation.enable(selected,current,context,object) : FloatingPlacementDeformation.disable(selected,current,context,object),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingStiltExclusions(LosslessJson settings,FloatingPlacementDeformation.Context context) {
        ImGui.textWrapped("Excludes material keys across all block states, before and after palette/edit replacement. Storage blocks and tile entities are always excluded. New values accept Minecraft material names only, without states; registry existence is unchecked. Existing unsupported values are preserved.");
        List<String> entries;
        try { entries=FloatingPlacementDeformation.exclusions(settings); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored exclusions are not a string array (read-only).");return; }
        if (entries==null) {
            if (ImGui.button("Enable empty authored exclusions")) {
                try { commitProfileChange(context.exclusionPrefix(),FloatingPlacementDeformation.enableExclusions(selected,workspace.read(selected),context),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (ImGui.button("Disable authored material exclusions (null)")) {
            try { commitProfileChange(context.exclusionPrefix(),FloatingPlacementDeformation.disableExclusions(selected,workspace.read(selected),context),null); }
            catch (Exception failure) { error=failure.getMessage(); }
            return;
        }
        String newKey=context.exclusionPrefix()+"new";ImString material=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New material key",material);drafts.update(newKey,"",material.get());
        ImGui.beginDisabled(!FloatingPlacementDeformation.materialSyntax(material.get()));
        if (ImGui.button("Add material exclusion")) { ImGui.endDisabled();changeFloatingExclusion(context,RiverProfiles.Action.ADD,-1,material.get(),newKey);return; }
        ImGui.endDisabled();
        if (entries.isEmpty()) ImGui.textWrapped("No authored exclusions. Add a material to populate the list.");
        for (int index=0;index<entries.size();index++) {
            ImGui.pushID(index);int selectedIndex=index;String current=entries.get(index),key=context.exclusionPrefix()+index+".value";
            if (!FloatingPlacementDeformation.materialSyntax(current)) ImGui.textWrapped("Stored exclusion has unsupported syntax/namespace and is preserved until replaced.");
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Material",input);drafts.update(key,current,input.get());
            if (ImGui.button("Apply material")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementDeformation.setExclusion(selected,workspace.read(selected),context,selectedIndex,input.get())));
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(entries.size()<=1);if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Move up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Move down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            ImGui.popID();if (action!=null) { changeFloatingExclusion(context,action,index,null,null);return; }
        }
    }
    private void changeFloatingExclusion(FloatingPlacementDeformation.Context context,RiverProfiles.Action action,int index,String material,String appliedKey) {
        try { commitProfileChange(context.exclusionPrefix(),FloatingPlacementDeformation.changeExclusion(selected,workspace.read(selected),context,action,index,material),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingObjectPalette(LosslessJson placement,FloatingObjectPalettes.Context context) {
        ImGui.textWrapped(context.stilt() ? "An explicit stilt palette replaces native source-object stilt material. The pinned consumer samples at the placement anchor. Material exclusions still apply after palette replacement." : "Replacement rules use this native weighted material palette. Matching, state merging, tile handling and sampling are not evaluated here.");
        LosslessJson palette;
        try { palette=FloatingObjectPalettes.object(placement,context); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette is a snippet or unsupported value (read-only).");return; }
        if (palette==null) {
            ImGui.textWrapped(context.stilt() ? "Absent/null keeps the source-object material rules. Creating an explicit palette uses STONE, zoom 5 and STATIC defaults." : "An absent replacement palette inherits STONE, zoom 5 and STATIC. Create it explicitly to author those settings.");
            if (ImGui.button("Create explicit material palette")) {
                try { commitProfileChange(context.prefix(),FloatingObjectPalettes.create(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (context.stilt() && ImGui.button("Restore source-object stilt material (null)")) {
            try { commitProfileChange(context.prefix(),FloatingObjectPalettes.restoreStilt(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }
            return;
        }
        drawGeneralBlockPalette(palette,context.target());
        for (String path : FloatingObjectPalettes.readOnlyPaths(placement,context)) ImGui.textWrapped(path+" (read-only)");
    }
    private void drawFloatingReplacements(LosslessJson placement,FloatingObjectReplacements.Context context) {
        if (!ImGui.treeNode("Object block replacements")) return;
        if (context.fixed()!=null) ImGui.textWrapped("Fixed object rules use this entry's seed. Replacements run on the scaled object before block-state rotation. Native loading requires nonempty find and replacement palettes; empty lists can be repaired here. No object transformation or content compatibility is evaluated.");
        ImGui.textWrapped("Rules run in stored order using native chance and state/material matching. Source .iob bytes remain unchanged. Registry, aliases, block states and effective replacements are not resolved here.");List<LosslessJson> rules;
        try { rules=FloatingObjectReplacements.rules(placement); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored rules contain snippets or unsupported values (read-only).");ImGui.treePop();return; }
        if (rules==null) {
            if (ImGui.button("Enable empty replacement list")) {
                try { commitProfileChange(context.prefix(),FloatingObjectReplacements.enable(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }
            }
            ImGui.treePop();return;
        }
        ImGui.textWrapped("New rules inherit chance 1, material-key matching and a STONE replacement palette.");
        String newKey=context.prefix()+"new.find";ImString first=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("First find block for new rule",first);drafts.update(newKey,"",first.get());
        if (ImGui.button("Add replacement rule")) { ImGui.treePop();changeFloatingReplacement(context,RiverProfiles.Action.ADD,-1,first.get(),newKey);return; }
        if (rules.isEmpty()) ImGui.textWrapped("Empty replacement list applies no rules.");
        for (int index=0;index<rules.size();index++) {
            if (!ImGui.treeNode("Rule "+index)) continue;
            int ruleIndex=index;var rule=rules.get(index);RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate rule")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove rule")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move rule up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(index==rules.size()-1);if (ImGui.button("Move rule down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();ImGui.treePop();changeFloatingReplacement(context,action,index,null,null);return; }
            String exact=rule.token("exact");ImGui.textWrapped("Exact uses native state matching; non-exact compares base material keys. No matching is computed by the editor.");
            if (exact==null) {
                if (ImGui.button("Enable state-matching flag (false)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectReplacements.enableExact(selected,workspace.read(selected),context,ruleIndex)));
            } else if (!exact.equals("true") && !exact.equals("false")) ImGui.textWrapped("Stored exact flag has an unsupported type (read-only).");
            else { ImBoolean flag=new ImBoolean(Boolean.parseBoolean(exact));if (ImGui.checkbox("Native state matching",flag)) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectReplacements.setExact(selected,workspace.read(selected),context,ruleIndex,flag.get()))); }
            String token=rule.token("chance"),key=context.rulePrefix(index)+"chance";
            if (token==null) {
                if (ImGui.button("Enable chance (1)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectReplacements.enableChance(selected,workspace.read(selected),context,ruleIndex)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored chance has an unsupported type (read-only).");
            else {
                ImGui.textWrapped("Finite chance from 0 to 1 for each native rule application.");ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Replacement chance",input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply replacement chance")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingObjectReplacements.setChance(selected,workspace.read(selected),context,ruleIndex,input.get())));
            }
            if (ImGui.treeNode("Find blocks")) { drawFloatingFindBlocks(rule,context,index);ImGui.treePop(); }
            if (ImGui.treeNode("Replacement palette")) { drawFloatingObjectPalette(placement,context.palette(index));ImGui.treePop(); }
            for (String field : rule.fields()) if (!FloatingObjectReplacements.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
            ImGui.treePop();
        }
        ImGui.treePop();
    }
    private void changeFloatingReplacement(FloatingObjectReplacements.Context context,RiverProfiles.Action action,int rule,String first,String appliedKey) {
        try { commitProfileChange(context.prefix(),FloatingObjectReplacements.change(selected,workspace.read(selected),context,action,rule,first),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingFindBlocks(LosslessJson rule,FloatingObjectReplacements.Context context,int ruleIndex) {
        ImGui.textWrapped("Find blocks are unweighted native block-data matches. Nonblank text is accepted; registry/state resolution is pending. Advanced find data, including weight, stays read-only.");List<LosslessJson> entries;
        try { entries=FloatingObjectReplacements.find(rule); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored find entries contain snippets or unsupported values (read-only).");return; }
        if (entries==null) {
            if (ImGui.button("Enable empty find list")) {
                try { commitProfileChange(context.findPrefix(ruleIndex),FloatingObjectReplacements.enableFind(selected,workspace.read(selected),context,ruleIndex),null); } catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String newKey=context.findPrefix(ruleIndex)+"new.block";ImString block=values.computeIfAbsent(newKey,ignored -> new ImString("",4096));ImGui.inputText("New find block",block);drafts.update(newKey,"",block.get());
        if (ImGui.button("Add find block")) { changeFloatingFind(context,ruleIndex,RiverProfiles.Action.ADD,-1,block.get(),newKey);return; }
        if (entries.isEmpty()) ImGui.textWrapped("Empty find list matches no blocks. Add a block to repair the required list.");
        for (int index=0;index<entries.size();index++) {
            ImGui.pushID(index);int findIndex=index;var entry=entries.get(index);
            try {
                String stored=entry.token("block")==null ? "" : entry.stringToken("block"),key=context.findPrefix(ruleIndex)+index+".block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(entry.token("block")==null ? "Missing find block" : "Find block",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply find block")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingObjectReplacements.setFindBlock(selected,workspace.read(selected),context,ruleIndex,findIndex,input.get())));
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored find block has an unsupported type (read-only)."); }
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate find")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(entries.size()<=1);if (ImGui.button("Remove find")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Find up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Find down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            for (String field : entry.fields()) if (!field.equals("block")) ImGui.textWrapped(field+" (read-only)");
            ImGui.popID();if (action!=null) { changeFloatingFind(context,ruleIndex,action,index,null,null);return; }
        }
    }
    private void changeFloatingFind(FloatingObjectReplacements.Context context,int rule,RiverProfiles.Action action,int index,String block,String appliedKey) {
        try { commitProfileChange(context.findPrefix(rule),FloatingObjectReplacements.changeFind(selected,workspace.read(selected),context,rule,action,index,block),appliedKey); } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingOverrides(LosslessJson placement,FloatingPlacementOverrides.Context context) {
        if (!ImGui.treeNode("Placement overrides")) return;
        if (FloatingObjectPlacements.configured(context.list()) && ImGui.treeNode("Force placement")) {
            ImGui.textWrapped("Force affects selected native guards. Hydrology, structure and block-specific paths can still veto placement. Anchored collections set force internally and keep it read-only here.");
            String key=null;boolean ambiguous=false;
            try { key=FloatingPlacementOverrides.forceKey(placement); } catch (IllegalArgumentException unsupported) { ambiguous=true;ImGui.textWrapped(unsupported.getMessage()); }
            if (!ambiguous) {
                if (key==null) {
                    if (ImGui.button("Enable stored forcePlace (false)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementOverrides.enableForce(selected,workspace.read(selected),context)));
                } else {
                    String token=placement.token(key);
                    if (!token.equals("true") && !token.equals("false")) ImGui.textWrapped("Stored native force flag is not boolean (read-only).");
                    else { ImBoolean enabled=new ImBoolean(Boolean.parseBoolean(token));if (ImGui.checkbox("Native force (stored as "+key+")",enabled)) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementOverrides.setForce(selected,workspace.read(selected),context,enabled.get()))); }
                }
            }
            ImGui.treePop();
        }
        if (ImGui.treeNode("Virtual heightmap")) {
            ImGui.textWrapped(FloatingPlacementOverrides.heightmapHelp());
            ImGui.textWrapped("Replaces native height queries with rounded noise samples; explicit/absolute anchor modes keep their own anchor rules. Noise disabled outputs offsetY. Restore null to use the underlying height queries. No noise or world-height validity is evaluated here.");
            LosslessJson heightmap=null;boolean unsupported=false;
            try { heightmap=FloatingPlacementOverrides.heightmap(placement); } catch (IllegalArgumentException invalid) { unsupported=true;ImGui.textWrapped("Stored heightmap is a snippet or unsupported value (read-only)."); }
            if (!unsupported) {
                if (heightmap==null) {
                    ImGui.textWrapped("Creating {} uses Iris noise defaults: IRIS style, zoom 1, opacity 1 and offsetY 0. This is a noise-height override, not a sampled terrain heightmap.");
                    if (ImGui.button("Create explicit virtual heightmap")) changeFloatingHeightmap(context,true);
                } else if (ImGui.button("Restore underlying height queries (null)")) changeFloatingHeightmap(context,false);
                else {
                    drawNoiseFields(heightmap,context.noiseTarget());
                    for (String path : FloatingPlacementOverrides.readOnlyHeightmapPaths(placement)) ImGui.textWrapped(path+" (read-only)");
                }
            }
            ImGui.treePop();
        }
        ImGui.treePop();
    }
    private void changeFloatingHeightmap(FloatingPlacementOverrides.Context context,boolean enable) {
        try { byte[] current=workspace.read(selected);commitProfileChange(context.heightmapPrefix(),enable ? FloatingPlacementOverrides.enableHeightmap(selected,current,context) : FloatingPlacementOverrides.restoreHeightmap(selected,current,context),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingPlacementMode(LosslessJson placement,int entry,String list,int index) {
        String current;
        try { current=placement.token("mode")==null ? FloatingObjectPlacements.defaultToken("mode") : placement.stringToken("mode"); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored mode is not a string (read-only).");return; }
        if (!FloatingObjectPlacements.modes().contains(current)) ImGui.textWrapped("Unknown stored mode is retained until an explicit selection.");
        if (ImGui.beginCombo("Placement mode",current.length()>128 ? "Unknown stored mode" : current+(placement.token("mode")==null ? " (Iris default)" : ""))) {
            for (String choice : FloatingObjectPlacements.modes()) {
                if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectPlacements.setMode(selected,workspace.read(selected),entry,list,index,choice)));
                if (ImGui.isItemHovered()) ImGui.setTooltip(FloatingObjectPlacements.modeHelp(choice));
            }
            ImGui.endCombo();
        }
    }
    private void drawFloatingPlacementSettings(LosslessJson placement,FloatingPlacementSettings.Context context) {
        for (String field : FloatingPlacementSettings.OBJECT_FIELDS) {
            if (!ImGui.treeNode(field.equals("scale") ? "Object scale" : "Density range")) continue;
            ImGui.textWrapped(FloatingPlacementSettings.help(field));LosslessJson object;
            try { object=FloatingPlacementSettings.object(placement,field); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored setting is a snippet or unsupported value (read-only).");ImGui.treePop();continue; }
            if (object==null) {
                ImGui.textWrapped(field.equals("scale") ? "Absent/null uses the dimension's allObjectScaleFactor. Creating an explicit scale, even an empty object, overrides that factor with class defaults: size 1, range 1 to 1, 7 variants, NONE interpolation." : "Absent/null uses fixed density. Creating a range uses Iris defaults: 16 to 32, STATIC style. The sampled result is rounded for placement attempts.");
                if (ImGui.button("Create explicit "+field)) changeFloatingPlacementSetting(context,field,true);
                ImGui.treePop();continue;
            }
            if (ImGui.button(field.equals("scale") ? "Restore dimension scale fallback" : "Restore fixed density")) { changeFloatingPlacementSetting(context,field,false);ImGui.treePop();continue; }
            if (field.equals("densityStyle")) {
                ImGui.textWrapped("Density is sampled once per chunk for this placement. Editor bounds: 0 to 2147483647, ordered and finite. These are editor limits; Iris styled ranges have no annotated bounds. Zero attempts disables placement. Full generation cost and native validity are unchecked.");
                drawFloatingPlacementBounds(object,context,false);
                if (ImGui.treeNode("Density generator style")) { drawStyleFields(object,context.styleTarget());ImGui.treePop(); }
            } else {
                ImGui.textWrapped("Size other than 1 selects a fixed scale and overrides the range. At size 1, Iris selects cached variants across the range; its upper endpoint is not included when the bounds differ. Floating-entry shrink factors, when present, apply afterward. Scaling is not previewed.");
                for (String member : List.of("size","variations")) drawFloatingScaleScalar(object,context,member);
                drawFloatingPlacementBounds(object,context,true);
                String current;
                try { current=object.token("interpolation")==null ? FloatingPlacementSettings.scaleDefault("interpolation") : object.stringToken("interpolation"); }
                catch (IllegalArgumentException unsupported) { current=null;ImGui.textWrapped("Stored interpolation has an unsupported type (read-only)."); }
                if (current!=null && ImGui.beginCombo("Upscale interpolation",current.length()>128 ? "Unknown stored interpolation" : current+(object.token("interpolation")==null ? " (Iris default)" : ""))) {
                    for (String choice : FloatingPlacementSettings.interpolations()) {
                        if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementSettings.setInterpolation(selected,workspace.read(selected),context,choice)));
                        if (ImGui.isItemHovered()) ImGui.setTooltip(FloatingPlacementSettings.interpolationHelp(choice));
                    }
                    ImGui.endCombo();
                }
            }
            for (String path : FloatingPlacementSettings.readOnlyPaths(placement)) if (path.equals(field) || path.startsWith(field+".")) ImGui.textWrapped(path+" (read-only)");
            ImGui.treePop();
        }
    }
    private void changeFloatingPlacementSetting(FloatingPlacementSettings.Context context,String field,boolean enable) {
        try { byte[] current=workspace.read(selected);commitProfileChange(context.prefix(field),enable ? FloatingPlacementSettings.enable(selected,current,context,field) : FloatingPlacementSettings.disable(selected,current,context,field),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingScaleScalar(LosslessJson scale,FloatingPlacementSettings.Context context,String field) {
        ImGui.pushID(field);ImGui.textWrapped(FloatingPlacementSettings.scaleHelp(field));ImGui.textWrapped(FloatingPlacementSettings.scaleConstraints(field));
        String token=scale.token(field),key=context.prefix("scale")+field;
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+FloatingPlacementSettings.scaleDefault(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingPlacementSettings.enableScaleScalar(selected,workspace.read(selected),context,field)));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored scalar has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingPlacementSettings.setScaleScalar(selected,workspace.read(selected),context,field,input.get())));
        }
        ImGui.popID();
    }
    private void drawFloatingPlacementBounds(LosslessJson object,FloatingPlacementSettings.Context context,boolean scale) {
        String field=scale ? "scale" : "densityStyle",minField=scale ? "minimumScale" : "min",maxField=scale ? "maximumScale" : "max";
        String min=object.token(minField),max=object.token(maxField);
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored range bounds have unsupported types (read-only).");return; }
        String storedMin=min!=null ? min : scale ? FloatingPlacementSettings.scaleDefault(minField) : FloatingPlacementSettings.rangeDefault(minField);
        String storedMax=max!=null ? max : scale ? FloatingPlacementSettings.scaleDefault(maxField) : FloatingPlacementSettings.rangeDefault(maxField);
        String minKey=context.prefix(field)+minField,maxKey=context.prefix(field)+maxField;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1)));
        ImString maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        if (scale) ImGui.textWrapped("Editor range: ordered finite scale factors from 0.01 to 50. Iris accepts reversed ranges; the editor authors ordered pairs.");
        ImGui.inputText(min==null ? "Minimum (Iris default)" : "Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum (Iris default)" : "Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply range bounds")) {
            try {
                byte[] current=workspace.read(selected);workspace.replace(selected,scale ? FloatingPlacementSettings.setScaleBounds(selected,current,context,minimum.get(),maximum.get()) : FloatingPlacementSettings.setDensityBounds(selected,current,context,minimum.get(),maximum.get()));
                drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null;
            } catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    private void drawFloatingObjectPool(LosslessJson placement,int entry,String list,int index,String pool,List<String> choices) {
        ImGui.textWrapped(FloatingObjectPlacements.help(pool));List<String> references;
        try { references=FloatingObjectPlacements.pool(placement,pool); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored pool is not a string array (read-only).");return; }
        if (references==null) {
            if (ImGui.button("Enable empty reference pool")) {
                try { commitProfileChange(FloatingObjectPlacements.poolPrefix(entry,list,index,pool),FloatingObjectPlacements.enablePool(selected,workspace.read(selected),entry,list,index,pool),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        ImString object=objectChoiceState(FloatingObjectPlacements.poolPrefix(entry,list,index,pool)+"new.object",choices);drawObjectChoice("New object reference",object,choices);
        ImGui.beginDisabled(!choices.contains(object.get()));
        if (ImGui.button("Add reference")) { ImGui.endDisabled();changeFloatingObjectPool(entry,list,index,pool,RiverProfiles.Action.ADD,-1,object.get());return; }
        ImGui.endDisabled();
        if (pool.equals("place") && references.isEmpty()) ImGui.textWrapped("Empty place pool selects no object. Add a reference to configure this placement.");
        for (int referenceIndex=0;referenceIndex<references.size();referenceIndex++) {
            ImGui.pushID(referenceIndex);int selectedReference=referenceIndex;String current=references.get(referenceIndex);
            if (!choices.contains(current)) ImGui.textWrapped("Stored reference is missing from the exact .iob catalog and is preserved until replaced.");
            if (ImGui.beginCombo("Object reference",current.length()>128 ? "Stored object reference" : current)) {
                for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectPlacements.setReference(selected,workspace.read(selected),entry,list,index,pool,selectedReference,choice,workspace)));
                ImGui.endCombo();
            }
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate reference")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(pool.equals("place") && references.size()<=1);if (ImGui.button("Remove reference")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(referenceIndex==0);if (ImGui.button("Move reference up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(referenceIndex==references.size()-1);if (ImGui.button("Move reference down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            ImGui.popID();if (action!=null) { changeFloatingObjectPool(entry,list,index,pool,action,referenceIndex,null);return; }
        }
    }
    private void changeFloatingObjectPool(int entry,String list,int index,String pool,RiverProfiles.Action action,int reference,String object) {
        try { commitProfileChange(FloatingObjectPlacements.poolPrefix(entry,list,index,pool),FloatingObjectPlacements.changePool(selected,workspace.read(selected),entry,list,index,pool,action,reference,object,workspace),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingTranslation(LosslessJson placement,int entry,String list,int index) {
        ImGui.textWrapped("Configured translation is retained in this collection. FLOATING uses translate.y for absolute height on native mode-based anchor paths; explicit cave anchors and floor alignment retain their own rules. Anchored floating top/bottom collections replace transforms.");
        LosslessJson translate;
        try { translate=FloatingObjectPlacements.translate(placement); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored translation is a snippet or unsupported value (read-only).");return; }
        if (translate==null) {
            if (ImGui.button("Create translation (source defaults)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectPlacements.createTranslation(selected,workspace.read(selected),entry,list,index)));
            return;
        }
        for (String axis : FloatingObjectPlacements.TRANSLATION_FIELDS) {
            ImGui.pushID(axis);ImGui.textWrapped(FloatingObjectPlacements.translationHelp(axis));ImGui.textWrapped(FloatingObjectPlacements.translationConstraints(axis));String token=translate.token(axis),key=FloatingObjectPlacements.placementPrefix(entry,list,index)+"translate."+axis;
            if (token==null) {
                if (ImGui.button("Enable "+axis+" ("+FloatingObjectPlacements.translationDefault(axis)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectPlacements.enableTranslation(selected,workspace.read(selected),entry,list,index,axis)));
            } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored shift has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(axis,input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply shift")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingObjectPlacements.setTranslation(selected,workspace.read(selected),entry,list,index,axis,input.get())));
            }
            ImGui.popID();
        }
        for (String field : translate.fields()) if (!FloatingObjectPlacements.TRANSLATION_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
    }
    private void drawFloatingRotation(LosslessJson placement,FloatingObjectRotations.Context context) {
        ImGui.textWrapped(FloatingObjectRotations.help());
        ImGui.textWrapped("This collection preserves configured rotation. Anchored top/bottom collections replace it. Rotation changes placement geometry and block states; native rendering and placement are not previewed.");
        if ("true".equals(placement.token("rotateTowardsSlope"))) ImGui.textWrapped("rotateTowardsSlope is enabled: Iris derives Y rotation from terrain and the Y minimum, replacing its maximum and interval. Author that option under Placement guards.");
        LosslessJson rotation;
        try { rotation=FloatingObjectRotations.object(placement); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored rotation is a snippet or unsupported value (read-only).");return; }
        if (rotation==null) {
            ImGui.textWrapped("Absent rotation is enabled in Iris, with X/Z disabled and Y enabled in 90-degree increments. Create explicit rotation to author these settings.");
            if (ImGui.button("Create rotation with Iris defaults")) {
                try { commitProfileChange(context.prefix(),FloatingObjectRotations.create(selected,workspace.read(selected),context),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String enabled=rotation.token("enabled");
        if (enabled==null) {
            if (ImGui.button("Enable stored rotation flag (true)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectRotations.enableRotationFlag(selected,workspace.read(selected),context)));
        } else if (!enabled.equals("true") && !enabled.equals("false")) ImGui.textWrapped("Stored rotation flag has an unsupported type (read-only).");
        else {
            ImBoolean flag=new ImBoolean(Boolean.parseBoolean(enabled));
            if (ImGui.checkbox("Rotation enabled",flag)) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectRotations.setRotationFlag(selected,workspace.read(selected),context,flag.get())));
        }
        for (String axis : FloatingObjectRotations.AXES) if (ImGui.treeNode(axis)) {
            drawFloatingRotationAxis(rotation,context,axis);ImGui.treePop();
        }
        for (String path : FloatingObjectRotations.readOnlyPaths(placement)) ImGui.textWrapped(path+" (read-only)");
    }
    private void drawFloatingRotationAxis(LosslessJson rotation,FloatingObjectRotations.Context context,String axis) {
        ImGui.textWrapped(FloatingObjectRotations.axisHelp(axis));LosslessJson object;
        try { object=FloatingObjectRotations.axisObject(rotation,axis); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored axis is a snippet or unsupported value (read-only).");return; }
        if (object==null) {
            ImGui.textWrapped(axis.equals("yAxis") ? "Absent Y is enabled with interval 90. Creating it preserves those defaults explicitly. An existing empty axis instead uses class defaults: disabled, interval 0." : "Absent axis is disabled with zero angle bounds and interval 0. Creating it retains those defaults.");
            if (ImGui.button("Create axis with Iris defaults")) {
                try { commitProfileChange(context.axisPrefix(axis),FloatingObjectRotations.createAxis(selected,workspace.read(selected),context,axis),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        String enabled=object.token("enabled");
        if (enabled==null) {
            ImGui.textWrapped("An absent enabled flag in an existing axis defaults to false.");
            if (ImGui.button("Enable stored axis flag (false)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectRotations.enableAxisField(selected,workspace.read(selected),context,axis,"enabled")));
        } else if (!enabled.equals("true") && !enabled.equals("false")) ImGui.textWrapped("Stored axis flag has an unsupported type (read-only).");
        else {
            ImBoolean flag=new ImBoolean(Boolean.parseBoolean(enabled));
            if (ImGui.checkbox("Axis enabled",flag)) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectRotations.setAxisFlag(selected,workspace.read(selected),context,axis,flag.get())));
        }
        ImGui.textWrapped("Angle bounds 0/0 allow any angle. Equal nonzero bounds lock the angle. The editor writes ordered bounds; Iris also accepts reversed ranges.");
        drawFloatingRotationAngles(object,context,axis);
        ImGui.textWrapped(FloatingObjectRotations.fieldHelp("interval"));ImGui.textWrapped(FloatingObjectRotations.constraints("interval"));
        String token=object.token("interval"),key=context.axisPrefix(axis)+"interval";
        if (token==null) {
            if (ImGui.button("Enable interval (0)")) applyRoutingExtra(() -> workspace.replace(selected,FloatingObjectRotations.enableAxisField(selected,workspace.read(selected),context,axis,"interval")));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored interval has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Interval",input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply interval")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingObjectRotations.setInterval(selected,workspace.read(selected),context,axis,input.get())));
        }
    }
    private void drawFloatingRotationAngles(LosslessJson object,FloatingObjectRotations.Context context,String axis) {
        String min=object.token("min"),max=object.token("max");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored angle bounds have unsupported types (read-only).");return; }
        String storedMin=min==null ? FloatingObjectRotations.axisDefault("min") : min,storedMax=max==null ? FloatingObjectRotations.axisDefault("max") : max;
        String minKey=context.axisPrefix(axis)+"min",maxKey=context.axisPrefix(axis)+"max";
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1)));
        ImString maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.textWrapped(FloatingObjectRotations.constraints("min"));
        ImGui.inputText(min==null ? "Minimum (Iris default)" : "Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum (Iris default)" : "Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply angle bounds")) {
            try {
                workspace.replace(selected,FloatingObjectRotations.setAngles(selected,workspace.read(selected),context,axis,minimum.get(),maximum.get()));
                drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null;
            } catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    private void drawFloatingBottomPalette(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Custom underside palette layers")) return;
        ImGui.textWrapped(FloatingBottomPalettes.description());
        ImGui.textWrapped("Used only in CUSTOM mode, nearer the underside than the top. Empty or null layers fall back to the existing top palette from the underside. This editor leaves bottomPaletteMode unchanged. Null slope conditions bypass the gate; no slopes or materials are sampled here.");
        drawFloatingBottomLayers(entry,index);ImGui.treePop();
    }
    private void drawFloatingBottomLayers(LosslessJson entry,int index) {
        List<LosslessJson> layers;
        try { layers=FloatingBottomPalettes.layers(entry); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored underside list contains snippets or unsupported entries (read-only).");return; }
        if (layers==null) {
            if (ImGui.button("Enable empty underside list (source default)")) {
                try { commitProfileChange(FloatingBottomPalettes.listPrefix(index),FloatingBottomPalettes.enable(selected,workspace.read(selected),index),null); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            return;
        }
        if (ImGui.button("Add underside layer (GRASS_BLOCK)")) { changeFloatingBottomLayer(index,RiverProfiles.Action.ADD,-1);return; }
        for (int layerIndex=0;layerIndex<layers.size();layerIndex++) {
            if (!ImGui.treeNode("Underside layer "+layerIndex)) continue;
            int selectedLayer=layerIndex;RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate layer")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();if (ImGui.button("Remove layer")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(layerIndex==0);if (ImGui.button("Move layer up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
            ImGui.sameLine();ImGui.beginDisabled(layerIndex==layers.size()-1);if (ImGui.button("Move layer down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeFloatingBottomLayer(index,action,layerIndex);return; }
            var layer=layers.get(layerIndex);String prefix=FloatingBottomPalettes.layerPrefix(index,layerIndex);
            drawThicknessBounds(layer,prefix,(minimum,maximum) -> workspace.replace(selected,FloatingBottomPalettes.setThickness(selected,workspace.read(selected),index,selectedLayer,minimum,maximum)));
            if (ImGui.treeNode("Slope condition")) {
                drawSlopeCondition(layer,prefix,
                        () -> workspace.replace(selected,FloatingBottomPalettes.createSlope(selected,workspace.read(selected),index,selectedLayer)),
                        (minimum,maximum) -> workspace.replace(selected,FloatingBottomPalettes.setSlope(selected,workspace.read(selected),index,selectedLayer,minimum,maximum)),true);
                ImGui.treePop();
            }
            if (ImGui.treeNode("Layer block palette")) { drawGeneralBlockPalette(layer,GeneralBlockPalettes.Target.floatingBottom(index,layerIndex));ImGui.treePop(); }
            ImGui.treePop();
        }
        if (ImGui.treeNode("Other underside data (read-only)")) { for (String path : FloatingBottomPalettes.readOnlyPaths(entry,index)) ImGui.textWrapped(path);ImGui.treePop(); }
    }
    private void changeFloatingBottomLayer(int index,RiverProfiles.Action action,int layer) {
        try { commitProfileChange(FloatingBottomPalettes.listPrefix(index),FloatingBottomPalettes.change(selected,workspace.read(selected),index,action,layer),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingNoise(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Floating noise styles")) return;
        ImGui.textWrapped("Style effects depend on top-shape mode, edge amplitude and carving references. A configured carving reference overrides direct carveStyle noise. These controls do not sample noise or enable world previews.");
        for (String field : FloatingNoiseStyles.EDITABLE) {
            if (!ImGui.treeNode(field)) continue;
            var target=NoiseStyleFields.Target.floating(index,field);drawStyleFields(entry,target);
            if (target.optionalFloating()) {
                try {
                    if (NoiseStyleFields.object(entry,target)!=null) {
                        ImGui.textWrapped("Disable replaces this entire style object with null.");
                        if (ImGui.button("Disable style (write null)")) {
                            try { commitProfileChange(target.draftPrefix(),FloatingNoiseStyles.disable(selected,workspace.read(selected),index,field),null); }
                            catch (Exception failure) { error=failure.getMessage(); }
                        }
                    }
                } catch (IllegalArgumentException unsupported) { /* The shared inspector already explains the read-only type. */ }
            }
            ImGui.treePop();
        }
        ImGui.treePop();
    }
    private void changeFloatingBiome(RiverProfiles.Action action,int index) {
        try { commitProfileChange(FloatingBiomes.prefix(),FloatingBiomes.change(selected,workspace.read(selected),action,index),null); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawFloatingBiomeTarget(LosslessJson entry,int index) {
        ImGui.textWrapped(FloatingBiomes.help("biome"));String current;
        try { current=entry.token("biome")==null || entry.token("biome").equals("null") ? "" : entry.stringToken("biome"); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored target biome is not a string or null (read-only).");return; }
        var choices=FloatingBiomes.biomeChoices(workspace);
        if (!current.isEmpty() && !choices.contains(current)) ImGui.textWrapped("Stored target is missing from this pack; it is retained until you choose Self or an exact pack key.");
        if (ImGui.beginCombo("Target biome",current.isEmpty() ? "Self (parent biome)" : current.length()>128 ? "Stored target" : current)) {
            if (ImGui.selectable("Self (parent biome)",current.isEmpty())) applyRoutingExtra(() -> workspace.replace(selected,FloatingBiomes.setBiome(selected,workspace.read(selected),index,"",workspace)));
            for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingBiomes.setBiome(selected,workspace.read(selected),index,choice,workspace)));
            ImGui.endCombo();
        }
    }
    private void drawFloatingScalar(LosslessJson entry,int index,String field) {
        ImGui.pushID(field);ImGui.textWrapped(FloatingBiomes.help(field));ImGui.textWrapped(FloatingBiomes.constraints(field));
        String token=entry.token(field),key=FloatingBiomes.entryPrefix(index)+field;
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+FloatingBiomes.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,FloatingBiomes.enableScalar(selected,workspace.read(selected),index,field)));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored value has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingBiomes.setScalar(selected,workspace.read(selected),index,field,input.get())));
        }
        ImGui.popID();
    }
    private void drawFloatingPair(LosslessJson entry,int index,String minField,String maxField,boolean optional) {
        if (!ImGui.treeNode(optional ? "Optional absolute Y limits" : minField.equals("minHeightAboveSurface") ? "Base altitude (absolute world Y)" : "Bottom depth")) return;
        if (optional) ImGui.textWrapped("The minimum clips the island bottom upward; the maximum clips its top downward.");
        else { ImGui.textWrapped(FloatingBiomes.help(minField));ImGui.textWrapped(FloatingBiomes.help(maxField)); }
        ImGui.textWrapped(optional ? "Exact signed 32-bit integers. Blank means no limit. The editor requires minimum <= maximum when both limits are set; world compatibility is not resolved here." : "Apply both exact integer bounds together. The editor requires minimum <= maximum. Base altitude is 0-2032; bottom depth is 0-512.");
        String min=entry.token(minField),max=entry.token(maxField);
        if (min!=null && !(optional && min.equals("null")) && !LosslessJson.numericToken(min) || max!=null && !(optional && max.equals("null")) && !LosslessJson.numericToken(max)) ImGui.textWrapped("Stored bounds have an unsupported type (read-only).");
        else {
            String minKey=FloatingBiomes.entryPrefix(index)+minField,maxKey=FloatingBiomes.entryPrefix(index)+maxField,storedMin=min==null || optional && min.equals("null") ? optional ? "" : FloatingBiomes.defaultToken(minField) : min,storedMax=max==null || optional && max.equals("null") ? optional ? "" : FloatingBiomes.defaultToken(maxField) : max;
            ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Minimum",minimum);drafts.update(minKey,storedMin,minimum.get());ImGui.inputText("Maximum",maximum);drafts.update(maxKey,storedMax,maximum.get());
            if (ImGui.button("Apply paired bounds")) {
                try {
                    byte[] next=optional ? FloatingBiomes.setAbsoluteBounds(selected,workspace.read(selected),index,minimum.get(),maximum.get()) : FloatingBiomes.setPair(selected,workspace.read(selected),index,minField.equals("minHeightAboveSurface"),minimum.get(),maximum.get());
                    workspace.replace(selected,next);drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null;
                } catch (Exception failure) { error=failure.getMessage(); }
            }
        }
        ImGui.treePop();
    }
    private void drawFloatingMode(LosslessJson entry,int index,String field) {
        ImGui.pushID(field);ImGui.textWrapped(FloatingBiomes.help(field));String current;
        try { current=entry.token(field)==null ? FloatingBiomes.defaultToken(field) : entry.stringToken(field); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored mode is not a string (read-only).");ImGui.popID();return; }
        if (!FloatingBiomes.choices(field).contains(current)) ImGui.textWrapped("Unknown stored mode is retained until an explicit replacement.");
        if (ImGui.beginCombo(field,current.length()>128 ? "Unknown stored mode" : current)) {
            for (String choice : FloatingBiomes.choices(field)) {
                if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,FloatingBiomes.setMode(selected,workspace.read(selected),index,field,choice)));
                if (ImGui.isItemHovered()) ImGui.setTooltip(FloatingBiomes.choiceHelp(field,choice));
            }
            ImGui.endCombo();
        }
        ImGui.popID();
    }
    private void drawFloatingLiteral(LosslessJson entry,int index,String field) {
        ImGui.pushID(field);ImGui.textWrapped(FloatingBiomes.help(field));
        ImGui.textWrapped(field.equals("carving") ? "Literal dimension carving ID or biome key; dimension IDs resolve first in Iris. Empty disables the reference. Resolution is not performed here." : "Block names require nonblank text; registry/state/fluid validation remains pending.");
        try {
            String token=entry.token(field),stored=token==null ? FloatingBiomes.defaultToken(field) : field.equals("carving") && token.equals("null") ? "" : entry.stringToken(field),key=FloatingBiomes.entryPrefix(index)+field;
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,stored,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingBiomes.setLiteral(selected,workspace.read(selected),index,field,input.get())));
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value has an unsupported type (read-only)."); }
        ImGui.popID();
    }
    private void drawFloatingFluidHeight(LosslessJson entry,int index) {
        ImGui.textWrapped(FloatingBiomes.help("localFluidHeight"));String token=entry.token("localFluidHeight");
        if (token!=null && !token.equals("null") && !LosslessJson.numericToken(token)) { ImGui.textWrapped("Stored fluid height is not numeric/null (read-only).");return; }
        String key=FloatingBiomes.entryPrefix(index)+"localFluidHeight",stored=token==null || token.equals("null") ? "" : token;
        ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Local fluid height (blank = no internal water)",input);drafts.update(key,stored,input.get());
        if (ImGui.button("Apply fluid height")) commitRiverOverride(key,() -> workspace.replace(selected,FloatingBiomes.setFluidHeight(selected,workspace.read(selected),index,input.get())));
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
        drawThicknessBounds(layer,BiomeLayers.layerPrefix(list,index),
                (minimum,maximum) -> workspace.replace(selected,BiomeLayers.setThickness(selected,workspace.read(selected),list,index,minimum,maximum)));
    }
    private void drawThicknessBounds(LosslessJson layer,String prefix,BoundWriter writer) {
        String min=layer.token("minHeight"),max=layer.token("maxHeight");
        if (min!=null && !LosslessJson.numericToken(min) || max!=null && !LosslessJson.numericToken(max)) { ImGui.textWrapped("Stored thickness has an unsupported type (read-only).");return; }
        String minKey=prefix+"minHeight",maxKey=prefix+"maxHeight",storedMin=min==null ? BiomeLayers.defaultHeight("minHeight") : min,storedMax=max==null ? BiomeLayers.defaultHeight("maxHeight") : max;
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(storedMin,Math.max(128,storedMin.getBytes(StandardCharsets.UTF_8).length+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(storedMax,Math.max(128,storedMax.getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.textWrapped("Thickness: exact integers; minimum 0-2032, maximum 1-2032. The editor requires minimum <= maximum and applies both together.");
        ImGui.inputText(min==null ? "Minimum thickness (Iris default)" : "Minimum thickness",minimum);drafts.update(minKey,storedMin,minimum.get());
        ImGui.inputText(max==null ? "Maximum thickness (Iris default)" : "Maximum thickness",maximum);drafts.update(maxKey,storedMax,maximum.get());
        if (ImGui.button("Apply layer thickness")) {
            try { writer.apply(minimum.get(),maximum.get());drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; }
            catch (Exception failure) { error=failure.getMessage(); }
        }
    }
    @FunctionalInterface
    private interface BoundWriter { void apply(String minimum,String maximum) throws Exception; }
    private void drawLayerSlope(LosslessJson layer,String list,int index) {
        drawSlopeCondition(layer,BiomeLayers.layerPrefix(list,index),
                () -> workspace.replace(selected,BiomeLayers.createSlope(selected,workspace.read(selected),list,index)),
                (minimum,maximum) -> workspace.replace(selected,BiomeLayers.setSlope(selected,workspace.read(selected),list,index,minimum,maximum)));
    }
    private void drawSlopeCondition(LosslessJson layer,String prefix,InspectorDrafts.Change create,BoundWriter writer) {
        drawSlopeCondition(layer,prefix,create,writer,false);
    }
    private void drawSlopeCondition(LosslessJson layer,String prefix,InspectorDrafts.Change create,BoundWriter writer,boolean nullBypassesGate) {
        LosslessJson slope;
        try { slope=nullBypassesGate && "null".equals(layer.token("slopeCondition")) ? null : BiomeLayers.slope(layer); }
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
        for (String item : palette.fields()) if (!target.layer() && !target.slabWall() && !target.floatingBottom() && !RiverMaterialFields.PALETTE_FIELDS.contains(item)) ImGui.textWrapped(item+" (read-only)");
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
        if (entries.isEmpty()) ImGui.textWrapped(target.ore() ? "Empty palette: this ore generator produces no blocks." : target.slabWall() ? "Empty palette: no material is selected. Add a block to author a material palette." : "This list is empty. Add a block to repair the required list.");
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
                if (DimensionFields.EDITABLE.isEmpty() || BiomeFields.EDITABLE.isEmpty() || GeneratorFields.EDITABLE.isEmpty() || RegionFields.EDITABLE.isEmpty() || NoiseFields.EDITABLE.isEmpty() || NoiseStyleFields.choices().isEmpty() || BiomeGeneratorLinks.minimumEntries() != 1 || BiomeGeneratorLinks.defaultGenerator() == null || BiomeChildren.description().isEmpty() || Terrain3DFields.EDITABLE.isEmpty() || CaveProfileFields.EDITABLE.isEmpty() || CavePlacementFields.choices("defaultObjectAnchor").isEmpty() || RiverPolicyFields.choices("placement").isEmpty() || RiverPolicyFields.choices("routing").isEmpty() || RiverBiomeLists.EDITABLE.isEmpty() || RiverIdLists.EDITABLE.isEmpty() || HydrologyFields.ROUTING_FIELDS.isEmpty() || RoutingExtras.outletChoices().isEmpty() || RiverGeometryFields.GEOMETRY_FIELDS.isEmpty() || SurfaceRiverFields.SECTIONS.isEmpty() || SurfacePondFields.CONTEXTS.isEmpty() || UndergroundRiverFields.ROOT_SCALARS.isEmpty() || GrottoFields.SECTIONS.isEmpty() || SeaCaveFields.EDITABLE.isEmpty() || RiverMaterialFields.defaultToken("weight")==null || RiverPaletteFields.zoomDefault()==null || RiverProfiles.maximumProfiles()!=64 || SurfacePools.maximumPools()!=64 || DeepFluids.maximumSystems()!=64 || DimensionHeights.defaultBound("min",true)==null || DimensionPalettes.maximumWeight()!=1000 || SurfaceLayerFallbacks.choices().isEmpty() || BiomeLayers.defaultBlock()==null || BiomeDerivatives.defaultKey()==null || BiomeSlabWall.classBlock()==null || FloatingBiomes.defaultToken("rarity")==null || InterpolationFields.choices().isEmpty() || CompositeNoise.minimumEntries() != 1 || FieldCoverage.fields("dimension").isEmpty()) throw new IllegalStateException("Missing field policy");
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
