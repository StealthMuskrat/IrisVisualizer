package dev.irisvisualizer.editor;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.app.WindowGlfw;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
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
    private final AsyncZipExport zipExport=new AsyncZipExport();
    private boolean showZipExport;
    private String zipExportMessage;
    private final AsyncAssetBatchImport assetBatchImport=new AsyncAssetBatchImport();
    private boolean showAssetBatchImport,assetBatchAcknowledge;
    private long assetBatchReviewed=-1;
    private String assetBatchError;
    private final AsyncAssetImport assetImport=new AsyncAssetImport();
    private boolean showAssetImport,assetImportAcknowledge;
    private long assetImportReviewed=-1;
    private String assetImportError;
    private final AsyncAssetReplacement assetReplacement=new AsyncAssetReplacement();
    private boolean showAssetReplacement,assetReplacementNoop,assetReplacementAllowUnrecorded;
    private long assetReplacementAssessed=-1;
    private String assetReplacementError;
    private String riverCatalogDimension;
    private final RiverIdLists.CatalogCache riverIdCatalogs = new RiverIdLists.CatalogCache();
    private final AsyncDefinitionAudit<BlockFallbacks.Audit> blockFallbackAudits = new AsyncDefinitionAudit<>(DefinitionAuditSource.Family.DIMENSION_FALLBACKS,BlockFallbacks::inspect);
    private final FallbackOverlapReview.Cache fallbackOverlapPages=new FallbackOverlapReview.Cache();
    private final ImString fallbackOverlapQuery=new ImString(1024);
    private boolean fallbackCurrentDimensionOnly;
    private BlockFallbacks.Audit fallbackReviewedAudit;
    private String fallbackGroup;
    private int fallbackGroupPage,fallbackDefinitionPage;
    private final AsyncDefinitionAudit<BlockAliasAudit.Audit> blockAliasAudits = new AsyncDefinitionAudit<>(DefinitionAuditSource.Family.BLOCK_ALIASES,BlockAliasAudit::inspect);
    private final AsyncDefinitionAudit<ExpressionReferenceAudit.Audit> expressionAudits = new AsyncDefinitionAudit<>(DefinitionAuditSource.Family.EXPRESSIONS,ExpressionReferenceAudit::inspect);
    private final ResourceNavigation resourceNavigation = new ResourceNavigation();
    private final NavigationHistory navigationHistory = new NavigationHistory();
    private final StructureDefinitions.PoolCache structurePools=new StructureDefinitions.PoolCache();
    private final AsyncStructureThemeReview structureThemeReferences=new AsyncStructureThemeReview();
    private boolean showStructureThemeReview;
    private final ImString themeReviewKey=new ImString(2048),themeReviewFilter=new ImString(2048);
    private String themeReviewError;
    private int themeReviewPage;
    private StructureThemeReferences.Review themeReviewedReview;
    private final StructureThemeReferences.PageCache themeReviewPages=new StructureThemeReferences.PageCache();
    private final JigsawPools.Catalog jigsawPoolCatalog=new JigsawPools.Catalog();
    private final JigsawPieces.Catalog jigsawPieceObjects=new JigsawPieces.Catalog();
    private final ImString structurePoolFilter=new ImString(1024);
    private final ImageMapDefinitions.SourceCache imageMapSources = new ImageMapDefinitions.SourceCache();
    private final ResourceSelectors.CatalogCache selectorCatalogs = new ResourceSelectors.CatalogCache();
    private final NoiseStyleSources.CatalogCache styleSourceCatalogs = new NoiseStyleSources.CatalogCache();
    private PackWorkspace referenceWorkspace;
    private String referencePath;
    private long referenceRevision = -1;
    private List<ReferenceDiagnostics.Link> referenceLinks = List.of();
    private final AsyncAssetImage imageLoader=new AsyncAssetImage();
    private String imageError;
    private int imageTexture, imageWidth, imageHeight;
    private long imageRequest=-1;
    private boolean imageUploadAttempted;
    private AssetImage.Decoded imageData;
    private ImageViewport imageViewport;
    private void clearImage() {
        if (imageTexture != 0) glDeleteTextures(imageTexture);
        imageTexture=0;imageError=null;imageData=null;imageViewport=null;imageWidth=0;imageHeight=0;
    }
    @Override protected void disposeImGui() {
        try { textSearch.close();zipExport.close();assetBatchImport.close();assetImport.close();assetReplacement.close();structureThemeReferences.close();blockFallbackAudits.close();blockAliasAudits.close();expressionAudits.close();jigsawReview.close();packReferenceReview.close();imageLoader.close();clearImage(); }
        finally { super.disposeImGui(); }
    }
    private void uploadImage(AssetImage.Decoded image) {
        if (image.width()>glGetInteger(GL_MAX_TEXTURE_SIZE) || image.height()>glGetInteger(GL_MAX_TEXTURE_SIZE)) throw new IllegalArgumentException("Image exceeds this GPU's texture size limit");
        int previous=glGetInteger(GL_TEXTURE_BINDING_2D),alignment=glGetInteger(GL_UNPACK_ALIGNMENT);
        var buffer=MemoryUtil.memAlloc(image.width()*image.height()*4);
        try {
            buffer.put(image.rgba()).flip();imageTexture=glGenTextures();
            glBindTexture(GL_TEXTURE_2D,imageTexture);glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,buffer);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH)!=image.width()) throw new IllegalStateException("GPU image allocation failed");
            imageWidth=image.width();imageHeight=image.height();imageData=image;imageViewport=new ImageViewport(imageWidth,imageHeight);
        } finally {
            glBindTexture(GL_TEXTURE_2D,previous);glPixelStorei(GL_UNPACK_ALIGNMENT,alignment);MemoryUtil.memFree(buffer);
        }
    }
    private void retryImage() {
        var next=imageLoader.retry(workspace,selected);clearImage();imageRequest=next.request();imageUploadAttempted=false;
    }
    private void drawImageAsset() {
        var load=imageLoader.select(workspace,selected);
        if (imageRequest!=load.request()) { clearImage();imageRequest=load.request();imageUploadAttempted=false; }
        if (!AssetImage.supportedPath(selected)) return;
        if (load.phase()==AsyncAssetImage.Phase.READY && !imageUploadAttempted) {
            imageUploadAttempted=true;
            try { uploadImage(load.image()); }
            catch (Exception failure) { clearImage();imageError=InspectorText.label(failure.getMessage()==null ? "Image upload failed" : failure.getMessage(),512); }
        }
        ImGui.separator(); ImGui.textWrapped("Read-only image asset — first frame");
        if (load.phase()==AsyncAssetImage.Phase.LOADING) { ImGui.textWrapped("Loading selected image... Other editing remains available.");return; }
        String failure=load.phase()==AsyncAssetImage.Phase.FAILED ? load.error() : imageError;
        if (failure!=null) { ImGui.textWrapped(failure);if (ImGui.button("Retry image decode/upload")) retryImage();return; }
        if (imageTexture != 0) {
            ImGui.text(imageWidth + " × " + imageHeight + " decoded pixels");
            if (ImGui.button("Fit image")) imageViewport.fit();ImGui.sameLine();if (ImGui.button("1:1 image")) imageViewport.actualSize();
            ImGui.sameLine();if (ImGui.button("Zoom -")) imageViewport.zoom(1/1.25);ImGui.sameLine();if (ImGui.button("Zoom +")) imageViewport.zoom(1.25);
            ImGui.textWrapped("Wheel: zoom at pointer. Left-drag: pan. Hover: decoded pixel RGBA. Fit never enlarges; 1:1 uses one logical display unit per source pixel.");
            float availableWidth=Math.max(1,ImGui.getContentRegionAvailX()),availableHeight=Math.max(1,ImGui.getContentRegionAvailY()-ImGui.getTextLineHeightWithSpacing());
            if (ImGui.beginChild("Read-only image canvas",availableWidth,availableHeight,false,ImGuiWindowFlags.NoScrollbar|ImGuiWindowFlags.NoScrollWithMouse)) {
                float canvasWidth=Math.max(1,ImGui.getContentRegionAvailX()),canvasHeight=Math.max(1,ImGui.getContentRegionAvailY());
                imageViewport.resize(canvasWidth,canvasHeight);
                float originX=ImGui.getCursorScreenPosX(),originY=ImGui.getCursorScreenPosY();
                ImGui.invisibleButton("Image pointer navigation",canvasWidth,canvasHeight);
                boolean hovered=ImGui.isItemHovered(),active=ImGui.isItemActive();
                double mouseX=ImGui.getMousePosX()-originX,mouseY=ImGui.getMousePosY()-originY;
                if (active && ImGui.isMouseDragging(0)) imageViewport.pan(ImGui.getIO().getMouseDeltaX(),ImGui.getIO().getMouseDeltaY());
                if (hovered && ImGui.getIO().getMouseWheel()!=0) imageViewport.zoom(Math.pow(1.25,Math.max(-8,Math.min(8,ImGui.getIO().getMouseWheel()))),Math.max(0,Math.min(canvasWidth,mouseX)),Math.max(0,Math.min(canvasHeight,mouseY)));
                var draw=ImGui.getWindowDrawList();float cell=Math.max(16,(float)Math.ceil(Math.max(canvasWidth,canvasHeight)/32));
                for (int row=0;row<32 && row*cell<canvasHeight;row++) for (int column=0;column<32 && column*cell<canvasWidth;column++) {
                    int color=(row+column)%2==0 ? 0xffeeeeee : 0xffcccccc;
                    draw.addRectFilled(originX+column*cell,originY+row*cell,originX+Math.min(canvasWidth,(column+1)*cell),originY+Math.min(canvasHeight,(row+1)*cell),color);
                }
                var view=imageViewport.geometry();
                draw.addImage((long)imageTexture,originX+(float)view.left(),originY+(float)view.top(),originX+(float)view.right(),originY+(float)view.bottom(),(float)view.u0(),(float)view.v0(),(float)view.u1(),(float)view.v1());
                if (hovered) {
                    var pixel=imageViewport.pixelAt(mouseX,mouseY);
                    if (pixel!=null) { int argb=imageData.argbAt(pixel.x(),pixel.y());ImGui.setTooltip("Pixel ("+pixel.x()+", "+pixel.y()+")  R "+((argb>>>16)&255)+"  G "+((argb>>>8)&255)+"  B "+(argb&255)+"  A "+(argb>>>24)); }
                }
            }
            ImGui.endChild();
            ImGui.textUnformatted(String.format(java.util.Locale.ROOT,"%.2f%% (%s)",100*imageViewport.scale(),imageViewport.fitting() ? "fit" : "manual"));
        }
    }
    private boolean styled, closeRequested;
    private final ImString filter = new ImString(256);
    private String indexedFilter;
    private long indexedRevision = -1;
    private PackWorkspace indexedWorkspace;
    private List<ResourceTree.Node> resources = List.of();
    private ResourceFinder.Family browserFamily=ResourceFinder.Family.ALL,indexedFamily;
    private boolean browserChangedOnly,indexedChangedOnly,indexedDirty;
    private long indexedAppliedRevision=-1;
    private boolean showQuickOpen,quickChangedOnly;
    private final ImString quickQuery=new ImString(256);
    private ResourceFinder.Family quickFamily=ResourceFinder.Family.ALL;
    private final ResourceFinder.Cache quickFinder=new ResourceFinder.Cache();
    private String quickSelected,quickPendingPath;
    private PackWorkspace quickPendingWorkspace;
    private boolean showTextSearch,textChangedOnly;
    private final ImString textQuery=new ImString(1024);
    private ResourceFinder.Family textFamily=ResourceFinder.Family.ALL;
    private final AsyncWorkspaceTextSearch textSearch=new AsyncWorkspaceTextSearch();
    private WorkspaceTextSearch.Query activeTextQuery;
    private String textSearchError,textPendingPath;
    private PackWorkspace textPendingWorkspace;
    private String selectedFolder,pendingFolderPath,pendingFolderAction;
    private boolean showPendingChanges;
    private final WorkspaceChanges.Cache pendingChanges=new WorkspaceChanges.Cache();
    private final ImString changesQuery=new ImString(1024);
    private WorkspaceChanges.Filter changesFilter=WorkspaceChanges.Filter.ALL;
    private String changesSelected,changesPendingPath;
    private PackWorkspace changesPendingWorkspace;
    private boolean showReferenceReview,reviewIncomingOnly;
    private final AsyncPackReferenceReview packReferenceReview=new AsyncPackReferenceReview();
    private final ImString referenceReviewQuery=new ImString(1024);
    private PackReferenceAudit.View referenceReviewView=PackReferenceAudit.View.ALL;
    private String referenceReviewPendingPath;
    private PackWorkspace referenceReviewPendingWorkspace;
    private boolean showJigsawReview,jigsawProblemsOnly;
    private final AsyncJigsawDependencyReview jigsawReview=new AsyncJigsawDependencyReview();
    private final ImString jigsawReviewQuery=new ImString(1024);
    private String jigsawReviewRoot;
    private JigsawDependencyReview.Scope jigsawReviewScope=JigsawDependencyReview.Scope.ALL;
    private boolean showJsonStructure;
    private final JsonStructure.Cache jsonSourceCache=new JsonStructure.Cache();
    private JsonStructure.Snapshot jsonActiveSnapshot,jsonWholeSnapshot,jsonTokenSnapshot;
    private final JsonStructure.PageCache jsonPages=new JsonStructure.PageCache();
    private JsonStructure.Preview jsonWholePreview,jsonTokenPreview;
    private JsonStructure.Pointer jsonCurrentPointer;
    private int jsonNode,jsonPage,jsonBufferedNode=-1;
    private final ImString jsonFind=new ImString(1024),jsonPointer=new ImString(65_536);
    private ImString jsonWholeText,jsonTokenText;
    private final SourceMatch.Focus sourceMatchFocus=new SourceMatch.Focus();
    private SourceMatch.View sourceMatchBuffered;
    private ImString sourceMatchBefore,sourceMatchText,sourceMatchAfter;
    private String jsonFindError,jsonPreviousFind="";
    @Override protected void preRun() {
        long handle = ((WindowGlfw) getWindow()).getHandle();
        glfwSetWindowCloseCallback(handle, window -> {
            glfwSetWindowShouldClose(window, false);
            closeRequested = true;
        });
    }
    private final InspectorDrafts drafts = new InspectorDrafts();
    private boolean showAppliedHistory;
    private PackWorkspace reviewedWorkspace;
    private long reviewedHistoryId=-1;
    private final AppliedEditReview.Cache editReviews=new AppliedEditReview.Cache();
    private AppliedEditReview.Comparison reviewedComparison;
    private ImString reviewedBefore,reviewedAfter;
    private final AppliedEditDiff.Cache editDiffs=new AppliedEditDiff.Cache();
    private AppliedEditDiff.Diff reviewedDiff;
    private boolean diffShowUnchanged;
    private int diffPage,diffSelected=-1,diffBufferedRow=-1;
    private ImString diffLineText;
    private final Map<String, ImString> values = new HashMap<>();
    @Override protected void configure(Configuration config) { config.setTitle("Iris Visualizer"); }
    @Override public void process() {
        assetBatchImport.poll(workspace);
        assetImport.poll(workspace);
        assetReplacement.poll(workspace);
        var fallbackState=blockFallbackAudits.poll(workspace);if (fallbackReviewedAudit!=null && fallbackState.audit()!=fallbackReviewedAudit) clearFallbackOverlapView();
        var themeState=structureThemeReferences.poll(workspace);if (themeReviewedReview!=null && themeState.review()!=themeReviewedReview) clearThemeReviewView();
        blockAliasAudits.poll(workspace);expressionAudits.poll(workspace);
        packReferenceReview.poll(workspace);
        jigsawReview.poll(workspace,jigsawReviewRoot);
        updateSourceMatchFocus();

        if (closeRequested) {
            closeRequested = false;
            try { transition(true, () -> glfwSetWindowShouldClose(((WindowGlfw) getWindow()).getHandle(), true)); }
            catch (Exception failure) { error = failure.getMessage(); }
        }
        if (!styled) { ImGui.styleColorsLight(); styled = true; }
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                for (String action : new String[]{"New", "Open", "Save", "Save As", "Save Copy", "Import", "Export"})
                    if (ImGui.menuItem(action)) perform(action);
                if (ImGui.menuItem("New image-map definition")) createResource("image-maps");
                if (ImGui.menuItem("New block alias definition")) createResource("blocks");
                if (ImGui.menuItem("New expression definition")) createResource("expressions");
                if (ImGui.menuItem("New structure definition")) createResource("structures");
                if (ImGui.menuItem("New jigsaw pool with empty membership")) createResource("jigsaw-pools");
                if (ImGui.menuItem("New jigsaw piece")) createResource("jigsaw-pieces");
                if (ImGui.menuItem("Import image/object asset")) importAsset();
                if (ImGui.menuItem("Review captured asset import")) showAssetImport=true;
                if (ImGui.menuItem("Import image/object batch")) importAssetBatch();
                if (ImGui.menuItem("Review captured asset batch")) showAssetBatchImport=true;
                if (ImGui.menuItem("Review ZIP export progress")) showZipExport=true;
                ImGui.beginDisabled(!AssetReplacement.supports(selected));if (ImGui.menuItem("Replace selected image/object asset")) chooseAssetReplacement(selected);ImGui.endDisabled();
                if (ImGui.menuItem("Review captured asset replacement")) showAssetReplacement=true;
                if (ImGui.menuItem("Review pending changes")) showPendingChanges=true;
                if (ImGui.menuItem("Review pack resource links")) showReferenceReview=true;
                if (ImGui.menuItem("Review jigsaw dependencies")) showJigsawReview=true;
                if (ImGui.menuItem("Review global piece theme tags")) showStructureThemeReview=true;
                ImGui.textWrapped("Active folder: "+(workspace.directory()==null ? "Not saved to a folder" : workspace.directory().toString()));
                ImGui.textWrapped("Save As switches the active folder. Save Copy keeps it. Both write applied in-memory values to a new or empty folder outside the active pack.");
                ImGui.endMenu();
            }
            if (ImGui.beginMenu("Edit")) {
                ImGui.beginDisabled(workspace.undoCount()==0);if (ImGui.menuItem("Undo applied edit")) restoreAppliedEdit(false);ImGui.endDisabled();
                if (workspace.undoPath()!=null) ImGui.textWrapped("Undo: "+workspace.undoPath());
                ImGui.beginDisabled(workspace.redoCount()==0);if (ImGui.menuItem("Redo applied edit")) restoreAppliedEdit(true);ImGui.endDisabled();
                if (workspace.redoPath()!=null) ImGui.textWrapped("Redo: "+workspace.redoPath());
                ImGui.textWrapped(workspace.undoCount()+" undo / "+workspace.redoCount()+" redo; "+workspace.historyBytes()+" snapshot bytes.");
                ImGui.textWrapped("Applied byte edits only, up to 128 edits / 32 MiB. Resource/folder creation, duplication or asset import clears history. Save retains it. History is memory-only and ends when the pack is replaced or the editor closes.");
                ImGui.textWrapped(workspace.historyNote());
                if (ImGui.menuItem("Review applied edits")) showAppliedHistory=true;
                ImGui.beginDisabled(workspace.undoCount()+workspace.redoCount()==0);if (ImGui.menuItem("Clear applied-edit history")) workspace.clearHistory();ImGui.endDisabled();
                ImGui.endMenu();
            }
            if (ImGui.beginMenu("Navigate")) {
                if (ImGui.menuItem("Quick Open resource")) showQuickOpen=true;
                ImGui.beginDisabled(!JsonStructure.supports(selected));if (ImGui.menuItem("Inspect selected JSON structure")) showJsonStructure=true;ImGui.endDisabled();
                if (ImGui.menuItem("Search JSON text")) showTextSearch=true;
                var back=navigationHistory.back();var forward=navigationHistory.forward();
                ImGui.beginDisabled(back.isEmpty());if (ImGui.menuItem("Back")) queueHistoryNavigation(false,1);ImGui.endDisabled();
                ImGui.beginDisabled(forward.isEmpty());if (ImGui.menuItem("Forward")) queueHistoryNavigation(true,1);ImGui.endDisabled();
                drawNavigationDestinations("Back history",back,false);drawNavigationDestinations("Forward history",forward,true);
                if (ImGui.beginMenu("Recent resources")) { int index=0;for (String path : navigationHistory.recent()) { ImGui.pushID(index++);if (ImGui.menuItem(path.length()>128 ? path.substring(0,128)+"..." : path)) selectResource(path);if (ImGui.isItemHovered()) ImGui.setTooltip(path);ImGui.popID(); }ImGui.endMenu(); }
                if (ImGui.menuItem("Clear navigation history")) navigationHistory.clear();
                ImGui.textWrapped("Up to 128 resource locations and 32 unique recent files in this pack, including search text. File selection only; applied edit history stays separate. New/Open/Import starts fresh navigation history.");
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
        if (ImGui.beginCombo("Resource family",browserFamily.label())) { for (var family : ResourceFinder.Family.values()) if (ImGui.selectable(family.label(),family==browserFamily)) browserFamily=family;ImGui.endCombo(); }
        if (ImGui.checkbox("Applied changes only",browserChangedOnly)) browserChangedOnly=!browserChangedOnly;
        if (ImGui.button("Clear browser filters")) { filter.set("");browserFamily=ResourceFinder.Family.ALL;browserChangedOnly=false; }
        if (indexedWorkspace != workspace || indexedRevision != workspace.resourceRevision() || !filter.get().equals(indexedFilter) || indexedFamily!=browserFamily || indexedChangedOnly!=browserChangedOnly || browserChangedOnly && (indexedAppliedRevision!=workspace.revision() || indexedDirty!=workspace.dirty())) {
            indexedWorkspace = workspace; indexedRevision = workspace.resourceRevision(); indexedFilter = filter.get();indexedFamily=browserFamily;indexedChangedOnly=browserChangedOnly;indexedAppliedRevision=workspace.revision();indexedDirty=workspace.dirty();
            resources = ResourceTree.build(workspace,indexedFilter,browserFamily,browserChangedOnly);
        }
        if (browserFamily!=ResourceFinder.Family.ALL || browserChangedOnly) ImGui.textWrapped("File filters hide empty folders. Clear filters to see all folders.");
        if (resources.isEmpty()) ImGui.textWrapped(workspace.paths().isEmpty() ? "No resources. Open a pack folder or import a ZIP." : "No matching resources.");
        ImGui.textDisabled("Ctrl-click: duplicate; Ctrl-right-click folder: create; Ctrl-middle-click folder: subfolder");
        drawResources(resources);
        if (selectedFolder!=null && workspace.directories().contains(selectedFolder) && ImGui.isWindowFocused() && !ImGui.getIO().getWantTextInput()) {
            if (ImGui.isKeyPressed(ImGuiKey.F2,false)) queueFolderAction(selectedFolder,"Rename");
            if (ImGui.isKeyPressed(ImGuiKey.Delete,false)) queueFolderAction(selectedFolder,"Delete");
        }
        ImGui.textDisabled("Empty subfolders: right-click for Rename / Move / Delete; F2 or Delete when selected. Folder changes clear applied-edit history.");
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
                    drawWorldBoundary(document);
                    drawStructurePlacements(document);
                    drawDimensionIntegrations(document);
                    drawNativeStructureAdjustments(document);
                    drawNativeGenerationFilters(document);
                    drawDimensionGenerationControls(document);
                    drawDimensionType(document);
                    drawDimensionStack(document);
                    drawDimensionCarvings(document);
                    drawSubterrainFeatures(document);
                    drawTerrainOverlays(document);
                    drawTerrainTransform(document);
                    drawImageMapBindings(document);
                    drawResourceSelectors(document);
                    drawRootStyles(document, true);
                    drawCaveProfile(document);
                    drawHydrology(document);
                    drawRiverPolicy(document);
                    drawHydrologyEnvelopes();
                    List<String> readOnly = DimensionFields.readOnlyPaths(document);
                    if (ImGui.collapsingHeader("Read-only fields (" + readOnly.size() + ")"))
                        for (String path : readOnly) drawReadOnlyField("dimension",path);
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
            if (bytes != null && ImageMapDefinitions.supports(selected)) {
                try { drawImageMapDefinition(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && BlockDefinitions.supports(selected)) {
                try { drawBlockDefinition(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && ExpressionDefinitions.supports(selected)) {
                try { drawExpressionDefinition(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && StructureDefinitions.supports(selected)) {
                try { drawStructureDefinition(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && JigsawPools.supports(selected)) {
                try { drawJigsawPool(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (bytes != null && JigsawPieces.supports(selected)) {
                try { drawJigsawPiece(new LosslessJson(bytes)); }
                catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            }
            if (AssetReplacement.supports(selected) && ImGui.button("Replace asset from file")) chooseAssetReplacement(selected);
            if (JigsawDependencyReview.supports(selected) && ImGui.button("Review jigsaw dependencies")) { jigsawReviewRoot=selected;showJigsawReview=true; }
            if (BlockDefinitions.supports(selected)) drawBlockAliasAudit();
            if (ExpressionDefinitions.supports(selected) || "image-maps".equals(ResourceTypes.base(selected))) drawExpressionReferenceAudit();
            drawCoverage();
            if (JsonStructure.supports(selected)) {
                if (ImGui.button("Browse JSON structure (read-only)")) showJsonStructure=true;
                if (ImGui.collapsingHeader("Original/current JSON (read-only)")) drawJsonSource(currentJsonSnapshot());
            } else {
                ImGui.text(byteCount + " bytes");
            }
        }
        if (selected != null) drawReferences();
        if (error != null) ImGui.textWrapped(error);
        ImGui.end();
        if (showQuickOpen) drawQuickOpen();
        if (showTextSearch) drawTextSearch();
        if (showPendingChanges) drawPendingChanges();
        if (showReferenceReview) drawPackReferenceReview();
        if (showJigsawReview) drawJigsawDependencyReview();
        if (showStructureThemeReview) drawStructureThemeReview();
        if (showZipExport) drawZipExport();
        if (showAssetBatchImport) drawAssetBatchImport();
        if (showAssetImport) drawAssetImport();
        if (showAssetReplacement) drawAssetReplacement();
        if (showJsonStructure) drawJsonStructure();
        applyPendingFolderAction();
        navigationHistory.observe(workspace,selected,filter.get());
        var navigationRequest=resourceNavigation.takeRequest(workspace);
        if (navigationRequest!=null) {
            try {
                var plan=navigationRequest.plan();
                if (plan!=null) {
                    navigationHistory.validate(plan,workspace);
                    transition(false,() -> { navigationHistory.commit(plan,workspace);selected=plan.location().path();filter.set(plan.location().filter());error=null; });
                } else {
                    boolean picker=quickPendingWorkspace==workspace && navigationRequest.target().equals(quickPendingPath);
                    boolean textResult=textPendingWorkspace==workspace && navigationRequest.target().equals(textPendingPath);
                    boolean pendingChange=changesPendingWorkspace==workspace && navigationRequest.target().equals(changesPendingPath);
                    boolean referenceReview=referenceReviewPendingWorkspace==workspace && navigationRequest.target().equals(referenceReviewPendingPath);
                    var sourceLocation=navigationRequest.match();
                    var sourceView=sourceLocation==null ? null : SourceMatch.inspect(workspace,sourceLocation);
                    boolean opened=navigationRequest.target().equals(selected) || transition(false,() -> { if (sourceLocation!=null) SourceMatch.validate(workspace,sourceLocation);selected=navigationRequest.target();filter.set("");error=null; });
                    if (opened && sourceView!=null) {
                        sourceMatchFocus.show(workspace,sourceView);showJsonStructure=true;var snapshot=currentJsonSnapshot();int containing=SourceMatch.containing(snapshot,sourceView);
                        if (containing>=0) { openJsonNode(containing);var pointer=snapshot.pointer(containing);if (pointer.complete()) jsonPointer.set(pointer.text()); }
                        updateSourceMatchFocus();
                    }
                    if ((picker || textResult || pendingChange || referenceReview) && opened) { if (picker) showQuickOpen=false;filter.set("");revealSelectedResource();navigationHistory.observe(workspace,selected,filter.get()); }
                }
            } catch (Exception failure) { error=failure.getMessage(); }
        }
        quickPendingWorkspace=null;quickPendingPath=null;
        textPendingWorkspace=null;textPendingPath=null;
        changesPendingWorkspace=null;changesPendingPath=null;
        referenceReviewPendingWorkspace=null;referenceReviewPendingPath=null;
        if (showAppliedHistory) drawAppliedHistory();
    }
    private void drawReferences() {
        if (!ReferenceDiagnostics.supports(selected) || !ImGui.collapsingHeader("Resource links (partial)")) return;
        if (referenceWorkspace != workspace || referenceRevision != workspace.revision() || !java.util.Objects.equals(referencePath, selected)) {
            referenceWorkspace = workspace; referenceRevision = workspace.revision(); referencePath = selected;
            referenceLinks = ReferenceDiagnostics.inspect(workspace, selected);
        }
        ImGui.textWrapped("Checks authored exact block-alias files in known material palettes, deposit palettes, replacement find lists, decorator block lists/forced blocks and standalone block definitions (including eight backup levels), authored expression/image-map sources in known noise styles and nested fractures, exact structure start-pool files, pool fallback and physical membership files, piece object assets and connector pool targets, image-map PNG sources and a bounded subset of dimension selectors/stacks/carving biomes/subterrain references/image-map bindings, region/biome lists, placement Iris-structure selections/cave-biome restrictions, floating biome references and authored object asset pools. Exact file contents, native aliases, compatibility and other references are unchecked. Save/Export remain available.");
        if (referenceLinks.isEmpty()) ImGui.textWrapped("No explicit links in this inspected subset; omitted defaults and unknown contexts are not resolved.");
        int referenceIndex=0;for (var link : referenceLinks) {
            ImGui.pushID(referenceIndex++);
            ImGui.textWrapped(InspectorText.label(link.field(),256) + ": " + InspectorText.label(link.key(),256));
            String status = switch (link.state()) {
                case EXACT_FILE -> "Exact file present (contents unchecked)";
                case NO_EXACT_FILE -> "No exact file match (aliases unchecked)";
                case ABSENT_REQUIRED -> "Required reference is absent";
                case EMPTY_REQUIRED -> "Required reference list is empty";
                case UNSUPPORTED -> "Reference inspection unavailable";
                case INVALID_KEY -> "Unsupported or unsafe resource key";
            };
            ImGui.textWrapped(status);
            if (link.state() == ReferenceDiagnostics.State.EXACT_FILE && ImGui.smallButton("Open linked resource")) {
                try { resourceNavigation.request(workspace,link.target()); }
                catch (Exception failure) { error = failure.getMessage(); }
            }
            ImGui.popID();
        }
        ImGui.textWrapped("Noise and material inspection each use at most 4,096 JSON nodes and 32 levels per source; labels show up to 256 characters. Material links list authored exact alias files only; omitted defaults, missing alias files, Minecraft registry names, string block filters and native fallback mappings are not resolved. Declared links do not establish active source selection, formula validity or cycle safety.");
    }
    private void drawJigsawPiece(LosslessJson root) {
        if (!ImGui.collapsingHeader("Jigsaw piece")) return;
        ImGui.pushID("jigsaw-piece");
        ImGui.textWrapped("Literal piece settings only. Object bytes, bounds, connector transforms, graph reachability, eligibility, native compatibility and effective placement are not evaluated. Unsupported previews stay disabled.");
        try {
            String current=JigsawPieces.object(root);ImGui.textWrapped(JigsawPieces.help("object"));
            ImString query=values.computeIfAbsent("jigsawPiece#object.filter",ignored -> new ImString(1025));ImGui.inputText("Object filter",query);
            try {
                var matches=jigsawPieceObjects.filter(workspace,query.get());ImGui.textUnformatted(matches.count()+" exact lowercase .iob keys; showing up to 200. Binary contents and loader aliases are unchecked.");
                if (ImGui.beginCombo("Piece object",current.isEmpty() ? "Choose exact object key" : InspectorText.label(current,128))) { int index=0;for (String key : matches.keys()) { ImGui.pushID(index++);if (ImGui.selectable(InspectorText.label(key,256).replace("#","\\u0023"),key.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.setObject(selected,workspace.read(selected),key,workspace)));ImGui.popID(); }ImGui.endCombo(); }
            } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            if (!current.isEmpty() && workspace.paths().contains("objects/"+current+".iob") && ImGui.button("Open exact object asset")) resourceNavigation.request(workspace,"objects/"+current+".iob");
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored object reference has an unsupported type and remains read-only."); }
        for (String name : JigsawPieces.FLAGS) {
            ImGui.pushID(name);try {
                boolean current=JigsawPieces.flag(root,name);var input=new ImBoolean(current);ImGui.textWrapped(JigsawPieces.help(name));if (root.token(name)==null) ImGui.textUnformatted("Implicit default: "+current);
                if (ImGui.checkbox(name,input)) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.setFlag(selected,workspace.read(selected),name,input.get())));
                if (root.token(name)==null && ImGui.button("Store displayed default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.setFlag(selected,workspace.read(selected),name,current)));
                if (root.token(name)!=null && ImGui.button("Use class default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.resetFlag(selected,workspace.read(selected),name)));
            } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored flag is unsupported and preserved read-only."); }ImGui.popID();
        }
        drawJigsawPieceThemes(root);drawJigsawPieceRules(root);drawJigsawConnectors(root);
        for (String name : JigsawPieces.readOnlyFields(root)) drawReadOnlyField("jigsawPiece",name);
        ImGui.popID();
    }
    private void drawJigsawConnectors(LosslessJson root) {
        if (!ImGui.treeNode("Jigsaw connectors")) return;
        ImGui.textWrapped(JigsawConnectors.help("connectors"));
        ImGui.textWrapped("Up to 64 connectors. Coordinates are nonnegative exact int32 triples. Object bounds and canonical planar face-center positions are unchecked. PLANAR_JIGSAW requires horizontal directions and UP_POSITIVE_Y tops; no structure mode is inferred. Names and channels match exact case and whitespace. Joint/rotation compatibility and graph reachability are not evaluated.");
        if ("null".equals(root.token("connectors"))) {
            ImGui.textWrapped("The native compiler rejects a null connector list. Remove only this override to restore the implicit empty list.");
            if (ImGui.button("Remove null connector override")) { try { commitProfileChange(JigsawConnectors.entriesPrefix(),JigsawConnectors.resetEmpty(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } else try {
            var rows=JigsawConnectors.entries(root);
            if (rows.isEmpty()) { ImGui.textWrapped("No authored connectors. This does not establish whether the piece is usable in any graph.");if (root.token("connectors")!=null && ImGui.button("Remove empty connector override")) { try { commitProfileChange(JigsawConnectors.entriesPrefix(),JigsawConnectors.resetEmpty(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            String add="jigsawConnector#new.",newKey=add+"pool";ImString first=values.computeIfAbsent(newKey,ignored -> new ImString(1025));String next=drawJigsawPoolKey("New connector pool",add,"jigsaw-pools",first.get());if (next!=null) first.set(next);drafts.update(newKey,"",first.get());
            ImGui.textWrapped("New connectors store only the selected pool. Position 0/0/0, NORTH_NEGATIVE_Z facing, UP_POSITIVE_Y top, ROLLABLE joint, empty names/channel, minecraft:air finalState and zero priorities remain implicit defaults.");
            ImGui.beginDisabled(rows.size()>=JigsawConnectors.MAX_ENTRIES);if (ImGui.button("Append connector")) { try { commitProfileChange(add,JigsawConnectors.append(selected,workspace.read(selected),first.get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.endDisabled();
            for (int index=0;index<rows.size();index++) {
                ImGui.pushID(index);int entry=index;var row=rows.get(index);String prefix=JigsawConnectors.prefix(index);
                if (ImGui.treeNode("Connector "+(index+1))) {
                    try {
                        String current=JigsawConnectors.value(row,"pool");ImGui.textWrapped(JigsawConnectors.help("pool"));String pool=drawJigsawPoolKey("Connector target pool",prefix+"pool.","jigsaw-pools",current);
                        if (pool!=null) applyRoutingExtra(() -> workspace.replace(selected,JigsawConnectors.setPool(selected,workspace.read(selected),entry,pool,workspace)));
                        if (!current.isEmpty() && workspace.paths().contains("jigsaw-pools/"+current+".json") && ImGui.button("Open exact connector pool")) resourceNavigation.request(workspace,"jigsaw-pools/"+current+".json");
                    } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored pool reference has an unsupported type and remains read-only."); }
                    ImGui.textWrapped(JigsawConnectors.help("position"));String positionPrefix=prefix+"position.",positionDraft=positionPrefix+"triple";
                    try {
                        var position=JigsawConnectors.position(row);String x=JigsawConnectors.axis(position,"x"),y=JigsawConnectors.axis(position,"y"),z=JigsawConnectors.axis(position,"z");if (position==null) ImGui.textUnformatted("Implicit constructor position: 0 / 0 / 0");
                        ImString px=values.computeIfAbsent(positionPrefix+"x",ignored -> new ImString(x,Math.max(128,x.length()+1))),py=values.computeIfAbsent(positionPrefix+"y",ignored -> new ImString(y,Math.max(128,y.length()+1))),pz=values.computeIfAbsent(positionPrefix+"z",ignored -> new ImString(z,Math.max(128,z.length()+1)));ImGui.inputText("Connector X",px);ImGui.inputText("Connector Y",py);ImGui.inputText("Connector Z",pz);drafts.update(positionDraft,x+"|"+y+"|"+z,px.get()+"|"+py.get()+"|"+pz.get());
                        if (ImGui.button("Apply XYZ together")) { try { commitProfileChange(positionPrefix,JigsawConnectors.setPosition(selected,workspace.read(selected),entry,px.get(),py.get(),pz.get()),positionDraft); } catch (Exception failure) { error=failure.getMessage(); } }
                        if (row.token("position")!=null && ImGui.button("Use zero coordinate defaults")) { try { commitProfileChange(positionPrefix,JigsawConnectors.resetPosition(selected,workspace.read(selected),entry),positionDraft); } catch (Exception failure) { error=failure.getMessage(); } }
                        for (String name : JigsawConnectors.otherPositionFields(position)) ImGui.textWrapped(InspectorText.label(name,256)+" (unknown position data; preserved read-only)");
                    } catch (IllegalArgumentException invalid) {
                        ImGui.textWrapped("Position contains a snippet or unsupported value and remains read-only; other connector fields stay independent.");
                        if ("null".equals(row.token("position")) && ImGui.button("Remove null position override")) { try { commitProfileChange(positionPrefix,JigsawConnectors.resetPosition(selected,workspace.read(selected),entry),positionDraft); } catch (Exception failure) { error=failure.getMessage(); } }
                    }
                    for (String name : JigsawConnectors.CHOICES) {
                        ImGui.pushID(name);ImGui.textWrapped(JigsawConnectors.help(name));try {
                            String current=JigsawConnectors.value(row,name);if (row.token(name)==null) ImGui.textUnformatted("Implicit default: "+current);
                            if (ImGui.beginCombo(name,InspectorText.label(current,128))) { for (String value : JigsawConnectors.choices(name)) if (ImGui.selectable(value,value.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,JigsawConnectors.setChoice(selected,workspace.read(selected),entry,name,value)));ImGui.endCombo(); }
                            if (row.token(name)!=null && ImGui.button("Use class default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name)));
                        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored enum is unsupported and preserved read-only.");if ("null".equals(row.token(name)) && ImGui.button("Restore class default from null")) applyRoutingExtra(() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name))); }ImGui.popID();
                    }
                    for (String name : JigsawConnectors.TEXT) {
                        ImGui.pushID(name);ImGui.textWrapped(JigsawConnectors.help(name));String draft=prefix+name;
                        try {
                            String stored=JigsawConnectors.value(row,name);int maximum=name.equals("finalState") ? JigsawConnectors.MAX_FINAL_STATE_CHARACTERS : JigsawConnectors.MAX_TEXT_CHARACTERS;ImString input=values.computeIfAbsent(draft,ignored -> new ImString(stored,Math.max(maximum*4+1,stored.getBytes(StandardCharsets.UTF_8).length+1)));if (row.token(name)==null) ImGui.textWrapped("Implicit default: "+(stored.isEmpty() ? "(empty)" : InspectorText.label(stored,256)));
                            ImGui.inputText(name,input);drafts.update(draft,stored,input.get());if (ImGui.button("Apply literal text")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.setText(selected,workspace.read(selected),entry,name,input.get())));
                            if (row.token(name)!=null && ImGui.button("Use class default")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name)));
                        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored text is unsupported and preserved read-only.");if ("null".equals(row.token(name)) && ImGui.button("Restore class default from null")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name))); }ImGui.popID();
                    }
                    ImGui.textWrapped("Names/targetName/channel allow empty or surrounding spaces and have 256-character editor limits; new control characters are unsupported. finalState is nonblank literal text up to 4096 characters. Native template/Studio output uses that state, and native graph validity requires it; no state parsing, registry lookup, export or block restoration runs here. VANILLA_PORTABLE rejects nonblank Iris channels.");
                    for (String name : JigsawConnectors.PRIORITIES) {
                        ImGui.pushID(name);String draft=prefix+name;ImGui.textWrapped(JigsawConnectors.help(name));try {
                            String stored=JigsawConnectors.value(row,name);ImString input=values.computeIfAbsent(draft,ignored -> new ImString(stored,Math.max(128,stored.length()+1)));if (row.token(name)==null) ImGui.textUnformatted("Implicit default: 0");ImGui.inputText(name,input);drafts.update(draft,stored,input.get());
                            if (ImGui.button("Apply signed priority")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.setPriority(selected,workspace.read(selected),entry,name,input.get())));
                            if (row.token(name)!=null && ImGui.button("Use zero priority default")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name)));
                        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored priority is unsupported and preserved read-only.");if ("null".equals(row.token(name)) && ImGui.button("Restore zero default from null")) commitRiverOverride(draft,() -> workspace.replace(selected,JigsawConnectors.reset(selected,workspace.read(selected),entry,name))); }ImGui.popID();
                    }
                    ImGui.textWrapped("Priorities are signed int32 values. Higher priorities run first; authored order breaks ties. Reordering can change seeded assembly behavior; no order is sampled here.");
                    RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=JigsawConnectors.MAX_ENTRIES);if (ImGui.button("Duplicate connector")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button(rows.size()==1 ? "Remove last connector (empty default)" : "Remove connector")) action=RiverProfiles.Action.REMOVE;
                    ImGui.beginDisabled(index==0);if (ImGui.button("Connector up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==rows.size()-1);if (ImGui.button("Connector down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                    if (action!=null) { try { commitProfileChange(JigsawConnectors.entriesPrefix(),JigsawConnectors.change(selected,workspace.read(selected),index,action),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();break; }
                    for (String name : JigsawConnectors.otherFields(row)) ImGui.textWrapped(InspectorText.label(name,256)+" (unknown connector data; preserved read-only)");ImGui.treePop();
                }ImGui.popID();
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Connector list is read-only: "+invalid.getMessage()); }
        ImGui.treePop();
    }
    private void drawJigsawPieceThemes(LosslessJson root) {
        if (!ImGui.treeNode("Piece theme tags")) return;
        ImGui.textWrapped(JigsawPieces.help("themes"));ImGui.textWrapped("Up to 64 exact case-sensitive tags, each up to 256 characters. Tags must be declared by structures that reach this piece; no owning structure is inferred or checked. VANILLA_PORTABLE rejects nonempty tags. Removing the last tag restores the implicit unfiltered default.");
        if ("null".equals(root.token("themes"))) {
            ImGui.textWrapped("Native filtering treats null as unfiltered, but the graph compiler rejects this null list. Removing only the override restores the implicit default.");
            if (ImGui.button("Remove null theme override")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.resetEmptyThemes(selected,workspace.read(selected))));
        } else try {
            var tags=JigsawPieces.themes(root);if (tags.isEmpty()) { ImGui.textUnformatted("No theme restriction: available to every selected theme.");if (root.token("themes")!=null && ImGui.button("Remove empty theme override")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.resetEmptyThemes(selected,workspace.read(selected)))); }
            String add="jigsawPiece#themes.new.",newKey=add+"tag";ImString input=values.computeIfAbsent(newKey,ignored -> new ImString(1025));ImGui.inputText("New exact theme tag",input);drafts.update(newKey,"",input.get());
            ImGui.beginDisabled(tags.size()>=JigsawPieces.MAX_THEMES);if (ImGui.button("Append theme tag")) { try { commitProfileChange(add,JigsawPieces.appendTheme(selected,workspace.read(selected),input.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.endDisabled();
            for (int index=0;index<tags.size();index++) {
                ImGui.pushID(index);int row=index;String stored=tags.get(index),key=JigsawPieces.themesPrefix()+index+".tag";ImString tag=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(1025,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Theme tag",tag);drafts.update(key,stored,tag.get());
                if (ImGui.button("Apply theme tag")) commitRiverOverride(key,() -> workspace.replace(selected,JigsawPieces.setTheme(selected,workspace.read(selected),row,tag.get())));
                RiverProfiles.Action action=null;if (ImGui.button(tags.size()==1 ? "Remove last tag (restore unfiltered)" : "Remove tag")) action=RiverProfiles.Action.REMOVE;
                ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Tag up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==tags.size()-1);if (ImGui.button("Tag down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                ImGui.popID();if (action!=null) { try { commitProfileChange(JigsawPieces.themesPrefix(),JigsawPieces.changeTheme(selected,workspace.read(selected),index,action),null); } catch (Exception failure) { error=failure.getMessage(); }break; }
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored theme list is read-only: "+invalid.getMessage()); }
        ImGui.treePop();
    }
    private void drawJigsawPieceRules(LosslessJson root) {
        if (!ImGui.treeNode("Piece placement rules")) return;
        ImGui.textWrapped(JigsawPieces.help("rules"));ImGui.textWrapped("Depth bounds are 0..30. Placement counts are 0..512; a maximum of zero is unbounded within the structure safety cap. Each pair applies atomically and independently. Native minimum-count feasibility, terminal cap validity and graph constraints are unchecked. VANILLA_PORTABLE rejects nondefault rules.");
        try {
            var rules=JigsawPieces.rules(root);
            if (rules==null) {
                ImGui.textWrapped("Absent/null rules use native defaults: depths 0..30, minimum placements 0, maximum placements 0 (unbounded), terminal false. Viewing does not store them.");
                if (ImGui.button("Create inline rule overrides")) { try { commitProfileChange("jigsawPiece#rules.",JigsawPieces.createRules(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
            } else {
                for (String group : List.of("depth","placements")) {
                    ImGui.pushID(group);try {
                        var fields=JigsawPieces.pairFields(group);String low=JigsawPieces.ruleToken(rules,fields.get(0)),high=JigsawPieces.ruleToken(rules,fields.get(1)),prefix="jigsawPiece#rules."+group+".",key=prefix+"pair";
                        ImGui.textWrapped(JigsawPieces.help(fields.get(0)));ImGui.textWrapped(JigsawPieces.help(fields.get(1)));
                        ImString minimum=values.computeIfAbsent(prefix+"minimum",ignored -> new ImString(low,Math.max(128,low.length()+1))),maximum=values.computeIfAbsent(prefix+"maximum",ignored -> new ImString(high,Math.max(128,high.length()+1)));ImGui.inputText("Minimum",minimum);ImGui.inputText("Maximum",maximum);drafts.update(key,low+"|"+high,minimum.get()+"|"+maximum.get());
                        if (ImGui.button("Apply paired bounds")) { try { commitProfileChange(prefix,JigsawPieces.setPair(selected,workspace.read(selected),group,minimum.get(),maximum.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
                        if ((rules.token(fields.get(0))!=null || rules.token(fields.get(1))!=null) && ImGui.button("Use pair class defaults")) { try { commitProfileChange(prefix,JigsawPieces.resetPair(selected,workspace.read(selected),group),key); } catch (Exception failure) { error=failure.getMessage(); } }
                    } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored pair contains an unsupported integer type and remains read-only; other settings stay independent."); }ImGui.popID();
                }
                try {
                    boolean current=JigsawPieces.terminal(rules);var input=new ImBoolean(current);ImGui.textWrapped(JigsawPieces.help("terminal"));if (rules.token("terminal")==null) ImGui.textUnformatted("Implicit terminal default: false");
                    if (ImGui.checkbox("Physical terminal cap",input)) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.setTerminal(selected,workspace.read(selected),input.get())));
                    if (rules.token("terminal")==null && ImGui.button("Store terminal default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.setTerminal(selected,workspace.read(selected),false)));
                    if (rules.token("terminal")!=null && ImGui.button("Use terminal class default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPieces.resetTerminal(selected,workspace.read(selected))));
                } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored terminal flag is unsupported and remains read-only."); }
                for (String name : JigsawPieces.otherRules(rules)) ImGui.textWrapped(InspectorText.label(name,256)+" (unknown rule data; preserved read-only)");
            }
            if (root.token("rules")!=null && (rules==null || rules.fields().isEmpty()) && ImGui.button("Remove null/empty rule override")) { try { commitProfileChange("jigsawPiece#rules.",JigsawPieces.resetEmptyRules(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored rules are not an inline object and remain read-only."); }
        ImGui.treePop();
    }
    private String drawJigsawPoolKey(String label,String prefix,String family,String current) {
        ImString query=values.computeIfAbsent(prefix+"filter",ignored -> new ImString(1025));ImGui.inputText(label+" filter",query);
        String chosen=null;try {
            var matches=jigsawPoolCatalog.filter(workspace,family,query.get());ImGui.textUnformatted(matches.count()+" exact keys; showing up to 200. Target contents and aliases are unchecked.");
            if (ImGui.beginCombo(label,current.isEmpty() ? "Choose exact key" : InspectorText.label(current,128))) { int row=0;for (String key : matches.keys()) { ImGui.pushID(row++);if (ImGui.selectable(InspectorText.label(key,256).replace("#","\\u0023"),key.equals(current))) chosen=key;ImGui.popID(); }ImGui.endCombo(); }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
        return chosen;
    }
    private void drawJigsawPool(LosslessJson root) {
        if (!ImGui.collapsingHeader("Jigsaw pool")) return;
        ImGui.pushID("jigsaw-pool");
        ImGui.textWrapped("Literal pool settings only. Selection, chance gates, themes, terminal roles, graph reachability and object compatibility are not evaluated. Native selection tries only the direct fallback in the same selection, and can use it alone at maximum depth. Ordered membership changes can change seeded results. Unsupported previews remain disabled.");
        try {
            String current=JigsawPools.fallback(root);ImGui.textWrapped(JigsawPools.help("fallback"));
            if (root.token("fallback")==null || root.token("fallback").equals("null")) ImGui.textUnformatted("Implicit/native fallback: no direct fallback");
            String chosen=drawJigsawPoolKey("Direct fallback","jigsawPool#fallback.","jigsaw-pools",current);
            if (chosen!=null) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setFallback(selected,workspace.read(selected),chosen,workspace)));
            if (ImGui.button("Store no direct fallback")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setFallback(selected,workspace.read(selected),"",workspace)));
            if (root.token("fallback")!=null && ImGui.button("Use fallback class default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.reset(selected,workspace.read(selected),"fallback")));
            if (!current.isEmpty() && workspace.paths().contains("jigsaw-pools/"+current+".json") && ImGui.button("Open exact fallback JSON")) resourceNavigation.request(workspace,"jigsaw-pools/"+current+".json");
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored fallback is unsupported and preserved read-only."); }
        try {
            boolean current=JigsawPools.mandatory(root);var input=new ImBoolean(current);ImGui.textWrapped(JigsawPools.help("mandatoryFallback"));
            if (root.token("mandatoryFallback")==null) ImGui.textUnformatted("Implicit mandatoryFallback default: false");
            if (ImGui.checkbox("Mandatory direct fallback",input)) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setMandatory(selected,workspace.read(selected),input.get())));
            if (root.token("mandatoryFallback")==null && ImGui.button("Store mandatory fallback default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setMandatory(selected,workspace.read(selected),false)));
            if (root.token("mandatoryFallback")!=null && ImGui.button("Use mandatory fallback class default")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.reset(selected,workspace.read(selected),"mandatoryFallback")));
            if (current) ImGui.textWrapped("Native graphs require a direct fallback when this flag is true. VANILLA_PORTABLE rejects mandatoryFallback=true. Structure requireCaps can also require a fallback; no structure context is inferred here.");
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored mandatoryFallback is unsupported and preserved read-only."); }
        ImGui.textWrapped(JigsawPools.help("pieces"));ImGui.textWrapped("At most 64 memberships are editable. New entries inherit weight 1, chance 1 and empty=false unless explicitly created as empty. An empty membership terminates a branch without a physical piece; it cannot serve as a physical start piece or required terminal cap. Empty=true and a nonblank piece key together are rejected by the native compiler.");
        if ("null".equals(root.token("pieces"))) {
            ImGui.textWrapped("Stored null memberships are unsupported. Restore the implicit empty list to repair this override.");
            if (ImGui.button("Remove null membership override")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.resetEmpty(selected,workspace.read(selected))));
        } else try {
            var rows=JigsawPools.entries(root);
            if (rows.isEmpty()) { ImGui.textWrapped("No declared memberships. Start pools need usable physical entries; adding a membership does not prove native eligibility.");if (root.token("pieces")!=null && ImGui.button("Remove empty membership override")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.resetEmpty(selected,workspace.read(selected)))); }
            try { ImGui.textUnformatted("Declared positive-weight total: "+JigsawPools.totalWeight(rows)); } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Weight total unavailable for unsupported or nonpositive weights."); }
            ImString first=values.computeIfAbsent("jigsawPool#new.piece",ignored -> new ImString(1025));String choice=drawJigsawPoolKey("New physical piece","jigsawPool#new.","jigsaw-pieces",first.get());if (choice!=null) first.set(choice);drafts.update("jigsawPool#new.piece","",first.get());
            ImGui.beginDisabled(rows.size()>=JigsawPools.MAX_ENTRIES);
            if (ImGui.button("Append physical membership")) { try { commitProfileChange("jigsawPool#new.",JigsawPools.append(selected,workspace.read(selected),false,first.get(),workspace),"jigsawPool#new.piece"); } catch (Exception failure) { error=failure.getMessage(); } }
            ImGui.sameLine();if (ImGui.button("Append empty membership")) { try { commitProfileChange("jigsawPool#new.",JigsawPools.append(selected,workspace.read(selected),true,null,workspace),null); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.endDisabled();
            for (int index=0;index<rows.size();index++) {
                ImGui.pushID(index);int entry=index;var row=rows.get(index);String prefix=JigsawPools.prefix(index);
                if (ImGui.treeNode("Membership "+(index+1))) {
                    try {
                        String key=JigsawPools.piece(row);boolean empty=JigsawPools.empty(row);ImGui.textWrapped(empty ? "Explicit empty membership" : "Physical piece: "+InspectorText.label(key,256));
                        if (empty && !key.isBlank()) ImGui.textWrapped("Ambiguous stored entry: empty=true also declares a piece. Use an explicit conversion below to repair it.");
                        String next=drawJigsawPoolKey("Physical piece",prefix+"piece.","jigsaw-pieces",key);
                        if (next!=null) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setMembership(selected,workspace.read(selected),entry,false,next,workspace)));
                        if (ImGui.button("Use empty membership (remove piece reference)")) applyRoutingExtra(() -> workspace.replace(selected,JigsawPools.setMembership(selected,workspace.read(selected),entry,true,null,workspace)));
                        if (!key.isEmpty() && workspace.paths().contains("jigsaw-pieces/"+key+".json") && ImGui.button("Open exact piece JSON")) resourceNavigation.request(workspace,"jigsaw-pieces/"+key+".json");
                    } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored piece/empty types are unsupported; weight and chance remain independently editable."); }
                    for (String field : List.of("weight","chance")) {
                        ImGui.pushID(field);try {
                            if (field.equals("weight")) JigsawPools.weight(row);else JigsawPools.chance(row);
                            String stored=row.token(field)==null ? "1" : row.token(field),draft=prefix+field;ImGui.textWrapped(JigsawPools.help(field));if (row.token(field)==null) ImGui.textUnformatted("Implicit default: 1");
                            ImString input=values.computeIfAbsent(draft,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(draft,stored,input.get());
                            if (ImGui.button("Apply "+field)) commitRiverOverride(draft,() -> workspace.replace(selected,field.equals("weight") ? JigsawPools.setWeight(selected,workspace.read(selected),entry,input.get()) : JigsawPools.setChance(selected,workspace.read(selected),entry,input.get())));
                        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored "+field+" has an unsupported type and remains read-only."); }ImGui.popID();
                    }
                    ImGui.textWrapped("Chance is an independent membership eligibility gate before weighted selection. Zero never passes; one always passes. VANILLA_PORTABLE rejects nondefault chance gates. The editor does not compute effective probabilities.");
                    RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=JigsawPools.MAX_ENTRIES);if (ImGui.button("Duplicate membership")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(rows.size()<=1);if (ImGui.button("Remove membership")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
                    ImGui.beginDisabled(index==0);if (ImGui.button("Membership up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==rows.size()-1);if (ImGui.button("Membership down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                    if (action!=null) { try { commitProfileChange(JigsawPools.entriesPrefix(),JigsawPools.change(selected,workspace.read(selected),action,index),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();break; }
                    for (String field : JigsawPools.otherFields(row,true)) ImGui.textWrapped(InspectorText.label(field,256)+" (unknown membership data; preserved read-only)");
                    ImGui.treePop();
                }ImGui.popID();
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Membership list is read-only: "+invalid.getMessage()); }
        for (String field : JigsawPools.otherFields(root,false)) drawReadOnlyField("jigsawPool",field);
        ImGui.popID();
    }
    private void drawStructureDefinition(LosslessJson root) {
        if (!ImGui.collapsingHeader("Structure assembly definition")) return;
        ImGui.pushID("structure-definition");
        ImGui.textWrapped("Assembly controls and bounded planar workcell authoring. Pool/piece contents, graph closure, canonical object fit, themes, placement context and native compatibility remain unvalidated. Settings are not adjusted automatically; previews remain disabled.");
        try {
            String current=StructureDefinitions.value(root,"startPool");
            ImGui.textWrapped(StructureDefinitions.help("startPool"));
            ImGui.inputText("Filter exact pool files",structurePoolFilter);
            var matches=structurePools.filter(workspace,structurePoolFilter.get());
            if (ImGui.beginCombo("Start pool",current.isEmpty() ? "Choose an exact pool" : InspectorText.label(current,128))) {
                int row=0;for (String key : matches.keys()) { ImGui.pushID(row++);if (ImGui.selectable("Choose pool",key.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setPool(selected,workspace.read(selected),key,workspace)));ImGui.sameLine();ImGui.textUnformatted(InspectorText.label(key,256));ImGui.popID(); }
                ImGui.endCombo();
            }
            ImGui.textWrapped(matches.count()+" matching pool files; "+matches.keys().size()+" shown (maximum 200). File presence does not validate a pool or assembly graph.");
            if (!current.isEmpty() && structurePools.choices(workspace).contains(current) && ImGui.button("Open exact start-pool file")) { try { resourceNavigation.request(workspace,"jigsaw-pools/"+current+".json"); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(unsupported.getMessage()); }
        for (String field : StructureDefinitions.NUMBERS) {
            ImGui.pushID(field);ImGui.textWrapped(StructureDefinitions.help(field));
            try {
                int[] input={Integer.parseInt(StructureDefinitions.value(root,field))};
                if (root.token(field)==null) ImGui.textUnformatted("Implicit default: "+input[0]);
                if (ImGui.sliderInt(field,input,StructureDefinitions.minimum(field),StructureDefinitions.maximum(field))) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setInteger(selected,workspace.read(selected),field,Integer.toString(input[0]))));
                if (root.token(field)==null && ImGui.button("Store displayed default")) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setInteger(selected,workspace.read(selected),field,StructureDefinitions.defaultValue(field))));
                if (root.token(field)!=null && ImGui.button("Use class default")) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.reset(selected,workspace.read(selected),field)));
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(unsupported.getMessage()); }
            ImGui.popID();
        }
        for (String field : StructureDefinitions.ENUMS) {
            ImGui.pushID(field);ImGui.textWrapped(StructureDefinitions.help(field));
            try {
                String current=StructureDefinitions.value(root,field);boolean implicit=root.token(field)==null || root.token(field).equals("null");
                if (ImGui.beginCombo(field,(implicit ? "Default: " : "")+InspectorText.label(current,128))) { for (String choice : StructureDefinitions.choices(field)) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setChoice(selected,workspace.read(selected),field,choice)));ImGui.endCombo(); }
                if (root.token(field)!=null && ImGui.button("Use class default")) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.reset(selected,workspace.read(selected),field)));
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(unsupported.getMessage()); }
            ImGui.popID();
        }
        try {
            var caps=new ImBoolean(Boolean.parseBoolean(StructureDefinitions.value(root,"requireCaps")));ImGui.textWrapped(StructureDefinitions.help("requireCaps"));
            if (root.token("requireCaps")==null) ImGui.textUnformatted("Implicit default: "+caps.get());
            if (ImGui.checkbox("Require physical terminal caps",caps)) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setCaps(selected,workspace.read(selected),caps.get())));
            if (root.token("requireCaps")==null && ImGui.button("Store cap default")) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.setCaps(selected,workspace.read(selected),Boolean.parseBoolean(StructureDefinitions.defaultValue("requireCaps")))));
            if (root.token("requireCaps")!=null && ImGui.button("Use class cap default")) applyRoutingExtra(() -> workspace.replace(selected,StructureDefinitions.reset(selected,workspace.read(selected),"requireCaps")));
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(unsupported.getMessage()); }
        for (String finding : StructureDefinitions.advisories(root)) ImGui.textWrapped(finding);
        ImGui.textWrapped("Portability findings cover three local rules only and do not gate Apply, Save or Export. Underground placement can override placeMode; multi-piece structures generally retain assembled piece positions.");
        drawStructureWorkcells(root);
        drawStructureThemes(root);
        drawStructureVanillaSource(root);
        drawFloatingReplacements(root,FloatingObjectReplacements.Context.structureRoot());
        if (ImGui.treeNode("Other structure data (read-only)")) { for (String field : StructureDefinitions.readOnlyFields(root)) drawReadOnlyField("structureDefinition",field);ImGui.treePop(); }
        ImGui.popID();
    }
    private void drawStructureVanillaSource(LosslessJson root) {
        if (!ImGui.treeNode("Vanilla structure source")) return;
        ImGui.textWrapped(StructureDefinitions.sourceHelp());ImGui.textWrapped(StructureDefinitions.sourceAdvisory(root));
        ImGui.textWrapped("Use an empty value or an exact lowercase namespace:path key, up to 256 characters. Editing this alias does not update placements or native registry resources. Runtime effects are not previewed.");
        try {
            String stored=StructureDefinitions.vanillaSource(root),key="structureSource#vanillaSource";
            if (root.token("vanillaSource")==null) ImGui.textUnformatted("Implicit default: empty source");
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(1025,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Native source key",input);drafts.update(key,stored,input.get());
            if (ImGui.button("Apply source key")) commitRiverOverride(key,() -> workspace.replace(selected,StructureDefinitions.setVanillaSource(selected,workspace.read(selected),input.get())));
            if (root.token("vanillaSource")!=null && ImGui.button("Use empty source default")) commitRiverOverride(key,() -> workspace.replace(selected,StructureDefinitions.resetVanillaSource(selected,workspace.read(selected))));
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored source is unsupported and preserved read-only."); }
        ImGui.treePop();
    }
    private void drawStructureThemes(LosslessJson root) {
        if (!ImGui.treeNode("Structure themes")) return;
        ImGui.textWrapped(StructureThemes.help("themeSets"));
        ImGui.textWrapped("Positive signed 32-bit weights; at most 64 entries and 256 characters for new keys. Keys are exact and case-sensitive, with no leading/trailing ASCII whitespace. Existing keys stay fixed because jigsaw pieces refer to them. Reordering changes seeded selection; no theme is sampled here. Graph reachability and start-piece eligibility remain unchecked.");
        String token=root.token("themeSets");
        if ("null".equals(token)) {
            ImGui.textWrapped("Stored null themeSets is invalid in the native compiler. Restoring the implicit default removes only this null field.");
            if (ImGui.button("Restore implicit unthemed default")) applyRoutingExtra(() -> workspace.replace(selected,StructureThemes.resetEmpty(selected,workspace.read(selected))));
            ImGui.treePop();return;
        }
        try {
            var rows=StructureThemes.entries(root);
            if (rows.isEmpty()) {
                ImGui.textWrapped(token==null ? "Implicit default: no declared themes; native selection uses the unthemed path." : "Explicit empty list: native selection uses the unthemed path.");
                if (token!=null && ImGui.button("Remove empty theme override")) applyRoutingExtra(() -> workspace.replace(selected,StructureThemes.resetEmpty(selected,workspace.read(selected))));
            }
            try { ImGui.textUnformatted("Applied positive-weight total: "+StructureThemes.totalWeight(rows)); }
            catch (IllegalArgumentException invalid) { ImGui.textWrapped("Weight total unavailable until unsupported or nonpositive weights are repaired."); }
            String add=StructureThemes.newPrefix(),addDraft=add+"pair";
            ImString newKey=values.computeIfAbsent(add+"key",ignored -> new ImString(1024)),newWeight=values.computeIfAbsent(add+"weight",ignored -> new ImString("1",64));
            ImGui.inputText("New exact theme key",newKey);ImGui.inputText("New theme weight",newWeight);drafts.update(addDraft,"|1",newKey.get()+"|"+newWeight.get());
            ImGui.beginDisabled(rows.size()>=StructureThemes.MAX_ENTRIES);if (ImGui.button("Add declared theme")) {
                try { commitProfileChange(add,StructureThemes.append(selected,workspace.read(selected),newKey.get(),newWeight.get()),addDraft); }
                catch (Exception failure) { error=failure.getMessage(); }
            }ImGui.endDisabled();
            for (int index=0;index<rows.size();index++) {
                int entry=index;var row=rows.get(index);String prefix=StructureThemes.prefix(index);ImGui.pushID(index);
                if (ImGui.treeNode("Theme entry "+(index+1))) {
                    try {
                        String key=StructureThemes.key(row);ImGui.textWrapped("Fixed key: "+(key.isEmpty() ? "(absent or empty required key)" : InspectorText.label(key,256)));
                        if (ImGui.button("Review global piece tag mentions")) { try { openStructureThemeReview(key);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
                    } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored key has an unsupported type and remains read-only."); }
                    try {
                        StructureThemes.weight(row);String stored=row.token("weight")==null ? "1" : row.token("weight"),key=prefix+"weight";
                        if (row.token("weight")==null) ImGui.textUnformatted("Implicit weight default: 1");
                        ImString weight=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(64,stored.length()+1)));ImGui.inputText("Weight",weight);drafts.update(key,stored,weight.get());
                        if (ImGui.button("Apply positive weight")) commitRiverOverride(key,() -> workspace.replace(selected,StructureThemes.setWeight(selected,workspace.read(selected),entry,weight.get())));
                    } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored weight is unsupported (read-only)."); }
                    String copy=prefix+"copy.",copyKey=copy+"key";ImString duplicateKey=values.computeIfAbsent(copyKey,ignored -> new ImString(1024));
                    ImGui.inputText("Copy applied entry as new key",duplicateKey);drafts.update(copyKey,"",duplicateKey.get());
                    ImGui.beginDisabled(rows.size()>=StructureThemes.MAX_ENTRIES);if (ImGui.button("Append copy with new key")) {
                        try { commitProfileChange(copy,StructureThemes.copy(selected,workspace.read(selected),index,duplicateKey.get()),copyKey); }
                        catch (Exception failure) { error=failure.getMessage(); }
                    }ImGui.endDisabled();
                    StructureThemes.Direction move=null;
                    ImGui.beginDisabled(index==0);if (ImGui.button("Move theme up")) move=StructureThemes.Direction.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==rows.size()-1);if (ImGui.button("Move theme down")) move=StructureThemes.Direction.DOWN;ImGui.endDisabled();
                    if (move!=null) { try { commitProfileChange(StructureThemes.entriesPrefix(),StructureThemes.move(selected,workspace.read(selected),index,move),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();break; }
                    for (String field : StructureThemes.otherFields(row)) ImGui.textWrapped(InspectorText.label(field,256)+" (unknown theme data; preserved read-only)");
                    ImGui.treePop();
                }
                ImGui.popID();
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Theme list is read-only: "+invalid.getMessage()); }
        ImGui.treePop();
    }
    private void drawStructureWorkcells(LosslessJson root) {
        if (!ImGui.treeNode("Planar workcell bounds")) return;
        ImGui.textWrapped("Used by PLANAR_JIGSAW only. Width/depth are 3..128, height 1..192, and volume is at most "+StructureWorkcells.maximumVolume()+" blocks for every cell, including disabled cells. All three dimensions apply together. Object fit and graph viability are not checked here.");
        if (ImGui.treeNode("Legacy fallback dimensions")) {
            String token=root.token("cellSize");ImGui.textWrapped(token==null ? "Absent cellSize uses the structure constructor's 15 x 15 x 15 dimensions." : token.equals("null") ? "Stored null cellSize uses the resolver's 16 x 16 x 16 fallback." : "Stored inline cellSize; missing axes use IrisPosition defaults of zero. Snippets and unsupported axes stay read-only.");
            drawStructureWorkcellBounds(root,-1,null);ImGui.treePop();
        }
        try {
            var entries=StructureWorkcells.entries(root);
            if (entries.isEmpty()) {
                ImGui.textWrapped("No explicit workcell map: all six archetypes use the applied legacy fallback dimensions. Creation preserves those applied bounds; unapplied fallback sliders remain separate.");
                if (ImGui.button("Create six workcells from applied fallback bounds")) {
                    try { commitProfileChange(StructureWorkcells.entriesPrefix(),StructureWorkcells.create(selected,workspace.read(selected)),null); }
                    catch (Exception failure) { error=failure.getMessage(); }
                }
            } else {
                ImGui.textWrapped("Explicit workcells override cellSize. Identity is fixed to a complete map with one entry per archetype; source order is retained.");
                for (int index=0;index<entries.size();index++) {
                    int cell=index;ImGui.pushID(index);String archetype=StructureWorkcells.archetype(entries.get(index));
                    if (ImGui.treeNode(archetype)) {
                        drawStructureWorkcellBounds(root,index,entries.get(index));
                        try {
                            var enabled=new ImBoolean(StructureWorkcells.entryEnabled(entries.get(index)));if (entries.get(index).token("enabled")==null) ImGui.textUnformatted("Implicit enabled default: true");
                            if (ImGui.checkbox("Participates in planar assembly",enabled)) applyRoutingExtra(() -> workspace.replace(selected,StructureWorkcells.setEnabled(selected,workspace.read(selected),cell,enabled.get())));
                            if (entries.get(index).token("enabled")==null && ImGui.button("Store enabled default")) applyRoutingExtra(() -> workspace.replace(selected,StructureWorkcells.setEnabled(selected,workspace.read(selected),cell,true)));
                        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(unsupported.getMessage()); }
                        for (String field : StructureWorkcells.entryOtherFields(entries.get(index))) drawReadOnlyField("structureWorkcell",field);
                        ImGui.treePop();
                    }
                    ImGui.popID();
                }
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Workcell list is read-only: "+unsupported.getMessage()); }
        ImGui.treePop();
    }
    private void drawStructureWorkcellBounds(LosslessJson root,int index,LosslessJson entry) {
        String prefix=StructureWorkcells.prefix(index),draftKey=prefix+"bounds";ImGui.pushID(index);
        try {
            var size=index<0 ? StructureWorkcells.size(root,index) : StructureWorkcells.entrySize(entry);String[] stored={Integer.toString(size.width()),Integer.toString(size.height()),Integer.toString(size.depth())};ImString[] inputs=new ImString[3];
            for (int axis=0;axis<3;axis++) {
                String field=StructureWorkcells.DIMENSIONS.get(axis),key=prefix+field,value=stored[axis];
                inputs[axis]=values.computeIfAbsent(key,ignored -> new ImString(value,64));int[] slider={Integer.parseInt(inputs[axis].get())};
                if (ImGui.sliderInt(field,slider,StructureWorkcells.minimum(field),StructureWorkcells.maximum(field))) inputs[axis].set(Integer.toString(slider[0]));
            }
            drafts.update(draftKey,size.key(),inputs[0].get()+"|"+inputs[1].get()+"|"+inputs[2].get());
            if (ImGui.button(index<0 ? "Store legacy bounds together" : "Apply cell bounds together")) {
                try { commitProfileChange(prefix,StructureWorkcells.setBounds(selected,workspace.read(selected),index,inputs[0].get(),inputs[1].get(),inputs[2].get()),draftKey); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
            if (index<0) for (String field : StructureWorkcells.otherFields(root,index)) ImGui.textWrapped(InspectorText.label(field,256)+" (unknown legacy data; preserved read-only)");
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored dimensions are read-only: "+unsupported.getMessage()); }
        ImGui.popID();
    }
    private void drawReadOnlyField(String group,String field) {
        ImGui.textWrapped(InspectorText.label(field,256)+": "+FieldCoverage.readOnlyReason(group,field));
    }
    private void drawCoverage() {
        List<String> groups = FieldCoverage.groups(selected);
        if (groups.isEmpty() || !ImGui.collapsingHeader("Pinned-source field coverage (partial)")) return;
        ImGui.textWrapped("Direct declared fields in selected source classes only; inherited and other nested schemas are not inventoried. Unclassified/unimplemented fields stay read-only.");
        for (String group : groups) {
            var fields = FieldCoverage.fields(group);
            long unclassified = fields.stream().filter(field -> field.classification().equals("unclassified")).count();
            long implemented = fields.stream().filter(FieldCoverage.Field::implemented).count();
            long readOnly=fields.stream().filter(field -> field.classification().equals("read-only")).count();
            long pending=fields.stream().filter(field -> field.classification().equals("generation") && !field.implemented()).count();
            ImGui.textWrapped(group + ": " + implemented + " authored, " + pending + " controls pending, " + readOnly + " read-only, " + unclassified + " unclassified / " + fields.size() + " inventoried");
            if (ImGui.treeNode("Fields##coverage-" + group)) {
                for (var field : fields) {
                    String status = field.implemented() ? "authored" : field.classification().equals("generation") ? "generation: control pending" : field.classification();
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
        drawProceduralObjects(document);
        drawDepositVariants(document,"regions");
        drawDeposits(document,"regions");
        drawOres(document,"regions");
        drawStructurePlacements(document);
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
        ImGui.textWrapped("Noise fracture lists have bounded recursive controls. Unsupported data remains read-only; no noise is sampled.");
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
        ImGui.textWrapped("Add creates an empty object using Iris defaults. Noise fracture lists have bounded recursive controls. List order affects generation.");
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
        drawNoiseFractures(object,target);
        if (ImGui.treeNode("Other noise data (read-only)##"+target.draftPrefix())) { for (String path : NoiseFractures.readOnlyPaths(object,target.fracturePath().size())) ImGui.textWrapped(path);ImGui.treePop(); }
    }
    private void drawNoiseFractures(LosslessJson noise,NoiseFields.Target target) {
        String prefix=NoiseFractures.listPrefix(target);
        if (!ImGui.treeNode("Coordinate fracture children##"+prefix)) return;
        ImGui.textWrapped(NoiseFractures.help());ImGui.textWrapped("Enabled children offset X/Z using child noise and half-opacity centering. List order changes seed salts. No noise is sampled by this editor. Style fracture objects have their own bounded recursive controls.");
        ImGui.textWrapped("Editor limits: "+NoiseFractures.MAXIMUM_DEPTH+" nested child levels and "+NoiseFractures.MAXIMUM_CHILDREN+" children per list. These are editor limits, not native schema limits. Snippets, mixed/null lists and deeper or oversized lists remain read-only.");
        if (target.fracturePath().size()>=NoiseFractures.MAXIMUM_DEPTH) { ImGui.textWrapped("This node's child list is beyond the editor depth limit. Existing data is preserved.");ImGui.treePop();return; }
        List<LosslessJson> children;
        try { children=NoiseFractures.entries(noise); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored fracture list is null, a snippet or contains non-object entries (read-only).");ImGui.treePop();return; }
        if (children!=null && children.size()>NoiseFractures.MAXIMUM_CHILDREN) { ImGui.textWrapped("Stored list exceeds the editor size limit (read-only).");ImGui.treePop();return; }
        ImGui.textWrapped("Add uses a default child object. Removing the final child removes the fracture member, restoring Iris's absent empty-list default. An existing explicit empty list can be repaired with Add.");
        ImGui.beginDisabled(children!=null && children.size()>=NoiseFractures.MAXIMUM_CHILDREN);
        boolean add=ImGui.button("Add default fracture child##"+prefix);ImGui.endDisabled();
        if (add) { changeNoiseFracture(target,CompositeNoise.Action.ADD,-1);ImGui.treePop();return; }
        if (children!=null) for (int index=0;index<children.size();index++) {
            if (!ImGui.treeNode("Fracture child "+index+"##"+prefix+index)) continue;
            CompositeNoise.Action action=null;ImGui.beginDisabled(children.size()>=NoiseFractures.MAXIMUM_CHILDREN);
            if (ImGui.button("Duplicate child")) action=CompositeNoise.Action.DUPLICATE;
            ImGui.endDisabled();ImGui.sameLine();if (ImGui.button(children.size()==1 ? "Remove final child (restore absence)" : "Remove child")) action=CompositeNoise.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move child up")) action=CompositeNoise.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==children.size()-1);if (ImGui.button("Move child down")) action=CompositeNoise.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { ImGui.treePop();changeNoiseFracture(target,action,index);ImGui.treePop();return; }
            drawNoiseFields(children.get(index),target.child(index));ImGui.treePop();
        }
        ImGui.treePop();
    }
    private void changeNoiseFracture(NoiseFields.Target target,CompositeNoise.Action action,int index) {
        try { commitProfileChange(NoiseFractures.listPrefix(target),NoiseFractures.change(selected,workspace.read(selected),target,action,index),null); }
        catch (Exception failure) { error=failure.getMessage(); }
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
        ImGui.textWrapped("Expression and image-map references can override the base style. Recursive fracture children distort its coordinate field; no style source is loaded or sampled here.");
        ImGui.textWrapped(target.strictBounds() ? "This density style requires positive cellular zoom even when cellular frequency is zero."
                : "Cellular frequency 0 disables cellularization. Set a positive cellular zoom before enabling it.");
        if (style == null) {
            ImGui.textWrapped(target.optionalFloating() || target.optionalFracture() || target.expressionSource() ? "Absent or null disables this style. Explicit enablement uses the generator-style class defaults ("+NoiseStyleFields.contextDefaultLabel(target)+")." : "Absent style uses " + NoiseStyleFields.contextDefaultLabel(target) + ". Enable it explicitly before changing style settings.");
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
        drawStyleSources(style,target);
        if (target.optionalFracture()) drawFractureMultiplier(style,target);
        drawStyleFracture(style,target);
        if (ImGui.treeNode("Other style data (read-only)##"+target.draftPrefix())) { for (String path : StyleFractures.readOnlyPaths(style,target.fractureDepth(),target.optionalFracture() || target.placementWarp())) ImGui.textWrapped(path);ImGui.treePop(); }
    }
    private void drawFractureMultiplier(LosslessJson style,NoiseStyleFields.Target child) {
        ImGui.pushID(child.draftPrefix()+"multiplier");ImGui.textWrapped(StyleFractures.multiplierConstraints());String token=style.token("multiplier"),key=child.draftPrefix()+"multiplier";
        if (token==null) {
            if (ImGui.button("Enable child multiplier ("+StyleFractures.multiplierDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,StyleFractures.enableMultiplier(selected,workspace.read(selected),child)));
        } else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored multiplier has an unsupported type (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText("Child multiplier",input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply child multiplier")) commitRiverOverride(key,() -> workspace.replace(selected,StyleFractures.setMultiplier(selected,workspace.read(selected),child,input.get())));
        }
        ImGui.popID();
    }
    private void drawStyleFracture(LosslessJson parent,NoiseStyleFields.Target target) {
        if (!ImGui.treeNode("Style fracture child##"+target.draftPrefix())) return;
        ImGui.textWrapped(StyleFractures.help());ImGui.textWrapped("The child uses its own generic style defaults and a separate positive multiplier. Native distortion runs before cellularization, parent zoom and caching. No native factories or noise sampling run here.");
        ImGui.textWrapped("Editor limit: "+StyleFractures.MAXIMUM_DEPTH+" nested style-fracture levels, separate from noise-fracture lists. Deeper and unsupported stored data is preserved.");
        if (target.fractureDepth()>=StyleFractures.MAXIMUM_DEPTH) { ImGui.textWrapped("This child is beyond the editor depth limit (read-only).");ImGui.treePop();return; }
        var child=target.child();drawStyleFields(parent,child);
        try {
            if (NoiseStyleFields.object(parent,child)!=null) {
                ImGui.textWrapped("Disable replaces this entire child object, including its nested and advanced data, with null.");
                if (ImGui.button("Disable style fracture (write null)##"+child.draftPrefix())) {
                    try {
                        if (drafts.transitionUnder(child.draftPrefix(),() -> !drafts.anyUnder(child.draftPrefix()) || JOptionPane.showConfirmDialog(null,"Discard unapplied text within this style-fracture child and disable it?","Unapplied style-fracture text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION,() -> workspace.replace(selected,StyleFractures.disable(selected,workspace.read(selected),child)))) { values.keySet().removeIf(key -> key.startsWith(child.draftPrefix()));error=null; }
                    } catch (Exception failure) { error=failure.getMessage(); }
                }
            }
        } catch (IllegalArgumentException unsupported) { /* The shared inspector explains unsupported types. */ }
        ImGui.treePop();
    }
    private void drawStyleSources(LosslessJson style,NoiseStyleFields.Target target) {
        if (!ImGui.treeNode("Sources and cache##"+target.draftPrefix())) return;
        ImGui.textWrapped("A non-null expression takes priority over imageMap. If the expression cannot load, Iris falls back to the base style without trying the image map. Clear expression to allow imageMap. File presence is checked; content, image-map type, parser and runtime compatibility are not validated.");
        for (String field : NoiseStyleSources.REFERENCES) {
            ImGui.pushID(field);String token=style.token(field);
            if (!NoiseStyleSources.supported(style,field)) ImGui.textWrapped(field+": stored type mismatch (read-only)");
            else {
                String current=token==null || token.equals("null") ? null : style.stringToken(field);var choices=styleSourceCatalogs.choices(workspace,field);
                if (current!=null && !choices.contains(current)) ImGui.textWrapped("Stored reference is outside the exact in-pack catalog and is preserved until replaced or cleared.");
                if (ImGui.beginCombo(field,current==null ? "Absent/null" : current.length()>128 ? "Stored reference" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,NoiseStyleSources.setReference(selected,workspace.read(selected),target,field,choice,workspace)));ImGui.endCombo(); }
                if (current!=null && ImGui.button("Clear reference (null)")) applyRoutingExtra(() -> workspace.replace(selected,NoiseStyleSources.setReference(selected,workspace.read(selected),target,field,null,workspace)));
                if (current!=null && choices.contains(current) && ImGui.button("Open exact source file")) { try { resourceNavigation.request(workspace,NoiseStyleSources.family(field)+"/"+current+".json"); } catch (Exception failure) { error=failure.getMessage(); } }
                if (choices.isEmpty()) ImGui.textWrapped("No exact "+NoiseStyleSources.family(field)+" JSON resources in this pack.");
            }
            ImGui.popID();
        }
        ImGui.textWrapped("Cache size is an exact integer 0..8192. Positive values override the caller fallback; 0 uses that fallback when present. Native Iris may persist cache files. This editor does not create or manage native noise caches.");
        String token=style.token("cacheSize"),key=target.draftPrefix()+"cacheSize";
        if (token==null) { if (ImGui.button("Enable cache size "+NoiseStyleSources.cacheDefault())) applyRoutingExtra(() -> workspace.replace(selected,NoiseStyleSources.enableCache(selected,workspace.read(selected),target))); }
        else if (!NoiseStyleSources.supported(style,"cacheSize")) ImGui.textWrapped("Stored cache size has an unsupported type or integer width (read-only).");
        else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Cache size",input);drafts.update(key,token,input.get());if (ImGui.button("Apply cache size")) commitRiverOverride(key,() -> workspace.replace(selected,NoiseStyleSources.setCache(selected,workspace.read(selected),target,input.get()))); }
        ImGui.treePop();
    }
    private void enableStyle(NoiseStyleFields.Target target, String field) {
        try { workspace.replace(selected,target.optionalFracture() && field.equals("style") ? StyleFractures.enable(selected,workspace.read(selected),target) : NoiseStyleFields.enable(selected,workspace.read(selected),target,field)); error = null; }
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
        drawStructurePlacements(document);
        drawCustomBiomes(document);
        drawResourceSelectors(document);
        drawSurfaceLayerFallback(document);
        drawBiomeLayers(document);
        drawBiomeDecorators(document);
        drawBiomeSlabWall(document);
        drawRootObjects(document,FloatingObjectPlacements.BIOME_ROOT);
        drawProceduralObjects(document);
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
            for (String field : document.fields()) if (!BiomeFields.EDITABLE.contains(field) && !NoiseStyleFields.BIOME_FIELDS.contains(field) && !BiomeChildren.EDITABLE.contains(field) && !Terrain3DFields.OBJECT_FIELDS.contains(field) && !CaveProfileFields.OBJECT_FIELDS.contains(field) && !RiverPolicyFields.OBJECT_FIELDS.contains(field) && !SurfaceLayerFallbacks.OBJECT_FIELDS.contains(field) && !BiomeLayers.LIST_FIELDS.contains(field) && !BiomeDerivatives.EDITABLE.contains(field) && !BiomeSlabWall.OBJECT_FIELDS.contains(field) && !FloatingBiomes.LIST_FIELDS.contains(field) && !ProceduralObjects.OBJECT_FIELDS.contains(field) && !BiomeObjectPlacements.LIST_FIELDS.contains(field) && !OreGenerators.LIST_FIELDS.contains(field) && !DepositGenerators.LIST_FIELDS.contains(field) && !DepositVariants.LIST_FIELDS.contains(field) && !BiomeOreHosts.LIST_FIELDS.contains(field) && !BiomeDecorators.LIST_FIELDS.contains(field) && !CustomBiomes.LIST_FIELDS.contains(field) && !ResourceSelectors.BIOME_FIELDS.contains(field) && !StructurePlacements.LIST_FIELDS.contains(field) && !field.equals("generators")) ImGui.textWrapped(field);
    }
    private void drawImageMapDefinition(LosslessJson document) {
        ImGui.textWrapped("Image-map definition authoring. File choices check exact in-pack lowercase .png paths only. PNG format, color mode/bit depth, dimensions, coordinate transforms, legend targets, binding compatibility and native compilation/output are unvalidated. Map sampling and generation previews remain disabled.");
        for (String finding : ImageMapDefinitions.findings(document)) ImGui.textWrapped("Applied authored-data check: "+finding+" (does not block Save/Export)");
        try {
            String source=ImageMapDefinitions.text(document,"source");var choices=imageMapSources.choices(workspace);
            if (!source.isEmpty() && !choices.contains(source)) ImGui.textWrapped("Stored source has no exact lowercase .png file match; native loader aliases are unchecked.");
            if (ImGui.beginCombo("PNG source",source.isEmpty() ? "Choose an existing PNG file" : source.length()>128 || source.indexOf(0)>=0 ? "Stored source" : source)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(source))) applyRoutingExtra(() -> workspace.replace(selected,ImageMapDefinitions.setSource(selected,workspace.read(selected),choice,workspace)));ImGui.endCombo(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored source is not a string (read-only)."); }
        for (String field : ImageMapDefinitions.ENUMS) {
            ImGui.pushID("imageMap.choice."+field);ImGui.textWrapped(ImageMapDefinitions.help(field));
            try { String current=ImageMapDefinitions.choice(document,field);if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored enum value" : current)) { for (String choice : ImageMapDefinitions.choices(field)) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,ImageMapDefinitions.setChoice(selected,workspace.read(selected),field,choice)));ImGui.endCombo(); } }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored enum has an unsupported type (read-only)."); }ImGui.popID();
        }
        ImGui.textWrapped("Settings apply independently. Applied-data notices describe known cross-field constraints; changing type does not clear legends, smoothing, alpha or fallback settings.");
        for (String field : ImageMapDefinitions.TOGGLES) {
            String token=document.token(field);if (token==null || token.equals("true") || token.equals("false")) { boolean current=Boolean.parseBoolean(token==null ? ImageMapDefinitions.defaultToken(field) : token);if (ImGui.checkbox(field+" (default "+ImageMapDefinitions.defaultToken(field)+")",current)) applyRoutingExtra(() -> workspace.replace(selected,ImageMapDefinitions.setToggle(selected,workspace.read(selected),field,!current))); }
            else ImGui.textWrapped(field+": stored type is not boolean (read-only).");
        }
        for (String field : ImageMapDefinitions.NUMBERS) if (!List.of("minimumHeight","maximumHeight").contains(field)) {
            ImGui.pushID("imageMap.number."+field);ImGui.textWrapped(ImageMapDefinitions.help(field));ImGui.textWrapped(ImageMapDefinitions.constraints(field));String token=document.token(field),key=ImageMapDefinitions.prefix()+field;
            if (token==null) { if (ImGui.button("Enable "+field+" ("+ImageMapDefinitions.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,ImageMapDefinitions.enableScalar(selected,workspace.read(selected),field))); }
            else if (!ImageMapDefinitions.numericSupported(document,field)) ImGui.textWrapped("Stored number has an unsupported type, width or finite range (read-only).");
            else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,ImageMapDefinitions.setScalar(selected,workspace.read(selected),field,input.get()))); }ImGui.popID();
        }
        if (ImGui.treeNode("Height decoding range")) {
            ImGui.textWrapped("Finite absolute-world-Y minimum/maximum; equal values are supported. Apply the ordered pair together. Offset/clamp remain independent; decoded output is not evaluated.");
            try {
                String low=ImageMapDefinitions.height(document,"minimumHeight"),high=ImageMapDefinitions.height(document,"maximumHeight"),prefix=ImageMapDefinitions.prefix()+"height.",key=prefix+"pair";
                ImString min=values.computeIfAbsent(prefix+"min",ignored -> new ImString(low,Math.max(128,low.length()+1))),max=values.computeIfAbsent(prefix+"max",ignored -> new ImString(high,Math.max(128,high.length()+1)));
                ImGui.inputText("Minimum absolute height",min);ImGui.inputText("Maximum absolute height",max);drafts.update(key,TerrainTransforms.draftPair(low,high),TerrainTransforms.draftPair(min.get(),max.get()));
                if (ImGui.button("Apply height pair")) { try { commitProfileChange(prefix,ImageMapDefinitions.setHeights(selected,workspace.read(selected),min.get(),max.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored height pair has unsupported values (read-only)."); }ImGui.treePop();
        }
        for (String field : ImageMapDefinitions.ORIGINS) drawImageMapOrigin(document,field);
        try {
            String current=ImageMapDefinitions.text(document,"fallbackTarget"),key=ImageMapDefinitions.prefix()+"fallbackTarget";if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Fallback target (literal string)",input);drafts.update(key,current,input.get());
            if (ImGui.button("Apply fallback target")) commitRiverOverride(key,() -> workspace.replace(selected,ImageMapDefinitions.setFallbackTarget(selected,workspace.read(selected),input.get())));
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored fallback target is not editable text (read-only)."); }
        drawImageMapColors(document);
        if (ImGui.collapsingHeader("Other image-map fields (read-only)")) for (String field : document.fields()) if (!ImageMapDefinitions.EDITABLE.contains(field)) ImGui.textWrapped(field);
    }
    private void drawImageMapOrigin(LosslessJson document,String field) {
        if (!ImGui.treeNode(field+" coordinates")) return;
        ImGui.textWrapped(ImageMapDefinitions.help(field));ImGui.textWrapped("Finite X/Z pair, with class defaults 0/0. sourceOrigin Z means source-image Y pixels. The editor does not compute transformed coordinates.");
        String prefix=ImageMapDefinitions.prefix()+field+".",key=prefix+"pair";
        try {
            String first=ImageMapDefinitions.coordinate(document,field,"x"),second=ImageMapDefinitions.coordinate(document,field,"z");
            ImString x=values.computeIfAbsent(prefix+"x",ignored -> new ImString(first,Math.max(128,first.length()+1))),z=values.computeIfAbsent(prefix+"z",ignored -> new ImString(second,Math.max(128,second.length()+1)));
            ImGui.inputText("X",x);ImGui.inputText("Z / image Y",z);drafts.update(key,TerrainTransforms.draftPair(first,second),TerrainTransforms.draftPair(x.get(),z.get()));
            if (ImGui.button("Apply coordinate pair")) { try { commitProfileChange(prefix,ImageMapDefinitions.setOrigin(selected,workspace.read(selected),field,x.get(),z.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            var origin=ImageMapDefinitions.origin(document,field);if (origin!=null) {
                for (String member : origin.fields()) if (!ImageMapDefinitions.AXES.contains(member)) ImGui.textWrapped(field+"."+member+" (read-only)");
                ImGui.textWrapped("Remove discards this entire origin object, including advanced data, and restores its absent 0/0 default.");
                if (ImGui.button("Remove origin override")) { try { commitProfileChange(prefix,ImageMapDefinitions.removeOrigin(selected,workspace.read(selected),field),null); } catch (Exception failure) { error=failure.getMessage(); } }
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored origin is null, a snippet or contains unsupported coordinates (read-only)."); }ImGui.treePop();
    }
    private void drawImageMapColors(LosslessJson document) {
        if (!ImGui.treeNode("Color legend")) return;
        ImGui.textWrapped("Use #RRGGBB keys and nonblank literal targets. Hex letter case is preserved, but duplicate raw RGB colors are rejected ignoring case. Target resources/blocks depend on the binding role and remain unchecked. Native COLOR_MAP requires a legend; a final removal restores absence and is reported until repaired or type changes.");
        List<LosslessJson.StringProperty> rows;
        try { rows=ImageMapDefinitions.colors(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored colors is null/mixed/unsupported (read-only).");ImGui.treePop();return; }
        if (rows==null) { if (ImGui.button("Enable explicit empty legend")) applyRoutingExtra(() -> workspace.replace(selected,ImageMapDefinitions.enableColors(selected,workspace.read(selected))));ImGui.treePop();return; }
        if (rows.size()>ImageMapDefinitions.MAXIMUM_COLORS) { ImGui.textWrapped("Above the 64-color editor limit (read-only).");ImGui.treePop();return; }
        String prefix=ImageMapDefinitions.prefix()+"colors#",key=prefix+"new.pair";ImString color=values.computeIfAbsent(prefix+"new.color",ignored -> new ImString("",4096)),target=values.computeIfAbsent(prefix+"new.target",ignored -> new ImString("",16384));
        ImGui.inputText("New #RRGGBB",color);ImGui.inputText("New target",target);drafts.update(key,TerrainTransforms.draftPair("",""),TerrainTransforms.draftPair(color.get(),target.get()));ImGui.beginDisabled(rows.size()>=ImageMapDefinitions.MAXIMUM_COLORS);boolean add=ImGui.button("Add legend entry");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,ImageMapDefinitions.addColor(selected,workspace.read(selected),color.get(),target.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        for (int index=0;index<rows.size();index++) {
            var row=rows.get(index);String pair=prefix+index+".pair";ImGui.pushID(index);
            if (row.key().indexOf(0)>=0 || row.value().indexOf(0)>=0) ImGui.textWrapped("Stored legend entry contains NUL text (text editing read-only).");
            else {
                ImString nextColor=values.computeIfAbsent(prefix+index+".color",ignored -> new ImString(row.key(),Math.max(4096,row.key().getBytes(StandardCharsets.UTF_8).length+1))),nextTarget=values.computeIfAbsent(prefix+index+".target",ignored -> new ImString(row.value(),Math.max(16384,row.value().getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("#RRGGBB",nextColor);ImGui.inputText("Literal target",nextTarget);drafts.update(pair,TerrainTransforms.draftPair(row.key(),row.value()),TerrainTransforms.draftPair(nextColor.get(),nextTarget.get()));
                if (ImGui.button("Apply legend pair")) { try { commitProfileChange(prefix,ImageMapDefinitions.setColor(selected,workspace.read(selected),row.key(),nextColor.get(),nextTarget.get()),pair); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            }
            if (ImGui.button("Remove legend entry")) { try { commitProfileChange(prefix,ImageMapDefinitions.removeColor(selected,workspace.read(selected),row.key()),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }
        ImGui.treePop();
    }
    private void drawImageMapBindings(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension image-map bindings")) return;
        ImGui.pushID("image-map-bindings");
        ImGui.textWrapped("Bind exact image-maps JSON file keys to generation roles. Definition types, source PNGs, colors, target resources, native compatibility and resulting terrain remain unvalidated; no images or maps are loaded or sampled here.");
        ImGui.textWrapped("Native local keys and mask references are trimmed and case-sensitive. TERRAIN_HEIGHT, BIOME, REGION and SURFACE_BLOCK each allow one binding; MASK and CUSTOM may repeat. MASK bindings cannot have additional mask layers. Renames/removals leave existing mask references unchanged.");
        List<LosslessJson> entries;
        try { entries=ImageMapBindings.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored imageMaps is null, a snippet or mixed/non-object list (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>ImageMapBindings.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-binding editor limit (read-only).");ImGui.popID();return; }
        for (String finding : ImageMapBindings.findings(document)) ImGui.textWrapped("Applied local check: "+finding+" (does not block Save/Export)");
        String newKey=ImageMapBindings.prefix()+"new.key",mapKey=ImageMapBindings.prefix()+"new.map",applicationKey=ImageMapBindings.prefix()+"new.application";
        ImString name=values.computeIfAbsent(newKey,ignored -> new ImString("",4096)),map=values.computeIfAbsent(mapKey,ignored -> new ImString("",4096)),role=values.computeIfAbsent(applicationKey,ignored -> new ImString("CUSTOM",128));
        ImGui.inputText("New local binding key",name);drafts.update(newKey,"",name.get());var mapChoices=styleSourceCatalogs.choices(workspace,"imageMap");
        if (ImGui.beginCombo("New image-map file",map.get().isEmpty() ? "Choose an exact file" : map.get())) { for (String choice : mapChoices) if (ImGui.selectable(choice,choice.equals(map.get()))) map.set(choice);ImGui.endCombo(); }
        if (ImGui.beginCombo("New binding application",role.get())) { for (String choice : ImageMapBindings.applications()) if (ImGui.selectable(choice,choice.equals(role.get()))) role.set(choice);ImGui.endCombo(); }
        ImGui.beginDisabled(name.get().isBlank() || map.get().isEmpty() || entries!=null && entries.size()>=ImageMapBindings.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add binding");ImGui.endDisabled();
        if (add) { try { commitProfileChange(ImageMapBindings.prefix(),ImageMapBindings.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,name.get(),map.get(),role.get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int bindingIndex=index;var node=entries.get(index);var context=ImageMapBindings.Context.binding(index);ImGui.pushID(context.prefix());
            if (ImGui.treeNode("Binding "+index)) {
                String copyKey=context.prefix()+"copy.key",copyRoleKey=context.prefix()+"copy.application";ImString copyName=values.computeIfAbsent(copyKey,ignored -> new ImString("",4096)),copyRole=values.computeIfAbsent(copyRoleKey,ignored -> new ImString("CUSTOM",128));
                ImGui.inputText("Duplicate with new key",copyName);drafts.update(copyKey,"",copyName.get());
                if (ImGui.beginCombo("Duplicate application (explicit)",copyRole.get())) { for (String choice : ImageMapBindings.applications()) if (ImGui.selectable(choice,choice.equals(copyRole.get()))) copyRole.set(choice);ImGui.endCombo(); }
                ImGui.textWrapped("Duplicate preserves the raw entry except its explicitly chosen new key/application. Existing masks stay unchanged; choose a role that permits them.");
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove binding")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(ImageMapBindings.prefix(),ImageMapBindings.change(selected,workspace.read(selected),action,index,copyName.get(),null,copyRole.get(),workspace),action==RiverProfiles.Action.DUPLICATE ? copyKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                try {
                    String stored=node.token("key")==null ? "" : node.stringToken("key"),key=context.prefix()+"key";if (stored.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Local binding key",input);drafts.update(key,stored,input.get());
                    if (ImGui.button("Apply key (mask links unchanged)")) commitRiverOverride(key,() -> workspace.replace(selected,ImageMapBindings.setKey(selected,workspace.read(selected),bindingIndex,input.get())));
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored key is not editable text (read-only)."); }
                drawImageMapReference(node,context,mapChoices);drawImageMapChoice(node,context,"application");drawImageMapMasks(document,node,index);
                for (String field : node.fields()) if (!ImageMapBindings.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawImageMapReference(LosslessJson node,ImageMapBindings.Context context,List<String> choices) {
        ImGui.textWrapped(ImageMapBindings.help(context,"map"));
        try {
            String current=node.token("map")==null ? "" : node.stringToken("map");
            if (!current.isBlank() && !(context.mask()<0 ? choices.contains(current) : choices.contains(current.trim()))) ImGui.textWrapped(context.mask()<0 ? "Stored map has no exact file match; native aliases are unchecked." : "Stored mask has no unambiguous local MASK key match.");
            if (ImGui.beginCombo(context.mask()<0 ? "Image-map resource" : "Named MASK binding",current.isEmpty() ? "Choose a reference" : current.length()>128 || current.indexOf(0)>=0 ? "Stored reference" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,ImageMapBindings.setMap(selected,workspace.read(selected),context,choice,workspace)));ImGui.endCombo(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored reference is not a string (read-only)."); }
    }
    private void drawImageMapChoice(LosslessJson node,ImageMapBindings.Context context,String field) {
        ImGui.textWrapped(ImageMapBindings.help(context,field));
        try {
            String current=ImageMapBindings.choice(node,context,field);List<String> choices=field.equals("application") ? ImageMapBindings.applications() : ImageMapBindings.operations();
            if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored enum value" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,ImageMapBindings.setChoice(selected,workspace.read(selected),context,field,choice)));ImGui.endCombo(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored choice is not a string (read-only)."); }
    }
    private void drawImageMapMasks(LosslessJson document,LosslessJson owner,int binding) {
        if (!ImGui.treeNode("Ordered mask layers")) return;
        String prefix=ImageMapBindings.masksPrefix(binding),key=prefix+"new.map";List<LosslessJson> masks;
        ImGui.textWrapped("References name local MASK bindings, not files. Native composition starts at weight 1, applies each operation in order and clamps after each layer. Inversion runs before threshold/falloff; a zero threshold/falloff pair preserves the sampled value. No mask composition is executed here.");
        try { masks=ImageMapBindings.masks(owner); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored masks are a snippet/mixed/non-object list (read-only).");ImGui.treePop();return; }
        if (masks!=null && masks.size()>ImageMapBindings.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-mask editor limit (read-only).");ImGui.treePop();return; }
        var choices=ImageMapBindings.maskKeys(document);ImString selectedMask=values.computeIfAbsent(key,ignored -> new ImString("",4096));
        if (ImGui.beginCombo("New local MASK reference",selectedMask.get().isEmpty() ? "Choose a MASK binding" : selectedMask.get())) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(selectedMask.get()))) selectedMask.set(choice);ImGui.endCombo(); }
        ImGui.beginDisabled(selectedMask.get().isEmpty() || masks!=null && masks.size()>=ImageMapBindings.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add mask layer");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,ImageMapBindings.changeMask(selected,workspace.read(selected),binding,RiverProfiles.Action.ADD,-1,selectedMask.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        if (masks==null) ImGui.textWrapped("Absent/null masks mean no layers. Add seeds a local reference; MULTIPLY, false and zero scalar defaults remain implicit.");
        else for (int index=0;index<masks.size();index++) {
            var context=ImageMapBindings.Context.binding(binding).mask(index);var node=masks.get(index);ImGui.pushID(context.prefix());
            if (ImGui.treeNode("Mask layer "+index)) {
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate layer")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==masks.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove layer")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(prefix,ImageMapBindings.changeMask(selected,workspace.read(selected),binding,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.treePop();return; }
                drawImageMapReference(node,context,choices);drawImageMapChoice(node,context,"operation");String inverted=node.token("inverted");
                if (inverted==null || inverted.equals("true") || inverted.equals("false")) { boolean value="true".equals(inverted);if (ImGui.checkbox("Inverted (native default false)",value)) applyRoutingExtra(() -> workspace.replace(selected,ImageMapBindings.setInverted(selected,workspace.read(selected),context,!value))); } else ImGui.textWrapped("Stored inversion is not boolean (read-only).");
                for (String field : List.of("threshold","falloff")) {
                    ImGui.pushID(field);ImGui.textWrapped(ImageMapBindings.help(context,field));String token=node.token(field),draft=context.prefix()+field;
                    if (token==null) { if (ImGui.button("Enable "+field+" default 0")) applyRoutingExtra(() -> workspace.replace(selected,ImageMapBindings.enableScalar(selected,workspace.read(selected),context,field))); }
                    else if (!ImageMapBindings.numericSupported(node,field)) ImGui.textWrapped("Stored scalar is not a finite number (read-only).");
                    else { ImString input=values.computeIfAbsent(draft,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field+" (0..1)",input);drafts.update(draft,token,input.get());if (ImGui.button("Apply")) commitRiverOverride(draft,() -> workspace.replace(selected,ImageMapBindings.setScalar(selected,workspace.read(selected),context,field,input.get()))); }
                    ImGui.popID();
                }
                for (String field : node.fields()) if (!ImageMapBindings.MASK_FIELDS.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.treePop();
    }
    private void drawStructurePlacements(LosslessJson document) {
        if (!ImGui.collapsingHeader("Structure placements")) return;ImGui.pushID("structure-placements");
        ImGui.textWrapped("Scheduling and source data only: no structure assembly, native generation, cave search, grid positions or preview is evaluated. Iris sources use exact in-pack structures/*.json keys; their contents are unvalidated. Native keys are syntax-only. Terrain and foundation controls are available below. Native jigsaw overrides are available within each native source.");
        ImGui.textWrapped("Placements require exactly one non-empty backend. New/copy placements require an explicit ID unique within this resource; pack-wide ID and anonymous-grid collisions are unverified. Copies retain all raw data except the ID. To change backend, create a new placement. REPLACE_SOURCE is dimension-only and native sources require an omitted/LEGACY anchor.");
        List<LosslessJson> entries;try { entries=StructurePlacements.entries(document); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored structures list is null/snippet/mixed/non-object (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>StructurePlacements.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-placement editor limit (read-only).");ImGui.popID();return; }
        String prefix=StructurePlacements.prefix(),formKey=prefix+"new.form";ImString id=values.computeIfAbsent(prefix+"new.id",ignored -> new ImString("",16384)),backend=values.computeIfAbsent(prefix+"new.backend",ignored -> new ImString("IRIS",128)),source=values.computeIfAbsent(prefix+"new.source",ignored -> new ImString("",16384));
        ImGui.inputText("New/copy placement ID",id);if (ImGui.beginCombo("New placement backend",backend.get())) { for (String choice : List.of("IRIS","NATIVE")) if (ImGui.selectable(choice,choice.equals(backend.get()))) { backend.set(choice);source.set(""); }ImGui.endCombo(); }
        if (backend.get().equals("IRIS")) { if (ImGui.beginCombo("Iris structure source",source.get().isEmpty() ? "Choose file key" : source.get())) { for (String choice : StructurePlacements.references(workspace,"structures")) if (ImGui.selectable(choice,choice.equals(source.get()))) source.set(choice);ImGui.endCombo(); } } else ImGui.inputText("Native namespace:key",source);
        drafts.update(formKey,TerrainTransforms.draftPair("IRIS",TerrainTransforms.draftPair("","")),TerrainTransforms.draftPair(backend.get(),TerrainTransforms.draftPair(id.get(),source.get())));
        ImGui.beginDisabled(id.get().isEmpty() || source.get().isEmpty() || entries!=null && entries.size()>=StructurePlacements.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add placement with native defaults");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,StructurePlacements.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,id.get(),backend.get(),source.get(),workspace),formKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int item=index;var entry=entries.get(index);String entryPrefix=StructurePlacements.prefix(index);ImGui.pushID(index);
            if (ImGui.treeNode("Placement "+index)) {
                RiverProfiles.Action action=null;ImGui.beginDisabled(id.get().isEmpty() || entries.size()>=StructurePlacements.MAXIMUM_ENTRIES);if (ImGui.button("Copy using new ID (source form ignored)")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(prefix,StructurePlacements.change(selected,workspace.read(selected),action,index,id.get(),null,null,workspace),action==RiverProfiles.Action.DUPLICATE ? formKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                String idKey=entryPrefix+"placementId";try { String current=StructurePlacements.text(entry,"placementId");if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");ImString input=values.computeIfAbsent(idKey,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("placementId (empty derives identity)",input);drafts.update(idKey,current,input.get());if (ImGui.button("Apply ID")) commitRiverOverride(idKey,() -> workspace.replace(selected,StructurePlacements.setId(selected,workspace.read(selected),item,input.get()))); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored ID is non-string/NUL (text read-only)."); }
                try { String active=StructurePlacements.backend(entry);ImGui.textWrapped("Backend: "+active);if (active.equals("IRIS")) drawStructureReferences(entry,index,"structures");else drawStructureNativeSources(entry,index); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Unsupported/mixed/empty backend data is preserved. Source controls are read-only."); }
                for (String field : StructurePlacements.ENUMS) { ImGui.pushID(field);ImGui.textWrapped(StructurePlacements.help(field));try { String current=StructurePlacements.text(entry,field);ImGui.textWrapped(StructurePlacements.choiceHelp(field,current));if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored option" : current)) { for (String choice : StructurePlacements.choices(field)) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StructurePlacements.setChoice(selected,workspace.read(selected),item,field,choice)));ImGui.endCombo(); } } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored choice is non-string (read-only)."); }ImGui.popID(); }
                drawStructureTuple(entry,index,"grid",List.of("spacing","separation"));drawStructureTuple(entry,index,"heights",List.of("minHeight","maxHeight"));drawStructureTuple(entry,index,"rings",List.of("ringCount","ringDistance","ringSpread"));
                for (String field : StructurePlacements.SCALARS) { ImGui.pushID(field);ImGui.textWrapped(StructurePlacements.help(field));ImGui.textWrapped(StructurePlacements.constraints(field));String key=entryPrefix+field;try { String current=StructurePlacements.number(entry,field);ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,StructurePlacements.setScalar(selected,workspace.read(selected),item,field,input.get()))); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored scalar has unsupported type/width/finite value (read-only)."); }ImGui.popID(); }
                for (String field : StructurePlacements.BOOLEANS) { ImGui.pushID(field);ImGui.textWrapped(StructurePlacements.help(field));try { boolean current=StructurePlacements.bool(entry,field);if (ImGui.checkbox(field,current)) applyRoutingExtra(() -> workspace.replace(selected,StructurePlacements.setBoolean(selected,workspace.read(selected),item,field,!current))); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored toggle is non-boolean (read-only)."); }ImGui.popID(); }
                drawStructureReferences(entry,index,"caveBiomes");for (String owner : StructurePlacementIntegration.OBJECT_FIELDS) drawPlacementIntegration(entry,index,owner);for (String field : entry.fields()) if (!StructurePlacements.EDITABLE.contains(field) && !StructurePlacementIntegration.OBJECT_FIELDS.contains(field)) ImGui.textWrapped(field+" (advanced read-only, preserved)");ImGui.treePop();
            }ImGui.popID();
        }for (String finding : StructurePlacements.readOnlyPaths(document,ResourceTypes.base(selected))) ImGui.textWrapped(finding+" (read-only)");ImGui.popID();
    }
    private void drawPlacementIntegration(LosslessJson entry,int index,String owner) {
        if (!ImGui.treeNode("Placement "+owner+" settings")) return;ImGui.pushID(owner);String prefix=StructurePlacements.prefix(index)+owner+".";ImGui.textWrapped(StructurePlacements.help(owner));
        ImGui.textWrapped(owner.equals("terrain") ? "Omitted/null terrain resolves to SOURCE. Iris assemblies support SOURCE, PRESERVE, BORE and FORCE_CARVE; native sources support all seven modes. Editing does not run terrain integration or change the source backend." : "Omitted/null stilt has no foundation settings. Enabling an empty object selects native class defaults: depth 64, spacing 1 and cobblestone palette. No ground scan, support geometry or material resolution is performed.");
        try {
            var object=StructurePlacementIntegration.object(entry,owner);
            if (object==null) { if (ImGui.button("Enable inline "+owner+" defaults")) { try { commitProfileChange(prefix,StructurePlacementIntegration.enable(selected,workspace.read(selected),index,owner),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            else { for (String field : owner.equals("stilt") ? NativeStructureAdjustments.STILT_FIELDS : NativeStructureAdjustments.TERRAIN_FIELDS) drawPlacementIntegrationValue(entry,object,index,owner,field);drawStructurePalette(entry,NativeStructurePalettes.Context.placement(ResourceTypes.base(selected),index,owner));for (String field : object.fields()) if (!(owner.equals("stilt") ? NativeStructureAdjustments.STILT_FIELDS : NativeStructureAdjustments.TERRAIN_FIELDS).contains(field) && !field.equals(owner.equals("stilt") ? "palette" : "encasePalette")) ImGui.textWrapped(field+" (read-only, preserved)"); }
            if (entry.token(owner)!=null) { ImGui.textWrapped("Reset discards this entire settings object, including advanced palette/unknown data, and restores the omitted default. Source lists, IDs and scheduling fields are preserved.");if (ImGui.button("Reset placement "+owner+" to omitted")) { try { commitProfileChange(prefix,StructurePlacementIntegration.reset(selected,workspace.read(selected),index,owner),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored placement settings are a snippet/non-object (read-only)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawPlacementIntegrationValue(LosslessJson entry,LosslessJson object,int index,String owner,String field) {
        ImGui.pushID(field);String key=StructurePlacements.prefix(index)+owner+"."+field;ImGui.textWrapped(NativeStructureAdjustments.help(owner,field));
        try {
            String current=NativeStructureAdjustments.value(object,owner,field);
            if (NativeStructureAdjustments.booleanField(owner,field)) { boolean flag=current.equals("true");if (ImGui.checkbox(field,flag)) applyRoutingExtra(() -> workspace.replace(selected,StructurePlacementIntegration.setBoolean(selected,workspace.read(selected),index,owner,field,!flag))); }
            else if (NativeStructureAdjustments.enumField(owner,field)) { var choices=field.equals("mode") ? StructurePlacementIntegration.modes(entry) : NativeStructureAdjustments.choices(field);ImGui.textWrapped(NativeStructureAdjustments.choiceHelp(field,current));if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored option" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StructurePlacementIntegration.setChoice(selected,workspace.read(selected),index,field,choice)));ImGui.endCombo(); } }
            else { ImGui.textWrapped(NativeStructureAdjustments.constraints(owner,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,StructurePlacementIntegration.setNumber(selected,workspace.read(selected),index,owner,field,input.get()))); }
            if (object.token(field)!=null && ImGui.button("Restore absent class default")) { try { commitProfileChange(key,StructurePlacementIntegration.resetLeaf(selected,workspace.read(selected),index,owner,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value/type/width is unsupported, or the backend cannot be determined (read-only)."); }ImGui.popID();
    }
    private void drawStructureTuple(LosslessJson entry,int index,String group,List<String> fields) {
        if (!ImGui.treeNode(group+" settings")) return;String prefix=StructurePlacements.prefix(index)+group+".",key=prefix+"tuple";ImString[] inputs=new ImString[fields.size()];StringBuilder baseline=new StringBuilder(),proposed=new StringBuilder();
        try { for (int i=0;i<fields.size();i++) { String field=fields.get(i),current=StructurePlacements.number(entry,field);ImGui.textWrapped(StructurePlacements.help(field));ImGui.textWrapped(StructurePlacements.constraints(field));inputs[i]=values.computeIfAbsent(prefix+field,ignored -> new ImString(current,Math.max(128,current.length()+1)));ImGui.inputText(field,inputs[i]);baseline.append(current).append('|');proposed.append(inputs[i].get()).append('|'); }drafts.update(key,baseline.toString(),proposed.toString());if (ImGui.button("Apply grouped "+group)) { try { byte[] next=group.equals("grid") ? StructurePlacements.setGrid(selected,workspace.read(selected),index,inputs[0].get(),inputs[1].get()) : group.equals("heights") ? StructurePlacements.setHeights(selected,workspace.read(selected),index,inputs[0].get(),inputs[1].get()) : StructurePlacements.setRings(selected,workspace.read(selected),index,inputs[0].get(),inputs[1].get(),inputs[2].get());commitProfileChange(prefix,next,key); } catch (Exception failure) { error=failure.getMessage(); } } } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored group has unsupported numeric types/widths (read-only)."); }ImGui.treePop();
    }
    private void drawStructureReferences(LosslessJson entry,int index,String field) {
        if (!ImGui.treeNode(field+" references")) return;String prefix=StructurePlacements.prefix(index)+field+".";ImGui.pushID(prefix);ImGui.textWrapped(StructurePlacements.help(field));ImGui.textWrapped("Choices check exact file presence only. Existing loader aliases/prefixes and resource contents are unverified. Duplicate choices are rejected; source lists retain at least one entry.");List<String> refs;
        try { refs=StructurePlacements.strings(entry,field); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored references are mixed/non-string (read-only).");ImGui.popID();ImGui.treePop();return; }if (refs!=null && refs.size()>StructurePlacements.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-reference editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }
        var choices=StructurePlacements.references(workspace,field);String newKey=prefix+"new";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));if (ImGui.beginCombo("New file key",next.get().isEmpty() ? "Choose key" : next.get())) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(next.get()))) next.set(choice);ImGui.endCombo(); }drafts.update(newKey,"",next.get());ImGui.beginDisabled(next.get().isEmpty() || refs!=null && refs.size()>=StructurePlacements.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add reference");ImGui.endDisabled();if (add) { try { commitProfileChange(prefix,StructurePlacements.changeReference(selected,workspace.read(selected),index,field,RiverProfiles.Action.ADD,-1,next.get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (refs!=null) for (int row=0;row<refs.size();row++) { int item=row;String current=refs.get(row);ImGui.pushID(row);if (ImGui.beginCombo("File reference",current.length()>128 || current.indexOf(0)>=0 ? "Stored key" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,StructurePlacements.setReference(selected,workspace.read(selected),index,field,item,choice,workspace)));ImGui.endCombo(); }RiverProfiles.Action action=null;ImGui.beginDisabled(row==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(row+1==refs.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(field.equals("structures") && refs.size()==1);if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();if (action!=null) { try { commitProfileChange(prefix,StructurePlacements.changeReference(selected,workspace.read(selected),index,field,action,row,null,workspace),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.popID();ImGui.treePop();return; }ImGui.popID(); }ImGui.popID();ImGui.treePop();
    }
    private void drawStructureNativeSources(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Native structure sources")) return;String prefix=StructurePlacements.prefix(index)+"nativeStructures.";ImGui.pushID(prefix);var sources=StructurePlacements.natives(entry);if (sources.size()>StructurePlacements.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-source editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }ImGui.textWrapped("Namespace:key syntax only. Live keys, jigsaw configuration and registry compatibility are unverified. Weights are positive signed 32-bit integers and their total must fit that range. Duplicate native source keys are rejected.");String newKey=prefix+"new";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));ImGui.inputText("New native source key",next);drafts.update(newKey,"",next.get());ImGui.beginDisabled(next.get().isEmpty() || sources.size()>=StructurePlacements.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add native source (implicit weight 1)");ImGui.endDisabled();if (add) { try { commitProfileChange(prefix,StructurePlacements.changeNative(selected,workspace.read(selected),index,RiverProfiles.Action.ADD,-1,next.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        for (int row=0;row<sources.size();row++) { int item=row;var source=sources.get(row);ImGui.pushID(row);if (ImGui.treeNode("Source "+row)) {
            for (String field : StructurePlacements.NATIVE_FIELDS) { ImGui.pushID(field);String key=prefix+row+"."+field;ImGui.textWrapped(StructurePlacements.help("native."+field));try { String current=field.equals("structure") ? StructurePlacements.nativeKey(source) : StructurePlacements.nativeWeight(source);if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,field.equals("structure") ? StructurePlacements.setNativeKey(selected,workspace.read(selected),index,item,input.get()) : StructurePlacements.setNativeWeight(selected,workspace.read(selected),index,item,input.get()))); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored native source field has unsupported type/width (read-only)."); }ImGui.popID(); }
            drawNativeJigsaw(source,new NativeJigsawOverrides.Context(ResourceTypes.base(selected),index,row));
            RiverProfiles.Action action=null;ImGui.beginDisabled(row==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(row+1==sources.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(sources.size()==1);if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();if (action!=null) { try { commitProfileChange(prefix,StructurePlacements.changeNative(selected,workspace.read(selected),index,action,row,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();ImGui.treePop();return; }for (String field : source.fields()) if (!StructurePlacements.NATIVE_FIELDS.contains(field) && !NativeJigsawOverrides.OBJECT_FIELDS.contains(field)) ImGui.textWrapped(field+" (advanced read-only, preserved)");ImGui.treePop(); }ImGui.popID(); }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeJigsaw(LosslessJson source,NativeJigsawOverrides.Context context) {
        if (!ImGui.treeNode("Native jigsaw overrides")) return;String prefix=context.prefix();ImGui.pushID(prefix);
        ImGui.textWrapped("Omitted overrides preserve the registered definition. These controls require a registered jigsaw source. Pool/connector existence, source compatibility, actual assembly span and dimension fit are unverified. No assembly or preview runs. Removing the entire override object also discards its advanced data.");
        try {
            var object=NativeJigsawOverrides.object(source);
            if (object==null) { if (ImGui.button("Enable inline jigsaw overrides")) { try { commitProfileChange(prefix,NativeJigsawOverrides.enable(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            else {
                if (ImGui.button("Remove entire jigsaw override object")) { try { commitProfileChange(prefix,NativeJigsawOverrides.reset(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
                for (String field : NativeJigsawOverrides.EDITABLE) { ImGui.pushID(field);String key=prefix+field;ImGui.textWrapped(NativeJigsawOverrides.help(field));
                    try { String current=NativeJigsawOverrides.value(object,field);if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
                        if (NativeJigsawOverrides.NUMBERS.contains(field)) ImGui.textWrapped("Exact integer "+NativeJigsawOverrides.bounds(field)+"; empty display inherits until explicitly applied.");
                        if ("null".equals(object.token(field)) && !NativeJigsawOverrides.NUMBERS.contains(field)) ImGui.textWrapped("Stored null may fail pack validation; reset to inherit or apply a supported value.");
                        if (field.equals("useExpansionHack") || !NativeJigsawOverrides.choices(field).isEmpty()) { var choices=field.equals("useExpansionHack") ? List.of("true","false") : NativeJigsawOverrides.choices(field);if (ImGui.beginCombo(field,current.isEmpty() ? "Inherit" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) commitRiverOverride(key,() -> workspace.replace(selected,NativeJigsawOverrides.set(selected,workspace.read(selected),context,field,choice)));ImGui.endCombo(); } }
                        else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply override")) commitRiverOverride(key,() -> workspace.replace(selected,NativeJigsawOverrides.set(selected,workspace.read(selected),context,field,input.get()))); }
                        if (object.token(field)!=null && ImGui.button("Reset to inherited (remove field)")) { try { commitProfileChange(key,NativeJigsawOverrides.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
                    } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored field has an unsupported type/width (read-only)."); }ImGui.popID();
                }
                for (String field : NativeJigsawOverrides.readOnlyPaths(source)) ImGui.textWrapped(field+" (advanced read-only, preserved)");
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored jigsaw is not an inline object (read-only, preserved)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawDimensionIntegrations(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension integrations and component controls")) return;ImGui.pushID("dimension-integrations");
        ImGui.textWrapped("These controls author pack data only. No datapack is fetched, local file opened, registry queried or component discovered. Import ownership, source content, platform support and existing-world compatibility are unverified. Unsupported previews stay disabled.");
        ImGui.textWrapped(DimensionIntegrations.help("borrowedDimensionType"));
        ImGui.textWrapped("Borrowing is supported by Fabric/Forge/NeoForge, not Bukkit. The registered type must exist; dimensionHeight.min must equal its min_y and the pack maximum must fit inside its top. Native hotload rejects a changed borrowed key: close and reopen Studio after changing it. Options/fullbright are preserved even when borrowing makes them ineffective.");
        String prefix=DimensionIntegrations.prefix("borrowedDimensionType"),key=prefix+"value";
        try { String stored=DimensionIntegrations.borrowed(document),current=stored==null ? "" : stored;if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Borrowed type (empty means generated)",input);drafts.update(key,current,input.get());if (ImGui.button("Apply borrowed type key")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionIntegrations.setBorrowed(selected,workspace.read(selected),input.get())));if (document.token("borrowedDimensionType")!=null && ImGui.button("Restore generated type (remove borrowed key)")) { try { commitProfileChange(prefix,DimensionIntegrations.resetBorrowed(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored borrowedDimensionType is non-string or contains NUL (text read-only)."); }
        for (String field : DimensionIntegrations.LIST_FIELDS) drawDimensionIntegrationList(document,field);ImGui.popID();
    }
    private void drawDimensionIntegrationList(LosslessJson document,String field) {
        if (!ImGui.treeNode(field)) return;ImGui.pushID(field);String prefix=DimensionIntegrations.prefix(field);ImGui.textWrapped(DimensionIntegrations.help(field));boolean components=field.equals("disabledComponents");
        ImGui.textWrapped(components ? "The choices are serializable reserved flags, not a list of active components. Custom flags use canonical CUSTOM:64..255. Availability/effect are unverified. CARVED also suppresses native river hydrology and gates underground features; Iris may disable flags for other reasons. No other settings are changed automatically." : "Enter an absolute HTTP(S) URL with a URI host, or an absolute local file URI without authority/query/fragment. Only syntax is checked, with no path normalization, download, archive inspection or file access. The server trims/deduplicates sources and normalizes local paths. Removing configured sources does not disable local ZIPs discovered under Iris/datapacks/imports.");
        List<String> entries;
        try { entries=DimensionIntegrations.entries(document,field); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored list is null/mixed/non-string (read-only).");ImGui.popID();ImGui.treePop();return; }
        if (entries!=null && entries.size()>DimensionIntegrations.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-entry editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }
        if (document.token(field)!=null && document.token(field).equals("null")) ImGui.textWrapped("Explicit null has no configured sources; Add converts it into a string list.");
        String newKey=prefix+"new";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));ImGui.inputText(components ? "New reserved flag or CUSTOM:ordinal" : "New source URI",next);
        if (components && ImGui.beginCombo("Choose reserved flag",next.get().length()>128 || next.get().indexOf(0)>=0 ? "Entered flag" : next.get().isEmpty() ? "Choose flag" : next.get())) { for (String flag : DimensionIntegrations.flags()) if (ImGui.selectable(flag,flag.equals(next.get()))) next.set(flag);ImGui.endCombo(); }drafts.update(newKey,"",next.get());
        ImGui.beginDisabled(next.get().isEmpty() || entries!=null && entries.size()>=DimensionIntegrations.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add integration entry");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,DimensionIntegrations.change(selected,workspace.read(selected),field,RiverProfiles.Action.ADD,-1,next.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (document.token(field)!=null && ImGui.button("Remove entire configured list")) { try { commitProfileChange(prefix,DimensionIntegrations.resetList(selected,workspace.read(selected),field),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int row=index;String current=entries.get(index),key=prefix+index;ImGui.pushID(index);
            if (current.indexOf(0)>=0) ImGui.textWrapped("Stored entry contains NUL (text read-only; structural removal is available).");
            else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(components ? "Serialized flag" : "Datapack source",input);drafts.update(key,current,input.get());if (ImGui.button("Apply entry")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionIntegrations.setEntry(selected,workspace.read(selected),field,row,input.get()))); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
            if (action!=null) { try { commitProfileChange(prefix,DimensionIntegrations.change(selected,workspace.read(selected),field,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeStructureAdjustments(LosslessJson document) {
        if (!ImGui.collapsingHeader("Native structure adjustments")) return;ImGui.pushID("native-adjustments");
        ImGui.textWrapped("These adjustments apply to native structures that still generate, including mod/datapack structures. Match keys are syntax-only registry families, not Iris file references. New empty adjustments match nothing. No placement, terrain operation, world-height compatibility or preview is evaluated here.");
        ImGui.textWrapped("Matching yShift values stack with undergroundYShift; preserveSourceY accumulates. The last matching non-null yBand, stilt and terrain objects win independently. preserveSourceY takes precedence over yBand; fixed vanilla alignments may ignore yBand. Native build-height clamping is not simulated.");
        List<LosslessJson> entries;
        try { entries=NativeStructureAdjustments.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored importedStructures/adjustments is null, a snippet or has non-object entries (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>NativeStructureAdjustments.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-adjustment editor limit (read-only).");ImGui.popID();return; }
        ImGui.beginDisabled(entries!=null && entries.size()>=NativeStructureAdjustments.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add inactive adjustment");ImGui.endDisabled();
        if (add) { try { commitProfileChange(NativeStructureAdjustments.prefix(),NativeStructureAdjustments.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            var entry=entries.get(index);ImGui.pushID(index);
            if (ImGui.treeNode("Adjustment "+index)) {
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(NativeStructureAdjustments.prefix(),NativeStructureAdjustments.change(selected,workspace.read(selected),action,index),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                drawNativeAdjustmentMatches(entry,index);drawNativeAdjustmentValue(entry,index,"","yShift");drawNativeAdjustmentValue(entry,index,"","preserveSourceY");
                for (String context : NativeStructureAdjustments.OPTIONAL) drawNativeAdjustmentObject(entry,index,context);
                for (String field : entry.fields()) if (!NativeStructureAdjustments.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only, preserved)");ImGui.treePop();
            }ImGui.popID();
        }ImGui.popID();
    }
    private void drawNativeAdjustmentMatches(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Structure match families")) return;String prefix=NativeStructureAdjustments.prefix(index)+"match.";ImGui.pushID(prefix);ImGui.textWrapped(NativeStructureAdjustments.help("","match"));List<String> list;
        try { list=NativeStructureAdjustments.matches(entry); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored match is null/non-string/mixed (read-only).");ImGui.popID();ImGui.treePop();return; }
        if (list!=null && list.size()>NativeStructureAdjustments.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-match editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }
        String newKey=prefix+"new";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));ImGui.inputText("New namespace:key or namespace:",next);drafts.update(newKey,"",next.get());ImGui.beginDisabled(next.get().isEmpty() || list!=null && list.size()>=NativeStructureAdjustments.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add match");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,NativeStructureAdjustments.changeMatch(selected,workspace.read(selected),index,RiverProfiles.Action.ADD,-1,next.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (list!=null) for (int row=0;row<list.size();row++) {
            int item=row;String current=list.get(row),key=prefix+row;ImGui.pushID(row);
            if (current.indexOf(0)>=0) ImGui.textWrapped("Stored match contains NUL (text read-only; structural removal is available).");
            else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Match family",input);drafts.update(key,current,input.get());if (ImGui.button("Apply match")) commitRiverOverride(key,() -> workspace.replace(selected,NativeStructureAdjustments.setMatch(selected,workspace.read(selected),index,item,input.get()))); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(row==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(row+1==list.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
            if (action!=null) { try { commitProfileChange(prefix,NativeStructureAdjustments.changeMatch(selected,workspace.read(selected),index,action,row,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeAdjustmentObject(LosslessJson entry,int index,String context) {
        if (!ImGui.treeNode(context+" override")) return;ImGui.pushID(context);String prefix=NativeStructureAdjustments.prefix(index)+context+".";ImGui.textWrapped(NativeStructureAdjustments.help("",context));
        try {
            var object=NativeStructureAdjustments.object(entry,context);
            if (object==null) { ImGui.textWrapped("Absent/null means no override in this entry. Enable writes an empty inline object with native class defaults.");if (ImGui.button("Enable inline "+context)) applyRoutingExtra(() -> workspace.replace(selected,NativeStructureAdjustments.enableOptional(selected,workspace.read(selected),index,context))); }
            else {
                if (context.equals("yBand")) {
                    ImGui.textWrapped("Absolute world Y, -4064..4064. Equal and reversed endpoints are supported; native resolution sorts the endpoints.");String key=prefix+"pair";
                    try { String low=NativeStructureAdjustments.value(object,context,"min"),high=NativeStructureAdjustments.value(object,context,"max");ImString min=values.computeIfAbsent(prefix+"min",ignored -> new ImString(low,Math.max(128,low.length()+1))),max=values.computeIfAbsent(prefix+"max",ignored -> new ImString(high,Math.max(128,high.length()+1)));ImGui.inputText("Minimum world Y",min);ImGui.inputText("Maximum world Y",max);drafts.update(key,low+"|"+high,min.get()+"|"+max.get());if (ImGui.button("Apply both Y-band endpoints")) { try { commitProfileChange(prefix,NativeStructureAdjustments.setBand(selected,workspace.read(selected),index,min.get(),max.get()),key); } catch (Exception failure) { error=failure.getMessage(); } } }
                    catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored Y band has a non-integer or unsupported-width endpoint (read-only)."); }
                } else {
                    ImGui.textWrapped(context.equals("stilt") ? "Native class defaults: maxDepth 64, spacing 1, cobblestone palette. Foundation palettes are authored below. supportNonOccluding is documented for Iris-authored structures." : "Absent leaves use SOURCE/BOX, padding 0, flattenRange 64, erosion strength 0.8/frequency 0.07, lobe frequency 0 and strength 0.85. Lobe frequency 0 derives 0.3 times erosion frequency. Encasing palettes are authored below; the omitted palette uses a native environment-dependent fallback.");
                    for (String field : context.equals("stilt") ? NativeStructureAdjustments.STILT_FIELDS : NativeStructureAdjustments.TERRAIN_FIELDS) drawNativeAdjustmentValue(object,index,context,field);
                    drawNativeStructurePalette(entry,index,context);
                }
                for (String field : object.fields()) if (!(context.equals("yBand") ? NativeStructureAdjustments.BAND_FIELDS : context.equals("stilt") ? NativeStructureAdjustments.STILT_FIELDS : NativeStructureAdjustments.TERRAIN_FIELDS).contains(field) && !(context.equals("stilt") && field.equals("palette") || context.equals("terrain") && field.equals("encasePalette"))) ImGui.textWrapped(context+"."+field+" (read-only, preserved)");
            }
            if (entry.token(context)!=null) { ImGui.textWrapped("Remove discards this entire override, including advanced palette/unknown data, and restores absence.");if (ImGui.button("Remove "+context+" override")) { try { commitProfileChange(prefix,NativeStructureAdjustments.removeOptional(selected,workspace.read(selected),index,context),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored override is a snippet/non-object (read-only)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeStructurePalette(LosslessJson entry,int index,String owner) {
        drawStructurePalette(entry,new NativeStructurePalettes.Context(index,owner));
    }
    private void drawStructurePalette(LosslessJson entry,NativeStructurePalettes.Context context) {
        String owner=context.owner();
        if (!ImGui.treeNode(context.member()+" material override")) return;ImGui.pushID(context.prefix());
        ImGui.textWrapped(owner.equals("stilt") ? "Omitting this palette uses the native foundation default, minecraft:cobblestone. Writing an explicit palette starts with cobblestone and keeps native STATIC style/zoom 5 implicit. A stored null has no inline palette; its native effect is not evaluated here." : "Omitted/null encasePalette uses the native environment fallback. Writing an explicit palette starts with STONE, replacing that fallback. Encasing is used by ENCASE; this control never changes the terrain mode.");
        if (context.placement() && owner.equals("terrain")) ImGui.textWrapped("Iris assembly placements cannot use ENCASE; their encasing palette is inactive. Registry/material-role compatibility and sampling are unverified for every backend.");
        try {
            var palette=NativeStructurePalettes.object(entry,context);
            if (palette==null) { if (ImGui.button("Write explicit "+NativeStructurePalettes.creationBlock(context)+" palette")) { try { commitProfileChange(context.prefix(),NativeStructurePalettes.create(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            else { NativeStructurePalettes.checkEntries(palette);drawGeneralBlockPalette(palette,context.target()); }
            var object=NativeStructureAdjustments.object(entry,owner);
            if (object!=null && object.token(context.member())!=null) { ImGui.textWrapped("Reset discards the whole palette, including advanced data, and restores the omitted native default. Block registries, state/solid-role requirements, noise sampling and terrain integration are unverified.");if (ImGui.button("Reset to omitted palette")) { try { commitProfileChange(context.prefix(),NativeStructurePalettes.reset(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette is a snippet/non-object, has mixed/null entries or exceeds the 64-entry editor limit (read-only)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeAdjustmentValue(LosslessJson object,int index,String context,String field) {
        ImGui.pushID(field);String key=NativeStructureAdjustments.prefix(index)+(context.isEmpty() ? "" : context+".")+field;ImGui.textWrapped(NativeStructureAdjustments.help(context,field));
        try {
            String current=NativeStructureAdjustments.value(object,context,field);
            if (NativeStructureAdjustments.booleanField(context,field)) { boolean flag=current.equals("true");if (ImGui.checkbox(field+" (default "+NativeStructureAdjustments.defaultToken(context,field)+")",flag)) applyRoutingExtra(() -> workspace.replace(selected,NativeStructureAdjustments.setBoolean(selected,workspace.read(selected),index,context,field,!flag))); }
            else if (NativeStructureAdjustments.enumField(context,field)) { ImGui.textWrapped(NativeStructureAdjustments.choiceHelp(field,current));if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored option" : current)) { for (String choice : NativeStructureAdjustments.choices(field)) { if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,NativeStructureAdjustments.setChoice(selected,workspace.read(selected),index,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(NativeStructureAdjustments.choiceHelp(field,choice)); }ImGui.endCombo(); } }
            else { ImGui.textWrapped(NativeStructureAdjustments.constraints(context,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,NativeStructureAdjustments.setNumber(selected,workspace.read(selected),index,context,field,input.get()))); }
            if (object.token(field)!=null && ImGui.button("Restore absent default")) { try { commitProfileChange(key,NativeStructureAdjustments.resetLeaf(selected,workspace.read(selected),index,context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value has an unsupported type, integer width or finite value (read-only)."); }ImGui.popID();
    }
    private void drawNativeGenerationFilters(LosslessJson document) {
        if (!ImGui.collapsingHeader("Native feature filters and structure-set frequency")) return;
        ImGui.pushID("native-generation-filters");
        ImGui.textWrapped("Registry keys and biome tags are syntax-only: this editor does not resolve a server registry, execute native placement or preview these settings. Existing enabled/disabled controls remain above. Feature filtering has no effect while importedFeatures.enabled is false (its native default).");
        ImGui.textWrapped("An empty allowed-step list permits all steps; denied steps take precedence. Unknown steps in a newer server are eligible only when the allowed list is empty. Biome rules add denials on top of global filters; they never enable a feature.");
        try { var features=NativeGenerationFilters.object(document,"importedFeatures");if (features==null) features=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8));for (String field : List.of("steps","disabledSteps")) drawNativeFilterStrings(features,field,-1,NativeGenerationFilters.prefix(field)); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored importedFeatures is null/non-object (read-only)."); }
        drawNativeFilterObjects(document,"biomeRules");drawNativeFilterObjects(document,"frequencyOverrides");ImGui.popID();
    }
    private void drawNativeFilterObjects(LosslessJson document,String list) {
        if (!ImGui.treeNode(list)) return;ImGui.pushID(list);String prefix=NativeGenerationFilters.prefix(list);ImGui.textWrapped(NativeGenerationFilters.help(list));
        List<LosslessJson> entries;
        try { entries=NativeGenerationFilters.entries(document,list); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored policy/list is null, a snippet or has non-object entries (read-only).");ImGui.popID();ImGui.treePop();return; }
        if (entries!=null && entries.size()>NativeGenerationFilters.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-entry editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }
        String newKey=prefix+"new";ImString newSet=null;
        if (list.equals("frequencyOverrides")) { ImGui.textWrapped("Exact registered structure-set keys differ from structure keys. Native resolution trims/lowercases keys and uses the last matching entry. New entries leave multiplier absent (native default 1); multiplier must be 0.01..16. Requested density is not a guaranteed realized placement rate.");newSet=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));ImGui.inputText("New structure-set key",newSet);drafts.update(newKey,"",newSet.get()); }
        else ImGui.textWrapped("A new empty rule matches no biomes and denies nothing. Keys refer to server registry biomes, including namespace: selectors and #namespace:tag selectors. They are separate from Iris biome file keys.");
        ImGui.beginDisabled(entries!=null && entries.size()>=NativeGenerationFilters.MAXIMUM_ENTRIES || newSet!=null && newSet.get().isEmpty());boolean add=ImGui.button("Add "+(list.equals("biomeRules") ? "inactive biome rule" : "frequency override"));ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,NativeGenerationFilters.change(selected,workspace.read(selected),list,RiverProfiles.Action.ADD,-1,newSet==null ? null : newSet.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int item=index;var entry=entries.get(index);String entryPrefix=prefix+index+".";ImGui.pushID(index);
            if (ImGui.treeNode("Entry "+index)) {
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(prefix,NativeGenerationFilters.change(selected,workspace.read(selected),list,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();ImGui.treePop();return; }
                if (list.equals("biomeRules")) for (String field : NativeGenerationFilters.EDITABLE) drawNativeFilterStrings(entry,field,index,entryPrefix+field+".");
                else {
                    for (String field : NativeGenerationFilters.FREQUENCY_FIELDS) {
                        ImGui.pushID(field);ImGui.textWrapped(NativeGenerationFilters.help(field));String key=entryPrefix+field;
                        try { String current=field.equals("structureSet") ? NativeGenerationFilters.structureSet(entry) : NativeGenerationFilters.multiplier(entry);if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,field.equals("structureSet") ? NativeGenerationFilters.setStructureSet(selected,workspace.read(selected),item,input.get()) : NativeGenerationFilters.setMultiplier(selected,workspace.read(selected),item,input.get())));if (field.equals("multiplier") && entry.token(field)!=null && ImGui.button("Restore absent multiplier (1)")) { try { commitProfileChange(key,NativeGenerationFilters.resetMultiplier(selected,workspace.read(selected),index),null); } catch (Exception failure) { error=failure.getMessage(); } } }
                        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value has an unsupported type/finite value (read-only)."); }ImGui.popID();
                    }
                }
                for (String field : entry.fields()) if (!(list.equals("biomeRules") ? NativeGenerationFilters.EDITABLE : NativeGenerationFilters.FREQUENCY_FIELDS).contains(field)) ImGui.textWrapped(field+" (read-only, preserved)");ImGui.treePop();
            }ImGui.popID();
        }ImGui.popID();ImGui.treePop();
    }
    private void drawNativeFilterStrings(LosslessJson owner,String field,int rule,String prefix) {
        if (!ImGui.treeNode(field)) return;ImGui.pushID(prefix);boolean step=rule<0;ImGui.textWrapped(NativeGenerationFilters.help(step ? field : "rule."+field));
        List<String> strings;
        try { strings=NativeGenerationFilters.strings(owner,field,step); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored list is null/non-string/mixed (read-only).");ImGui.popID();ImGui.treePop();return; }
        if (strings!=null && strings.size()>NativeGenerationFilters.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-entry editor limit (read-only).");ImGui.popID();ImGui.treePop();return; }
        String newKey=prefix+"new";ImString next=values.computeIfAbsent(newKey,ignored -> new ImString("",16384));
        if (step) { if (ImGui.beginCombo("New step",next.get().isEmpty() ? "Choose step" : next.get())) { for (String choice : NativeGenerationFilters.steps()) if (ImGui.selectable(choice,choice.equals(next.get()))) next.set(choice);ImGui.endCombo(); } } else ImGui.inputText("New registry selector",next);drafts.update(newKey,"",next.get());
        ImGui.beginDisabled(next.get().isEmpty() || strings!=null && strings.size()>=NativeGenerationFilters.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add string entry");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,NativeGenerationFilters.changeString(selected,workspace.read(selected),field,rule,RiverProfiles.Action.ADD,-1,next.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
        if (strings!=null) for (int index=0;index<strings.size();index++) {
            int item=index;String current=strings.get(index),key=prefix+index;ImGui.pushID(index);
            if (step) { ImGui.textWrapped(NativeGenerationFilters.stepHelp(current));if (ImGui.beginCombo("Step",current.length()>128 || current.indexOf(0)>=0 ? "Stored step" : current)) { for (String choice : NativeGenerationFilters.steps()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,NativeGenerationFilters.setString(selected,workspace.read(selected),field,rule,item,choice)));ImGui.endCombo(); } }
            else if (current.indexOf(0)>=0) ImGui.textWrapped("Stored selector contains NUL (text read-only; structural removal is available).");
            else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Registry selector",input);drafts.update(key,current,input.get());if (ImGui.button("Apply selector")) commitRiverOverride(key,() -> workspace.replace(selected,NativeGenerationFilters.setString(selected,workspace.read(selected),field,rule,item,input.get()))); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==strings.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
            if (action!=null) { try { commitProfileChange(prefix,NativeGenerationFilters.changeString(selected,workspace.read(selected),field,rule,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }ImGui.popID();ImGui.treePop();
    }
    private void drawDimensionGenerationControls(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension generation and server Studio controls")) return;
        ImGui.pushID("dimension-generation");
        ImGui.textWrapped("These settings change generated pack data. Studio layouts run in supported server Studio worlds; they do not enable an editor preview. Existing worlds and native compatibility are not evaluated here.");
        ImGui.textWrapped("Underground features require useMantle, carvingEnabled, an OVERWORLD mode and the CARVED component. Disabled components are authored in the dimension integration controls; active components are unverified. SUPERFLAT has limited generation features. No settings are changed automatically when choosing a mode.");
        if (ImGui.treeNode("Generation mode")) {
            ImGui.textWrapped(DimensionGenerationControls.help("mode"));
            try {
                var object=DimensionGenerationControls.mode(document);
                if (object==null) { ImGui.textWrapped("Absent mode uses OVERWORLD. Enabling writes an empty inline object and keeps that default.");if (ImGui.button("Enable inline mode")) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.enableMode(selected,workspace.read(selected)))); }
                else {
                    String current=DimensionGenerationControls.modeType(document);ImGui.textWrapped(DimensionGenerationControls.choiceHelp(false,current));
                    if (ImGui.beginCombo("Mode type",current.length()>128 || current.indexOf(0)>=0 ? "Stored type" : current)) { for (String choice : DimensionGenerationControls.modes()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.setMode(selected,workspace.read(selected),choice)));ImGui.endCombo(); }
                    if (object.token("type")!=null && ImGui.button("Restore OVERWORLD default (remove type)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.resetModeType(selected,workspace.read(selected))));
                    for (String field : object.fields()) if (!DimensionGenerationControls.EDITABLE.contains(field)) ImGui.textWrapped("mode."+field+" (read-only, preserved)");
                }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored mode is null, a snippet/non-object, or has a non-string type (read-only)."); }
            ImGui.treePop();
        }
        ImGui.textWrapped(DimensionGenerationControls.help("studioMode"));
        try {
            String current=DimensionGenerationControls.studioMode(document);ImGui.textWrapped(DimensionGenerationControls.choiceHelp(true,current));
            if (ImGui.beginCombo("Server Studio mode",current.length()>128 || current.indexOf(0)>=0 ? "Stored Studio mode" : current)) { for (String choice : DimensionGenerationControls.studioModes()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.setStudioMode(selected,workspace.read(selected),choice)));ImGui.endCombo(); }
            if (document.token("studioMode")!=null && ImGui.button("Restore NORMAL default (remove studioMode)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.resetRoot(selected,workspace.read(selected),"studioMode")));
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored studioMode is not a string (read-only)."); }
        for (String field : DimensionGenerationControls.BOOLEANS) {
            ImGui.pushID(field);ImGui.textWrapped(DimensionGenerationControls.help(field));
            try { boolean current=DimensionGenerationControls.booleanValue(document,field);if (ImGui.checkbox(field+" (native default "+DimensionGenerationControls.defaultToken(field)+")",current)) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.setBoolean(selected,workspace.read(selected),field,!current)));if (document.token(field)!=null && ImGui.button("Restore absent default")) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.resetRoot(selected,workspace.read(selected),field))); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored toggle is not boolean (read-only)."); }ImGui.popID();
        }
        for (String field : DimensionGenerationControls.NUMBERS) {
            ImGui.pushID(field);ImGui.textWrapped(DimensionGenerationControls.help(field));ImGui.textWrapped(DimensionGenerationControls.constraints(field));String key=DimensionGenerationControls.prefix()+field;
            try {
                String current=DimensionGenerationControls.numberValue(document,field);
                if (document.token(field)==null) { if (ImGui.button("Enable native default "+current)) applyRoutingExtra(() -> workspace.replace(selected,DimensionGenerationControls.enableNumber(selected,workspace.read(selected),field))); }
                else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionGenerationControls.setNumber(selected,workspace.read(selected),field,input.get())));if (ImGui.button("Restore absent default")) { try { commitProfileChange(key,DimensionGenerationControls.resetRoot(selected,workspace.read(selected),field),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value is not an exact signed 32-bit integer (read-only)."); }ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawTerrainOverlays(LosslessJson document) {
        if (!ImGui.collapsingHeader("Terrain overlay noise")) return;
        ImGui.pushID("terrain-overlays");
        ImGui.textWrapped("Iris adds each fitted overlay to terrain height. Bounds are signed integer offsets -2032..2032, not absolute world Y. Equal and reversed pairs are supported; native height bounds account for both ends. Native clamping, image-map overrides and generation are not evaluated here.");
        List<LosslessJson> entries;
        try { entries=TerrainOverlays.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored overlays are null, a snippet or mixed/non-object list (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>TerrainOverlays.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-overlay editor limit (read-only).");ImGui.popID();return; }
        ImGui.textWrapped("Add writes explicit IRIS generator and min/max 0; it starts with a zero offset. Final removal restores field absence. Structural edits preserve raw entry data.");
        ImGui.beginDisabled(entries!=null && entries.size()>=TerrainOverlays.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add zero-offset overlay (IRIS)");ImGui.endDisabled();
        if (add) { try { commitProfileChange(TerrainOverlays.prefix(),TerrainOverlays.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int entryIndex=index;var entry=entries.get(index);ImGui.pushID(TerrainOverlays.prefix(index));
            if (ImGui.treeNode("Overlay "+index)) {
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(TerrainOverlays.prefix(),TerrainOverlays.change(selected,workspace.read(selected),action,index),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                try {
                    String low=TerrainOverlays.bound(entry,"min"),high=TerrainOverlays.bound(entry,"max"),prefix=TerrainOverlays.prefix(index)+"bounds.",key=prefix+"pair";
                    ImString min=values.computeIfAbsent(prefix+"min",ignored -> new ImString(low,Math.max(128,low.length()+1))),max=values.computeIfAbsent(prefix+"max",ignored -> new ImString(high,Math.max(128,high.length()+1)));
                    ImGui.inputText("Minimum offset",min);ImGui.inputText("Maximum offset",max);drafts.update(key,low+"|"+high,min.get()+"|"+max.get());
                    if (ImGui.button("Apply both offset bounds")) { try { commitProfileChange(prefix,TerrainOverlays.setBounds(selected,workspace.read(selected),index,min.get(),max.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored bounds are not exact signed 32-bit integers (read-only)."); }
                if (ImGui.treeNode("Overlay generator style")) { drawStyleFields(entry,TerrainOverlays.styleTarget(entryIndex));ImGui.treePop(); }
                for (String field : entry.fields()) if (!TerrainOverlays.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawTerrainTransform(LosslessJson document) {
        if (!ImGui.collapsingHeader("External terrain-transform descriptor")) return;
        ImGui.pushID("terrain-transform");
        ImGui.textWrapped("Author the ID and exact implementation version of a provider installed in Iris. Matching is case-sensitive and preserves whitespace; versions are exact strings, not version ranges. This editor does not discover, install, invoke or validate providers, their settings, or transformed output.");
        LosslessJson descriptor;
        try { descriptor=TerrainTransforms.object(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored terrainTransform is not an inline object (read-only).");ImGui.popID();return; }
        String prefix=TerrainTransforms.prefix()+"identity.",key=prefix+"pair";
        try {
            String id=descriptor==null ? "" : TerrainTransforms.identity(descriptor,"id"),version=descriptor==null ? "" : TerrainTransforms.identity(descriptor,"version");
            if (id.indexOf(0)>=0 || version.indexOf(0)>=0) throw new IllegalArgumentException("NUL text is not editable");
            ImString nextId=values.computeIfAbsent(prefix+"id",ignored -> new ImString(id,Math.max(4096,id.getBytes(StandardCharsets.UTF_8).length+1))),nextVersion=values.computeIfAbsent(prefix+"version",ignored -> new ImString(version,Math.max(4096,version.getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Exact provider ID",nextId);ImGui.inputText("Exact implementation version",nextVersion);drafts.update(key,TerrainTransforms.draftPair(id,version),TerrainTransforms.draftPair(nextId.get(),nextVersion.get()));
            if (ImGui.button(descriptor==null ? "Create descriptor with this identity" : "Apply provider identity pair")) { try { byte[] updated=descriptor==null ? TerrainTransforms.create(selected,workspace.read(selected),nextId.get(),nextVersion.get()) : TerrainTransforms.setIdentity(selected,workspace.read(selected),nextId.get(),nextVersion.get());commitProfileChange(prefix,updated,key); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored ID/version has an unsupported type or NUL text (read-only)."); }
        if (descriptor==null) { ImGui.textWrapped("Absent/null descriptor leaves terrain transformation disabled. Creation writes the chosen ID/version; settings retain their empty class default until enabled.");ImGui.popID();return; }
        drawTerrainTransformSettings(descriptor);
        for (String field : descriptor.fields()) if (!TerrainTransforms.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
        ImGui.textWrapped("Remove discards this entire descriptor, including settings and advanced data, and restores absence.");
        if (ImGui.button("Remove terrain-transform descriptor")) { try { commitProfileChange(TerrainTransforms.prefix(),TerrainTransforms.remove(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } }
        ImGui.popID();
    }
    private void drawTerrainTransformSettings(LosslessJson descriptor) {
        if (!ImGui.treeNode("Provider string settings")) return;
        ImGui.textWrapped("Literal string keys and values are passed to the provider. Empty strings and punctuation are allowed; provider-specific meaning is unchecked. Map iteration order has no runtime guarantee. Final removal restores the empty default by removing settings.");
        List<LosslessJson.StringProperty> rows;
        try { rows=TerrainTransforms.settings(descriptor); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored settings are null, mixed or unsupported (read-only).");ImGui.treePop();return; }
        if (rows==null) { if (ImGui.button("Enable explicit empty settings map")) applyRoutingExtra(() -> workspace.replace(selected,TerrainTransforms.enableSettings(selected,workspace.read(selected))));ImGui.treePop();return; }
        if (rows.size()>TerrainTransforms.MAXIMUM_SETTINGS) { ImGui.textWrapped("Above the 64-setting editor limit (read-only).");ImGui.treePop();return; }
        String prefix=TerrainTransforms.prefix()+"settings#",newKey=prefix+"new.pair";ImString name=values.computeIfAbsent(prefix+"new.key",ignored -> new ImString("",4096)),value=values.computeIfAbsent(prefix+"new.value",ignored -> new ImString("",16384));
        ImGui.inputText("New literal key",name);ImGui.inputText("New string value",value);drafts.update(newKey,TerrainTransforms.draftPair("",""),TerrainTransforms.draftPair(name.get(),value.get()));ImGui.beginDisabled(rows.size()>=TerrainTransforms.MAXIMUM_SETTINGS);boolean add=ImGui.button("Add setting");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,TerrainTransforms.add(selected,workspace.read(selected),name.get(),value.get()),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        for (int index=0;index<rows.size();index++) {
            var row=rows.get(index);String key=prefix+index+".pair";ImGui.pushID(index);
            if (row.key().indexOf(0)>=0 || row.value().indexOf(0)>=0) {
                ImGui.textWrapped("Stored setting contains NUL text (text editing read-only).");
                if (ImGui.button("Remove this setting")) { try { commitProfileChange(prefix,TerrainTransforms.removeSetting(selected,workspace.read(selected),row.key()),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
                ImGui.popID();continue;
            }
            ImString nextKey=values.computeIfAbsent(prefix+index+".key",ignored -> new ImString(row.key(),Math.max(4096,row.key().getBytes(StandardCharsets.UTF_8).length+1))),nextValue=values.computeIfAbsent(prefix+index+".value",ignored -> new ImString(row.value(),Math.max(16384,row.value().getBytes(StandardCharsets.UTF_8).length+1)));
            ImGui.inputText("Literal key",nextKey);ImGui.inputText("String value",nextValue);drafts.update(key,TerrainTransforms.draftPair(row.key(),row.value()),TerrainTransforms.draftPair(nextKey.get(),nextValue.get()));
            if (ImGui.button("Apply setting pair")) { try { commitProfileChange(prefix,TerrainTransforms.set(selected,workspace.read(selected),row.key(),nextKey.get(),nextValue.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            ImGui.sameLine();if (ImGui.button("Remove setting")) { try { commitProfileChange(prefix,TerrainTransforms.removeSetting(selected,workspace.read(selected),row.key()),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }
        ImGui.treePop();
    }
    private void drawSubterrainFeatures(LosslessJson document) {
        if (!ImGui.collapsingHeader("Underground feature definitions")) return;
        ImGui.pushID("subterrain-features");
        ImGui.textWrapped("Authored faults, cenotes, lava tubes and terraces. Native planning requires useMantle, carvingEnabled, OVERWORLD mode and CARVED availability. Registry/material compatibility, full footprint/Y-band fitting, region selection and generated geometry are unvalidated. No planning, rasterization or preview runs here.");
        ImGui.textWrapped("IDs use native letters/digits/_/- and slash syntax with trimmed case-sensitive identity. New IDs must be unique across this list, including disabled entries (editor guard). Priority is a signed integer; higher priority wins and native stable IDs break ties.");
        List<LosslessJson> entries;
        try { entries=SubterrainFeatures.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored feature list is null, a snippet or mixed/non-object data (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>SubterrainFeatures.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the native 64-definition limit (read-only).");ImGui.popID();return; }
        for (String finding : SubterrainFeatures.findings(document)) ImGui.textWrapped("Applied feature check: "+finding+" (does not block Save/Export)");
        List<String> biomes=SubterrainFeatures.choices(workspace,"biomes"),regions=SubterrainFeatures.choices(workspace,"regions");String newKey=SubterrainFeatures.prefix()+"new.id",biomeKey=SubterrainFeatures.prefix()+"new.biome";
        ImString id=values.computeIfAbsent(newKey,ignored -> new ImString("",4096)),biome=values.computeIfAbsent(biomeKey,ignored -> new ImString("",4096));ImGui.inputText("New stable feature ID",id);drafts.update(newKey,"",id.get());
        if (ImGui.beginCombo("New occupied-cell biome",biome.get().isEmpty() ? "Choose an exact biome" : biome.get())) { for (String choice : biomes) if (ImGui.selectable(choice,choice.equals(biome.get()))) biome.set(choice);ImGui.endCombo(); }
        ImGui.textWrapped("Add seeds ID/biome only; CENOTE, enabled=true and all numeric/range/material defaults remain implicit. Final removal restores field absence.");
        ImGui.beginDisabled(id.get().isBlank() || biome.get().isEmpty() || entries!=null && entries.size()>=SubterrainFeatures.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add feature definition");ImGui.endDisabled();
        if (add) { try { commitProfileChange(SubterrainFeatures.prefix(),SubterrainFeatures.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,id.get(),biome.get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries!=null) for (int index=0;index<entries.size();index++) {
            int feature=index;var entry=entries.get(index);String prefix=SubterrainFeatures.prefix(index);ImGui.pushID(prefix);
            if (ImGui.treeNode("Feature "+index)) {
                String copyKey=prefix+"copy.id";ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",4096));ImGui.inputText("Duplicate with new ID",copyId);drafts.update(copyKey,"",copyId.get());
                RiverProfiles.Action action=null;if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove feature")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(SubterrainFeatures.prefix(),SubterrainFeatures.change(selected,workspace.read(selected),action,index,copyId.get(),null,workspace),action==RiverProfiles.Action.DUPLICATE ? copyKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                for (String field : List.of("id","solid")) {
                    ImGui.pushID(field);ImGui.textWrapped(SubterrainFeatures.help(field));String key=prefix+field;
                    try { String stored=SubterrainFeatures.text(entry,field);if (stored.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(16384,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,stored,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,field.equals("id") ? SubterrainFeatures.setId(selected,workspace.read(selected),feature,input.get()) : SubterrainFeatures.setSolid(selected,workspace.read(selected),feature,input.get()))); }
                    catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored text has an unsupported type/value (read-only)."); }ImGui.popID();
                }
                try { String current=SubterrainFeatures.text(entry,"biome");if (!current.isBlank() && !biomes.contains(current)) ImGui.textWrapped("Stored biome has no exact authorable file match; native trimming/aliases/content are unchecked.");if (ImGui.beginCombo("Occupied-cell biome",current.isEmpty() ? "No explicit biome" : current.length()>128 || current.indexOf(0)>=0 ? "Stored biome" : current)) { if (ImGui.selectable("No explicit biome (empty)",current.isEmpty())) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setBiome(selected,workspace.read(selected),feature,null,workspace)));for (String choice : biomes) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setBiome(selected,workspace.read(selected),feature,choice,workspace)));ImGui.endCombo(); } }
                catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored biome reference is unsupported (read-only)."); }
                String enabled=entry.token("enabled");if (enabled==null || enabled.equals("true") || enabled.equals("false")) { boolean value=enabled==null || enabled.equals("true");if (ImGui.checkbox("Enabled (native default true)",value)) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setEnabled(selected,workspace.read(selected),feature,!value))); } else ImGui.textWrapped("Stored enabled is not boolean (read-only).");
                try { String current=SubterrainFeatures.family(entry);if (ImGui.beginCombo("Geometry family",current.length()>128 || current.indexOf(0)>=0 ? "Stored family" : current)) { for (String choice : SubterrainFeatures.families()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setFamily(selected,workspace.read(selected),feature,choice)));ImGui.endCombo(); } }
                catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored family is not a string (read-only)."); }
                try { String current=SubterrainFeatures.fluid(entry);ImGui.textWrapped("Native fluid rule: "+SubterrainFeatures.effectiveFluid(entry)+"; no material is resolved here.");if (ImGui.beginCombo("Retained fluid override",current==null ? "Family default" : current.length()>128 || current.indexOf(0)>=0 ? "Stored fluid" : current)) { if (ImGui.selectable("Family default (remove override)",current==null)) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setFluid(selected,workspace.read(selected),feature,null)));for (String choice : SubterrainFeatures.fluids()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setFluid(selected,workspace.read(selected),feature,choice)));ImGui.endCombo(); } }
                catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored fluid/family is unsupported (read-only)."); }
                for (String field : SubterrainFeatures.NUMBERS) {
                    ImGui.pushID(field);ImGui.textWrapped(SubterrainFeatures.help(field));ImGui.textWrapped(SubterrainFeatures.constraints(field));String token=entry.token(field),key=prefix+field;
                    if (token==null) { if (ImGui.button("Enable default "+SubterrainFeatures.defaultToken(field))) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.enableScalar(selected,workspace.read(selected),feature,field))); }
                    else if (!SubterrainFeatures.numericSupported(entry,field)) ImGui.textWrapped("Stored scalar has an unsupported type, width or finite value (read-only).");
                    else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,SubterrainFeatures.setScalar(selected,workspace.read(selected),feature,field,input.get()))); }ImGui.popID();
                }
                drawSubterrainRange(entry,index);drawSubterrainRegions(entry,index,regions);
                for (String field : entry.fields()) if (!SubterrainFeatures.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawSubterrainRange(LosslessJson entry,int feature) {
        if (!ImGui.treeNode("Inclusive absolute world-Y band")) return;
        ImGui.textWrapped("Absent range defaults to -48..48; inline empty range uses IrisRange defaults 16..32. Finite ordered bounds must fit signed 32-bit Y. Native planning uses ceil(min)/floor(max) and requires the complete feature/seals to fit; band/geometry fitting and resulting placement are unvalidated.");
        String prefix=SubterrainFeatures.prefix(feature)+"worldYRange.",key=prefix+"pair";
        try { String low=SubterrainFeatures.bound(entry,"min"),high=SubterrainFeatures.bound(entry,"max");ImString min=values.computeIfAbsent(prefix+"min",ignored -> new ImString(low,Math.max(128,low.length()+1))),max=values.computeIfAbsent(prefix+"max",ignored -> new ImString(high,Math.max(128,high.length()+1)));
            ImGui.inputText("Inclusive minimum world Y",min);ImGui.inputText("Inclusive maximum world Y",max);drafts.update(key,TerrainTransforms.draftPair(low,high),TerrainTransforms.draftPair(min.get(),max.get()));
            if (ImGui.button("Apply world-Y pair")) { try { commitProfileChange(prefix,SubterrainFeatures.setRange(selected,workspace.read(selected),feature,min.get(),max.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            var range=SubterrainFeatures.range(entry);if (range!=null) { for (String field : range.fields()) if (!List.of("min","max").contains(field)) ImGui.textWrapped("worldYRange."+field+" (read-only)");ImGui.textWrapped("Remove discards the entire range object, including advanced data, and restores the absent -48..48 default.");if (ImGui.button("Remove range override")) { try { commitProfileChange(prefix,SubterrainFeatures.removeRange(selected,workspace.read(selected),feature),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored range is null, a snippet or contains unsupported bounds (read-only)."); }ImGui.treePop();
    }
    private void drawSubterrainRegions(LosslessJson entry,int feature,List<String> choices) {
        if (!ImGui.treeNode("Allowed placement-center regions")) return;
        ImGui.textWrapped("Absent/empty permits all regions. Exact in-pack native key syntax only; contents and center-region selection are unchecked. Raw order/duplicates are preserved here; native membership is a set. Final removal restores absence.");
        List<String> regions;try { regions=SubterrainFeatures.regions(entry); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored allowedRegions is null/mixed/unsupported (read-only).");ImGui.treePop();return; }
        if (regions!=null && regions.size()>SubterrainFeatures.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-region editor limit (read-only).");ImGui.treePop();return; }
        String prefix=SubterrainFeatures.prefix(feature)+"allowedRegions#",key=prefix+"new";ImString next=values.computeIfAbsent(key,ignored -> new ImString("",4096));if (ImGui.beginCombo("New allowed region",next.get().isEmpty() ? "Choose a region" : next.get())) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(next.get()))) next.set(choice);ImGui.endCombo(); }
        ImGui.beginDisabled(next.get().isEmpty() || regions!=null && regions.size()>=SubterrainFeatures.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add region reference");ImGui.endDisabled();if (add) { try { commitProfileChange(prefix,SubterrainFeatures.changeRegion(selected,workspace.read(selected),feature,RiverProfiles.Action.ADD,-1,next.get(),workspace),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        if (regions!=null) for (int index=0;index<regions.size();index++) { int region=index;String current=regions.get(index);ImGui.pushID(index);
            if (ImGui.beginCombo("Region",current.length()>128 || current.indexOf(0)>=0 ? "Stored region" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,SubterrainFeatures.setRegion(selected,workspace.read(selected),feature,region,choice,workspace)));ImGui.endCombo(); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate reference")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==regions.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove reference")) action=RiverProfiles.Action.REMOVE;
            if (action!=null) { try { commitProfileChange(prefix,SubterrainFeatures.changeRegion(selected,workspace.read(selected),feature,action,index,null,workspace),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }ImGui.treePop();
    }
    private void drawDimensionCarvings(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension carving entries")) return;
        ImGui.pushID("dimension-carving");
        ImGui.textWrapped("Dimension-level cave-biome overrides use absolute world Y with inclusive bounds. Native root lookup picks the last usable matching entry; cave generation can combine multiple entry profiles. Enabled flags, target content gates, native carving and noise are not evaluated here; unsupported previews remain disabled.");
        ImGui.textWrapped("Child selection includes the parent and usable linked entries, uses biome rarity, and does not apply the child's Y range. Cycles and self-links are allowed; native depth is capped at 32. ID changes and removals leave existing links unchanged.");
        List<LosslessJson> entries;
        try { entries=DimensionCarvings.entries(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored carving list is a snippet, mixed list or unsupported type (read-only).");ImGui.popID();return; }
        if (entries!=null && entries.size()>DimensionCarvings.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-entry editor limit (read-only).");ImGui.popID();return; }
        for (String finding : DimensionCarvings.findings(document)) ImGui.textWrapped("Applied local ID check: "+finding+" (Save/Export remain available)");
        String newKey=DimensionCarvings.prefix()+"new.id",biomeKey=DimensionCarvings.prefix()+"new.biome";
        ImString newId=values.computeIfAbsent(newKey,ignored -> new ImString("",4096)),biome=values.computeIfAbsent(biomeKey,ignored -> new ImString("",4096));
        ImGui.inputText("New local ID (lowercase letters/digits/_/-)",newId);drafts.update(newKey,"",newId.get());
        var choices=selectorCatalogs.choices(workspace,"carvingBiome");
        if (ImGui.beginCombo("New cave biome (exact file)",biome.get().isEmpty() ? "Choose a biome" : biome.get())) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(biome.get()))) biome.set(choice);ImGui.endCombo(); }
        ImGui.beginDisabled(newId.get().isEmpty() || biome.get().isEmpty() || entries!=null && entries.size()>=DimensionCarvings.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add carving entry");ImGui.endDisabled();
        if (add) { try { commitProfileChange(DimensionCarvings.prefix(),DimensionCarvings.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,newId.get(),biome.get(),workspace),newKey); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        if (entries==null) { ImGui.textWrapped("Absent/null list: no authored dimension carving entries. Add seeds only ID and biome; native defaults supply all other fields.");ImGui.popID();return; }
        for (int index=0;index<entries.size();index++) {
            int entryIndex=index;var entry=entries.get(index);String prefix=DimensionCarvings.prefix(index);ImGui.pushID(prefix);
            if (ImGui.treeNode("Carving entry "+index)) {
                String copyKey=prefix+"copy.id";ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",4096));ImGui.inputText("Duplicate with new ID",copyId);drafts.update(copyKey,"",copyId.get());
                RiverProfiles.Action action=null;
                if (ImGui.button("Duplicate entry")) action=RiverProfiles.Action.DUPLICATE;
                ImGui.sameLine();ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();
                ImGui.sameLine();ImGui.beginDisabled(index+1==entries.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove entry")) action=RiverProfiles.Action.REMOVE;
                if (action!=null) { try { commitProfileChange(DimensionCarvings.prefix(),DimensionCarvings.change(selected,workspace.read(selected),action,index,copyId.get(),null,workspace),action==RiverProfiles.Action.DUPLICATE ? copyKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                try {
                    String stored=entry.token("id")==null ? "" : entry.stringToken("id"),key=prefix+"id";ImString id=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                    ImGui.inputText("Local ID",id);drafts.update(key,stored,id.get());if (ImGui.button("Apply ID (links unchanged)")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionCarvings.setId(selected,workspace.read(selected),entryIndex,id.get())));
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored ID is not a string (read-only)."); }
                try {
                    String current=entry.token("biome")==null ? "" : entry.stringToken("biome");
                    if (!current.isEmpty() && !choices.contains(current)) ImGui.textWrapped("Stored biome has no exact file match; native trimmed aliases and contents are unchecked.");
                    if (ImGui.beginCombo("Cave biome",current.isEmpty() ? "Choose an exact biome" : current.length()>128 ? "Stored biome key" : current)) { for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionCarvings.setBiome(selected,workspace.read(selected),entryIndex,choice,workspace)));ImGui.endCombo(); }
                } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored biome is not a string (read-only)."); }
                String enabled=entry.token("enabled");if (enabled==null || enabled.equals("true") || enabled.equals("false")) { boolean value=enabled==null || enabled.equals("true");if (ImGui.checkbox("Enabled (native default true)",value)) applyRoutingExtra(() -> workspace.replace(selected,DimensionCarvings.setEnabled(selected,workspace.read(selected),entryIndex,!value))); } else ImGui.textWrapped("Stored enabled is not a boolean (read-only).");
                drawCarvingRange(entry,index);
                for (String field : DimensionCarvings.SCALARS) {
                    ImGui.pushID(field);ImGui.textWrapped(DimensionCarvings.help(field));ImGui.textWrapped(field.equals("childRecursionDepth") ? "Exact depth 0..32; editor uses the native clamp range." : "Finite shrink factor >= 0.0001; editor uses the native minimum clamp.");String token=entry.token(field),key=prefix+field;
                    if (token==null) { if (ImGui.button("Enable default "+DimensionCarvings.defaultToken(field))) applyRoutingExtra(() -> workspace.replace(selected,DimensionCarvings.enableScalar(selected,workspace.read(selected),entryIndex,field))); }
                    else if (!DimensionCarvings.numericSupported(entry,field)) ImGui.textWrapped("Stored scalar has an unsupported type or integer width (read-only).");
                    else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionCarvings.setScalar(selected,workspace.read(selected),entryIndex,field,input.get()))); }
                    ImGui.popID();
                }
                drawCarvingChildren(document,entry,index);
                if (ImGui.treeNode("Child selection style")) { drawStyleFields(entry,DimensionCarvings.styleTarget(index));ImGui.treePop(); }
                for (String field : entry.fields()) if (!DimensionCarvings.EDITABLE.contains(field)) ImGui.textWrapped(field+" (read-only)");
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.popID();
    }
    private void drawCarvingRange(LosslessJson entry,int index) {
        if (!ImGui.treeNode("Inclusive absolute world-Y range")) return;
        String prefix=DimensionCarvings.prefix(index)+"worldYRange.",key=prefix+"pair";
        ImGui.textWrapped("Absent range defaults to -64..320; an inline empty range uses class defaults 16..32. Explicit null is unbounded. Bounds may be fractional and are not clamped to dimension height. Applying a pair requires finite ordered values (editor guard).");
        try {
            String low=DimensionCarvings.rangeBound(entry,"min"),high=DimensionCarvings.rangeBound(entry,"max");
            ImString min=values.computeIfAbsent(prefix+"min",ignored -> new ImString(low,Math.max(128,low.length()+1))),max=values.computeIfAbsent(prefix+"max",ignored -> new ImString(high,Math.max(128,high.length()+1)));
            if ("null".equals(entry.token("worldYRange"))) ImGui.textWrapped("Currently unbounded; inputs are defaults for an explicit range.");
            ImGui.inputText("Inclusive minimum world Y",min);ImGui.inputText("Inclusive maximum world Y",max);drafts.update(key,low+"|"+high,min.get()+"|"+max.get());
            if (ImGui.button("Apply explicit world-Y pair")) { try { commitProfileChange(prefix,DimensionCarvings.setRange(selected,workspace.read(selected),index,min.get(),max.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            if (!"null".equals(entry.token("worldYRange")) && ImGui.button("Remove Y restriction (write null)")) { try { commitProfileChange(prefix,DimensionCarvings.unbounded(selected,workspace.read(selected),index),null); } catch (Exception failure) { error=failure.getMessage(); } }
            var range=DimensionCarvings.range(entry);if (range!=null) for (String field : range.fields()) if (!List.of("min","max").contains(field)) ImGui.textWrapped("worldYRange."+field+" (read-only)");
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored range/bounds are unsupported (read-only)."); }
        ImGui.treePop();
    }
    private void drawCarvingChildren(LosslessJson document,LosslessJson entry,int index) {
        if (!ImGui.treeNode("Child carving-entry links")) return;
        String prefix=DimensionCarvings.prefix(index)+"children#",key=prefix+"new";List<String> children;
        try { children=DimensionCarvings.children(entry); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored children are not a string list (read-only).");ImGui.treePop();return; }
        if (children!=null && children.size()>DimensionCarvings.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-child editor limit (read-only).");ImGui.treePop();return; }
        ImString choice=values.computeIfAbsent(key,ignored -> new ImString("",4096));var ids=DimensionCarvings.ids(document);
        if (ImGui.beginCombo("Local child ID",choice.get().isEmpty() ? "Choose an ID" : choice.get())) { for (String id : ids) if (ImGui.selectable(id,id.equals(choice.get()))) choice.set(id);ImGui.endCombo(); }
        ImGui.beginDisabled(choice.get().isEmpty() || children!=null && children.size()>=DimensionCarvings.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add child link");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,DimensionCarvings.changeChild(selected,workspace.read(selected),index,RiverProfiles.Action.ADD,-1,choice.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        if (children!=null) for (int child=0;child<children.size();child++) {
            ImGui.pushID(child);ImGui.textWrapped(children.get(child));RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate link")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(child==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(child+1==children.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove link")) action=RiverProfiles.Action.REMOVE;
            if (action!=null) { try { commitProfileChange(prefix,DimensionCarvings.changeChild(selected,workspace.read(selected),index,action,child,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }ImGui.popID();
        }
        ImGui.treePop();
    }
    private void drawResourceSelectors(LosslessJson document) {
        if (!ImGui.collapsingHeader(ResourceTypes.base(selected).equals("dimensions") ? "Focused biome and region" : "Reachable carving-biome reference")) return;
        ImGui.textWrapped("Choices are exact in-pack file keys; target contents, aliases, compatibility exclusions and effective selection are unvalidated. Missing stored strings stay preserved until explicitly replaced or cleared. These controls do not sample or preview generation.");
        if (ResourceTypes.base(selected).equals("dimensions")) ImGui.textWrapped("A usable focused biome takes priority and resolves its containing region, or a native fallback region. Focused region is used when no usable biome focus takes priority. Native buffet mode can override focus. Clearing one selector leaves the other unchanged.");
        else ImGui.textWrapped("carvingBiome only registers a reachable biome; it does not replace terrain under carvings. Cave selection comes from region caveBiomes. Self/cyclic references may be authored; native traversal handles visited keys and no traversal runs here.");
        for (String field : ResourceSelectors.fields(selected)) {
            ImGui.pushID(ResourceSelectors.prefix(selected,field));ImGui.textWrapped(ResourceSelectors.help(field));
            if (!ResourceSelectors.supported(document,field)) ImGui.textWrapped("Stored selector is not a string (read-only).");
            else {
                String current=ResourceSelectors.stored(document,field);var choices=selectorCatalogs.choices(workspace,field);
                if (!current.isBlank() && !choices.contains(current)) ImGui.textWrapped("Stored selector has no exact file match; it is preserved and native aliases are unchecked.");
                if (ImGui.beginCombo(field,current.isBlank() ? "None (absent/blank)" : current.length()>128 ? "Stored selector" : current)) {
                    if (ImGui.selectable("None (write empty string)",current.isBlank())) applyRoutingExtra(() -> workspace.replace(selected,ResourceSelectors.set(selected,workspace.read(selected),field,null,workspace)));
                    for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,ResourceSelectors.set(selected,workspace.read(selected),field,choice,workspace)));
                    ImGui.endCombo();
                }
                if (!current.isEmpty() && ImGui.button("Clear selector (empty string)")) applyRoutingExtra(() -> workspace.replace(selected,ResourceSelectors.set(selected,workspace.read(selected),field,null,workspace)));
                if (choices.isEmpty()) ImGui.textWrapped("No exact "+ResourceSelectors.family(field)+" files in this pack.");
            }
            ImGui.popID();
        }
    }
    private void drawCustomBiomes(LosslessJson document) {
        if (!ImGui.collapsingHeader("Custom biome definitions")) return;
        ImGui.pushID("customBiomes");ImGui.textWrapped("Native field: customDerivitives. A nonempty list enables custom biome definitions. One definition is used directly; multiple definitions are selected through the owning biome's noise distribution, so order matters. No custom datapack or biome preview is generated here.");
        ImGui.textWrapped("New IDs use simple lowercase letters, digits, _ and - (editor subset), with uniqueness checked within this list. Generated registry keys, aliases, registry contents and target-version compatibility are unvalidated. Editor limit: 64 definitions or entries per nested list.");
        List<LosslessJson> entries;
        try { entries=CustomBiomes.entries(document); } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored custom list contains snippets or unsupported entries (read-only).");ImGui.popID();return; }
        if (entries==null) entries=List.of();if (entries.size()>CustomBiomes.MAXIMUM_ENTRIES) { ImGui.textWrapped("Stored list exceeds the editor limit (read-only).");ImGui.popID();return; }
        String key=CustomBiomes.prefix()+"new.id";ImString id=values.computeIfAbsent(key,ignored -> new ImString("",256));ImGui.inputText("New custom ID",id);drafts.update(key,"",id.get());
        ImGui.beginDisabled(entries.size()>=CustomBiomes.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add custom definition");ImGui.endDisabled();
        if (add) { try { commitProfileChange(CustomBiomes.prefix(),CustomBiomes.change(selected,workspace.read(selected),RiverProfiles.Action.ADD,-1,id.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Custom definition "+index)) continue;
            var context=CustomBiomes.Context.biome(index);var entry=entries.get(index);RiverProfiles.Action action=null;String copyKey=context.prefix()+"duplicate.id";ImString copyId=values.computeIfAbsent(copyKey,ignored -> new ImString("",256));ImGui.inputText("Duplicate with new ID",copyId);drafts.update(copyKey,"",copyId.get());
            ImGui.beginDisabled(entries.size()>=CustomBiomes.MAXIMUM_ENTRIES);if (ImGui.button("Duplicate definition")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button(entries.size()==1 ? "Remove final definition (restore absence)" : "Remove definition")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move definition up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Move definition down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { try { commitProfileChange(CustomBiomes.prefix(),CustomBiomes.change(selected,workspace.read(selected),action,index,action==RiverProfiles.Action.DUPLICATE ? copyId.get() : null),action==RiverProfiles.Action.DUPLICATE ? copyKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();return; }
            drawCustomText(entry,context,"id");drawCustomScalar(entry,context,"temperature");drawCustomScalar(entry,context,"humidity");drawCustomScalar(entry,context,"spawnRarity");drawCustomEnum(entry,context,"category");drawCustomEnum(entry,context,"downfallType");
            if (ImGui.treeNode("Biome colors")) {
                ImGui.textWrapped("Sky, fog, water and water-fog colors are always emitted by native datagen; use #RRGGBB here. Grass and foliage accept blank to omit their override. Other stored string formats are retained until explicitly replaced.");
                for (String field : CustomBiomes.COLORS) drawCustomText(entry,context,field);ImGui.treePop();
            }
            drawCustomTags(entry,context);drawCustomParticle(entry,context);drawCustomSpawns(entry,context);ImGui.treePop();
        }
        if (ImGui.treeNode("Other custom-biome data (read-only)")) { for (String path : CustomBiomes.readOnlyPaths(document)) ImGui.textWrapped(path);ImGui.treePop(); }
        ImGui.popID();
    }
    private void drawCustomText(LosslessJson node,CustomBiomes.Context context,String field) {
        ImGui.pushID(context.prefix()+field);ImGui.textWrapped(CustomBiomes.help(context,field));String stored;
        try { stored=node.token(field)==null ? CustomBiomes.defaultToken(context,field) : node.stringToken(field); }
        catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored value is not a string (read-only).");ImGui.popID();return; }
        String key=context.prefix()+field;ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(256,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,stored,input.get());
        if (ImGui.button(node.token(field)==null ? "Write explicit value" : "Apply")) commitRiverOverride(key,() -> workspace.replace(selected,CustomBiomes.setText(selected,workspace.read(selected),context,field,input.get())));
        ImGui.popID();
    }
    private void drawCustomScalar(LosslessJson node,CustomBiomes.Context context,String field) {
        ImGui.pushID(context.prefix()+field);ImGui.textWrapped(CustomBiomes.help(context,field));ImGui.textWrapped(CustomBiomes.constraints(context,field));String token=node.token(field),key=context.prefix()+field;
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+CustomBiomes.defaultToken(context,field)+")")) applyRoutingExtra(() -> workspace.replace(selected,CustomBiomes.enableScalar(selected,workspace.read(selected),context,field)));
        } else if (!CustomBiomes.numericSupported(node,context,field)) ImGui.textWrapped("Stored number has an unsupported type or integer width (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,CustomBiomes.setScalar(selected,workspace.read(selected),context,field,input.get())));
        }
        ImGui.popID();
    }
    private void drawCustomEnum(LosslessJson node,CustomBiomes.Context context,String field) {
        ImGui.pushID(context.prefix()+field);ImGui.textWrapped(CustomBiomes.help(context,field));String current;
        try { current=node.token(field)==null ? CustomBiomes.defaultToken(context,field) : context.spawn()>=0 && field.equals("group") && node.token(field).equals("null") ? "Null (native MISC fallback)" : node.stringToken(field); }
        catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored enum has an unsupported type (read-only).");ImGui.popID();return; }
        if (ImGui.beginCombo(field,current.length()>128 ? "Stored enum" : current)) { for (String choice : CustomBiomes.choices(context,field)) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,CustomBiomes.setEnum(selected,workspace.read(selected),context,field,choice)));ImGui.endCombo(); }
        ImGui.popID();
    }
    private void drawCustomParticle(LosslessJson entry,CustomBiomes.Context owner) {
        if (!ImGui.treeNode("Ambient particle")) return;
        ImGui.textWrapped("Particle keys are fully namespaced syntax only; the live registry is not checked. Native probability is 1 / rarity. Absent/null disables particles.");
        try {
            var particle=CustomBiomes.particle(entry);
            if (particle==null) {
                if (ImGui.button("Enable particle (minecraft:flash, rarity 35)")) applyRoutingExtra(() -> workspace.replace(selected,CustomBiomes.enableParticle(selected,workspace.read(selected),owner)));
            } else {
                var context=owner.particleTarget();drawCustomText(particle,context,"particle");drawCustomScalar(particle,context,"rarity");ImGui.textWrapped("Disable discards the entire particle object, including advanced data, and writes null.");
                if (ImGui.button("Disable ambient particle")) {
                    try { byte[] next=CustomBiomes.disableParticle(selected,workspace.read(selected),owner);String prefix=context.prefix();if (drafts.transitionUnder(prefix,() -> !drafts.anyUnder(prefix) || JOptionPane.showConfirmDialog(null,"Discard unapplied particle text and disable this particle object?","Unapplied particle text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION,() -> workspace.replace(selected,next))) { values.keySet().removeIf(key -> key.startsWith(prefix));error=null; } } catch (Exception failure) { error=failure.getMessage(); }
                }
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored particle is a snippet or unsupported value (read-only)."); }
        ImGui.treePop();
    }
    private void drawCustomCounts(LosslessJson node,CustomBiomes.Context context) {
        ImGui.textWrapped("Apply positive exact spawn counts together; this editor requires minimum <= maximum. Native integer width permits up to 2147483647; target-version spawn behavior is unvalidated.");
        if (!CustomBiomes.numericSupported(node,context,"minCount") || !CustomBiomes.numericSupported(node,context,"maxCount")) { ImGui.textWrapped("Stored count pair has an unsupported type/width (read-only).");return; }
        String min=node.token("minCount")==null ? CustomBiomes.defaultToken(context,"minCount") : node.token("minCount"),max=node.token("maxCount")==null ? CustomBiomes.defaultToken(context,"maxCount") : node.token("maxCount"),minKey=context.prefix()+"minCount",maxKey=context.prefix()+"maxCount";
        ImString minimum=values.computeIfAbsent(minKey,ignored -> new ImString(min,Math.max(128,min.length()+1))),maximum=values.computeIfAbsent(maxKey,ignored -> new ImString(max,Math.max(128,max.length()+1)));ImGui.inputText("Minimum count",minimum);drafts.update(minKey,min,minimum.get());ImGui.inputText("Maximum count",maximum);drafts.update(maxKey,max,maximum.get());
        if (ImGui.button("Apply count pair")) { try { workspace.replace(selected,CustomBiomes.setCounts(selected,workspace.read(selected),context,minimum.get(),maximum.get()));drafts.applied(minKey);drafts.applied(maxKey);values.remove(minKey);values.remove(maxKey);error=null; } catch (Exception failure) { error=failure.getMessage(); } }
    }
    private void drawCustomSpawns(LosslessJson entry,CustomBiomes.Context owner) {
        if (!ImGui.treeNode("Custom spawn entries")) return;
        ImGui.textWrapped("Native datagen emits this list independently of spawnRarity, including when rarity is 0. A native content gate may omit entity types missing on the target server. This editor checks fully namespaced key syntax, not registries or runtime spawning.");
        List<LosslessJson> rows;
        try { rows=CustomBiomes.spawns(entry); } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored spawn list is mixed, null or a snippet (read-only).");ImGui.treePop();return; }
        if (rows==null) rows=List.of();if (rows.size()>CustomBiomes.MAXIMUM_ENTRIES) { ImGui.textWrapped("Stored spawn list exceeds the editor limit (read-only).");ImGui.treePop();return; }
        String prefix=CustomBiomes.listPrefix(owner,"spawns"),key=prefix+"new.type",initial=CustomBiomes.defaultToken(owner.spawn(0),"type");ImString type=values.computeIfAbsent(key,ignored -> new ImString(initial,4096));ImGui.inputText("New entity type",type);drafts.update(key,initial,type.get());ImGui.beginDisabled(rows.size()>=CustomBiomes.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add spawn entry");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,CustomBiomes.changeSpawn(selected,workspace.read(selected),owner,RiverProfiles.Action.ADD,-1,type.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        for (int index=0;index<rows.size();index++) {
            if (!ImGui.treeNode("Spawn "+index)) continue;
            RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=CustomBiomes.MAXIMUM_ENTRIES);if (ImGui.button("Duplicate spawn")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button(rows.size()==1 ? "Remove final spawn (restore absence)" : "Remove spawn")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move spawn up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==rows.size()-1);if (ImGui.button("Move spawn down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { try { commitProfileChange(prefix,CustomBiomes.changeSpawn(selected,workspace.read(selected),owner,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.treePop();return; }
            var context=owner.spawn(index);var row=rows.get(index);drawCustomText(row,context,"type");drawCustomEnum(row,context,"group");drawCustomScalar(row,context,"weight");drawCustomCounts(row,context);ImGui.treePop();
        }
        ImGui.treePop();
    }
    private void drawCustomTags(LosslessJson entry,CustomBiomes.Context owner) {
        if (!ImGui.treeNode("Authored biome tags")) return;
        ImGui.textWrapped("Native datagen combines authored tags with inherited vanilla-derivative tags and deduplicates emitted membership. These are authored values only; effective registry membership is not resolved. Use namespaced tag keys without #.");
        List<String> tags;
        try { tags=CustomBiomes.tags(entry); } catch (IllegalArgumentException invalid) { ImGui.textWrapped("Stored tag list is mixed, null or unsupported (read-only).");ImGui.treePop();return; }
        if (tags==null) tags=List.of();if (tags.size()>CustomBiomes.MAXIMUM_ENTRIES) { ImGui.textWrapped("Stored tag list exceeds the editor limit (read-only).");ImGui.treePop();return; }
        String prefix=CustomBiomes.listPrefix(owner,"tags"),key=prefix+"new.tag";ImString tag=values.computeIfAbsent(key,ignored -> new ImString("",4096));ImGui.inputText("New tag key",tag);drafts.update(key,"",tag.get());ImGui.beginDisabled(tags.size()>=CustomBiomes.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add authored tag");ImGui.endDisabled();
        if (add) { try { commitProfileChange(prefix,CustomBiomes.changeTag(selected,workspace.read(selected),owner,RiverProfiles.Action.ADD,-1,tag.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();return; }
        for (int index=0;index<tags.size();index++) {
            ImGui.pushID("customTag"+index);String stored=tags.get(index),rowKey=prefix+index;int slot=index;ImString input=values.computeIfAbsent(rowKey,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Tag",input);drafts.update(rowKey,stored,input.get());if (ImGui.button("Apply tag")) commitRiverOverride(rowKey,() -> workspace.replace(selected,CustomBiomes.setTag(selected,workspace.read(selected),owner,slot,input.get())));
            RiverProfiles.Action action=null;ImGui.beginDisabled(tags.size()>=CustomBiomes.MAXIMUM_ENTRIES);if (ImGui.button("Duplicate tag")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button(tags.size()==1 ? "Remove final tag (restore absence)" : "Remove tag")) action=RiverProfiles.Action.REMOVE;
            ImGui.beginDisabled(index==0);if (ImGui.button("Move tag up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==tags.size()-1);if (ImGui.button("Move tag down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            if (action!=null) { try { commitProfileChange(prefix,CustomBiomes.changeTag(selected,workspace.read(selected),owner,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            ImGui.popID();
        }
        ImGui.treePop();
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
                try { var style=NoiseStyleFields.object(decorator,target);if (style!=null) for (String child : StyleFractures.readOnlyPaths(style)) ImGui.textWrapped(child+" (read-only)"); } catch (IllegalArgumentException unsupported) { }
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
        try { String stored=block==null || block.token("block")==null ? "" : block.stringToken("block"),key=context.prefix()+"forceBlock.block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(block==null ? "Block for explicit override" : "Surface block",input);drafts.update(key,stored,input.get());if (ImGui.button("Apply forced surface block")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setForceBlock(selected,workspace.read(selected),context,input.get())));if (block!=null) drawExactBlockAlias(stored,key); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block text has an unsupported type (read-only)."); }
        if (block!=null) drawInlineBlockData(block,InlineBlockData.Context.forced(context),1);ImGui.treePop();
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
            try { String stored=entry.token("block")==null ? "" : entry.stringToken("block"),key=prefix+"block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Block",input);drafts.update(key,stored,input.get());if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setBlock(selected,workspace.read(selected),context,field,blockIndex,input.get())));drawExactBlockAlias(stored,key); }
            catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block text has an unsupported type (read-only)."); }
            if (BiomeDecorators.weighted(field)) {
                String token=entry.token("weight"),key=prefix+"weight";
                if (token==null) { if (ImGui.button("Enable weight 1")) applyRoutingExtra(() -> workspace.replace(selected,BiomeDecorators.enableWeight(selected,workspace.read(selected),context,field,blockIndex))); }
                else if (!LosslessJson.numericToken(token)) ImGui.textWrapped("Stored weight has an unsupported type (read-only).");
                else { ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Exact weight 1..1000",input);drafts.update(key,token,input.get());if (ImGui.button("Apply weight")) commitRiverOverride(key,() -> workspace.replace(selected,BiomeDecorators.setWeight(selected,workspace.read(selected),context,field,blockIndex,input.get()))); }
            }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate block")) action=RiverProfiles.Action.DUPLICATE;ImGui.sameLine();ImGui.beginDisabled(field.equals("palette") && entries.size()<=1);if (ImGui.button("Remove block")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Block up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Block down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            drawInlineBlockData(entry,InlineBlockData.Context.decorator(context,field,blockIndex),entries.size());
        }
    }
    private void changeDecoratorBlock(BiomeDecorators.Context context,String field,RiverProfiles.Action action,int index,String value,String appliedKey) { try { commitProfileChange(context.blocksPrefix(field),BiomeDecorators.changeBlock(selected,workspace.read(selected),context,field,action,index,value),appliedKey); } catch (Exception failure) { error=failure.getMessage(); } }
    private static String mappingDraft(String source,String target) { return source.length()+":"+source+target; }
    private void drawInlineBlockData(LosslessJson entry,BlockDefinitions.Context context,int count) {
        if (count>PaletteBlockData.MAX_ENTRIES) { ImGui.textWrapped("Block-state/backup editing is limited to palettes of 64 entries; advanced data is preserved.");return; }
        if (ImGui.treeNode("Block states and backups")) { ImGui.textWrapped("Structured states and bounded backups use native block-data definitions. These edits preserve the root block and stored weight. Registry, alias cycles and material-role compatibility remain unverified. Backup weights, debug and block-entity payloads stay read-only. No blocks are resolved or generated.");drawBlockDefinitionNode(entry,new BlockDefinitions.Scope(context,0));ImGui.treePop(); }
        for (String field : InlineBlockData.readOnlyPaths(entry,context.weightedRoot())) ImGui.textWrapped(field+" (read-only, preserved)");
    }
    private void drawExactBlockAlias(String block,String key) {
        String target=BlockDefinitions.exactAlias(workspace,block);if (target==null) return;ImGui.pushID(key+".exactAlias");
        ImGui.textWrapped("Exact alias file present; contents, loader aliases and cycles unchecked: "+target);
        if (ImGui.smallButton("Open exact block alias")) { try { resourceNavigation.request(workspace,target); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID();
    }
    private <T> T drawDefinitionAuditControls(String key,AsyncDefinitionAudit<T> audit) {
        ImGui.pushID(key);
        try {
            if (ImGui.button("Scan / refresh audit")) audit.refresh(workspace);
            ImGui.sameLine();if (ImGui.button("Cancel / clear audit")) audit.clear();
            var state=audit.poll(workspace);
            if (state.phase()==AsyncDefinitionAudit.Phase.SCANNING) ImGui.textWrapped("Scanning applied definitions in the background... Editing and Save/Export remain available. Refresh replaces the request; Cancel discards it.");
            else if (state.phase()==AsyncDefinitionAudit.Phase.FAILED) ImGui.textWrapped("Definition audit failed: "+state.error()+". Refresh explicitly to retry.");
            else if (state.audit()==null) ImGui.textWrapped("No audit for the applied workspace state. Scan explicitly; applied edits and resource changes invalidate results. Selection, inspector drafts and Save/Export do not rescan.");
            ImGui.textWrapped("The immutable snapshot copies up to 512 sorted definitions / 8 MB, matching the audit's oversize skips and first byte-budget stop. Exact target presence is retained up to 100,000 paths / 16 million UTF-16 characters. Unrelated and binary payloads are never copied. Collapsing this section retains the request/result; Cancel clears it.");
            return state.audit();
        } finally { ImGui.popID(); }
    }
    private void drawBlockAliasAudit() {
        if (!ImGui.collapsingHeader("Exact block-alias references")) return;
        ImGui.textWrapped("Read-only literal file references in blocks/ definitions and their backup chains. The audit does not run the native loader, inspect material consumers, validate registry states or prove generation safety. Backup links are conditional; reported cycles are potential cycles, including unused definitions. Save/Export remain available.");
        ImGui.textWrapped("Limits: 512 sorted definitions, 1 MB per JSON file, 8 MB total, eight backup levels, 4096 references and 256 notices.");
        var audit=drawDefinitionAuditControls("blockAliasAudit",blockAliasAudits);if (audit==null) return;
        ImGui.textWrapped(audit.definitions()+" definitions; "+audit.inspected().size()+" inspected; "+audit.parsed()+" parsed; "+audit.edges().size()+" exact references; "+audit.cycles().size()+" potential cycle groups.");
        if (audit.incomplete()) ImGui.textWrapped("Reference coverage is incomplete; see notices below.");
        if (!audit.inspected().contains(selected)) ImGui.textWrapped("The selected definition was not inspected within these limits. Missing links or cycles may remain unknown.");
        var outgoing=audit.outgoing(selected);var incoming=audit.incoming(selected);if (outgoing.isEmpty()) ImGui.textWrapped("No recorded outgoing exact alias reference. Material keys without an exact local alias file remain unchecked.");
        int index=0;for (var edge : outgoing) { ImGui.pushID(index++);ImGui.textWrapped(edge.field()+" -> "+edge.target()+(edge.implicit() ? " (implicit native air default)" : "")+(edge.conditional() ? " (conditional backup)" : ""));if (ImGui.smallButton("Open referenced alias")) { try { resourceNavigation.request(workspace,edge.target());ImGui.popID();return; } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID(); }
        ImGui.textWrapped(incoming.size()+" recorded incoming exact references.");for (var edge : incoming) { ImGui.pushID(index++);ImGui.textWrapped(edge.source()+": "+edge.field()+(edge.conditional() ? " (conditional backup)" : ""));if (ImGui.smallButton("Open referring definition")) { try { resourceNavigation.request(workspace,edge.source());ImGui.popID();return; } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID(); }
        for (var cycle : audit.cycles(selected)) { ImGui.textWrapped("Potential cycle group (members, not traversal order): "+String.join(", ",cycle.members()));if (cycle.backupLinks()) ImGui.textWrapped("This group includes conditional backup links; the audit does not evaluate whether they execute."); }
        if (ImGui.treeNode("Alias audit notices")) { for (var notice : audit.notices()) ImGui.textWrapped(notice.path()+(notice.field().isEmpty() ? "" : ": "+notice.field())+" — "+notice.message());ImGui.treePop(); }
    }
    private void drawExpressionReferenceAudit() {
        if (!ImGui.collapsingHeader("Expression source references and declarations")) return;
        ImGui.textWrapped("Read-only literal links in expression variable/function styles and their fracture children. No formula is parsed; formula identifiers, other resource consumers, loader aliases, provider compatibility and runtime applicability are unchecked. Potential cycle groups include unused and overridden sources and do not prove execution. Save/Export remain available.");
        ImGui.textWrapped("Limits: 512 sorted expression definitions, 1 MB per JSON, 8 MB total, 64 entries per source list, eight style-fracture levels, 4096 references and 256 notices. Exact file presence does not validate target contents.");
        var audit=drawDefinitionAuditControls("expressionAudit",expressionAudits);if (audit==null) return;
        ImGui.textWrapped(audit.definitions()+" definitions; "+audit.inspected().size()+" inspected; "+audit.parsed()+" JSON objects parsed; "+audit.edges().size()+" literal links; "+audit.cycles().size()+" potential cycle groups.");
        if (audit.incomplete()) ImGui.textWrapped("Reference/declaration coverage is incomplete; see notices below.");
        if (ExpressionDefinitions.supports(selected) && !audit.inspected().contains(selected)) ImGui.textWrapped("The selected definition was not inspected within these limits; missing links and cycles may remain unknown.");
        var outgoing=audit.outgoing(selected);if (outgoing.isEmpty()) ImGui.textWrapped("No recorded outgoing literal source links in this file.");int index=0;
        for (var edge : outgoing) { ImGui.pushID(index++);ImGui.textWrapped(edge.field()+" -> "+edge.target()+(edge.present() ? " (exact file present)" : " (exact file absent)")+(edge.higherPriorityConfigured() ? " (higher-priority source field configured; runtime unchecked)" : ""));if (edge.present() && ImGui.smallButton("Open referenced source")) { try { resourceNavigation.request(workspace,edge.target());ImGui.popID();return; } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID(); }
        var incoming=audit.incoming(selected);ImGui.textWrapped(incoming.size()+" recorded incoming links from expression definitions.");
        for (var edge : incoming) { ImGui.pushID(index++);ImGui.textWrapped(edge.source()+": "+edge.field()+(edge.higherPriorityConfigured() ? " (higher-priority source field configured)" : ""));if (ImGui.smallButton("Open referring expression")) { try { resourceNavigation.request(workspace,edge.source());ImGui.popID();return; } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID(); }
        for (var cycle : audit.cycles(selected)) ImGui.textWrapped("Potential cycle group (members, not traversal order): "+String.join(", ",cycle));
        if (ImGui.treeNode("Expression reference/declaration notices")) { for (var notice : audit.notices()) ImGui.textWrapped(notice.path()+(notice.field().isEmpty() ? "" : ": "+notice.field())+" — "+notice.message());ImGui.treePop(); }
    }
    private void drawExpressionDefinition(LosslessJson root) {
        ImGui.textWrapped("Expression text and source data only. No parser, function, engine source or evaluation is run. Grammar, built-in identifiers, provider compatibility and recursion remain unchecked; previews remain disabled.");
        ImGui.textWrapped("Inherited x/y/z bindings: 1D = x/-1/-1; 2D = x/z/-1; 3D = x/y/z. Variables use engine value, then engine stream, then style, then static value. Functions use engine stream before style; stream functions use two arguments. A function without a provider is ignored natively.");
        ImGui.textWrapped("New names use ASCII identifiers, exclude x/y/z, and are unique across both source lists. Renaming or duplicating a source does not rewrite expression text. List edits preserve other resources and unknown fields. Removing a row/list/provider discards that selected subtree, including advanced data.");
        try { String current=ExpressionDefinitions.body(root),key=ExpressionDefinitions.bodyPrefix();ImGui.textWrapped(ExpressionDefinitions.help("root","expression"));if (current.isEmpty()) ImGui.textWrapped("Required expression text is absent/null or empty; enter text to repair it.");ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(ExpressionDefinitions.MAX_TEXT*4+1,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputTextMultiline("Expression text",input,-1,150);drafts.update(key,current,input.get());if (ImGui.button("Apply expression text")) commitRiverOverride(key,() -> workspace.replace(selected,ExpressionDefinitions.setBody(selected,workspace.read(selected),input.get()))); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored expression text has an unsupported type or NUL (read-only, preserved)."); }
        for (String list : ExpressionDefinitions.LISTS) if (ImGui.treeNode(list)) { drawExpressionList(root,list);ImGui.treePop(); }
        var readOnly=ExpressionDefinitions.readOnlyPaths(root);if (ImGui.collapsingHeader("Read-only expression fields ("+readOnly.size()+")")) for (String field : readOnly) ImGui.textWrapped(field+" (preserved)");
    }
    private void drawExpressionList(LosslessJson root,String list) {
        String prefix=ExpressionDefinitions.listPrefix(list);ImGui.pushID(prefix);ImGui.textWrapped(ExpressionDefinitions.help("root",list));
        try { var rows=ExpressionDefinitions.entries(root,list);if (rows!=null && rows.size()>ExpressionDefinitions.MAX_ENTRIES) throw new IllegalArgumentException("List limit");String key=prefix+"new.name";ImString input=values.computeIfAbsent(key,ignored -> new ImString("",4096));ImGui.inputText("Name for new source",input);drafts.update(key,"",input.get());ImGui.beginDisabled(input.get().isBlank() || rows!=null && rows.size()>=ExpressionDefinitions.MAX_ENTRIES);boolean add=ImGui.button("Add source with native defaults");ImGui.endDisabled();
            if (add) { try { commitProfileChange(prefix,ExpressionDefinitions.change(selected,workspace.read(selected),list,RiverProfiles.Action.ADD,-1,input.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (root.token(list)!=null && ImGui.button("Remove entire source list")) { try { commitProfileChange(prefix,ExpressionDefinitions.resetList(selected,workspace.read(selected),list),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (rows!=null) for (int index=0;index<rows.size();index++) { ImGui.pushID(index);if (ImGui.treeNode("Source "+index)) {
                var context=new ExpressionDefinitions.Context(list,index);String duplicateKey=context.prefix()+"duplicate.name";ImString duplicate=values.computeIfAbsent(duplicateKey,ignored -> new ImString("",4096));ImGui.inputText("Unique name for duplicate",duplicate);drafts.update(duplicateKey,"",duplicate.get());RiverProfiles.Action action=null;ImGui.beginDisabled(duplicate.get().isBlank() || rows.size()>=ExpressionDefinitions.MAX_ENTRIES);if (ImGui.button("Duplicate with new name")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove source")) action=RiverProfiles.Action.REMOVE;ImGui.beginDisabled(index==0);if (ImGui.button("Move up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==rows.size());if (ImGui.button("Move down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                if (action!=null) { try { commitProfileChange(prefix,ExpressionDefinitions.change(selected,workspace.read(selected),list,action,index,action==RiverProfiles.Action.DUPLICATE ? duplicate.get() : null),action==RiverProfiles.Action.DUPLICATE ? duplicateKey : null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                drawExpressionSource(rows.get(index),context);ImGui.treePop();
            }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored source list is null, mixed, a snippet or exceeds 64 entries (read-only, preserved)."); }ImGui.popID();
    }
    private void drawExpressionSource(LosslessJson row,ExpressionDefinitions.Context context) {
        try { String current=ExpressionDefinitions.name(row,context),stored=current==null ? "" : current,key=context.prefix()+"name";ImGui.textWrapped(ExpressionDefinitions.help(context.list(),"name"));ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(stored.isEmpty() ? "Missing required name" : "Name",input);drafts.update(key,stored,input.get());if (ImGui.button("Apply source name")) commitRiverOverride(key,() -> workspace.replace(selected,ExpressionDefinitions.setName(selected,workspace.read(selected),context,input.get()))); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored name has an unsupported type or NUL (read-only, preserved)."); }
        drawExpressionNumber(row,context,context.variable() ? "staticValue" : "args");
        if (ImGui.treeNode("Style provider")) { drawStyleFields(row,context.styleTarget());try { ExpressionDefinitions.style(row);if (row.token("styleValue")!=null && ImGui.button("Restore no style provider")) { try { commitProfileChange(context.prefix()+"styleValue.",ExpressionDefinitions.resetStyle(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Unsupported provider type is preserved."); }ImGui.treePop(); }
        drawExpressionEngineSource(row,context,"engineStreamValue");if (context.variable()) drawExpressionEngineSource(row,context,"engineValue");
        if (!context.variable() && (row.token("styleValue")==null || row.token("styleValue").equals("null")) && (row.token("engineStreamValue")==null || row.token("engineStreamValue").equals("null"))) ImGui.textWrapped("This function has no provider and is ignored natively until a style or engine stream is selected.");
        for (String field : row.fields()) if (!context.fields().contains(field)) ImGui.textWrapped(field+" (read-only, preserved)");
    }
    private void drawExpressionNumber(LosslessJson row,ExpressionDefinitions.Context context,String field) {
        ImGui.pushID(field);ImGui.textWrapped(ExpressionDefinitions.help(context.list(),field));
        ImGui.textWrapped(field.equals("args") ? "Exact signed 32-bit integer, minimum 2. An engine stream forces native arity 2 regardless of this stored value. Provider/large-arity compatibility is unchecked." : "Finite native double. This value is used only when all higher-priority sources are absent/null.");
        try { String current=ExpressionDefinitions.number(row,context,field),key=context.prefix()+field;ImGui.textWrapped(row.token(field)==null ? "Implicit native default: "+current : "Explicit stored value");ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(128,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply number")) commitRiverOverride(key,() -> workspace.replace(selected,ExpressionDefinitions.setNumber(selected,workspace.read(selected),context,field,input.get())));if (row.token(field)!=null && ImGui.button("Reset number to native default")) { try { commitProfileChange(key,ExpressionDefinitions.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } } }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored number has unsupported type/width (read-only, preserved)."); }ImGui.popID();
    }
    private void drawExpressionEngineSource(LosslessJson row,ExpressionDefinitions.Context context,String field) {
        ImGui.pushID(field);ImGui.textWrapped(ExpressionDefinitions.help(context.list(),field));ImGui.textWrapped("Requires a native engine context when evaluated. The editor does not initialize or query this source.");
        try { String current=ExpressionDefinitions.source(row,context,field);if (ImGui.beginCombo(field,current==null ? "Absent/null" : current.length()>128 ? "Stored source" : current)) { for (String choice : ExpressionDefinitions.choices(context,field)) { if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,ExpressionDefinitions.setSource(selected,workspace.read(selected),context,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(ExpressionDefinitions.choiceHelp(context,field,choice)); }ImGui.endCombo(); }
            if (current!=null && ImGui.button("Clear source (null)")) applyRoutingExtra(() -> workspace.replace(selected,ExpressionDefinitions.setSource(selected,workspace.read(selected),context,field,null)));if (row.token(field)!=null && ImGui.button("Reset source to absence")) { try { commitProfileChange(context.prefix()+field,ExpressionDefinitions.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored source has an unsupported type or NUL (read-only, preserved)."); }ImGui.popID();
    }
    private void drawBlockDefinition(LosslessJson document) {
        ImGui.textWrapped("Block aliases supply native block states. Caller properties override an alias's state; an invalid merged override keeps that alias state. Otherwise an unresolved block tries its backup, then air. Registry compatibility, alias cycles and the resulting block are unchecked. No blocks are resolved or generated.");
        ImGui.textWrapped("Edit at most eight backup levels and 64 properties per state map. Weights in alias files are ignored by native alias resolution and remain read-only, along with debug and block-entity data.");
        drawBlockDefinitionNode(document,new BlockDefinitions.Scope(null,0));
        var readOnly=BlockDefinitions.readOnlyPaths(document);if (ImGui.collapsingHeader("Read-only block fields ("+readOnly.size()+")")) for (String field : readOnly) ImGui.textWrapped(field+" (preserved)");
    }
    private void drawBlockDefinitionNode(LosslessJson node,BlockDefinitions.Scope scope) {
        int depth=scope.depth();String prefix=scope.prefix();ImGui.pushID(prefix);
        if (scope.standalone() || scope.depth()>0) { ImGui.textWrapped(BlockDefinitions.help("block"));
        try { String current=BlockDefinitions.block(node);if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL block");if (!BlockDefinitions.blockSyntax(current)) ImGui.textWrapped("External or unsupported block text is preserved; replace it with a vanilla base block or alias key.");String key=prefix+"block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.textWrapped(node.token("block")==null ? "Implicit block: "+current : "Stored block");ImGui.inputText("Block or alias",input);drafts.update(key,current,input.get());
            if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,BlockDefinitions.setBlock(selected,workspace.read(selected),scope,input.get())));
            if ((scope.standalone() || !scope.context().fluid()) && node.token("block")!=null && ImGui.button("Reset block to implicit air")) { try { commitProfileChange(key,BlockDefinitions.resetBlock(selected,workspace.read(selected),scope),null); } catch (Exception failure) { error=failure.getMessage(); } }
            String exact=BlockDefinitions.exactAlias(workspace,current);if (exact!=null) { ImGui.textWrapped("Exact alias file present; contents and cycles unchecked: "+exact);if (ImGui.button("Open exact alias file")) { try { resourceNavigation.request(workspace,exact); } catch (Exception failure) { error=failure.getMessage(); } } }
            if ((scope.standalone() || !scope.context().fluid()) && ImGui.beginCombo("Existing alias keys", "Choose an exact local alias")) { for (String alias : BlockDefinitions.aliases(workspace)) if (ImGui.selectable(alias,alias.equals(input.get()))) { input.set(alias);drafts.update(key,current,alias); }ImGui.endCombo(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block has an unsupported type or NUL text (read-only)."); } }
        if (ImGui.treeNode("State properties")) { drawBlockDefinitionStates(node,scope);ImGui.treePop(); }
        if (ImGui.treeNode("Backup block")) {
            ImGui.textWrapped(BlockDefinitions.help("backup"));if (!scope.standalone() && scope.context().fluid()) ImGui.textWrapped("New fluid backups use Minecraft water, lava or bubble_column. Fluid-role and registry behavior remain unverified.");ImGui.textWrapped("Removing a backup discards that entire subtree, including advanced data.");
            try { var child=BlockDefinitions.backup(node);if (child==null) {
                if (depth<BlockDefinitions.MAX_DEPTH) { String key=prefix+"backup.new.block";ImString input=values.computeIfAbsent(key,ignored -> new ImString(scope.seedBlock(),16384));ImGui.inputText("New backup block",input);drafts.update(key,scope.seedBlock(),input.get());ImGui.beginDisabled(!BlockDefinitions.blockSyntax(input.get()));boolean create=ImGui.button("Create explicit backup");ImGui.endDisabled();if (create) { try { commitProfileChange(prefix+"backup.",BlockDefinitions.createBackup(selected,workspace.read(selected),scope,input.get()),key); } catch (Exception failure) { error=failure.getMessage(); } } }else ImGui.textWrapped("Backup editor depth limit reached.");
            }else if (depth>=BlockDefinitions.MAX_DEPTH) ImGui.textWrapped("Deeper backup data is read-only and preserved.");else drawBlockDefinitionNode(child,scope.child());
            if (node.token("backup")!=null && ImGui.button("Remove entire backup")) { try { commitProfileChange(prefix+"backup.",BlockDefinitions.resetBackup(selected,workspace.read(selected),scope),null); } catch (Exception failure) { error=failure.getMessage(); } }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored backup is a snippet or non-object value (read-only, preserved)."); }
            ImGui.treePop();
        }ImGui.popID();
    }
    private void drawBlockDefinitionStates(LosslessJson node,BlockDefinitions.Scope scope) {
        String prefix=scope.prefix()+"data.";ImGui.pushID("state-map");ImGui.textWrapped(BlockDefinitions.help("data"));ImGui.textWrapped("Use lowercase property names with string, boolean or numeric values. Values and properties are not checked against the Minecraft registry. Nested, null and gameplay properties remain read-only. Removing the entire map discards its advanced data.");
        try { var map=BlockDefinitions.data(node);if (map==null) { if (ImGui.button("Create empty state map")) { try { commitProfileChange(prefix,BlockDefinitions.createData(selected,workspace.read(selected),scope),null); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID();return; }
            if (map.fields().size()>BlockDefinitions.MAX_STATES) throw new IllegalArgumentException("State map limit");
            if (ImGui.button("Remove entire state map")) { try { commitProfileChange(prefix,BlockDefinitions.resetData(selected,workspace.read(selected),scope),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            ImGui.beginDisabled(map.fields().size()>=BlockDefinitions.MAX_STATES);drawBlockDefinitionStateInput(scope,null,new BlockDefinitions.State("",BlockDefinitions.Kind.STRING,""),prefix+"new");ImGui.endDisabled();int index=0;
            for (String property : map.fields()) { String key=prefix+(index++);ImGui.pushID(key);try { drawBlockDefinitionStateInput(scope,property,BlockDefinitions.state(map,property),key); } catch (IllegalArgumentException unsupported) { ImGui.textWrapped(property+" (unsupported state property/type, read-only, preserved)"); }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored state map is null, non-object or above the 64-property limit (read-only, preserved)."); }ImGui.popID();
    }
    private void drawBlockDefinitionStateInput(BlockDefinitions.Scope scope,String previous,BlockDefinitions.State state,String key) {
        ImGui.pushID(key);ImString name=values.computeIfAbsent(key+".key",ignored -> new ImString(state.key(),4096)),kind=values.computeIfAbsent(key+".kind",ignored -> new ImString(state.kind().name(),32)),value=values.computeIfAbsent(key+".value",ignored -> new ImString(state.value(),Math.max(16384,state.value().getBytes(StandardCharsets.UTF_8).length+1)));
        ImGui.inputText("Property",name);if (ImGui.beginCombo("Value type",kind.get())) { for (var option : BlockDefinitions.Kind.values()) if (ImGui.selectable(option.name(),option.name().equals(kind.get()))) kind.set(option.name());ImGui.endCombo(); }ImGui.inputText("Value",value);
        drafts.update(key,state.key()+"\0"+state.kind()+"\0"+state.value(),name.get()+"\0"+kind.get()+"\0"+value.get());
        if (ImGui.button(previous==null ? "Add property" : "Apply property")) { try { commitProfileChange(scope.prefix()+"data.",BlockDefinitions.setState(selected,workspace.read(selected),scope,previous,name.get(),BlockDefinitions.Kind.valueOf(kind.get()),value.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
        if (previous!=null && ImGui.button("Remove property")) { try { commitProfileChange(scope.prefix()+"data.",BlockDefinitions.removeState(selected,workspace.read(selected),scope,previous),null); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID();
    }
    private void clearFallbackOverlapView() { fallbackOverlapPages.clear();fallbackReviewedAudit=null;fallbackGroup=null;fallbackGroupPage=0;fallbackDefinitionPage=0; }
    private void drawFallbackOverlapReview() {
        ImGui.textWrapped("Advisory normalized source identity only. Native target resolution and iteration-dependent winners are unchecked. Recorded overlaps may be incomplete; this never blocks Save/Export.");
        var audit=drawDefinitionAuditControls("fallbackOverlapAudit",blockFallbackAudits);
        if (audit==null) { clearFallbackOverlapView();return; }
        if (fallbackReviewedAudit!=audit) { clearFallbackOverlapView();fallbackReviewedAudit=audit; }
        ImGui.textWrapped(audit.inspectedDimensions()+" supported dimension resources / "+audit.readDimensions()+" JSON files read / "+audit.eligibleDimensions()+" eligible top-level dimensions; "+audit.bytes()+" JSON bytes; "+audit.skippedDimensions()+" files skipped; "+audit.definitions()+" recorded nonblank definitions; "+audit.overlaps().size()+" recorded overlap groups.");
        ImGui.textWrapped(audit.incomplete() ? "Incomplete audit: later/unsupported data may add overlaps or definitions to existing groups. Counts describe recorded data only." : "No unsupported data or audit limits encountered. Registry, targets and native winners remain unchecked.");
        if (ImGui.inputText("Overlap source, target or path contains",fallbackOverlapQuery)) { fallbackGroupPage=0;fallbackDefinitionPage=0; }
        if (ImGui.checkbox("Groups mentioning current dimension",fallbackCurrentDimensionOnly)) { fallbackCurrentDimensionOnly=!fallbackCurrentDimensionOnly;fallbackGroupPage=0;fallbackDefinitionPage=0; }
        if (ImGui.button("Clear overlap filters")) { fallbackOverlapQuery.set("");fallbackCurrentDimensionOnly=false;fallbackGroupPage=0;fallbackDefinitionPage=0; }
        try {
            var page=fallbackOverlapPages.page(audit,new FallbackOverlapReview.Query(fallbackOverlapQuery.get(),fallbackCurrentDimensionOnly ? selected : null),fallbackGroupPage);fallbackGroupPage=page.page();
            ImGui.textWrapped(page.matches()+" matching recorded groups; page "+(page.page()+1)+"; "+page.entries().size()+" shown.");
            ImGui.beginDisabled(!page.previous());if (ImGui.smallButton("Previous overlap groups")) fallbackGroupPage--;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(!page.next());if (ImGui.smallButton("Next overlap groups")) fallbackGroupPage++;ImGui.endDisabled();
            if (page.entries().stream().noneMatch(group -> group.normalizedSource().equals(fallbackGroup))) { fallbackGroup=page.entries().isEmpty() ? null : page.entries().getFirst().normalizedSource();fallbackDefinitionPage=0; }
            int index=0;for (var group : page.entries()) {
                ImGui.pushID(index++);if (ImGui.smallButton(group.normalizedSource().equals(fallbackGroup) ? "Selected overlap" : "Select overlap")) { fallbackGroup=group.normalizedSource();fallbackDefinitionPage=0; }ImGui.sameLine();ImGui.textUnformatted(InspectorText.label(group.normalizedSource(),256)+" ("+group.definitions().size()+" recorded definitions)");ImGui.popID();
            }
            var group=page.entries().stream().filter(item -> item.normalizedSource().equals(fallbackGroup)).findFirst().orElse(null);
            if (group!=null) {
                var definitions=FallbackOverlapReview.definitions(group,fallbackDefinitionPage);fallbackDefinitionPage=definitions.page();ImGui.separator();ImGui.textWrapped("Selected source: "+InspectorText.label(group.normalizedSource(),256)+"; definition page "+(definitions.page()+1)+"; "+definitions.entries().size()+" / "+definitions.matches()+" recorded definitions shown.");
                ImGui.textWrapped("All recorded definitions for this group are shown across pages, including other dimensions when the current-dimension filter is active. Their order does not predict a native winner.");
                ImGui.beginDisabled(!definitions.previous());if (ImGui.smallButton("Previous overlap definitions")) fallbackDefinitionPage--;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(!definitions.next());if (ImGui.smallButton("Next overlap definitions")) fallbackDefinitionPage++;ImGui.endDisabled();
                index=0;for (var item : definitions.entries()) { ImGui.pushID(index++);ImGui.textWrapped(InspectorText.label(item.path(),256)+" | "+InspectorText.label(item.source(),256)+" -> "+InspectorText.label(item.target(),512));if (ImGui.smallButton("Open fallback definition resource")) { try { resourceNavigation.request(workspace,item.path()); } catch (Exception failure) { error=failure.getMessage(); } }ImGui.popID(); }
            }
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
        if (ImGui.treeNode("Fallback overlap audit notices")) { for (var notice : audit.notices()) ImGui.textWrapped(InspectorText.label(notice.path(),256)+": "+notice.message());if (audit.omittedNotices()>0) ImGui.textWrapped(audit.omittedNotices()+" additional notices omitted.");ImGui.treePop(); }
        ImGui.textWrapped("Limits: 512 top-level dimension candidates, 1 MB/file, 8 MB JSON, 4,096 recorded definitions, 256 notices and 200 groups/definitions per page. Oversized files are skipped; scanning stops at the first byte or definition overflow. Labels escape controls and shorten long values; Open uses the draft guard to show original source data. No mappings are changed or resolved by this review.");
    }
    private void drawBlockFallbacks(LosslessJson document) {
        if (!ImGui.collapsingHeader("Missing-block fallbacks")) return;ImGui.pushID("blockFallbacks");
        ImGui.textWrapped(BlockFallbacks.help());
        ImGui.textWrapped("Native resolution tries the registry, then legacy renames, then one fallback target lookup. Fallback targets are not followed through other fallback rules. Source ids use base material identity. Registry readiness, block/state validity, aliases and effective content compatibility are not evaluated here.");
        ImGui.textWrapped("Iris collects fallback maps from every top-level dimension JSON in the pack. Duplicate and aliased sources can overlap across files or within a map. Native file/property iteration determines the winner; the editor does not predict it. Removing a mapping here leaves other dimension maps intact.");
        if (ImGui.treeNode("Pack-wide source overlaps")) { drawFallbackOverlapReview();ImGui.treePop(); }
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
        ImGui.textWrapped("Materials are selected uniformly by entry. Stored weights are ignored by the native deposit selector and remain read-only; structured states and backups are editable below. Block names require nonblank text; registry/state validity is unchecked.");
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
                drawExactBlockAlias(stored,key);
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block has an unsupported type (read-only)."); }
            RiverProfiles.Action action=null;if (ImGui.button("Duplicate block")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(palette.size()<=1);if (ImGui.button("Remove block")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Block up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==palette.size()-1);if (ImGui.button("Block down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            drawInlineBlockData(value,InlineBlockData.Context.deposit(context,row),palette.size());
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
    private void drawProceduralObjects(LosslessJson document) {
        if (!ImGui.collapsingHeader("Procedural object collections")) return;ImGui.pushID(ProceduralObjects.prefix());
        ImGui.textWrapped("Trees, ruins, formations, coral, fungi and crystals use native class defaults. Chance is checked per chunk before density attempts. Variants are pre-baked by Iris; this editor never generates or caches them. Shape controls use pinned native defaults and field bounds. Material roles and palettes are available below. Tree canopy, decorator, secondary-leaf and ruin-accent controls are available below. Source compatibility, name collisions, memory cost and actual placement are unverified. Unsupported previews remain disabled.");
        ImGui.textWrapped("Removing an entire collection or owner discards all data inside that selected subtree, including advanced fields.");
        try {
            var owner=ProceduralObjects.object(document);
            if (owner==null) { ImGui.textWrapped("Absent or null proceduralObjects has no configured procedural placements.");if (ImGui.button("Enable inline procedural collections")) { try { commitProfileChange(ProceduralObjects.prefix(),ProceduralObjects.enable(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            else {
                if (ImGui.button("Remove entire procedural owner")) { try { commitProfileChange(ProceduralObjects.prefix(),ProceduralObjects.reset(selected,workspace.read(selected)),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
                for (String kind : ProceduralObjects.LIST_FIELDS) if (ImGui.treeNode(kind)) { drawProceduralList(document,kind);ImGui.treePop(); }
                for (String path : ProceduralObjects.readOnlyPaths(document,ResourceTypes.base(selected))) ImGui.textWrapped(path+" (read-only, preserved)");
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored proceduralObjects is not an inline object (read-only, preserved)."); }ImGui.popID();
    }
    private void drawProceduralList(LosslessJson document,String kind) {
        String prefix=ProceduralObjects.listPrefix(kind);ImGui.pushID(prefix);ImGui.textWrapped(ProceduralObjects.help(kind,null));
        try {
            var rows=ProceduralObjects.entries(document,kind);if (rows!=null && rows.size()>ProceduralObjects.MAXIMUM_ENTRIES) { ImGui.textWrapped("Above the 64-entry editor limit (read-only).");ImGui.popID();return; }
            if (rows==null) ImGui.textWrapped("Absent/null list is empty. Add stores one empty object using native class defaults.");
            ImGui.beginDisabled(rows!=null && rows.size()>=ProceduralObjects.MAXIMUM_ENTRIES);boolean add=ImGui.button("Add placement with native defaults");ImGui.endDisabled();
            if (add) { try { commitProfileChange(prefix,ProceduralObjects.change(selected,workspace.read(selected),kind,RiverProfiles.Action.ADD,-1),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (ProceduralObjects.object(document).token(kind)!=null && ImGui.button("Remove entire configured collection")) { try { commitProfileChange(prefix,ProceduralObjects.resetList(selected,workspace.read(selected),kind),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (rows!=null) for (int index=0;index<rows.size();index++) { ImGui.pushID(index);if (ImGui.treeNode("Placement "+index)) {
                var context=new ProceduralObjects.Context(ResourceTypes.base(selected),kind,index);RiverProfiles.Action action=null;
                ImGui.beginDisabled(rows.size()>=ProceduralObjects.MAXIMUM_ENTRIES);if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;
                ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==rows.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                if (action!=null) { try { commitProfileChange(prefix,ProceduralObjects.change(selected,workspace.read(selected),kind,action,index),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                for (String field : ProceduralObjects.fields(kind)) drawProceduralValue(rows.get(index),context,field);drawProceduralShapes(rows.get(index),context);drawProceduralMaterials(rows.get(index),context);drawProceduralTransforms(rows.get(index),context);if (kind.equals("trees")) drawProceduralCanopies(rows.get(index),context);drawProceduralAccents(rows.get(index),context);ImGui.treePop();
            }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored list is mixed, a snippet or non-object data (read-only, preserved)."); }ImGui.popID();
    }
    private void drawProceduralAccents(LosslessJson entry,ProceduralObjects.Context owner) {
        var lists=ProceduralAccents.lists(owner.kind());if (lists.isEmpty() || !ImGui.treeNode("Decorators and accent foliage")) return;
        ImGui.textWrapped("Ordered native accent data only. A nonempty secondary-leaf palette overrides weighted foliage; otherwise a nonempty weighted list overrides the single secondary material. Weights are nonnegative signed 32-bit integers and Iris sums them in 64 bits; all-zero weighted foliage falls back to primary leaves. Decorator palettes override their single block when active. Material/target compatibility, orientation, chance sampling and generation cost are unverified; no accents are generated.");
        for (String list : lists) if (ImGui.treeNode(list)) { drawProceduralAccentList(entry,owner,list);ImGui.treePop(); }ImGui.treePop();
    }
    private void drawProceduralAccentList(LosslessJson entry,ProceduralObjects.Context owner,String list) {
        String prefix=ProceduralAccents.listPrefix(owner,list);ImGui.pushID(prefix);
        try { var rows=ProceduralAccents.entries(entry,list);if (rows!=null && rows.size()>64) throw new IllegalArgumentException("Accent list limit");
            ImGui.textWrapped("Absent/null lists are empty. Add requires a Minecraft or unnamespaced block/alias and leaves target/chance/other native defaults implicit. Removing a whole collection or entry discards its selected subtree, including advanced data.");
            String key=prefix+"new.block";ImString input=values.computeIfAbsent(key,ignored -> new ImString("",16384));ImGui.inputText("Block for new accent",input);drafts.update(key,"",input.get());ImGui.beginDisabled(!ProceduralMaterials.blockSyntax(input.get()) || rows!=null && rows.size()>=64);boolean add=ImGui.button("Add accent with native defaults");ImGui.endDisabled();
            if (add) { try { commitProfileChange(prefix,ProceduralAccents.change(selected,workspace.read(selected),owner,list,RiverProfiles.Action.ADD,-1,input.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (entry.token(list)!=null && ImGui.button("Remove entire accent collection")) { try { commitProfileChange(prefix,ProceduralAccents.resetList(selected,workspace.read(selected),owner,list),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();return; }
            if (rows!=null) for (int index=0;index<rows.size();index++) { ImGui.pushID(index);if (ImGui.treeNode("Accent "+index)) {
                RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=64);if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==rows.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                if (action!=null) { try { commitProfileChange(prefix,ProceduralAccents.change(selected,workspace.read(selected),owner,list,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();return; }
                var c=new ProceduralAccents.Context(owner,list,index);for (String field : ProceduralAccents.scalars(c)) drawProceduralAccentValue(rows.get(index),c,field);if (c.palette()) drawProceduralPalette(entry,c.paletteContext());for (String field : rows.get(index).fields()) if (!ProceduralAccents.fields(c).contains(field)) ImGui.textWrapped(field+" (read-only, preserved)");ImGui.treePop();
            }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored accents contain snippets/mixed non-object values or exceed the 64-entry limit (read-only, preserved)."); }ImGui.popID();
    }
    private void drawProceduralAccentValue(LosslessJson entry,ProceduralAccents.Context context,String field) {
        String key=context.prefix()+field;ImGui.pushID(field);ImGui.textWrapped(ProceduralAccents.help(context,field));
        try { String current=ProceduralAccents.value(entry,context,field),type=ProceduralAccents.type(context,field);ImGui.textWrapped(entry.token(field)==null ? "Implicit native default: "+current : "null".equals(entry.token(field)) ? "Explicit null block is preserved; replace/reset to use supported text." : "Explicit stored value");
            if (!ProceduralAccents.choices(context,field).isEmpty()) { if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored target" : current)) { for (String choice : ProceduralAccents.choices(context,field)) { if (ImGui.selectable(choice,choice.equals(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralAccents.set(selected,workspace.read(selected),context,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(ProceduralAccents.choiceHelp(context,field,choice)); }ImGui.endCombo(); } }
            else if (type.equals("boolean")) { if (ImGui.checkbox(field,Boolean.parseBoolean(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralAccents.set(selected,workspace.read(selected),context,field,Boolean.toString(!Boolean.parseBoolean(current)))));if (entry.token(field)==null && ImGui.button("Store native default")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralAccents.set(selected,workspace.read(selected),context,field,current))); }
            else { if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");if (type.equals("String")) { if (!current.isEmpty() && !ProceduralMaterials.blockSyntax(current)) ImGui.textWrapped("Unsupported external/invalid block text is preserved. Replace it with supported syntax or reset.");ImGui.textWrapped("Empty text restores the native no-single-block default; a palette can still resolve the decorator material."); }else ImGui.textWrapped(ProceduralAccents.constraints(context,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply accent value")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralAccents.set(selected,workspace.read(selected),context,field,input.get()))); }
            if (entry.token(field)!=null && ImGui.button("Reset field to native default")) { try { commitProfileChange(key,ProceduralAccents.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored accent value has unsupported type/width (read-only, preserved)."); }ImGui.popID();
    }
    private void drawProceduralCanopies(LosslessJson entry,ProceduralObjects.Context owner) {
        if (!ImGui.treeNode("Tree canopy")) return;
        ImGui.textWrapped("Pinned canopy and branch data only. Omitted canopy settings use native class defaults. Explicit nonempty layers replace profile-driven layers in their stored order. A branch object enables the native branch-driven crown; sub-branches also depend on branchDepth. Numeric controls enforce source annotations and widths; native function clamping, geometry and runtime cost are unverified. No foliage or branches are generated.");
        ImGui.textWrapped("Removing an owner or the entire layer list discards its selected subtree, including advanced data. Parent and sibling configuration is preserved.");
        drawCanopyOwner(entry,ProceduralCanopies.Context.object(owner,"canopy"));ImGui.treePop();
    }
    private void drawCanopyOwner(LosslessJson entry,ProceduralCanopies.Context context) {
        if (!ImGui.treeNode(context.scope())) return;ImGui.pushID(context.prefix());
        try { var object=ProceduralCanopies.object(entry,context);
            if (object==null) { ImGui.textWrapped("No inline owner is stored. Create writes an empty object with pinned native class defaults; existing null/default interactions are not evaluated.");if (ImGui.button("Create inline canopy settings")) { try { commitProfileChange(context.prefix(),ProceduralCanopies.enable(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }if (ProceduralCanopies.stored(entry,context) && ImGui.button("Reset stored null owner (remove field)")) { try { commitProfileChange(context.prefix(),ProceduralCanopies.reset(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } }
            else {
                if (ImGui.button("Remove entire selected canopy owner")) { try { commitProfileChange(context.prefix(),ProceduralCanopies.reset(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
                for (String field : ProceduralCanopies.scalars(context)) drawCanopyValue(object,context,field);
                if (context.scope().equals("canopy")) { drawCanopyLayers(entry,context.owner());drawCanopyOwner(entry,ProceduralCanopies.Context.object(context.owner(),"canopy.branches")); }
                if (context.scope().equals("canopy.branches")) drawCanopyOwner(entry,ProceduralCanopies.Context.object(context.owner(),"canopy.branches.subBranches"));
                for (String path : ProceduralCanopies.readOnlyPaths(entry,context)) ImGui.textWrapped(path+" (read-only, preserved)");
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored canopy owner is a snippet/non-object or lacks an inline ancestor (read-only, preserved)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawCanopyValue(LosslessJson object,ProceduralCanopies.Context context,String field) {
        String key=context.prefix()+field;ImGui.pushID(field);ImGui.textWrapped(ProceduralCanopies.help(context,field));
        try { String current=ProceduralCanopies.value(object,context,field),type=ProceduralCanopies.type(context,field);ImGui.textWrapped(object.token(field)==null ? "Implicit class default: "+current : "Explicit stored value");
            if (!ProceduralCanopies.choices(context,field).isEmpty()) { if (ImGui.beginCombo(field,current.length()>128 || current.indexOf(0)>=0 ? "Stored option" : current)) { for (String choice : ProceduralCanopies.choices(context,field)) { if (ImGui.selectable(choice,choice.equals(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralCanopies.set(selected,workspace.read(selected),context,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(ProceduralCanopies.choiceHelp(context,field,choice)); }ImGui.endCombo(); } }
            else if (type.equals("boolean")) { if (ImGui.checkbox(field,Boolean.parseBoolean(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralCanopies.set(selected,workspace.read(selected),context,field,Boolean.toString(!Boolean.parseBoolean(current)))));if (object.token(field)==null && ImGui.button("Store class default")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralCanopies.set(selected,workspace.read(selected),context,field,current))); }
            else { ImGui.textWrapped(ProceduralCanopies.constraints(context,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(256,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply canopy value")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralCanopies.set(selected,workspace.read(selected),context,field,input.get()))); }
            if (object.token(field)!=null && ImGui.button("Reset leaf (remove field)")) { try { commitProfileChange(key,ProceduralCanopies.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored canopy value has unsupported type/width (read-only, preserved)."); }ImGui.popID();
    }
    private void drawCanopyLayers(LosslessJson entry,ProceduralObjects.Context owner) {
        if (!ImGui.treeNode("Explicit crown layers")) return;String prefix=owner.prefix()+"canopy.layers.";ImGui.pushID(prefix);
        ImGui.textWrapped("Omitted, null or empty layers use native profile-driven layers. Add writes an empty native layer (offset 0, radius 2). Removing the final layer restores omission. Order is preserved and can affect the branch-driven crown.");
        try { var rows=ProceduralCanopies.layers(entry);if (rows!=null && rows.size()>64) throw new IllegalArgumentException("Layer limit");ImGui.beginDisabled(rows!=null && rows.size()>=64);boolean add=ImGui.button("Add crown layer with native defaults");ImGui.endDisabled();
            if (add) { try { commitProfileChange(prefix,ProceduralCanopies.changeLayers(selected,workspace.read(selected),owner,RiverProfiles.Action.ADD,-1),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            if (ProceduralCanopies.object(entry,ProceduralCanopies.Context.object(owner,"canopy")).token("layers")!=null && ImGui.button("Remove entire explicit layer list")) { try { commitProfileChange(prefix,ProceduralCanopies.resetLayers(selected,workspace.read(selected),owner),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            if (rows!=null) for (int index=0;index<rows.size();index++) { ImGui.pushID(index);if (ImGui.treeNode("Layer "+index)) {
                RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=64);if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==rows.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
                if (action!=null) { try { commitProfileChange(prefix,ProceduralCanopies.changeLayers(selected,workspace.read(selected),owner,action,index),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.treePop();ImGui.popID();ImGui.popID();ImGui.treePop();return; }
                var context=ProceduralCanopies.Context.layer(owner,index);for (String field : ProceduralCanopies.scalars(context)) drawCanopyValue(rows.get(index),context,field);for (String path : ProceduralCanopies.readOnlyPaths(entry,context)) ImGui.textWrapped(path+" (read-only, preserved)");ImGui.treePop();
            }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored layers are mixed/non-inline or above the 64-entry limit (read-only, preserved)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawProceduralMaterials(LosslessJson entry,ProceduralObjects.Context context) {
        if (!ImGui.treeNode("Materials and palettes")) return;String kind=context.kind();
        ImGui.textWrapped("Native material roles and fallback rules only. A nonempty palette takes precedence when its role is resolved; other role conditions may select different material data. Inline empty palette objects inherit STONE, STATIC style and zoom 5. Explicit empty entry lists retain the native single-material/role fallback. Registry, alias targets, block states, leaf/solid/waterlogging compatibility and sampling are unverified.");
        for (String field : ProceduralMaterials.materials(kind)) { String key=context.prefix()+"material#"+field;ImGui.pushID(key);ImGui.textWrapped(ProceduralMaterials.help(kind,field));
            try { String stored=ProceduralMaterials.material(entry,kind,field),current=stored==null ? "" : stored;if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
                ImGui.textWrapped(entry.token(field)==null ? "Implicit native default: "+ProceduralMaterials.defaultBlock(kind,field) : "null".equals(entry.token(field)) ? "Explicit null: native role fallback is not evaluated." : "Explicit stored material");
                if (!current.isEmpty() && !ProceduralMaterials.blockSyntax(current)) ImGui.textWrapped("Unsupported external/invalid block text is preserved. Replace it with supported syntax or reset the field.");
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply block/alias text")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralMaterials.setMaterial(selected,workspace.read(selected),context,field,input.get())));
                if (ProceduralMaterials.optional(kind,field) && ImGui.button("Clear optional material to none")) { try { commitProfileChange(key,ProceduralMaterials.clearMaterial(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
                if (entry.token(field)!=null && ImGui.button("Reset material to native default")) { try { commitProfileChange(key,ProceduralMaterials.resetMaterial(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored material is not nullable text or contains NUL (read-only, preserved)."); }ImGui.popID();
        }
        for (String field : ProceduralMaterials.palettes(kind)) drawProceduralPalette(entry,new ProceduralMaterials.Context(context,field));ImGui.treePop();
    }
    private void drawProceduralPalette(LosslessJson entry,ProceduralMaterials.Context context) {
        if (!ImGui.treeNode(context.field()+" palette")) return;ImGui.pushID(context.prefix());ImGui.textWrapped(context.stilt() ? "Omitted/null stilt palette extends the object's original layer under native stilt rules. Creating a STONE palette replaces that fallback; the placement mode is not changed." : context.accent() ? ProceduralAccents.help(context.accentContext(),"palette") : ProceduralMaterials.help(context.kind(),context.field()));
        ImGui.textWrapped("Create explicitly writes a STONE entry, leaving native STATIC style/zoom 5 implicit. Reset removes the whole palette, including advanced data, and restores the native omitted fallback. No registry, role or sampling checks run.");
        try { var palette=ProceduralMaterials.object(entry,context);if (palette==null) { if (ImGui.button("Create explicit STONE palette")) { try { commitProfileChange(context.prefix(),ProceduralMaterials.create(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } } }else { ProceduralMaterials.checkEntries(palette);drawGeneralBlockPalette(palette,context.target()); }
            var parent=ProceduralMaterials.parent(entry,context);if (parent.token(context.member())!=null && ImGui.button("Reset to omitted palette")) { try { commitProfileChange(context.prefix(),ProceduralMaterials.reset(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored palette or owner is non-inline, has mixed/null entries or exceeds the 64-entry limit (read-only, preserved)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawProceduralTransforms(LosslessJson entry,ProceduralObjects.Context context) {
        if (!ImGui.treeNode("Placement transforms and deformation")) return;
        ImGui.textWrapped("Controls author native placement settings only. Translation, rotation and clamps are class defaults when omitted; explicit null requires replacement or reset. Stilt/vacuum omission or null uses native mode-specific fallback. Mode effects, rotation/state compatibility, terrain geometry, material existence and runtime cost are unverified. No transformation or deformation runs.");
        ImGui.textWrapped("Removing a whole settings object discards its selected subtree, including advanced fields. Procedural stilt palettes are available below.");
        for (String scope : ProceduralTransforms.OBJECT_FIELDS) drawProceduralTransformOwner(entry,context,scope);ImGui.treePop();
    }
    private void drawProceduralTransformOwner(LosslessJson entry,ProceduralObjects.Context context,String scope) {
        if (!ImGui.treeNode(scope)) return;String prefix=ProceduralTransforms.prefix(context,scope);ImGui.pushID(prefix);
        try { var object=ProceduralTransforms.object(entry,scope);
            if (object==null) { ImGui.textWrapped(scope.equals("rotation.yAxis") ? "Omitted Y axis enables 90-degree steps. Create preserves that context; an explicit empty axis instead uses disabled/zero class defaults." : "No inline settings are stored. Create writes only this selected owner.");
                if (ImGui.button("Create/replace inline settings")) { try { commitProfileChange(prefix,ProceduralTransforms.enable(selected,workspace.read(selected),context,scope),null); } catch (Exception failure) { error=failure.getMessage(); } }
                if (ImGui.button("Reset selected owner (remove field)")) { try { commitProfileChange(prefix,ProceduralTransforms.reset(selected,workspace.read(selected),context,scope),null); } catch (Exception failure) { error=failure.getMessage(); } }
            } else {
                if (ImGui.button("Remove entire selected settings object")) { try { commitProfileChange(prefix,ProceduralTransforms.reset(selected,workspace.read(selected),context,scope),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
                if (scope.equals("clamp") || scope.startsWith("rotation.")) drawProceduralTransformRange(object,context,scope);
                for (String field : ProceduralTransforms.fields(scope)) if (!ProceduralTransforms.paired(scope,field)) drawProceduralTransformValue(object,context,scope,field);
                if (scope.equals("rotation")) for (String axis : ProceduralTransforms.AXES) drawProceduralTransformOwner(entry,context,"rotation."+axis);
                if (scope.equals("stiltSettings")) { drawProceduralExclusions(object,context);drawProceduralPalette(entry,new ProceduralMaterials.Context(context,"stiltSettings.palette")); }
                for (String path : ProceduralTransforms.readOnlyPaths(entry,scope,context)) ImGui.textWrapped(path+" (read-only, preserved)");
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored settings contain non-inline or unsupported data (read-only, preserved)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawProceduralTransformValue(LosslessJson object,ProceduralObjects.Context context,String scope,String field) {
        String key=ProceduralTransforms.prefix(context,scope)+field;ImGui.pushID(field);ImGui.textWrapped(ProceduralTransforms.help(scope,field));
        try { String current=ProceduralTransforms.value(object,scope,field);ImGui.textWrapped(object.token(field)==null ? "Implicit class default: "+current : "Explicit stored value");
            if (ProceduralTransforms.type(scope,field).equals("boolean")) { if (ImGui.checkbox(field,Boolean.parseBoolean(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralTransforms.set(selected,workspace.read(selected),context,scope,field,Boolean.toString(!Boolean.parseBoolean(current)))));if (object.token(field)==null && ImGui.button("Store class default")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralTransforms.set(selected,workspace.read(selected),context,scope,field,current))); }
            else { ImGui.textWrapped(ProceduralTransforms.constraints(scope,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(256,current.length()+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply scalar")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralTransforms.set(selected,workspace.read(selected),context,scope,field,input.get()))); }
            if (object.token(field)!=null && ImGui.button("Reset scalar (remove field)")) { try { commitProfileChange(key,ProceduralTransforms.resetLeaf(selected,workspace.read(selected),context,scope,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored scalar has unsupported type/width (read-only)."); }ImGui.popID();
    }
    private void drawProceduralTransformRange(LosslessJson object,ProceduralObjects.Context context,String scope) {
        String prefix=ProceduralTransforms.prefix(context,scope)+"range#.",key=prefix+"tuple";var pair=ProceduralTransforms.rangeFields(scope);ImGui.pushID(prefix);
        ImGui.textWrapped(scope.equals("clamp") ? "Placement height bounds apply to the whole object and require minimum <= maximum." : "Angles accept equal/reversed bounds. Zero/zero is unlimited, equal nonzero locks an angle; native zero intervals resolve to a one-degree step. Inline axes use disabled/zero class defaults.");
        try { String left=ProceduralTransforms.value(object,scope,pair.get(0)),right=ProceduralTransforms.value(object,scope,pair.get(1));ImGui.textWrapped(ProceduralTransforms.constraints(scope,pair.get(0)));ImGui.textWrapped(ProceduralTransforms.constraints(scope,pair.get(1)));ImString a=values.computeIfAbsent(prefix+pair.get(0),ignored -> new ImString(left,Math.max(256,left.length()+1))),b=values.computeIfAbsent(prefix+pair.get(1),ignored -> new ImString(right,Math.max(256,right.length()+1)));ImGui.inputText(pair.get(0),a);ImGui.inputText(pair.get(1),b);drafts.update(key,left+"|"+right,a.get()+"|"+b.get());
            if (ImGui.button("Apply both bounds")) { try { commitProfileChange(prefix,ProceduralTransforms.setRange(selected,workspace.read(selected),context,scope,a.get(),b.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            if ((object.token(pair.get(0))!=null || object.token(pair.get(1))!=null) && ImGui.button("Reset both bounds")) { try { commitProfileChange(prefix,ProceduralTransforms.resetRange(selected,workspace.read(selected),context,scope),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored bounds have unsupported types/widths (read-only)."); }ImGui.popID();
    }
    private void drawProceduralExclusions(LosslessJson object,ProceduralObjects.Context context) {
        if (!ImGui.treeNode("Stilt material exclusions")) return;String prefix=ProceduralTransforms.prefix(context,"stiltSettings")+"exclude#.";ImGui.pushID(prefix);ImGui.textWrapped(ProceduralTransforms.exclusionHelp());ImGui.textWrapped("Minecraft material-key syntax only; material existence is unverified. Original spelling/order is preserved. Existing external/invalid keys are preserved with a warning. Removing the final entry restores the absent empty list.");
        try { var rows=ProceduralTransforms.exclusions(object);if (rows!=null && rows.size()>64) throw new IllegalArgumentException("Oversized list");String key=prefix+"new";ImString next=values.computeIfAbsent(key,ignored -> new ImString("",16384));ImGui.inputText("New material key",next);drafts.update(key,"",next.get());ImGui.beginDisabled(next.get().isEmpty() || rows!=null && rows.size()>=64);boolean add=ImGui.button("Add exclusion");ImGui.endDisabled();
            if (add) { try { commitProfileChange(prefix,ProceduralTransforms.changeExclusion(selected,workspace.read(selected),context,RiverProfiles.Action.ADD,-1,next.get()),key); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            if (object.token("exclude")!=null && ImGui.button("Remove configured exclusion list")) { try { commitProfileChange(prefix,ProceduralTransforms.resetExclusions(selected,workspace.read(selected),context),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.treePop();return; }
            if (rows!=null) for (int index=0;index<rows.size();index++) { int item=index;String current=rows.get(index),draft=prefix+index;ImGui.pushID(index);if (!FloatingPlacementDeformation.materialSyntax(current)) ImGui.textWrapped("Unsupported external/invalid material key is preserved.");if (current.indexOf(0)>=0) ImGui.textWrapped("Stored key contains NUL (text read-only).");else { ImString input=values.computeIfAbsent(draft,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText("Material",input);drafts.update(draft,current,input.get());if (ImGui.button("Apply key")) commitRiverOverride(draft,() -> workspace.replace(selected,ProceduralTransforms.setExclusion(selected,workspace.read(selected),context,item,input.get()))); }
                RiverProfiles.Action action=null;ImGui.beginDisabled(rows.size()>=64);if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Remove")) action=RiverProfiles.Action.REMOVE;ImGui.beginDisabled(index==0);if (ImGui.button("Up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index+1==rows.size());if (ImGui.button("Down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();if (action!=null) { try { commitProfileChange(prefix,ProceduralTransforms.changeExclusion(selected,workspace.read(selected),context,action,index,null),null); } catch (Exception failure) { error=failure.getMessage(); }ImGui.popID();ImGui.popID();ImGui.treePop();return; }ImGui.popID(); }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored exclusion list is mixed/non-string or above the 64-entry limit (read-only)."); }ImGui.popID();ImGui.treePop();
    }
    private void drawProceduralShapes(LosslessJson entry,ProceduralObjects.Context context) {
        if (!ImGui.treeNode("Shape controls")) return;String kind=context.kind();
        ImGui.textWrapped("Native field bounds only. Iris normalizes the displayed range endpoints when generating; reversed authored ranges retain their order here. Shape interactions, native clamping, material roles, geometry size and baking cost are unverified. No geometry is generated.");
        for (var pair : ProceduralShapes.pairs(kind)) drawProceduralShapePair(entry,context,pair);
        for (String field : ProceduralShapes.fields(kind)) if (!ProceduralShapes.paired(kind,field)) drawProceduralValue(entry,context,field);ImGui.treePop();
    }
    private void drawProceduralShapePair(LosslessJson entry,ProceduralObjects.Context context,List<String> pair) {
        String kind=context.kind(),first=pair.get(0),second=pair.get(1),prefix=context.prefix()+"shapeRange#"+first+".",key=prefix+"tuple";ImGui.pushID(prefix);
        try {
            String left=ProceduralObjects.value(entry,kind,first),right=ProceduralObjects.value(entry,kind,second);ImGui.textWrapped(ProceduralObjects.help(kind,first));ImGui.textWrapped(ProceduralObjects.help(kind,second));
            ImGui.textWrapped(first+": "+ProceduralObjects.constraints(kind,first)+(entry.token(first)==null ? " (implicit "+left+")" : " (stored)"));
            ImGui.textWrapped(second+": "+ProceduralObjects.constraints(kind,second)+(entry.token(second)==null ? " (implicit "+right+")" : " (stored)"));
            ImString a=values.computeIfAbsent(prefix+first,ignored -> new ImString(left,Math.max(256,left.length()+1))),b=values.computeIfAbsent(prefix+second,ignored -> new ImString(right,Math.max(256,right.length()+1)));ImGui.inputText(first,a);ImGui.inputText(second,b);drafts.update(key,left+"|"+right,a.get()+"|"+b.get());
            if (ImGui.button("Apply both range endpoints")) { try { commitProfileChange(prefix,ProceduralShapes.setPair(selected,workspace.read(selected),context,first,a.get(),b.get()),key); } catch (Exception failure) { error=failure.getMessage(); } }
            if ((entry.token(first)!=null || entry.token(second)!=null) && ImGui.button("Reset both endpoints (remove fields)")) { try { commitProfileChange(prefix,ProceduralShapes.resetPair(selected,workspace.read(selected),context,first),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored shape range has an unsupported type/width (read-only, preserved)."); }ImGui.popID();
    }
    private void drawProceduralValue(LosslessJson entry,ProceduralObjects.Context context,String field) {
        String kind=context.kind(),key=context.prefix()+field;ImGui.pushID(field);ImGui.textWrapped(ProceduralObjects.help(kind,field));
        try {
            String current=ProceduralObjects.value(entry,kind,field),type=ProceduralObjects.type(kind,field);if (current.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
            ImGui.textWrapped(entry.token(field)==null ? "Implicit native default: "+current : "Explicit stored value");
            if (type.equals("boolean")) { if (ImGui.checkbox(field,Boolean.parseBoolean(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralObjects.set(selected,workspace.read(selected),context,field,Boolean.toString(!Boolean.parseBoolean(current)))));if (entry.token(field)==null && ImGui.button("Store native default")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralObjects.set(selected,workspace.read(selected),context,field,current))); }
            else if (!ProceduralObjects.choices(kind,field).isEmpty()) { if (ImGui.beginCombo(field,current.length()>128 ? "Stored option" : current)) { for (String choice : ProceduralObjects.choices(kind,field)) { if (ImGui.selectable(choice,choice.equals(current))) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralObjects.set(selected,workspace.read(selected),context,field,choice)));if (ImGui.isItemHovered()) ImGui.setTooltip(ProceduralObjects.choiceHelp(kind,field,choice)); }ImGui.endCombo(); } }
            else { if (!type.equals("String")) ImGui.textWrapped(ProceduralObjects.constraints(kind,field));ImString input=values.computeIfAbsent(key,ignored -> new ImString(current,Math.max(16384,current.getBytes(StandardCharsets.UTF_8).length+1)));ImGui.inputText(field,input);drafts.update(key,current,input.get());if (ImGui.button("Apply value")) commitRiverOverride(key,() -> workspace.replace(selected,ProceduralObjects.set(selected,workspace.read(selected),context,field,input.get()))); }
            if (entry.token(field)!=null && ImGui.button("Reset to native default (remove field)")) { try { commitProfileChange(key,ProceduralObjects.resetLeaf(selected,workspace.read(selected),context,field),null); } catch (Exception failure) { error=failure.getMessage(); } }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored value has an unsupported type/width (read-only, preserved)."); }ImGui.popID();
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
            if (ImGui.treeNode("Other placement data (read-only)")) { for (String field : FloatingObjectPlacements.readOnlyFields(placement,list)) drawReadOnlyField("objectPlacement",field);ImGui.treePop(); }
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
        if (context.structure()) ImGui.textWrapped("These rules apply to every stamped piece in this structure. The editor supports up to 64 ordered rules. Native matching, chance, state merging and palette sampling remain unevaluated. Loot data and source object files are preserved.");
        if (context.fixed()!=null) ImGui.textWrapped("Fixed object rules use this entry's seed. Replacements run on the scaled object before block-state rotation. Native loading requires nonempty find and replacement palettes; empty lists can be repaired here. No object transformation or content compatibility is evaluated.");
        ImGui.textWrapped("Rules run in stored order using native chance and state/material matching. Source .iob bytes remain unchanged. Registry, aliases, block states and effective replacements are not resolved here.");List<LosslessJson> rules;
        try { rules=FloatingObjectReplacements.rules(placement,context); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored rules contain snippets, unsupported values or exceed this owner's editor limit (read-only).");ImGui.treePop();return; }
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
        ImGui.textWrapped("Find blocks are unweighted native block-data matches. Exact matching uses state properties; non-exact matching uses the base block. In content-gate declared keys, properties already in block text take precedence over the separate data map. Registry, alias and placeholder behavior remains unverified. Structured states and backups are editable below; ignored weights and advanced payloads stay read-only.");List<LosslessJson> entries;
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
                drawExactBlockAlias(stored,key);
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored find block has an unsupported type (read-only)."); }
            RiverProfiles.Action action=null;
            if (ImGui.button("Duplicate find")) action=RiverProfiles.Action.DUPLICATE;
            ImGui.sameLine();ImGui.beginDisabled(entries.size()<=1);if (ImGui.button("Remove find")) action=RiverProfiles.Action.REMOVE;ImGui.endDisabled();
            ImGui.beginDisabled(index==0);if (ImGui.button("Find up")) action=RiverProfiles.Action.UP;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==entries.size()-1);if (ImGui.button("Find down")) action=RiverProfiles.Action.DOWN;ImGui.endDisabled();
            drawInlineBlockData(entry,InlineBlockData.Context.find(context,ruleIndex,findIndex),entries.size());
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
        ImGui.textWrapped(target.procedural() ? "Procedural palettes use Minecraft or unnamespaced block/alias syntax. Registry, state and block-role validation remain pending; advanced block data is preserved." : "General palettes use weights 1-"+DimensionPalettes.maximumWeight()+". Block names are checked for nonblank text only; registry/state/block-role validation remains pending. Structured states and backups are editable below; debug and block-entity payloads remain read-only.");
        if (ImGui.treeNode("Palette noise")) {
            String token=palette.token("zoom"),key=target.noisePrefix()+"zoom";
            ImGui.textWrapped(target.zoomConstraints());
            if (token==null) {
                if (ImGui.button("Enable palette zoom ("+target.zoomDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,target.enableZoom(selected,workspace.read(selected))));
            } else if (!LosslessJson.numericToken(token) || target.nativeStructure() && !NativeStructurePalettes.supportedZoom(palette) || target.procedural() && !ProceduralMaterials.supportedZoom(palette)) ImGui.textWrapped("Stored palette zoom has an unsupported type (read-only).");
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
        ImGui.beginDisabled((target.nativeStructure() || target.procedural()) && entries.size()>=NativeStructurePalettes.MAXIMUM_ENTRIES);boolean addBlock=ImGui.button("Add block");ImGui.endDisabled();
        if (addBlock) { changeGeneralBlockPalette(target,RiverProfiles.Action.ADD,-1,newBlock.get(),newKey);return; }
        if (entries.isEmpty()) ImGui.textWrapped(target.procedural() ? "Empty entries retain native single-material/role fallback; add a block to activate the palette." : target.ore() ? "Empty palette: this ore generator produces no blocks." : target.slabWall() ? "Empty palette: no material is selected. Add a block to author a material palette." : "This list is empty. Add a block to repair the required list.");
        for (int index=0;index<entries.size();index++) {
            if (!ImGui.treeNode("Block "+index)) continue;
            int entryIndex=index;var entry=entries.get(index);RiverProfiles.Action action=null;
            ImGui.beginDisabled((target.nativeStructure() || target.procedural()) && entries.size()>=NativeStructurePalettes.MAXIMUM_ENTRIES);if (ImGui.button("Duplicate")) action=RiverProfiles.Action.DUPLICATE;ImGui.endDisabled();
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
                if (stored.indexOf(0)>=0) throw new IllegalArgumentException("NUL text");
                if (target.procedural() && !ProceduralMaterials.blockSyntax(stored)) ImGui.textWrapped("Unsupported external/invalid block text is preserved; replace with supported syntax to edit it.");
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(4096,stored.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText(entry.token("block")==null ? "Required block (missing)" : "Block",input);drafts.update(key,stored,input.get());
                if (ImGui.button("Apply block")) commitRiverOverride(key,() -> workspace.replace(selected,target.setBlock(selected,workspace.read(selected),entryIndex,input.get())));
                drawExactBlockAlias(stored,key);
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored block is non-string or contains NUL (read-only)."); }
            String token=entry.token("weight"),key=target.listPrefix()+index+".weight";
            if (token==null) {
                if (ImGui.button("Enable weight ("+DimensionPalettes.weightDefault()+")")) applyRoutingExtra(() -> workspace.replace(selected,target.enableWeight(selected,workspace.read(selected),entryIndex)));
            } else if (!LosslessJson.numericToken(token) || target.nativeStructure() && !NativeStructurePalettes.supportedWeight(entry) || target.procedural() && !ProceduralMaterials.supportedWeight(entry)) ImGui.textWrapped("Stored weight has an unsupported type (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));
                ImGui.inputText("Weight (1-"+DimensionPalettes.maximumWeight()+")",input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply weight")) commitRiverOverride(key,() -> workspace.replace(selected,target.setWeight(selected,workspace.read(selected),entryIndex,input.get())));
            }
            drawInlineBlockData(entry,new PaletteBlockData.Context(target.styleTarget(),entryIndex),entries.size());
            ImGui.treePop();
        }
    }
    private void changeGeneralBlockPalette(GeneralBlockPalettes.Target target,RiverProfiles.Action action,int index,String value,String appliedKey) {
        try { commitProfileChange(target.listPrefix(),target.change(selected,workspace.read(selected),action,index,value),appliedKey); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDimensionStack(LosslessJson document) {
        if (!ImGui.collapsingHeader("Dimension stack and upper terrain")) return;
        ImGui.pushID("dimensionStack");var choices=DimensionStacks.choices(workspace);
        ImGui.textWrapped("Dimension keys are exact files in this pack. Target contents, loader aliases, height/layout compatibility and native generation are unvalidated. These controls do not sample or preview terrain.");
        ImGui.textWrapped(DimensionStacks.help("upperDimension"));
        try {
            String token=document.token("upperDimension"),current=token==null || token.equals("null") ? "none" : document.stringToken("upperDimension");
            if (DimensionStacks.upperActive(document) && !choices.contains(current)) ImGui.textWrapped("Stored upper dimension is missing from the exact in-pack catalog; it is preserved until replaced or cleared.");
            if (ImGui.beginCombo("Upper dimension",DimensionStacks.upperActive(document) ? current.length()>128 ? "Stored upper dimension" : current : "None")) {
                if (ImGui.selectable("None (native disable)",!DimensionStacks.upperActive(document))) applyRoutingExtra(() -> workspace.replace(selected,DimensionStacks.setUpper(selected,workspace.read(selected),null,workspace)));
                ImGui.beginDisabled(document.token("dimensionStack")!=null);
                for (String choice : choices) if (!choice.equalsIgnoreCase("none") && ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionStacks.setUpper(selected,workspace.read(selected),choice,workspace)));
                ImGui.endDisabled();ImGui.endCombo();
            }
        } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored upperDimension has an unsupported type (read-only)."); }
        ImGui.textWrapped("Upper terrain and dimensionStack are mutually exclusive. Remove the stack member before selecting an active upper dimension. Upper gap/carving/object settings remain separate controls.");
        for (String finding : DimensionStacks.findings(selected,document,workspace)) ImGui.textWrapped("Applied stack check: "+finding+" (does not block Save/Export)");
        LosslessJson stack;
        try { stack=DimensionStacks.object(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored dimensionStack is null, a snippet or unsupported value (read-only).");ImGui.popID();return; }
        ImGui.textWrapped(DimensionStacks.help("dimensionStack"));
        if (stack==null) {
            String key=DimensionStacks.prefix()+"new.top";ImString top=values.computeIfAbsent(key,ignored -> new ImString("",4096));
            if (ImGui.beginCombo("Initial top dimension",top.get().isEmpty() ? "Choose a dimension" : top.get())) {
                for (String choice : choices) if (!choice.equals(DimensionStacks.host(selected)) && ImGui.selectable(choice,choice.equals(top.get()))) { top.set(choice);drafts.update(key,"",choice); }
                ImGui.endCombo();
            }
            ImGui.textWrapped("Create writes [chosen top, host] in top-to-bottom order. Spacer stays at 32 and blending stays absent until explicitly enabled.");
            ImGui.beginDisabled(top.get().isEmpty());boolean create=ImGui.button("Create dimension stack");ImGui.endDisabled();
            if (create) { try { commitProfileChange(DimensionStacks.prefix(),DimensionStacks.create(selected,workspace.read(selected),top.get(),workspace),key); } catch (Exception failure) { error=failure.getMessage(); } }
            ImGui.popID();return;
        }
        drawStackLayers(stack,choices);drawStackScalar(stack,"spacer");
        if (ImGui.treeNode("Boundary blend")) {
            ImGui.textWrapped(DimensionStacks.help("blend"));ImGui.textWrapped("Absent blend or amplitude 0 gives a constant nominal gap. Positive amplitude samples separate bounded noise at each seam; this editor does not predict resulting heights or clipping.");
            try {
                var blend=DimensionStacks.blend(stack);
                if (blend==null) {
                    if (ImGui.button("Enable boundary blend (amplitude 8, SIMPLEX style)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionStacks.enableBlend(selected,workspace.read(selected))));
                } else {
                    drawStackScalar(blend,"amplitude");drawStyleFields(blend,DimensionStacks.styleTarget());
                    ImGui.textWrapped("Remove discards this entire blend object, including style/fracture and advanced data, and restores absence.");
                    if (ImGui.button("Remove boundary blend")) removeStackConfiguration(true);
                }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored blend is null, a snippet or unsupported value (read-only)."); }
            ImGui.treePop();
        }
        for (String path : DimensionStacks.readOnlyPaths(document)) ImGui.textWrapped(path+" (read-only)");
        ImGui.textWrapped("Remove stack discards all layer/blend and advanced stack data and restores field absence. This does not change the selected upperDimension reference.");
        if (ImGui.button("Remove dimension stack")) removeStackConfiguration(false);
        ImGui.popID();
    }
    private void drawStackScalar(LosslessJson object,String field) {
        ImGui.pushID("stack."+field);ImGui.textWrapped(DimensionStacks.help(field));ImGui.textWrapped("Exact integer 0..256.");String token=object.token(field),key=(field.equals("spacer") ? DimensionStacks.prefix() : DimensionStacks.blendPrefix())+field;
        if (token==null) {
            if (ImGui.button("Enable "+field+" ("+DimensionStacks.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,DimensionStacks.enableScalar(selected,workspace.read(selected),field)));
        } else if (!DimensionStacks.numericSupported(object,field)) ImGui.textWrapped("Stored value has an unsupported type or integer width (read-only).");
        else {
            ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
            if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionStacks.setScalar(selected,workspace.read(selected),field,input.get())));
        }
        ImGui.popID();
    }
    private void stageStackLayers(String key,String stored,List<String> rows) {
        String token=DimensionStacks.draftToken(rows);values.put(key,new ImString(token,Math.max(128,token.getBytes(StandardCharsets.UTF_8).length+1)));drafts.update(key,stored,token);
    }
    private void drawStackLayers(LosslessJson stack,List<String> choices) {
        ImGui.textWrapped(DimensionStacks.help("dimensions"));ImGui.textWrapped("Stage changes, then Apply the whole order. The host must occur exactly once at the bottom; other dimensions may repeat. Editor limit: 64 layers, separate from native schema limits. Unchanged string tokens retain their original escapes.");
        List<String> storedRows;
        try { storedRows=DimensionStacks.layers(stack); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored dimensions contains non-string entries or an unsupported type (read-only).");return; }
        if (storedRows!=null && storedRows.size()>DimensionStacks.MAXIMUM_LAYERS) { ImGui.textWrapped("Stored layer list exceeds the editor limit (read-only).");return; }
        String stored=stack.token("dimensions")==null ? "[]" : stack.token("dimensions"),key=DimensionStacks.prefix()+"dimensions";
        ImString input=values.computeIfAbsent(key,ignored -> new ImString(stored,Math.max(128,stored.getBytes(StandardCharsets.UTF_8).length+1)));var rows=new java.util.ArrayList<>(DimensionStacks.draftLayers(input.get()));drafts.update(key,stored,input.get());
        ImGui.beginDisabled(rows.size()>=DimensionStacks.MAXIMUM_LAYERS);boolean add=ImGui.button("Stage another layer (before bottom)");ImGui.endDisabled();
        if (add) { rows.add(Math.max(0,rows.size()-1),"");stageStackLayers(key,stored,rows);return; }
        for (int index=0;index<rows.size();index++) {
            ImGui.pushID("stackLayer"+index);String current=rows.get(index),replacement=null;ImGui.text("Layer "+index+(index==rows.size()-1 ? " (bottom)" : ""));
            if (ImGui.beginCombo("Dimension",current.isEmpty() ? "Choose a dimension" : current.length()>128 ? "Stored dimension key" : current)) {
                for (String choice : choices) if (ImGui.selectable(choice,choice.equals(current))) replacement=choice;
                ImGui.endCombo();
            }
            if (replacement!=null) { rows.set(index,replacement);stageStackLayers(key,stored,rows);ImGui.popID();return; }
            if (!current.isEmpty() && !choices.contains(current)) ImGui.textWrapped("Stored key is missing; it stays preserved until the whole draft order is successfully applied.");
            if (ImGui.button("Stage removal")) { rows.remove(index);stageStackLayers(key,stored,rows);ImGui.popID();return; }
            ImGui.beginDisabled(index==0);boolean up=ImGui.button("Stage move up");ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(index==rows.size()-1);boolean down=ImGui.button("Stage move down");ImGui.endDisabled();
            if (up || down) { java.util.Collections.swap(rows,index,up ? index-1 : index+1);stageStackLayers(key,stored,rows);ImGui.popID();return; }
            ImGui.popID();
        }
        if (ImGui.button("Apply whole layer order")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionStacks.replaceLayers(selected,workspace.read(selected),DimensionStacks.draftLayers(input.get()),workspace)));
        ImGui.sameLine();if (ImGui.button("Discard layer order draft")) { drafts.applied(key);values.remove(key); }
    }
    private void removeStackConfiguration(boolean blend) {
        String prefix=blend ? DimensionStacks.blendPrefix() : DimensionStacks.prefix();
        try {
            if (drafts.transitionUnder(prefix,() -> !drafts.anyUnder(prefix) || JOptionPane.showConfirmDialog(null,"Discard unapplied text in this "+(blend ? "boundary blend" : "dimension stack")+" and remove its configuration?","Unapplied dimension-stack text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION,() -> workspace.replace(selected,blend ? DimensionStacks.disableBlend(selected,workspace.read(selected)) : DimensionStacks.disable(selected,workspace.read(selected))))) { values.keySet().removeIf(key -> key.startsWith(prefix));error=null; }
        } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawDimensionType(LosslessJson document) {
        if (!ImGui.collapsingHeader("Environment and dimension type")) return;
        ImGui.pushID("dimensionType");
        ImGui.textWrapped("These settings author the generated dimension type. Inherit follows the base environment; effective values depend on the target platform/version. Registry, generated datapack and existing-world compatibility are unvalidated. Iris can refuse hotloads that change environment or the generated type; close and reopen Studio after such changes.");
        ImGui.textWrapped("The borrowed dimension type is authored in the dimension integration controls. On supported modded platforms it overrides dimensionOptions and fullbright; those values remain preserved here. No live world is modified.");
        ImGui.textWrapped(DimensionTypeFields.help("environment"));String environmentToken=document.token("environment");
        if (environmentToken==null) {
            if (ImGui.button("Enable environment (NORMAL)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.enableRoot(selected,workspace.read(selected),"environment")));
        } else {
            try {
                String current=document.stringToken("environment");if (!DimensionTypeFields.environments().contains(current)) ImGui.textWrapped("Stored environment is unknown; it is preserved until you select a pinned choice.");
                if (ImGui.beginCombo("Environment",current)) {
                    for (String choice : DimensionTypeFields.environments()) if (ImGui.selectable(choice,choice.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.setEnvironment(selected,workspace.read(selected),choice)));
                    ImGui.endCombo();
                }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored environment has an unsupported type (read-only)."); }
        }
        ImGui.textWrapped(DimensionTypeFields.help("fullbright"));String bright=document.token("fullbright");
        if (bright==null) {
            if (ImGui.button("Enable fullbright (false)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.enableRoot(selected,workspace.read(selected),"fullbright")));
        } else if (!bright.equals("true") && !bright.equals("false")) ImGui.textWrapped("Stored fullbright has an unsupported type (read-only).");
        else if (ImGui.checkbox("Fullbright",bright.equals("true"))) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.setFullbright(selected,workspace.read(selected),!bright.equals("true"))));
        ImGui.textWrapped("Fullbright sets the generated type's ambient light to 1 without rewriting the stored ambientLight override. CUSTOM uses the native overworld base for type options.");
        LosslessJson options;
        try { options=DimensionTypeFields.object(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored dimensionOptions is null, a snippet or unsupported value (read-only).");ImGui.popID();return; }
        if (options==null) {
            if (ImGui.button("Create dimension options (inherit all)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.enable(selected,workspace.read(selected))));
            ImGui.popID();return;
        }
        for (String field : DimensionTypeFields.STATES) {
            ImGui.pushID(field);ImGui.textWrapped(DimensionTypeFields.help(field));String token=options.token(field);
            if (token==null) {
                if (ImGui.button("Enable "+field+" (inherit)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.enableField(selected,workspace.read(selected),field)));
            } else if (!DimensionTypeFields.supported(options,field)) ImGui.textWrapped("Stored state is not a string (read-only).");
            else {
                String current=options.stringToken(field);if (!DimensionTypeFields.stateChoices().contains(current)) ImGui.textWrapped("Unknown stored state is preserved until a pinned choice is selected.");
                if (ImGui.beginCombo(field,current.equals("DEFAULT") ? "Inherit base" : current)) {
                    for (String state : DimensionTypeFields.stateChoices()) if (ImGui.selectable(state.equals("DEFAULT") ? "Inherit base (DEFAULT)" : state,state.equals(current))) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.setState(selected,workspace.read(selected),field,state)));
                    ImGui.endCombo();
                }
            }
            ImGui.popID();
        }
        for (String field : DimensionTypeFields.NUMBERS) {
            ImGui.pushID(field);ImGui.textWrapped(DimensionTypeFields.help(field));ImGui.textWrapped(DimensionTypeFields.constraints(field));String token=options.token(field),key=DimensionTypeFields.prefix()+field;
            if (DimensionTypeFields.nullable(field)) ImGui.textWrapped(field.equals("fixedTime") ? "Null enables the normal time cycle. -1 inherits the base time behavior; these are distinct states." : "Null disables clouds. -1 inherits the base cloud behavior and cannot set an explicit cloud plane at Y -1.");
            if (token==null) {
                if (ImGui.button("Enable "+field+" (inherit -1)")) applyRoutingExtra(() -> workspace.replace(selected,DimensionTypeFields.enableField(selected,workspace.read(selected),field)));
            } else if (!DimensionTypeFields.supported(options,field)) ImGui.textWrapped("Stored value has an unsupported type or integer width (read-only).");
            else {
                if (token.equals("null")) ImGui.textWrapped(field.equals("fixedTime") ? "Current: normal time cycle" : "Current: clouds disabled");
                else {
                    ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
                    if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionTypeFields.setNumber(selected,workspace.read(selected),field,input.get())));
                }
                if (ImGui.button("Inherit base (-1)")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionTypeFields.setNumber(selected,workspace.read(selected),field,"-1")));
                if (DimensionTypeFields.nullable(field) && !token.equals("null")) {
                    if (ImGui.button(field.equals("fixedTime") ? "Normal time cycle (null)" : "Disable clouds (null)")) commitRiverOverride(key,() -> workspace.replace(selected,DimensionTypeFields.setNull(selected,workspace.read(selected),field)));
                }
            }
            ImGui.popID();
        }
        for (String path : DimensionTypeFields.readOnlyPaths(document)) ImGui.textWrapped(path+" (read-only)");
        ImGui.popID();
    }
    private void drawWorldBoundary(LosslessJson document) {
        if (!ImGui.collapsingHeader("World border")) return;
        ImGui.pushID("worldBoundary");ImGui.textWrapped(WorldBoundaryFields.help("worldBoundary"));
        ImGui.textWrapped("Absent or null leaves the native border unchanged; clearing this configuration does not reset an existing live border. This editor edits pack data only. Diameter is the full border width, not a radius.");
        LosslessJson boundary;
        try { boundary=WorldBoundaryFields.object(document); }
        catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored world border is a snippet or unsupported value (read-only).");ImGui.popID();return; }
        if (boundary==null) {
            if (ImGui.button("Enable world border (Iris defaults)")) applyRoutingExtra(() -> workspace.replace(selected,WorldBoundaryFields.enable(selected,workspace.read(selected))));
            ImGui.popID();return;
        }
        if (ImGui.treeNode("Center coordinates")) {
            ImGui.textWrapped("Apply X and Z together. An absent center uses (0, 0); null or snippet centers remain read-only. Center bounds do not clamp the full border extent.");
            try {
                var center=WorldBoundaryFields.center(boundary);
                if (center!=null && (!WorldBoundaryFields.supported(center,"x") || !WorldBoundaryFields.supported(center,"z"))) ImGui.textWrapped("Stored center coordinates have unsupported types (read-only).");
                else {
                    String x=center==null || center.token("x")==null ? WorldBoundaryFields.defaultToken("center.x") : center.token("x"),z=center==null || center.token("z")==null ? WorldBoundaryFields.defaultToken("center.z") : center.token("z");
                    String xKey=WorldBoundaryFields.prefix()+"center.x",zKey=WorldBoundaryFields.prefix()+"center.z";
                    ImString xInput=values.computeIfAbsent(xKey,ignored -> new ImString(x,Math.max(128,x.length()+1))),zInput=values.computeIfAbsent(zKey,ignored -> new ImString(z,Math.max(128,z.length()+1)));
                    ImGui.textWrapped(WorldBoundaryFields.constraints("center.x"));ImGui.inputText("Center X",xInput);drafts.update(xKey,x,xInput.get());ImGui.inputText("Center Z",zInput);drafts.update(zKey,z,zInput.get());
                    if (ImGui.button(center==null ? "Create center pair" : "Apply center pair")) {
                        try { workspace.replace(selected,WorldBoundaryFields.setCenter(selected,workspace.read(selected),xInput.get(),zInput.get()));drafts.applied(xKey);drafts.applied(zKey);values.remove(xKey);values.remove(zKey);error=null; }
                        catch (Exception failure) { error=failure.getMessage(); }
                    }
                }
            } catch (IllegalArgumentException unsupported) { ImGui.textWrapped("Stored center is null, a snippet or unsupported value (read-only)."); }
            ImGui.treePop();
        }
        for (String field : WorldBoundaryFields.SCALARS) {
            ImGui.pushID(field);ImGui.textWrapped(WorldBoundaryFields.help(field));ImGui.textWrapped(WorldBoundaryFields.constraints(field));String token=boundary.token(field),key=WorldBoundaryFields.prefix()+field;
            if (token==null) {
                if (ImGui.button("Enable "+field+" ("+WorldBoundaryFields.defaultToken(field)+")")) applyRoutingExtra(() -> workspace.replace(selected,WorldBoundaryFields.enableScalar(selected,workspace.read(selected),field)));
            } else if (!WorldBoundaryFields.supported(boundary,field)) ImGui.textWrapped("Stored value has an unsupported type or integer width (read-only).");
            else {
                ImString input=values.computeIfAbsent(key,ignored -> new ImString(token,Math.max(128,token.length()+1)));ImGui.inputText(field,input);drafts.update(key,token,input.get());
                if (ImGui.button("Apply")) commitRiverOverride(key,() -> workspace.replace(selected,WorldBoundaryFields.setScalar(selected,workspace.read(selected),field,input.get())));
            }
            ImGui.popID();
        }
        for (String path : WorldBoundaryFields.readOnlyPaths(document)) ImGui.textWrapped(path+" (read-only)");
        ImGui.textWrapped("Clear discards the entire inline border configuration, including advanced fields, and writes null.");
        if (ImGui.button("Clear border configuration (write null)")) {
            try {
                if (drafts.transitionUnder(WorldBoundaryFields.prefix(),() -> !drafts.anyUnder(WorldBoundaryFields.prefix()) || JOptionPane.showConfirmDialog(null,"Discard unapplied world-border text and clear the entire border configuration?","Unapplied world-border text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION,() -> workspace.replace(selected,WorldBoundaryFields.disable(selected,workspace.read(selected))))) { values.keySet().removeIf(key -> key.startsWith(WorldBoundaryFields.prefix()));error=null; }
            } catch (Exception failure) { error=failure.getMessage(); }
        }
        ImGui.popID();
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
        ImGui.textWrapped("1-64 weighted fluid entries; weights 1-64. Minecraft entries permit water, lava and bubble_column. Custom names pass the native name filter but still require runtime fluid/registry validation. Structured states and backups are editable below; debug and block-entity payloads remain read-only.");
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
                drawExactBlockAlias(block,key);
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
            drawInlineBlockData(entry,new PaletteBlockData.Context(target.styleTarget(),fluidIndex),fluids.size());
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
            boolean other=drafts.anyOtherUnder(prefix,appliedKey);
            return !other || JOptionPane.showConfirmDialog(null,"This discards other unapplied text in the affected settings. Continue?","Unapplied text",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION;
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
            ImGui.textWrapped("Weighted blocks: at least one entry; weights 1-64. Structured state/backup controls are available below. Runtime block registry, state and solidity validation is pending.");
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
                    drawExactBlockAlias(block,key);
                } catch (IllegalArgumentException mismatch) { ImGui.textWrapped("Stored block name is not a string (read-only)."); }
                drawRiverMaterialNumber(entry,target,index,"weight");
                drawInlineBlockData(entry,new PaletteBlockData.Context(NoiseStyleFields.Target.palette(target),selectedIndex),entries.size());
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
            boolean other=drafts.anyOtherUnder(prefix,appliedKey);
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
                boolean expanded = ImGui.treeNodeEx(node.name(), ImGuiTreeNodeFlags.SpanAvailWidth | (node.path().equals(selectedFolder) ? ImGuiTreeNodeFlags.Selected : 0));
                if (!ImGui.getIO().getKeyCtrl() && ImGui.isItemClicked(0)) selectedFolder=node.path();
                boolean createChild=ImGui.getIO().getKeyCtrl() && ImGui.isItemClicked(2),createHere=ImGui.getIO().getKeyCtrl() && ImGui.isItemClicked(1);
                if (!ImGui.getIO().getKeyCtrl() && ImGui.beginPopupContextItem("Folder operations")) {
                    ImGui.beginDisabled(!workspace.editableEmptyFolder(node.path()));
                    for (String action : List.of("Rename","Move","Delete")) if (ImGui.menuItem(action+" empty folder")) queueFolderAction(node.path(),action);
                    ImGui.endDisabled();ImGui.textWrapped("Only empty leaf subfolders in supported resource families can be changed.");ImGui.endPopup();
                }
                if (createChild) createSubfolder(node.path());
                else if (createHere) createResource(node.path());
                if (expanded) { drawResources(node.children()); ImGui.treePop(); }
            } else if (ImGui.selectable(node.name() + (workspace.changed(node.path()) ? " *" : ""), node.path().equals(selected))) {
                selectedFolder=null;
                if (ImGui.getIO().getKeyCtrl()) duplicateResource(node.path());
                else if (!node.path().equals(selected)) selectResource(node.path());
            }
            ImGui.popID();
        }
    }
    private void queueFolderAction(String path,String action) { pendingFolderPath=path;pendingFolderAction=action; }
    private void applyPendingFolderAction() {
        String path=pendingFolderPath,action=pendingFolderAction;pendingFolderPath=null;pendingFolderAction=null;
        if (path==null || action==null) return;
        try {
            if (!workspace.editableEmptyFolder(path)) throw new java.io.IOException("Choose an empty leaf resource subfolder. Base folders and folders containing any files or subfolders are unchanged.");
            String next;
            if (action.equals("Rename")) {
                String name=JOptionPane.showInputDialog(null,"New empty folder name (lowercase letters, digits, _ or -):",path.substring(path.lastIndexOf('/')+1));if (name==null) return;
                next=workspace.renameEmptyFolder(path,name);
            } else if (action.equals("Move")) {
                String parent=JOptionPane.showInputDialog(null,"Existing destination folder within "+path.substring(0,path.indexOf('/'))+"/:\nThe folder keeps its name.",path.substring(0,path.lastIndexOf('/')));if (parent==null) return;
                next=workspace.moveEmptyFolder(path,parent);
            } else if (action.equals("Delete")) {
                if (JOptionPane.showConfirmDialog(null,"Remove empty folder from the workspace?\n"+path+"\nSave applies the removal to the active pack. This clears applied-edit history.","Remove empty folder",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
                workspace.deleteEmptyFolder(path);next=null;
            } else return;
            selectedFolder=next;filter.set("");error=null;
            // No resource bytes or inspector selection changed: all unapplied inspector text remains available.
        } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void chooseAssetReplacement(String target) {
        try {
            AssetReplacement.target(workspace,target);
            var chooser=new JFileChooser();chooser.setDialogTitle("Capture replacement for "+target);chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);chooser.setAcceptAllFileFilterUsed(false);
            String extension=ResourceTypes.extension(target).toLowerCase(java.util.Locale.ROOT);
            if (extension.equals(".iob")) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Iris object (*.iob)","iob"));
            else if (extension.equals(".png")) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG image (*.png)","png"));
            else chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JPEG image (*.jpg, *.jpeg)","jpg","jpeg"));
            if (chooser.showOpenDialog(null)!=JFileChooser.APPROVE_OPTION) return;
            assetReplacement.request(workspace,target,chooser.getSelectedFile().toPath());assetReplacementAssessed=-1;assetReplacementAllowUnrecorded=false;assetReplacementError=null;showAssetReplacement=true;
        } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawAssetReplacement() {
        if (!ImGui.begin("Captured asset replacement")) { ImGui.end();return; }
        if (ImGui.button("Close asset replacement")) { assetReplacement.clear();assetReplacementAssessed=-1;assetReplacementError=null;showAssetReplacement=false;ImGui.end();return; }
        ImGui.textWrapped("Capture a source file, review its exact byte count and SHA-256, then Apply to replace only the existing asset's in-memory bytes. The target path and authored references stay fixed. Unrelated inspector drafts remain available; Save is separate and repeats its conflict checks.");
        var state=assetReplacement.poll(workspace);
        if (ImGui.button("Cancel / clear captured asset")) { assetReplacement.clear();assetReplacementAssessed=-1;assetReplacementAllowUnrecorded=false;assetReplacementError=null;state=assetReplacement.poll(workspace); }
        ImGui.beginDisabled(!AssetReplacement.supports(selected));if (ImGui.button("Choose source for selected asset")) chooseAssetReplacement(selected);ImGui.endDisabled();
        state=assetReplacement.poll(workspace);
        if (state.target()!=null) {
            ImGui.textWrapped("Pinned target: "+InspectorText.label(state.target().path(),512)+"; "+state.target().beforeBytes()+" bytes when prepared.");
            ImGui.beginDisabled(!workspace.paths().contains(state.target().path()));if (ImGui.smallButton("Open replacement target resource")) { try { resourceNavigation.request(workspace,state.target().path()); } catch (Exception failure) { assetReplacementError=InspectorText.label(failure.getMessage(),512); } }ImGui.endDisabled();
            ImGui.beginDisabled(!workspace.paths().contains(state.target().path()));if (ImGui.smallButton("Choose another source for pinned target")) chooseAssetReplacement(state.target().path());ImGui.endDisabled();
        }
        state=assetReplacement.poll(workspace);
        if (state.source()!=null) ImGui.textWrapped("Chosen source: "+InspectorText.label(state.source().toString(),512));
        if (state.phase()==AsyncAssetReplacement.Phase.CAPTURING) ImGui.textWrapped("Capturing asset bytes in the background... No pack bytes change until Apply. Choosing another source replaces the pending request; Cancel discards it.");
        else if (state.phase()==AsyncAssetReplacement.Phase.FAILED || state.phase()==AsyncAssetReplacement.Phase.STALE) ImGui.textWrapped(state.detail()+". Choose a source again to retry.");
        else if (state.phase()==AsyncAssetReplacement.Phase.APPLIED) ImGui.textWrapped(state.detail());
        else if (state.phase()==AsyncAssetReplacement.Phase.IDLE) ImGui.textWrapped("No captured replacement. Select an existing images/ PNG/JPEG or objects/ .iob asset and choose a source file.");
        if (state.receipt()!=null) { ImGui.textWrapped("Captured source: "+InspectorText.label(state.receipt().source().toString(),512));ImGui.textUnformatted("Captured bytes: "+state.receipt().bytes());ImGui.textWrapped("Captured SHA-256: "+state.receipt().sha256()); }
        if (state.phase()==AsyncAssetReplacement.Phase.READY) {
            try {
                if (assetReplacementAssessed!=state.request()) { assetReplacementNoop=assetReplacement.unchanged(workspace);assetReplacementAllowUnrecorded=false;assetReplacementAssessed=state.request(); }
                boolean retained=assetReplacementNoop || AssetReplacement.fitsHistory(state.target(),state.receipt());
                if (assetReplacementNoop) ImGui.textWrapped("Captured bytes are identical to the current asset. Applying is a no-op and retains history, revisions and dirty state.");
                else if (retained) ImGui.textWrapped("This applied byte edit can be retained in Undo/Redo. Older history entries may be evicted at the existing history limits.");
                else {
                    ImGui.textWrapped("The combined before/after bytes exceed the 32 MiB retained-edit budget. Applying will clear existing history, and this replacement cannot be undone through that timeline.");
                    if (ImGui.checkbox("Acknowledge applying without retained Undo and clearing history",assetReplacementAllowUnrecorded)) assetReplacementAllowUnrecorded=!assetReplacementAllowUnrecorded;
                }
                ImGui.beginDisabled(!retained && !assetReplacementAllowUnrecorded);boolean apply=ImGui.button("Apply captured replacement bytes");ImGui.endDisabled();
                if (apply) { assetReplacement.apply(workspace,assetReplacementAllowUnrecorded);assetReplacementAssessed=-1;assetReplacementError=null; }
            } catch (Exception failure) { assetReplacementError=InspectorText.label(failure.getMessage(),512); }
        }
        if (assetReplacementError!=null) ImGui.textWrapped(assetReplacementError);
        ImGui.textWrapped("Limits: 64 MiB/source and available workspace capacity. Only regular files without symbolic-link source paths are accepted. Source and target extensions must match, allowing JPG/JPEG interchange. Captured bytes are immutable; later source changes do not alter them. Applying rechecks workspace identity, exact target byte revision and current capacity. No image/object format validation, decoding, reference rewriting, baseline adoption or disk write occurs. Labels shorten long paths. Unsupported previews stay disabled.");
        ImGui.end();
    }
    private void importAsset() {
        try {
            var chooser=new JFileChooser();chooser.setDialogTitle("Capture new PNG/JPEG image or Iris object asset");chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images and Iris objects (*.png, *.jpg, *.jpeg, *.iob)","png","jpg","jpeg","iob"));
            if (chooser.showOpenDialog(null)!=JFileChooser.APPROVE_OPTION) return;
            Path source=chooser.getSelectedFile().toPath();var asset=AssetImports.describe(source);
            String key=JOptionPane.showInputDialog(null,"Capture into "+asset.family()+"/ as a new resource key (omit "+asset.extension()+"):\n"+asset.bytes()+" bytes. Review captured bytes before applying. Existing resources are never replaced.\nLimit: 64 MiB/file and available workspace capacity.",asset.suggestedKey());
            if (key==null) return;
            assetImport.request(workspace,source,key);assetImportReviewed=-1;assetImportAcknowledge=false;assetImportError=null;showAssetImport=true;
        } catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawAssetImport() {
        if (!ImGui.begin("Captured new asset import")) { ImGui.end();return; }
        if (ImGui.button("Close asset import")) { assetImport.clear();assetImportReviewed=-1;assetImportError=null;showAssetImport=false;ImGui.end();return; }
        ImGui.textWrapped("Capture a source, review its exact byte count and SHA-256, then Apply to create the new in-memory asset. Selection and unrelated inspector drafts remain available. Import clears applied-edit history; Save is separate.");
        if (ImGui.button("Choose source and new destination")) importAsset();
        if (ImGui.button("Cancel / clear captured import")) { assetImport.clear();assetImportReviewed=-1;assetImportAcknowledge=false;assetImportError=null; }
        var state=assetImport.poll(workspace);
        if (state.request()!=assetImportReviewed) { assetImportReviewed=state.request();assetImportAcknowledge=false;assetImportError=null; }
        if (state.target()!=null) ImGui.textWrapped("Pinned new destination: "+InspectorText.label(state.target().path(),512));
        if (state.source()!=null) ImGui.textWrapped("Chosen source: "+InspectorText.label(state.source().toString(),512));
        if (state.phase()==AsyncAssetImport.Phase.CAPTURING) ImGui.textWrapped("Capturing bytes in the background... Pack bytes and history stay unchanged until Apply. A new choice replaces the pending request.");
        else if (state.phase()==AsyncAssetImport.Phase.FAILED || state.phase()==AsyncAssetImport.Phase.STALE) ImGui.textWrapped(state.detail()+". Choose a source and destination again to retry.");
        else if (state.phase()==AsyncAssetImport.Phase.APPLIED) {
            ImGui.textWrapped(state.detail());
            if (ImGui.button("Open imported resource")) { try { resourceNavigation.request(workspace,state.target().path()); } catch (Exception failure) { assetImportError=InspectorText.label(failure.getMessage(),512); } }
        } else if (state.phase()==AsyncAssetImport.Phase.IDLE) ImGui.textWrapped("No captured import. Choose a PNG/JPEG or .iob file and an unused lowercase resource key.");
        if (state.receipt()!=null) { ImGui.textWrapped("Captured source: "+InspectorText.label(state.receipt().source().toString(),512));ImGui.textUnformatted("Captured bytes: "+state.receipt().bytes());ImGui.textWrapped("Captured SHA-256: "+state.receipt().sha256()); }
        if (state.phase()==AsyncAssetImport.Phase.READY) {
            ImGui.textWrapped("This creates a resource and clears all prior applied-edit Undo/Redo history. The import itself is not retained in that timeline.");
            if (ImGui.checkbox("Acknowledge creating an asset and clearing applied-edit history",assetImportAcknowledge)) assetImportAcknowledge=!assetImportAcknowledge;
            ImGui.beginDisabled(!assetImportAcknowledge);boolean apply=ImGui.button("Apply captured new asset");ImGui.endDisabled();
            if (apply) { try { assetImport.apply(workspace,assetImportAcknowledge);assetImportError=null; } catch (Exception failure) { assetImportError=InspectorText.label(failure.getMessage(),512); } }
        }
        if (assetImportError!=null) ImGui.textWrapped(assetImportError);
        ImGui.textWrapped("Limits: 64 MiB/source and available workspace capacity. Only regular files without symbolic-link source paths are accepted. Bytes are opaque and private; later source edits or deletion do not change the captured import. Apply repeats destination, Windows path, entry-count and capacity checks without reading the source. No decoding, reference rewrite, baseline adoption or disk write occurs. Opening the imported resource uses the normal draft guard. Unsupported previews stay disabled.");
        ImGui.end();
    }
    private void importAssetBatch() {
        try {
            var chooser=new JFileChooser();chooser.setDialogTitle("Capture new image/object batch (up to 32 files)");chooser.setMultiSelectionEnabled(true);chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images and Iris objects (*.png, *.jpg, *.jpeg, *.iob)","png","jpg","jpeg","iob"));
            if (chooser.showOpenDialog(null)!=JFileChooser.APPROVE_OPTION) return;
            var sources=java.util.Arrays.stream(chooser.getSelectedFiles()).map(java.io.File::toPath).toList();
            String prefix=JOptionPane.showInputDialog(null,"Optional folder prefix within each asset family (leave empty for images/ or objects/):\nNew keys use lowercase sanitized source filename stems, with lowercase original extensions.\nReview every destination and SHA-256 before Apply. Duplicate destinations reject the entire batch; use single-file imports to choose distinct keys.\nLimits: 32 files, 64 MiB/file, 128 MiB/batch and available workspace capacity.","");
            if (prefix==null) return;
            assetBatchImport.request(workspace,sources,prefix);assetBatchReviewed=-1;assetBatchAcknowledge=false;assetBatchError=null;showAssetBatchImport=true;
        } catch (Exception failure) { error=InspectorText.label(failure.getMessage(),512); }
    }
    private void drawAssetBatchImport() {
        if (!ImGui.begin("Captured asset batch")) { ImGui.end();return; }
        if (ImGui.button("Close asset batch")) { assetBatchImport.clear();assetBatchReviewed=-1;assetBatchError=null;showAssetBatchImport=false;ImGui.end();return; }
        ImGui.textWrapped("Capture several source files, review all new destinations and SHA-256 receipts, then Apply the whole batch in memory. A failed destination or capacity check installs no files. Selection and unrelated drafts remain available; Save is separate.");
        if (ImGui.button("Choose batch sources and folder prefix")) importAssetBatch();
        if (ImGui.button("Cancel / clear captured batch")) { assetBatchImport.clear();assetBatchReviewed=-1;assetBatchAcknowledge=false;assetBatchError=null; }
        var state=assetBatchImport.poll(workspace);
        if (state.request()!=assetBatchReviewed) { assetBatchReviewed=state.request();assetBatchAcknowledge=false;assetBatchError=null; }
        if (state.target()!=null) {
            ImGui.textUnformatted("Pinned batch: "+state.target().inputs().size()+" new files");
            for (var input : state.target().inputs()) ImGui.textWrapped(InspectorText.label(input.source().toString(),512)+" -> "+InspectorText.label(input.target(),512));
        }
        if (state.phase()==AsyncAssetBatchImport.Phase.CAPTURING) ImGui.textWrapped("Capturing and hashing the batch in the background... No resources or history change until Apply. A new choice replaces the pending request.");
        else if (state.phase()==AsyncAssetBatchImport.Phase.FAILED || state.phase()==AsyncAssetBatchImport.Phase.STALE) ImGui.textWrapped(state.detail()+". Choose sources and a prefix again to retry.");
        else if (state.phase()==AsyncAssetBatchImport.Phase.APPLIED) ImGui.textWrapped(state.detail());
        else if (state.phase()==AsyncAssetBatchImport.Phase.IDLE) ImGui.textWrapped("No captured batch. Choose 1 to 32 PNG/JPEG or .iob sources. Duplicate or occupied destinations are never overwritten.");
        if (!state.receipts().isEmpty()) ImGui.textUnformatted("Captured total: "+state.receipts().stream().mapToLong(AssetReplacement.Receipt::bytes).sum()+" bytes across "+state.receipts().size()+" files");
        for (int i=0;i<state.receipts().size();i++) {
            var receipt=state.receipts().get(i);ImGui.pushID(i);ImGui.separator();ImGui.textWrapped("Destination: "+InspectorText.label(receipt.target(),512));ImGui.textWrapped("Captured source: "+InspectorText.label(receipt.source().toString(),512));ImGui.textUnformatted("Captured bytes: "+receipt.bytes());ImGui.textWrapped("SHA-256: "+receipt.sha256());
            if (state.phase()==AsyncAssetBatchImport.Phase.APPLIED && ImGui.smallButton("Open imported batch resource")) { try { resourceNavigation.request(workspace,receipt.target()); } catch (Exception failure) { assetBatchError=InspectorText.label(failure.getMessage(),512); } }ImGui.popID();
        }
        if (state.phase()==AsyncAssetBatchImport.Phase.READY) {
            ImGui.textWrapped("Apply creates every reviewed resource as one structural operation, clears prior applied-edit Undo/Redo history, and is not retained in that timeline. No source file is read again.");
            if (ImGui.checkbox("Acknowledge creating the whole batch and clearing applied-edit history",assetBatchAcknowledge)) assetBatchAcknowledge=!assetBatchAcknowledge;
            ImGui.beginDisabled(!assetBatchAcknowledge);boolean apply=ImGui.button("Apply entire captured asset batch");ImGui.endDisabled();
            if (apply) { try { assetBatchImport.apply(workspace,assetBatchAcknowledge);assetBatchError=null; } catch (Exception failure) { assetBatchError=InspectorText.label(failure.getMessage(),512); } }
        }
        if (assetBatchError!=null) ImGui.textWrapped(assetBatchError);
        ImGui.textWrapped("Limits: 32 regular files, 64 MiB/source, 128 MiB captured total and available workspace capacity. Source paths containing symbolic links are rejected. Source names are converted to new lowercase keys; contents stay opaque. Current path spelling, folder/removal, joint entry-count and byte budgets are rechecked before any installation. Later source edits/deletion do not alter captured bytes. Save independently checks external disk collisions. No decoding, reference rewrite, baseline adoption or native loading occurs. Unsupported previews stay disabled.");
        ImGui.end();
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
            String name = JOptionPane.showInputDialog(null, parent.startsWith("jigsaw-pools") ? "New pool name (omit .json; starts with one empty membership):" : "New resource name (omit .json):", "new_resource");
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
        PackWorkspace previous=workspace;
        boolean committed = drafts.transition(replaceWorkspace ? this::replaceAllowed : this::discardDraftsAllowed, change);
        if (committed) { values.clear(); riverCatalogDimension=null;if (replaceWorkspace) { selectedFolder=null;pendingFolderPath=null;pendingFolderAction=null;assetBatchImport.clear();showAssetBatchImport=false;assetBatchReviewed=-1;assetBatchError=null;assetBatchAcknowledge=false;assetImport.clear();showAssetImport=false;assetImportReviewed=-1;assetImportError=null;assetImportAcknowledge=false;assetReplacement.clear();showAssetReplacement=false;assetReplacementAssessed=-1;assetReplacementError=null;assetReplacementAllowUnrecorded=false;structureThemeReferences.clear();showStructureThemeReview=false;clearThemeReviewView();themeReviewKey.set("");themeReviewFilter.set("");themeReviewError=null;blockFallbackAudits.clear();clearFallbackOverlapView();fallbackOverlapQuery.set("");fallbackCurrentDimensionOnly=false;blockAliasAudits.clear();expressionAudits.clear();jigsawPoolCatalog.clear();jigsawPieceObjects.clear();structurePools.clear();structurePoolFilter.set("");previous.clearHistory();editReviews.clear();reviewedWorkspace=null;reviewedHistoryId=-1;reviewedComparison=null;reviewedBefore=null;reviewedAfter=null;editDiffs.clear();reviewedDiff=null;diffPage=0;diffSelected=-1;diffBufferedRow=-1;diffLineText=null;resourceNavigation.clear();navigationHistory.reset(workspace,selected,filter.get());clearSourceMatchFocus();jsonSourceCache.clear();showJsonStructure=false;jsonActiveSnapshot=null;jsonWholeSnapshot=null;jsonTokenSnapshot=null;jsonWholePreview=null;jsonTokenPreview=null;jsonCurrentPointer=null;jsonPages.clear();jsonWholeText=null;jsonTokenText=null;jsonNode=0;jsonPage=0;jsonBufferedNode=-1;jsonFind.set("");jsonPointer.set("");jsonFindError=null;jsonPreviousFind="";jigsawReview.clear();showJigsawReview=false;jigsawReviewRoot=null;jigsawReviewQuery.set("");jigsawReviewScope=JigsawDependencyReview.Scope.ALL;jigsawProblemsOnly=false;packReferenceReview.clear();showReferenceReview=false;referenceReviewQuery.set("");referenceReviewView=PackReferenceAudit.View.ALL;reviewIncomingOnly=false;referenceReviewPendingPath=null;referenceReviewPendingWorkspace=null;pendingChanges.clear();showPendingChanges=false;changesQuery.set("");changesFilter=WorkspaceChanges.Filter.ALL;changesSelected=null;changesPendingPath=null;changesPendingWorkspace=null;textSearch.clear();activeTextQuery=null;showTextSearch=false;textQuery.set("");textFamily=ResourceFinder.Family.ALL;textChangedOnly=false;textSearchError=null;textPendingPath=null;textPendingWorkspace=null;quickFinder.clear();showQuickOpen=false;quickQuery.set("");quickFamily=ResourceFinder.Family.ALL;quickChangedOnly=false;quickSelected=null;quickPendingPath=null;quickPendingWorkspace=null;browserFamily=ResourceFinder.Family.ALL;browserChangedOnly=false; } else { revealSelectedResource();navigationHistory.observe(workspace,selected,filter.get()); } }
        return committed;
    }
    private void revealSelectedResource() {
        if (selected==null) return;
        if (!browserFamily.matches(selected)) browserFamily=ResourceFinder.Family.ALL;
        if (browserChangedOnly && !workspace.changed(selected)) browserChangedOnly=false;
    }
    private static ImString jsonReadOnlyBuffer(String text) { return new ImString(text,Math.max(128,text.getBytes(StandardCharsets.UTF_8).length+1)); }
    private JsonStructure.Snapshot currentJsonSnapshot() {
        var snapshot=jsonSourceCache.inspect(workspace,selected);
        if (jsonActiveSnapshot!=snapshot) { jsonActiveSnapshot=snapshot;jsonWholeSnapshot=null;jsonTokenSnapshot=null;jsonWholeText=null;jsonTokenText=null;jsonWholePreview=null;jsonTokenPreview=null;jsonCurrentPointer=null;jsonPages.clear();jsonBufferedNode=-1;openJsonNode(0);jsonPointer.set(""); }
        return snapshot;
    }
    private void clearSourceMatchFocus() {
        sourceMatchFocus.clear();sourceMatchBuffered=null;sourceMatchBefore=null;sourceMatchText=null;sourceMatchAfter=null;
    }
    private void updateSourceMatchFocus() {
        var current=sourceMatchFocus.current(workspace,selected);
        if (current!=sourceMatchBuffered) {
            sourceMatchBuffered=current;sourceMatchBefore=current==null ? null : jsonReadOnlyBuffer(current.before());sourceMatchText=current==null ? null : jsonReadOnlyBuffer(current.matched());sourceMatchAfter=current==null ? null : jsonReadOnlyBuffer(current.after());
        }
    }
    private void drawSourceMatchFocus(JsonStructure.Snapshot snapshot) {
        updateSourceMatchFocus();var view=sourceMatchBuffered;if (view==null) return;
        if (!ImGui.collapsingHeader("Focused source match (read-only)",ImGuiTreeNodeFlags.DefaultOpen)) return;
        var request=view.request();ImGui.textWrapped("Applied source: line "+request.line()+", column "+request.column()+"; UTF-16 offset "+request.offset()+", length "+view.matched().length()+". Original source spelling; context is clipped to 512 characters on either side.");
        ImGui.inputTextMultiline(view.clippedBefore() ? "Before match (earlier text omitted)" : "Before match",sourceMatchBefore,-1,70,ImGuiInputTextFlags.ReadOnly);
        ImGui.inputText("Matched original source",sourceMatchText,ImGuiInputTextFlags.ReadOnly);
        ImGui.inputTextMultiline(view.clippedAfter() ? "After match (later text omitted)" : "After match",sourceMatchAfter,-1,70,ImGuiInputTextFlags.ReadOnly);
        int containing=SourceMatch.containing(snapshot,view);
        if (containing>=0) {
            var pointer=snapshot.pointer(containing);ImGui.textWrapped("Containing indexed token: "+(pointer.text().isEmpty() ? "(root)" : InspectorText.label(pointer.text(),512)));
            if (!pointer.complete() || snapshot.status()==JsonStructure.Status.LIMITED) ImGui.textWrapped("The JSON index or pointer is limited; a more specific token may be unindexed.");
            if (ImGui.smallButton("Return to containing indexed token")) { openJsonNode(containing);if (pointer.complete()) jsonPointer.set(pointer.text()); }
            ImGui.textWrapped("Member-name and separator matches belong to their containing object/array. This locates source text; it does not identify an editable field.");
        } else ImGui.textWrapped("No containing indexed token is available. The literal context remains visible for malformed/duplicate-key JSON, text outside root tokens or source above the 1 MB JSON-index limit.");
        if (ImGui.smallButton("Clear focused match")) clearSourceMatchFocus();
        ImGui.separator();
    }
    private void refreshJsonToken(JsonStructure.Snapshot snapshot) {
        if (jsonTokenSnapshot!=snapshot || jsonBufferedNode!=jsonNode) { jsonTokenSnapshot=snapshot;jsonBufferedNode=jsonNode;jsonTokenPreview=snapshot.token(jsonNode);jsonCurrentPointer=snapshot.pointer(jsonNode);jsonTokenText=jsonReadOnlyBuffer(jsonTokenPreview.text()); }
    }
    private void drawJsonSource(JsonStructure.Snapshot snapshot) {
        ImGui.textWrapped(snapshot.detail());
        if (snapshot.status()==JsonStructure.Status.INVALID_UTF8 || snapshot.status()==JsonStructure.Status.CONTROL_TEXT || snapshot.status()==JsonStructure.Status.TOO_LARGE || snapshot.status()==JsonStructure.Status.NOT_JSON) { ImGui.textUnformatted(snapshot.bytes()+" applied bytes; source text unavailable.");return; }
        if (jsonWholeSnapshot!=snapshot) { jsonWholeSnapshot=snapshot;jsonWholePreview=snapshot.sourcePreview();jsonWholeText=jsonReadOnlyBuffer(jsonWholePreview.text()); }
        ImGui.textWrapped("Applied source: "+snapshot.bytes()+" bytes, "+jsonWholePreview.totalCharacters()+" UTF-16 characters"+(jsonWholePreview.truncated() ? "; first 16,384 shown" : ""));
        ImGui.inputTextMultiline("Applied source text",jsonWholeText,-1,180,ImGuiInputTextFlags.ReadOnly);
    }
    private void openJsonNode(int id) { jsonNode=id;jsonPage=0;jsonFind.set("");jsonPreviousFind="";jsonFindError=null; }
    private void drawJsonStructure() {
        if (!ImGui.begin("JSON structure (read-only)")) { ImGui.end();return; }
        if (ImGui.button("Close JSON browser")) { clearSourceMatchFocus();showJsonStructure=false;ImGui.end();return; }
        var snapshot=currentJsonSnapshot();
        ImGui.textWrapped("Resource: "+InspectorText.label(selected,256));
        drawSourceMatchFocus(snapshot);
        if (!snapshot.indexed()) { drawJsonSource(snapshot);ImGui.end();return; }
        ImGui.textWrapped(snapshot.detail());
        ImGui.textWrapped(snapshot.nodes().size()+" indexed nodes. Browsing shows applied source only; unknown fields stay read-only. This is not schema or native validation, and viewing does not insert defaults.");
        if (ImGui.button("Root")) openJsonNode(0);ImGui.sameLine();ImGui.beginDisabled(jsonNode==0);if (ImGui.button("Parent")) openJsonNode(snapshot.node(jsonNode).parent());ImGui.endDisabled();
        ImGui.inputText("Exact JSON pointer (empty = root)",jsonPointer);
        if (ImGui.button("Go to indexed pointer")) {
            try { int found=snapshot.find(jsonPointer.get());if (found<0) jsonFindError="Pointer not indexed: it may be absent or beyond index limits.";else openJsonNode(found); }
            catch (IllegalArgumentException invalid) { jsonFindError=invalid.getMessage(); }
        }
        refreshJsonToken(snapshot);ImGui.textWrapped("Current pointer: "+(jsonNode==0 ? "(root)" : InspectorText.label(jsonCurrentPointer.text(),512)));if (!jsonCurrentPointer.complete()) ImGui.textWrapped("Pointer exceeds the 16,384-character representation limit; this display is incomplete.");
        ImGui.inputText("Find indexed names / scalar prefixes",jsonFind);
        if (!jsonFind.get().equals(jsonPreviousFind)) { jsonPage=0;jsonPreviousFind=jsonFind.get();jsonFindError=null; }
        if (ImGui.button("Clear JSON search")) { jsonFind.set("");jsonPreviousFind="";jsonPage=0;jsonFindError=null; }
        try {
            var page=jsonPages.page(snapshot,jsonNode,jsonFind.get(),jsonPage);jsonPage=page.page();
            ImGui.textWrapped(page.matches()+" matching indexed nodes; page "+(page.page()+1)+"; "+page.nodes().size()+" shown. Empty search lists direct children; search matches names or the first 256 source characters of scalar tokens.");
            ImGui.beginDisabled(!page.previous());if (ImGui.button("Previous nodes")) jsonPage--;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(!page.next());if (ImGui.button("Next nodes")) jsonPage++;ImGui.endDisabled();
            for (int id : page.nodes()) { var node=snapshot.node(id);ImGui.pushID(id);if (ImGui.button("Open node")) openJsonNode(id);ImGui.sameLine();ImGui.textUnformatted(node.kind()+" : "+(node.segment().isEmpty() ? "(empty member name)" : InspectorText.label(node.segment(),256)));ImGui.textWrapped(snapshot.summary(id));ImGui.popID(); }
        } catch (IllegalArgumentException invalid) { jsonFindError=invalid.getMessage(); }
        if (jsonFindError!=null) ImGui.textWrapped(jsonFindError);
        refreshJsonToken(snapshot);var node=snapshot.node(jsonNode);
        ImGui.separator();ImGui.textWrapped(node.kind()+" token; source UTF-16 offsets "+node.start()+".."+node.end()+"; "+jsonTokenPreview.totalCharacters()+" characters"+(jsonTokenPreview.truncated() ? "; first 16,384 shown" : ""));
        if (node.limited()) ImGui.textWrapped("Some direct children or descendants are not indexed. The original token remains preserved.");
        ImGui.inputTextMultiline("Selected original token",jsonTokenText,-1,180,ImGuiInputTextFlags.ReadOnly);
        ImGui.textWrapped("Limits: 1 MB source, 4,096 nodes, 32 indexed child levels, 200 nodes/page, 256 search characters and 16,384-character token/pointer previews. Labels escape controls and shorten long text; token panes preserve source spelling. Pointers use / segments and ~0 / ~1 escapes, not dotted field paths. Save/Export remain independent; unsupported previews stay disabled.");
        ImGui.end();
    }
    private void clearThemeReviewView() { themeReviewedReview=null;themeReviewPages.clear();themeReviewPage=0; }
    private void openStructureThemeReview(String key) {
        StructureThemeReferences.validateKey(key);themeReviewKey.set(key);showStructureThemeReview=true;structureThemeReferences.refresh(workspace,key);themeReviewError=null;
    }
    private void drawStructureThemeReview() {
        if (!ImGui.begin("Global piece theme tag review")) { ImGui.end();return; }
        if (ImGui.button("Close piece theme review")) { structureThemeReferences.clear();clearThemeReviewView();showStructureThemeReview=false;ImGui.end();return; }
        ImGui.textWrapped("Exact case-sensitive decoded tag mentions in applied jigsaw-piece JSON. Pieces may belong to other structures; graph membership, native eligibility and effective theme selection are unchecked. No key rename, removal, tag edit or generation is authorized by these results.");
        ImGui.inputText("Exact tag to review",themeReviewKey);
        if (ImGui.button("Scan / replace piece tag review")) { try { structureThemeReferences.refresh(workspace,themeReviewKey.get());themeReviewError=null; } catch (Exception failure) { themeReviewError=InspectorText.label(failure.getMessage(),512); } }
        var state=structureThemeReferences.poll(workspace);ImGui.beginDisabled(state.key()==null);
        if (ImGui.button("Refresh pinned tag")) { structureThemeReferences.refresh(workspace,state.key());themeReviewError=null; }
        ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Cancel / clear piece tag review")) { structureThemeReferences.clear();clearThemeReviewView();themeReviewError=null; }
        state=structureThemeReferences.poll(workspace);ImGui.textWrapped("Pinned review tag: "+(state.key()==null ? "None" : InspectorText.label(state.key(),256)));
        if (themeReviewError!=null) ImGui.textWrapped(themeReviewError);
        if (state.phase()==AsyncStructureThemeReview.Phase.SCANNING) ImGui.textWrapped("Reviewing piece tags in the background... Editing and Save/Export remain available. A new request replaces pending work; Cancel discards the result.");
        else if (state.phase()==AsyncStructureThemeReview.Phase.FAILED) ImGui.textWrapped("Piece tag review failed: "+state.error()+". Refresh explicitly to retry.");
        else if (state.review()==null) ImGui.textWrapped("No review for the current applied workspace state. Applied edits/Undo/Redo and resource changes require an explicit refresh. Selection, drafts, Save/Export and collapsing this window do not rescan.");
        var review=state.review();
        if (review==null) clearThemeReviewView();
        else {
            if (themeReviewedReview!=review) { clearThemeReviewView();themeReviewedReview=review; }
            ImGui.textWrapped(review.inspected()+" / "+review.eligible()+" source files inspected; "+review.bytes()+" JSON bytes; "+review.skipped()+" skipped; "+review.matchedFiles()+" inspected files with explicit mentions; "+review.unfiltered()+" inspected files with absent/null/empty tag lists.");
            if (review.incomplete()) ImGui.textWrapped("Incomplete review: skipped or unsupported pieces may contain matching tags. Counts describe inspected text only.");
            if (review.truncated()) ImGui.textWrapped("Some matching inspected files were not retained. Path filtering and paging cover retained matches only.");
            if (ImGui.inputText("Retained matching piece path contains",themeReviewFilter)) themeReviewPage=0;
            if (ImGui.button("Clear piece path filter")) { themeReviewFilter.set("");themeReviewPage=0; }
            try {
                var page=themeReviewPages.page(review,themeReviewFilter.get(),themeReviewPage);themeReviewPage=page.page();ImGui.textWrapped(page.matches()+" retained matching files after the path filter; page "+(page.page()+1)+"; "+page.hits().size()+" shown.");
                ImGui.beginDisabled(!page.previous());if (ImGui.smallButton("Previous piece tag matches")) themeReviewPage--;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(!page.next());if (ImGui.smallButton("Next piece tag matches")) themeReviewPage++;ImGui.endDisabled();
                int row=0;for (var hit : page.hits()) { ImGui.pushID(row++);if (ImGui.button("Open matching piece JSON")) { try { resourceNavigation.request(workspace,hit.path()); } catch (Exception failure) { themeReviewError=InspectorText.label(failure.getMessage(),512); } }ImGui.sameLine();ImGui.textUnformatted(InspectorText.label(hit.path(),256)+" : "+hit.occurrences()+" explicit mentions");ImGui.popID(); }
            } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            if (ImGui.treeNode("Piece theme review notices")) { for (var notice : review.notices()) ImGui.textWrapped(InspectorText.label(notice.path(),256)+": "+notice.detail());if (review.omittedNotices()>0) ImGui.textUnformatted(review.omittedNotices()+" additional notices omitted.");ImGui.treePop(); }
        }
        ImGui.textWrapped("Limits: exact 1-256 UTF-16-character key without controls; first 256 sorted eligible pieces, 1 MB/file, 8 MB copied/inspected JSON, up to 256 retained matching files, 200 displayed per page and 32 notices. Oversized/byte-skipped pieces are not read; later small files may fit. Eligible path capture is bounded to 100,000 paths / 16 million UTF-16 characters. Unrelated and binary payloads are excluded. Open uses the existing deferred draft guard and keeps this review available while browsing a piece. Original bytes and unsupported previews remain unchanged.");
        ImGui.end();
    }
    private void drawJigsawDependencyReview() {
        if (!ImGui.begin("Jigsaw dependency review")) { ImGui.end();return; }
        if (ImGui.button("Close dependency review")) { jigsawReview.clear();showJigsawReview=false;ImGui.end();return; }
        ImGui.textWrapped("Authored file paths from one pinned structure, pool or piece. This follows declared start pools, direct fallback fields, physical memberships, connector pools and object-file presence. Repeated paths and cycle groups do not establish runtime execution or invalidity. Native fallback uses a direct fallback only; following its own declarations here is a file review, not fallback execution.");
        ImGui.beginDisabled(!JigsawDependencyReview.supports(selected));
        if (ImGui.button("Scan selected structure / pool / piece")) {
            try { jigsawReviewRoot=selected;jigsawReview.refresh(workspace,jigsawReviewRoot); }
            catch (Exception failure) { error=failure.getMessage(); }
        }
        ImGui.endDisabled();
        ImGui.beginDisabled(jigsawReviewRoot==null || !workspace.paths().contains(jigsawReviewRoot));
        if (ImGui.button("Refresh pinned root")) { try { jigsawReview.refresh(workspace,jigsawReviewRoot); } catch (Exception failure) { error=failure.getMessage(); } }
        ImGui.endDisabled();ImGui.sameLine();if (ImGui.button("Cancel / clear dependency scan")) jigsawReview.clear();
        ImGui.textWrapped("Pinned root: "+InspectorText.label(jigsawReviewRoot,256));
        ImGui.inputText("Dependency path, field or key contains",jigsawReviewQuery);
        if (ImGui.beginCombo("Dependency link scope",jigsawReviewScope.label())) { for (var scope : JigsawDependencyReview.Scope.values()) if (ImGui.selectable(scope.label(),scope==jigsawReviewScope)) jigsawReviewScope=scope;ImGui.endCombo(); }
        if (ImGui.checkbox("Only non-exact diagnostics",jigsawProblemsOnly)) jigsawProblemsOnly=!jigsawProblemsOnly;
        if (ImGui.button("Clear dependency filters")) { jigsawReviewQuery.set("");jigsawReviewScope=JigsawDependencyReview.Scope.ALL;jigsawProblemsOnly=false; }
        var scan=jigsawReview.poll(workspace,jigsawReviewRoot);var review=scan.review();
        if (scan.phase()==AsyncJigsawDependencyReview.Phase.SCANNING) ImGui.textWrapped("Scanning jigsaw dependencies in the background... Editing and Save/Export remain available. Refresh replaces the request; Cancel discards it.");
        else if (scan.phase()==AsyncJigsawDependencyReview.Phase.FAILED) ImGui.textWrapped("Dependency scan failed: "+scan.error()+". Refresh explicitly to retry.");
        else if (review==null) ImGui.textWrapped("No scan for this root and applied workspace state. Scan explicitly; selecting or opening another resource keeps the pinned root. Applied edits and resource changes require a refresh.");
        else {
            ImGui.textWrapped(review.nodes().size()+" visited resources; "+review.jsonFiles()+" JSON files inspected; "+review.bytes()+" JSON bytes; "+review.edges().size()+" recorded diagnostics; "+review.count(ReferenceDiagnostics.State.EXACT_FILE)+" exact-file links; "+review.cycles().size()+" repeated-path groups; "+review.fallbackCycles().size()+" fallback-only groups.");
            ImGui.textWrapped(review.incomplete() ? "Some outgoing dependencies are unknown because of unsupported data or review limits. See recorded diagnostics and notices." : "No unsupported data or review limits encountered in these known fields. Coverage and native validation remain partial.");
            if (ImGui.treeNode("Recorded route to current selection")) {
                var route=review.route(selected);
                if (route.isEmpty()) ImGui.textWrapped("The current selection has no recorded visited path from this root.");
                for (int i=0;i<Math.min(64,route.size());i++) { var node=route.get(i);ImGui.textWrapped(node.distance()+": "+InspectorText.label(node.path(),256)+(node.via()==null ? "" : " via "+InspectorText.label(node.via(),256))); }
                if (route.size()>64) ImGui.textWrapped("Only the first 64 route resources are displayed.");
                ImGui.textWrapped("This is one shortest authored-link path. It does not establish generation reachability or connector eligibility.");ImGui.treePop();
            }
            try {
                var query=new JigsawDependencyReview.Query(jigsawReviewQuery.get(),jigsawReviewScope,selected,jigsawProblemsOnly);
                var result=jigsawReview.query(workspace,jigsawReviewRoot,query);ImGui.textWrapped(result.matches()+" matching link diagnostics; "+result.edges().size()+" shown.");
                if (result.truncated()) ImGui.textWrapped("The first 200 matching diagnostics are shown. Narrow the filters for later links.");
                int index=0;for (var edge : result.edges()) {
                    ImGui.pushID(index++);ImGui.textUnformatted(edge.state()+": "+InspectorText.label(edge.field(),256));ImGui.textWrapped(InspectorText.label(edge.source(),256));ImGui.textWrapped("Stored key / diagnostic: "+InspectorText.label(edge.key(),256));
                    if (edge.target()!=null) ImGui.textWrapped("Exact target: "+InspectorText.label(edge.target(),256));
                    if (ImGui.smallButton("Open dependency source")) queueReferenceReviewOpen(edge.source());
                    if (edge.state()==ReferenceDiagnostics.State.EXACT_FILE && edge.target()!=null) { ImGui.sameLine();if (ImGui.smallButton("Open dependency target")) queueReferenceReviewOpen(edge.target()); }
                    ImGui.popID();
                }
                if (ImGui.treeNode("Visited resource paths")) {
                    var matches=review.nodes().stream().filter(node -> query.text().isEmpty() || node.path().toLowerCase(java.util.Locale.ROOT).contains(query.text())).toList();
                    ImGui.textWrapped(matches.size()+" matching visited resources; showing up to 200. Link scope/diagnostic filters apply to links only.");
                    int nodeIndex=0;for (var node : matches.stream().limit(200).toList()) { ImGui.pushID(nodeIndex++);ImGui.textWrapped(node.distance()+" / "+node.status()+": "+InspectorText.label(node.path(),256));if (ImGui.smallButton("Open visited resource")) queueReferenceReviewOpen(node.path());ImGui.popID(); }ImGui.treePop();
                }
            } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
            if (ImGui.treeNode("Repeated authored-path groups")) { drawJigsawCycleGroups(review.cycles());ImGui.textWrapped("Sorted component members are not an ordered cycle witness. Recursive pool/piece declarations can be intentional; matching, rules and termination are unchecked.");ImGui.treePop(); }
            if (ImGui.treeNode("Fallback-only authored groups")) { drawJigsawCycleGroups(review.fallbackCycles());ImGui.textWrapped("These are literal fallback fields, not a claim that the native direct-fallback path recurses.");ImGui.treePop(); }
            if (ImGui.treeNode("Dependency review notices")) { for (var notice : review.notices()) { ImGui.textWrapped(InspectorText.label(notice.path(),256));ImGui.textWrapped(notice.message()); }if (review.omittedNotices()>0) ImGui.textWrapped(review.omittedNotices()+" further notices omitted.");ImGui.treePop(); }
        }
        ImGui.textWrapped("Limits: 512 visited resources, 256 inspected JSON files, 1 MB/file, 8 MB JSON total, 4,096 edges, 128 notices and 200 displayed matches. Snapshot limits: 4,096 copied JSON files / 16 MB, root first then lexical order, with exact target presence retained (up to 100,000 paths / 16 million UTF-16 characters). Reached targets omitted from the snapshot are marked skipped with unknown outgoing links. Object payloads are never read. Scans inspect applied memory on one background worker; applying edits invalidates results without rescanning. Geometry, theme/rule eligibility, binary bounds, priorities, registry/state compatibility, native assembly, noise/material dependencies and package export closure are unchecked. No files are changed; this review cannot authorize rename/move/delete. Save/Export remain available; unsupported previews remain disabled.");
        ImGui.end();
    }
    private void drawJigsawCycleGroups(List<List<String>> groups) {
        ImGui.textWrapped(groups.size()+" groups; showing up to 20 groups and 20 members per group.");
        int index=0;for (var group : groups.stream().limit(20).toList()) { ImGui.pushID(index++);if (ImGui.treeNode(group.size()+" component members")) { for (String path : group.stream().limit(20).toList()) { ImGui.textWrapped(InspectorText.label(path,256));if (ImGui.smallButton("Open component member##"+group.indexOf(path))) queueReferenceReviewOpen(path); }if (group.size()>20) ImGui.textWrapped("Additional members omitted from display.");ImGui.treePop(); }ImGui.popID(); }
    }
    private void drawPackReferenceReview() {
        if (!ImGui.begin("Pack resource links (partial)")) { ImGui.end();return; }
        if (ImGui.button("Close resource-link review")) { packReferenceReview.clear();showReferenceReview=false;ImGui.end();return; }
        ImGui.textWrapped("Known typed fields in dimension, region, biome, generator, expression, image-map, structure, jigsaw-pool, jigsaw-piece and block-definition JSON. Material links include authored exact aliases in known palettes, replacement find lists, deposit/decorator blocks and eight backup levels. Bare block names without a matching alias file are not missing-resource findings. Implicit defaults, registry states, string block filters and native fallback mappings are not resolved. Noise source links include known inline style contexts and nested fractures; both declared sources are listed even when native precedence overrides one. Exact file presence does not establish target-content validity, native alias resolution or generation compatibility. Unknown fields, other resource families and other references are not covered. This review cannot establish that rename, move or deletion is safe.");
        if (ImGui.button("Scan / refresh known resource links")) packReferenceReview.refresh(workspace);
        ImGui.sameLine();if (ImGui.button("Cancel / clear link scan")) packReferenceReview.clear();
        ImGui.inputText("Source, field, key or target contains",referenceReviewQuery);
        if (ImGui.beginCombo("Link state",referenceReviewView.label())) { for (var view : PackReferenceAudit.View.values()) if (ImGui.selectable(view.label(),view==referenceReviewView)) referenceReviewView=view;ImGui.endCombo(); }
        if (ImGui.checkbox("Known incoming links to current selection",reviewIncomingOnly)) reviewIncomingOnly=!reviewIncomingOnly;
        if (ImGui.button("Clear link filters")) { referenceReviewQuery.set("");referenceReviewView=PackReferenceAudit.View.ALL;reviewIncomingOnly=false; }
        var scan=packReferenceReview.poll(workspace);var audit=scan.audit();
        if (scan.phase()==AsyncPackReferenceReview.Phase.SCANNING) ImGui.textWrapped("Scanning resource links in the background... Editing and Save/Export remain available. Refresh starts a new review; Cancel discards the result.");
        else if (scan.phase()==AsyncPackReferenceReview.Phase.FAILED) ImGui.textWrapped("Resource link scan failed: "+scan.error()+". Refresh explicitly to retry.");
        else if (audit==null) ImGui.textWrapped("No scan for the current applied workspace state. Scan explicitly after New/Open/Import or an applied edit/resource change; changing filters does not rescan.");
        else {
            ImGui.textWrapped(audit.inspectedFiles()+" / "+audit.eligibleFiles()+" eligible resources inspected; "+audit.bytes()+" bytes; "+audit.skippedFiles()+" skipped files; "+audit.findings().size()+" recorded diagnostics.");
            ImGui.textWrapped(audit.count(ReferenceDiagnostics.State.EXACT_FILE)+" exact-file links; "+audit.count(ReferenceDiagnostics.State.NO_EXACT_FILE)+" no-exact-file links; "+(audit.count(ReferenceDiagnostics.State.ABSENT_REQUIRED)+audit.count(ReferenceDiagnostics.State.EMPTY_REQUIRED))+" required-field diagnostics; "+audit.count(ReferenceDiagnostics.State.INVALID_KEY)+" invalid keys; "+audit.count(ReferenceDiagnostics.State.UNSUPPORTED)+" unsupported diagnostics.");
            ImGui.textWrapped(audit.incomplete() ? "Inspection is incomplete: unsupported inputs or scan limits were encountered. Counts cover recorded diagnostics only." : "No skips/unsupported diagnostics within this scan. Reference coverage remains partial.");
            if (!audit.notices().isEmpty() && ImGui.treeNode("Skipped/truncated inspection notices")) { for (var notice : audit.notices()) { ImGui.textWrapped(PackReferenceAudit.display(notice.path()));ImGui.textWrapped(notice.message()); }ImGui.treePop(); }
            if (audit.omittedNotices()>0) ImGui.textWrapped(audit.omittedNotices()+" additional inspection notices omitted.");
            if (reviewIncomingOnly && selected==null) ImGui.textWrapped("Select a resource to filter its known incoming references.");
            else try {
                var result=packReferenceReview.query(workspace,new PackReferenceAudit.Query(referenceReviewQuery.get(),referenceReviewView,reviewIncomingOnly ? selected : null));
                if (reviewIncomingOnly) ImGui.textWrapped("Exact incoming target: "+PackReferenceAudit.display(selected));
                ImGui.textWrapped(result.matches()+" matching diagnostics; "+result.findings().size()+" shown.");
                if (result.truncated()) ImGui.textWrapped("Only the first 200 matches in source/field traversal order are shown. Narrow the filters to reach later findings.");
                int index=0;for (var finding : result.findings()) {
                    ImGui.pushID(index++);ImGui.textUnformatted(finding.state()+": "+PackReferenceAudit.display(finding.field()));ImGui.textWrapped(PackReferenceAudit.display(finding.source()));ImGui.textWrapped("Stored key / diagnostic: "+PackReferenceAudit.display(finding.key()));
                    if (finding.target()!=null) ImGui.textWrapped("Exact target: "+PackReferenceAudit.display(finding.target()));
                    if (ImGui.button("Open source resource")) queueReferenceReviewOpen(finding.source());
                    if (finding.state()==ReferenceDiagnostics.State.EXACT_FILE && finding.target()!=null) { ImGui.sameLine();if (ImGui.button("Open target resource")) queueReferenceReviewOpen(finding.target()); }
                    ImGui.popID();
                }
            } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
        }
        ImGui.textWrapped("Explicit scan limits: 512 eligible files, 8 MB total, 1 MB/file, 4,096 recorded diagnostics and 256 notices. Per-resource checks also cap links. Filtering shows up to 200 matches with 256 search characters. Long labels are shortened to 256 UTF-16 characters plus ellipsis; control characters are escaped for display. Scans run in the background against an applied snapshot. Edits cancel and invalidate the review without rescanning. The snapshot holds up to 100,000 exact file paths / 16 million UTF-16 path characters and excludes object/image payloads. Drafts, disk state and formulas are not inspected. Save/Export remain available; no data is rewritten and no native resource is loaded.");
        ImGui.end();
    }
    private void queueReferenceReviewOpen(String path) {
        resourceNavigation.request(workspace,path);referenceReviewPendingPath=path;referenceReviewPendingWorkspace=workspace;
    }
    private void drawPendingChanges() {
        if (!ImGui.begin("Pending workspace changes")) { ImGui.end();return; }
        if (ImGui.button("Close pending changes")) { showPendingChanges=false;ImGui.end();return; }
        ImGui.inputText("Pending path contains",changesQuery);
        if (ImGui.beginCombo("Pending change kind",changesFilter.label())) { for (var kind : WorkspaceChanges.Filter.values()) if (ImGui.selectable(kind.label(),kind==changesFilter)) changesFilter=kind;ImGui.endCombo(); }
        if (ImGui.button("Clear pending filters")) { changesQuery.set("");changesFilter=WorkspaceChanges.Filter.ALL; }
        ImGui.textWrapped("Applied in-memory changes since the current save baseline, including opaque/unknown files, new empty folders and removed empty folders. Unapplied inspector text is excluded. This is a pending-write inventory, not a diff or a complete scan for external disk edits.");
        ImGui.textWrapped("Active folder: "+(workspace.directory()==null ? "None" : workspace.directory().toString()));
        try {
            var result=pendingChanges.query(workspace,new WorkspaceChanges.Query(changesQuery.get(),changesFilter));
            ImGui.textWrapped(result.newFiles()+" new files; "+result.modifiedFiles()+" modified files; "+result.newFolders()+" new folders; "+result.removedFolders()+" removed empty folders; "+result.fileBytes()+" current file bytes across all pending changes.");
            ImGui.textWrapped(result.matches()+" matching changes; "+result.entries().size()+" shown in path order.");
            if (result.truncated()) ImGui.textWrapped("Only the first 200 matching changes are shown. Narrow the path/kind filters to reach other entries.");
            if (result.total()==0) ImGui.textWrapped("No applied changes are pending. Unchanged resources are not checked for external disk edits.");
            if (result.entries().stream().noneMatch(entry -> entry.path().equals(changesSelected))) changesSelected=result.entries().isEmpty() ? null : result.entries().getFirst().path();
            int index=0;for (var entry : result.entries()) {
                ImGui.pushID(index++);if (ImGui.selectable("Select change",entry.path().equals(changesSelected))) changesSelected=entry.path();ImGui.sameLine();ImGui.textUnformatted(entry.kind().label()+": "+entry.path());ImGui.popID();
            }
            var entry=result.entries().stream().filter(item -> item.path().equals(changesSelected)).findFirst().orElse(null);
            if (entry!=null) {
                ImGui.separator();ImGui.textWrapped(entry.kind().label()+": "+entry.path());
                if (entry.kind()==WorkspaceChanges.Kind.NEW_FILE || entry.kind()==WorkspaceChanges.Kind.MODIFIED_FILE) {
                    ImGui.textUnformatted(entry.bytes()+" applied bytes");
                    if (ImGui.button("Open changed resource")) { resourceNavigation.request(workspace,entry.path());changesPendingPath=entry.path();changesPendingWorkspace=workspace; }
                }
                if (ImGui.button("Check / refresh selected destination")) pendingChanges.check(workspace,entry);
                var check=pendingChanges.checked(workspace,entry);
                if (check==null) ImGui.textWrapped("Destination not checked for this workspace state.");
                else { ImGui.textWrapped("Last explicit check: "+check.status());ImGui.textWrapped(check.detail());if (check.diskBytes()>=0) ImGui.textUnformatted(check.diskBytes()+" disk bytes observed"); }
            }
            ImGui.textWrapped("Disk checks inspect only the selected destination and hash modified files up to 16 MiB, stopping with an overflow byte if a file grows. They never write, merge or discard data, and do not check free space, permissions or every save prerequisite. Filesystem changes require explicit Refresh; an earlier result cannot guarantee a later Save. Save always repeats its full preflight. Save As/Save Copy write complete snapshots to fresh folders.");
            if (ImGui.button("Save applied workspace")) perform("Save");ImGui.sameLine();if (ImGui.button("Save As fresh folder")) perform("Save As");ImGui.sameLine();if (ImGui.button("Save Copy to fresh folder")) perform("Save Copy");
        } catch (IllegalArgumentException failure) { ImGui.textWrapped(failure.getMessage()); }
        ImGui.end();
    }
    private void drawTextSearch() {
        if (!ImGui.begin("Search JSON text")) { ImGui.end();return; }
        if (ImGui.button("Close text search")) { textSearch.clear();activeTextQuery=null;showTextSearch=false;ImGui.end();return; }
        ImGui.inputText("Literal source text",textQuery);
        if (ImGui.beginCombo("Text search family",textFamily.label())) { for (var family : ResourceFinder.Family.values()) if (ImGui.selectable(family.label(),family==textFamily)) textFamily=family;ImGui.endCombo(); }
        if (ImGui.checkbox("Search applied changes only",textChangedOnly)) textChangedOnly=!textChangedOnly;
        if (ImGui.button("Search applied JSON text")) {
            try { activeTextQuery=new WorkspaceTextSearch.Query(textQuery.get(),textFamily,textChangedOnly);textSearch.retry(workspace,activeTextQuery);textSearchError=null; }
            catch (IllegalArgumentException invalid) { textSearchError=invalid.getMessage(); }
        }
        ImGui.sameLine();if (ImGui.button("Cancel / clear text search")) { textQuery.set("");activeTextQuery=null;textSearch.clear();textSearchError=null; }
        ImGui.textWrapped("Case-sensitive literal source search in .json files, including unknown/malformed JSON. Escapes are searched as stored; no regular expressions, JSON decoding, formulas or native loading. Search applies the form; results refresh in the background after applied edits. Changed-files searches also refresh after Save changes the baseline. Unapplied inspector text is excluded.");
        if (textSearchError!=null) ImGui.textWrapped(textSearchError);
        if (activeTextQuery!=null) {
            ImGui.textWrapped("Results for: "+activeTextQuery.text()+"; "+activeTextQuery.family().label()+(activeTextQuery.changedOnly() ? "; applied changes only" : ""));
            var search=textSearch.select(workspace,activeTextQuery);
            if (search.phase()==AsyncWorkspaceTextSearch.Phase.SEARCHING) ImGui.textWrapped("Searching applied JSON in the background... Editing and Save/Export remain available.");
            else if (search.phase()==AsyncWorkspaceTextSearch.Phase.FAILED) ImGui.textWrapped("Text search failed: "+search.error()+". Search again to retry.");
            var result=search.result();
            if (result!=null) {
                ImGui.textWrapped(result.matches()+" non-overlapping matches; "+result.hits().size()+" shown; "+result.scannedFiles()+" / "+result.eligibleFiles()+" eligible JSON files searched; "+result.scannedBytes()+" bytes read.");
                if (result.incomplete()) ImGui.textWrapped("Incomplete search: "+result.tooLarge()+" oversized, "+result.invalidText()+" invalid UTF-8/control-text, "+result.byteBudgetSkipped()+" byte-budget and "+result.fileBudgetSkipped()+" file-budget skips. Skipped files may contain matches.");
                if (result.truncated()) ImGui.textWrapped("Only the first 200 matches in sorted path/source order are shown; narrow the search.");
                int index=0;for (var hit : result.hits()) {
                    ImGui.pushID(index++);
                    if (ImGui.button("Open resource")) { resourceNavigation.request(workspace,hit.path());textPendingPath=hit.path();textPendingWorkspace=workspace; }
                    ImGui.sameLine();if (ImGui.smallButton("Show source match")) {
                        try { resourceNavigation.request(workspace,SourceMatch.request(workspace,activeTextQuery,hit));textPendingPath=hit.path();textPendingWorkspace=workspace; }
                        catch (Exception failure) { textSearchError=failure.getMessage(); }
                    }
                    ImGui.sameLine();ImGui.textUnformatted("Line "+hit.line()+", column "+hit.column()+" (UTF-16)");
                    ImGui.textWrapped(hit.path());
                    ImGui.textUnformatted((hit.clippedBefore() ? "..." : "")+hit.excerpt()+(hit.clippedAfter() ? "..." : ""));
                    ImGui.popID();
                }
            }
        }
        ImGui.textWrapped("Limits: 1 MiB/file, 16 MiB read, 4,096 eligible files, 200 results, 256 query characters and 160-character excerpts. The search snapshot records up to 100,000 eligible paths / 16 million UTF-16 path characters; narrow the family or changed-files filter if that limit is reached. Cancel/close discards pending work and results. Columns count decoded UTF-16 units. Open selects the resource using the draft guard. Show source match opens read-only context and its containing indexed JSON token when available; it never edits text.");
        ImGui.end();
    }
    private void drawQuickOpen() {
        if (!ImGui.begin("Quick Open resource")) { ImGui.end();return; }
        if (ImGui.button("Close Quick Open")) { showQuickOpen=false;ImGui.end();return; }
        ImGui.inputText("Path or filename",quickQuery);
        if (ImGui.beginCombo("Find family",quickFamily.label())) { for (var family : ResourceFinder.Family.values()) if (ImGui.selectable(family.label(),family==quickFamily)) quickFamily=family;ImGui.endCombo(); }
        if (ImGui.checkbox("Find applied changes only",quickChangedOnly)) quickChangedOnly=!quickChangedOnly;
        if (ImGui.button("Clear finder filters")) { quickQuery.set("");quickFamily=ResourceFinder.Family.ALL;quickChangedOnly=false; }
        ImGui.textWrapped("Case-insensitive path search. All space-separated terms must match; exact paths and filenames rank first. This lists files only and does not read their contents. Opening uses the unapplied-text guard.");
        try {
            var result=quickFinder.query(workspace,new ResourceFinder.Query(quickQuery.get(),quickFamily,quickChangedOnly));
            if (result.hits().stream().noneMatch(hit -> hit.path().equals(quickSelected))) quickSelected=result.hits().isEmpty() ? null : result.hits().getFirst().path();
            ImGui.textWrapped(result.matches()+" matches; "+result.hits().size()+" shown; "+result.indexedFiles()+" / "+result.totalFiles()+" files indexed.");
            if (result.truncated()) ImGui.textWrapped("Only the best 100 results are shown; narrow the search to find more.");
            if (result.incompleteIndex()) ImGui.textWrapped("The path index budget was reached; remaining sorted paths are unsearched. Use the resource browser for those files.");
            for (var hit : result.hits()) { ImGui.pushID(hit.path());String path=hit.path().length()>128 ? hit.path().substring(0,128)+"..." : hit.path();if (ImGui.selectable(path+(workspace.changed(hit.path()) ? " *" : ""),hit.path().equals(quickSelected))) quickSelected=hit.path();if (ImGui.isItemHovered()) ImGui.setTooltip(hit.path());ImGui.popID(); }
            if (quickSelected!=null) {
                ImGui.textWrapped(quickSelected+" — "+workspace.size(quickSelected)+" bytes");
                if (ImGui.button("Open selected resource")) { resourceNavigation.request(workspace,quickSelected);quickPendingPath=quickSelected;quickPendingWorkspace=workspace; }
            }
            ImGui.textWrapped("Limits: 100 results, eight terms / 256 search characters, 100,000 files and 16 million normalized path characters. Other files includes unsupported families. Applied changes excludes unapplied text and folder-only changes.");
        } catch (IllegalArgumentException invalid) { ImGui.textWrapped(invalid.getMessage()); }
        ImGui.end();
    }
    private void drawAppliedHistory() {
        if (!ImGui.begin("Applied edit history")) { ImGui.end();return; }
        if (ImGui.button("Close history review")) { showAppliedHistory=false;ImGui.end();return; }
        ImGui.textWrapped("Review retained applied byte edits. Before/after text describes one edit, not the complete destination of a multi-step restore. Undo/Redo through an edit includes intervening edits across resources and uses the unapplied-text guard. Nothing is written to disk until Save.");
        ImGui.textWrapped(workspace.undoCount()+" undo / "+workspace.redoCount()+" redo; "+workspace.historyNote());
        if (reviewedWorkspace!=workspace) { reviewedWorkspace=workspace;reviewedHistoryId=-1; }
        var undo=workspace.historySteps(false);var redo=workspace.historySteps(true);
        drawAppliedHistoryChoices("Undo timeline (next first)",undo);drawAppliedHistoryChoices("Redo timeline (next first)",redo);
        var active=java.util.stream.Stream.concat(undo.stream(),redo.stream()).filter(step -> step.id()==reviewedHistoryId).findFirst().orElse(null);
        if (active==null) reviewedHistoryId=-1;
        var comparison=editReviews.inspect(workspace,reviewedHistoryId);
        if (comparison!=reviewedComparison) { reviewedComparison=comparison;reviewedBefore=comparison==null ? null : new ImString(comparison.before().text(),Math.max(128,comparison.before().text().getBytes(StandardCharsets.UTF_8).length+1));reviewedAfter=comparison==null ? null : new ImString(comparison.after().text(),Math.max(128,comparison.after().text().getBytes(StandardCharsets.UTF_8).length+1)); }
        if (active!=null && comparison!=null) {
            ImGui.textWrapped("Edit #"+active.id()+": "+active.path());
            ImGui.textWrapped((active.forward() ? "Redo " : "Undo ")+active.steps()+" applied edit(s), including the selected edit. Intervening edits are listed below; the text panes show only this selected edit.");
            if (ImGui.treeNode("Affected edits (next first)")) { for (var step : active.forward() ? redo : undo) if (step.steps()<=active.steps()) ImGui.textWrapped("#"+step.id()+": "+step.path());ImGui.treePop(); }
            if (ImGui.beginTabBar("Retained edit source review")) {
                if (ImGui.beginTabItem("Line changes")) { drawAppliedHistoryDiff(comparison);ImGui.endTabItem(); }
                if (ImGui.beginTabItem("Before / after source")) { drawAppliedHistoryText("Before applied edit",comparison.before(),reviewedBefore);drawAppliedHistoryText("After applied edit",comparison.after(),reviewedAfter);ImGui.endTabItem(); }
                ImGui.endTabBar();
            }
            if (ImGui.button((active.forward() ? "Redo " : "Undo ")+active.steps()+" edit(s) through #"+active.id())) {
                try { transition(false,() -> { selected=active.forward() ? workspace.redoThrough(active.id()) : workspace.undoThrough(active.id());filter.set("");error=null; }); }
                catch (Exception failure) { error=failure.getMessage(); }
            }
        } else { editDiffs.clear();reviewedDiff=null;diffLineText=null;diffSelected=-1;diffBufferedRow=-1;ImGui.textWrapped("Choose an available edit to review. Cleared, evicted and discarded Redo entries cannot be restored."); }
        ImGui.textWrapped("Text display: at most 64 KiB encoded and 16,384 UTF-16 characters per side. Binary/control-byte, invalid UTF-8 and larger snapshots show metadata only. Snapshot bytes remain unchanged; no JSON or native source is parsed or evaluated. Unsupported world previews remain disabled.");
        if (error!=null) ImGui.textWrapped(error);ImGui.end();
    }
    private void drawAppliedHistoryChoices(String label,List<AppliedEditReview.Step> steps) {
        ImGui.beginDisabled(steps.isEmpty());
        if (ImGui.beginCombo(label,steps.isEmpty() ? "No retained edits" : "Choose an edit")) {
            for (var step : steps) { ImGui.pushID(Long.toString(step.id()));String path=step.path().length()>128 ? step.path().substring(0,128)+"..." : step.path();if (ImGui.selectable(step.steps()+". #"+step.id()+" "+path+" ("+step.beforeBytes()+" -> "+step.afterBytes()+" bytes)",step.id()==reviewedHistoryId)) reviewedHistoryId=step.id();ImGui.popID(); }
            ImGui.endCombo();
        }ImGui.endDisabled();
    }
    private void drawAppliedHistoryDiff(AppliedEditReview.Comparison comparison) {
        var diff=editDiffs.inspect(comparison);
        if (diff!=reviewedDiff) { reviewedDiff=diff;diffPage=0;diffSelected=-1;diffBufferedRow=-1;diffLineText=null; }
        ImGui.textWrapped(diff.detail());
        if (!diff.compared()) { if (diff.status()==AppliedEditDiff.Status.LIMITED) ImGui.textUnformatted(diff.beforeLines()+" before / "+diff.afterLines()+" after lines; no partial comparison.");return; }
        ImGui.textUnformatted(diff.added()+" added / "+diff.removed()+" removed / "+diff.unchanged()+" unchanged lines in this edit.");
        if (ImGui.checkbox("Show unchanged lines",diffShowUnchanged)) { diffShowUnchanged=!diffShowUnchanged;diffPage=0; }
        var page=AppliedEditDiff.page(diff,diffShowUnchanged,diffPage);diffPage=page.page();
        ImGui.textUnformatted(page.matches()+" matching lines; page "+(page.page()+1)+"; "+page.rows().size()+" shown.");
        ImGui.beginDisabled(!page.previous());if (ImGui.smallButton("Previous change lines")) diffPage--;ImGui.endDisabled();ImGui.sameLine();ImGui.beginDisabled(!page.next());if (ImGui.smallButton("Next change lines")) diffPage++;ImGui.endDisabled();
        if (!page.rows().contains(diffSelected)) diffSelected=page.rows().isEmpty() ? -1 : page.rows().getFirst();
        for (int index : page.rows()) {
            var row=diff.rows().get(index);ImGui.pushID(index);if (ImGui.smallButton(diffSelected==index ? "Selected line" : "Select line")) diffSelected=index;ImGui.sameLine();
            ImGui.textUnformatted(row.kind()+"  before "+(row.beforeLine()==0 ? "—" : row.beforeLine())+" / after "+(row.afterLine()==0 ? "—" : row.afterLine())+"  ["+row.ending()+"]  "+InspectorText.label(row.text(),256));ImGui.popID();
        }
        if (diffSelected>=0) {
            var row=diff.rows().get(diffSelected);if (diffBufferedRow!=diffSelected) { diffBufferedRow=diffSelected;diffLineText=jsonReadOnlyBuffer(row.text()); }
            ImGui.textWrapped("Selected original line: "+row.kind()+"; ending "+row.ending()+" (NONE means no final newline). Tabs, spaces and raw escapes remain literal. Long list labels are shortened; the full retained line is below.");
            ImGui.inputTextMultiline("Selected retained source line",diffLineText,-1,100,ImGuiInputTextFlags.ReadOnly);
        } else ImGui.textWrapped("No changed lines in this view. Enable Show unchanged lines to inspect matching text.");
        ImGui.textWrapped("Line comparison is read-only and limited to complete text up to 64 KiB, 16,384 UTF-16 characters and 512 lines per side; up to 200 rows are shown per page. The Before / after source tab remains available. This compares one retained edit, not a multi-step restore destination; no JSON or native data is interpreted.");
    }
    private void drawAppliedHistoryText(String label,AppliedEditReview.Text text,ImString buffer) {
        ImGui.textWrapped(label+": "+text.bytes()+" bytes"+(text.truncated() ? " (text truncated; full bytes retained)" : ""));
        if (text.kind()==AppliedEditReview.Kind.TEXT) ImGui.inputTextMultiline(label,buffer,-1,150,ImGuiInputTextFlags.ReadOnly);
        else ImGui.textWrapped(switch (text.kind()) { case TOO_LARGE -> "Text display skipped above the encoded-byte limit.";case INVALID_UTF8 -> "Text display skipped because the snapshot is not strict UTF-8.";case BINARY -> "Text display skipped because the snapshot contains binary/control bytes.";default -> "Text unavailable."; });
    }
    private void restoreAppliedEdit(boolean forward) {
        try { transition(false,() -> { selected=forward ? workspace.redo() : workspace.undo();filter.set("");error=null; }); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void queueHistoryNavigation(boolean forward,int steps) {
        try { resourceNavigation.request(workspace,navigationHistory.plan(workspace,forward,steps)); }
        catch (Exception failure) { error=failure.getMessage(); }
    }
    private void drawNavigationDestinations(String label,List<NavigationHistory.Destination> entries,boolean forward) {
        if (ImGui.beginMenu(label)) { for (var entry : entries) { ImGui.pushID(entry.steps());String path=entry.location().path();if (ImGui.menuItem(entry.steps()+". "+(path.length()>128 ? path.substring(0,128)+"..." : path))) queueHistoryNavigation(forward,entry.steps());if (ImGui.isItemHovered()) ImGui.setTooltip(path+"\nSearch: "+entry.location().filter());ImGui.popID(); }ImGui.endMenu(); }
    }
    private void selectResource(String path) {
        try { resourceNavigation.request(workspace,path); }
        catch (Exception failure) { error = failure.getMessage(); }
    }
    private boolean discardArrayDrafts(String field, String appliedKey) {
        return discardListDrafts(field, key -> key.equals(appliedKey));
    }
    private boolean discardListDrafts(String field, java.util.function.Predicate<String> included) {
        boolean other = values.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(field + "#")
                && !included.test(entry.getKey()) && drafts.contains(entry.getKey()));
        return !other || JOptionPane.showConfirmDialog(null, "This discards other unapplied text in the affected settings. Continue?",
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
    private void drawZipExport() {
        if (!ImGui.begin("Captured ZIP export")) { ImGui.end();return; }
        if (ImGui.button("Hide ZIP export progress")) { showZipExport=false;ImGui.end();return; }
        var state=zipExport.poll();ImGui.textWrapped("Export uses the applied workspace snapshot captured when Export was requested. Later edits, Save, selection changes and New/Open do not change that snapshot. Unapplied inspector drafts are excluded and remain available. Export never marks the workspace saved or adopts a disk baseline.");
        if (state.source()!=null) { var source=state.source();ImGui.textWrapped("Captured applied revision: "+source.revision()+"; "+source.files()+" files, "+source.directories()+" explicit folders, "+source.bytes()+" uncompressed bytes.");ImGui.textWrapped("Captured pack folder: "+(source.activeDirectory()==null ? "Not saved to a folder" : InspectorText.label(source.activeDirectory().toString(),512))); }
        if (state.archive()!=null) ImGui.textWrapped("Chosen ZIP: "+InspectorText.label(state.archive().toString(),512)+(state.replace() ? " (confirmed replacement)" : " (new file only)"));
        if (state.phase()==AsyncZipExport.Phase.EXPORTING || state.phase()==AsyncZipExport.Phase.CANCELLING || state.phase()==AsyncZipExport.Phase.PUBLISHING) {
            ImGui.textUnformatted("Completed entries: "+state.entries()+"; uncompressed bytes written: "+state.bytes());
            if (state.phase()==AsyncZipExport.Phase.PUBLISHING) ImGui.textWrapped("Publishing the completed ZIP. Publication has begun and cannot be cancelled.");
            else if (state.phase()==AsyncZipExport.Phase.CANCELLING) ImGui.textWrapped("Cancellation requested. Waiting for the worker to close and remove its temporary file.");
            else if (ImGui.button("Cancel export before publication")) zipExportMessage=zipExport.cancel() ? "Cancellation accepted before publication." : "Export cannot be cancelled now; review its result.";
        } else {
            if (state.phase()==AsyncZipExport.Phase.SUCCEEDED) { ImGui.textWrapped("ZIP export completed. The receipt describes the captured snapshot, which may differ from current applied edits.");ImGui.textWrapped("Published ZIP: "+InspectorText.label(state.receipt().archive().toString(),512));ImGui.textUnformatted("ZIP bytes: "+state.receipt().bytes());ImGui.textWrapped("ZIP SHA-256: "+state.receipt().sha256()); }
            else if (state.detail()!=null) ImGui.textWrapped(state.detail());
            else ImGui.textWrapped("No active export. File > Export captures applied values and starts a background ZIP job.");
            if (ImGui.button("Export current applied workspace")) perform("Export");
            if (ImGui.button("Clear completed export receipt")) { zipExport.clear();zipExportMessage=null; }
        }
        if (zipExportMessage!=null) ImGui.textWrapped(zipExportMessage);
        ImGui.textWrapped("One export at a time. Snapshot limits: 512 MiB applied bytes, 100,000 file/folder entries and 16 million UTF-16 path characters. Compression, disk writes, existing-target conflict hashes and ZIP hashing run on the worker. The completed archive is staged beside its destination; existing targets are never truncated. Confirmed replacement requires an unchanged regular file of at most 1 GiB and filesystem support for atomic replacement. New targets never intentionally overwrite occupied files. Source/destination symbolic-link paths and targets inside the captured pack are rejected. Cancellation is accepted only before publication starts; hiding this window keeps the job running. Process termination can leave temporary files if cleanup cannot finish. Unsupported previews stay disabled.");
        ImGui.end();
    }
    private void perform(String action) {
        try {
            switch (action) {
                case "New" -> {
                    String name = JOptionPane.showInputDialog(null, "Pack key (letters a-z, digits, _ or -):", "new_pack");
                    if (name == null) return;
                    PackWorkspace next = StarterPack.create(name);
                    String dimension = "dimensions/" + StarterPack.normalizeName(name) + ".json";
                    transition(true, () -> { workspace = next; selected = dimension;filter.set(""); });
                }
                case "Open", "Import" -> {
                    Path path = choose(action.equals("Open"), false);
                    if (path != null) transition(true, () -> {
                        PackWorkspace next = action.equals("Open") ? PackWorkspace.open(path) : PackWorkspace.importZip(path);
                        workspace = next; selected = null;filter.set("");
                    });
                }
                case "Save" -> { if (!appliedValuesOnlyAllowed() || !saveWorkspace()) return; }
                case "Save As", "Save Copy" -> {
                    if (!appliedValuesOnlyAllowed()) return;
                    Path path=choose(true,true);if (path==null) return;
                    if (action.equals("Save As")) workspace.saveAs(path);else workspace.saveCopy(path);
                }
                case "Export" -> {
                    if (zipExport.busy()) { showZipExport=true;return; }
                    if (!appliedValuesOnlyAllowed()) return;Path path=choose(false,true);if (path==null) return;
                    boolean exists=java.nio.file.Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (exists && JOptionPane.showConfirmDialog(null,"Replace existing ZIP after the captured export completes?\n"+path,"Export",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
                    zipExport.start(workspace,path,exists);zipExportMessage=null;showZipExport=true;
                }
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
