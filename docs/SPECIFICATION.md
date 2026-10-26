# Iris Visualizer — Development Prompt

## 1. Purpose

Build a standalone desktop application for visualizing and editing pack configurations for Iris, the dimension engine.

- The application simulates and previews what Minecraft terrain and other generated voxels would look like with a given Iris pack configuration.
- It must not require a running Minecraft client or server.
- It changes generation behavior through values available in the pack's JSON configuration; it is not a voxel editor.

## 2. Technology and Iris Compatibility

### Technology

- **Language:** Java 25.
- **Rendering:** LWJGL.
- **UI:** ImGui.

### Supported platform and distribution

- **Windows x64 is the only supported platform.** Do not implement or package support for Linux, macOS, or other platforms.
- Require a compatible Java 25 x64 runtime to be installed on the user's computer.
- Do not bundle or automatically install a Java runtime. Provide clear launch instructions and a clear message when the required runtime is unavailable.

### Version requirements

- Use the latest Iris version and support only the latest Iris pack format.
- Do not implement backward compatibility for older pack versions. Users are responsible for updating their packs before opening them.
- Record the exact Iris version and source revision used for each application build and its generation-parity tests, so results are reproducible.
- When updating to a newer Iris version, update the comparison baseline without adding support for older pack formats.

### References

- **Iris documentation:** https://github.com/VolmitSoftware/docs/tree/master/iris
- **Iris repository:** https://github.com/VolmitSoftware/Iris
- **Example Iris Overworld pack:** https://github.com/IrisDimensions/overworld

## 3. Scope and Restrictions

### Editing scope

- Only implement pack editing for settings that affect how voxels are generated in the world.
- Other settings and resources must be read-only.
- Required file operations, validation, resource inspection, and preview controls are supporting application functionality, not additional generation features.
- Keep file management, read-only viewing, and editor workflows in the UI and simple pack-file handling code. Do not add these features to the generation engine or increase its complexity to support them.

### Exclusions

- Do not implement a voxel editor or image editor.
- Do not implement any kind of file-history feature, including backups, version snapshots, autosave recovery, crash-recovery copies, or temporary recovery files.
- Do not implement support for blocks added by mods or external plugins.

### Block compatibility

- Support vanilla Minecraft blocks and their block states, including Iris-defined aliases that reference vanilla block states.
- If a pack references an unsupported external block, show a clear warning, preserve the original configuration, and do not silently substitute another block.

### Rendering fidelity and assets

- Target recognizable vanilla Minecraft rendering with correct block geometry and block states.
- Support non-cube models, state-dependent orientation, transparent and cutout materials, fluids, and biome tinting.
- Use simple lighting initially. Advanced shadows, reflections, and shader effects are outside the initial rendering scope; the reference images do not require them.
- Obtain vanilla models and textures from a user-selected Minecraft installation or compatible client archive, matched to the Minecraft version targeted by the pinned Iris build. Reading these assets must not require a running Minecraft client or server.
- Keep rendering limitations separate from generation limitations. If a generated vanilla block has a missing or unsupported visual model, display a clearly marked visual placeholder and report the limitation while preserving its original generated block ID and state data.

## 4. Architecture

The application has three main parts:

1. **Engine:** Handles generation backend work. Focus on loading supported pack generation data and producing block, biome, and noise output. Reuse the official Iris core and generation code wherever feasible to reduce development time and maintain native generation behavior while remaining standalone. Do not put resource-management tools, file workflows, or unrelated gameplay systems into the engine.
2. **UI:** Bridges the user's pack configuration edits to the backend. Keep resource management and editor workflows outside the generation engine.
3. **Renderer:** Displays the data produced by the engine.

Implement any functionality needed as development progresses, within the scope and restrictions above.

Before committing to full engine development, verify with a small proof of concept that the official Iris core can initialize and generate representative terrain in a standalone application without a running Minecraft client or server. Identify any platform-dependent code or services and determine whether a minimal standalone adapter can satisfy them. If doing so would require substantial reimplementation of Iris generation, report that limitation and seek direction rather than silently building a custom replacement engine.

## 5. Development Order and Testing

Develop and comprehensively test each part in this order:

1. **Engine first.** Begin with the standalone Iris-core feasibility proof of concept described in Section 4. Once feasible, test with the example Iris Overworld pack to ensure the output matches native Iris for supported vanilla-compatible configurations. Compare generated block and biome data using identical pack content, Iris version, seeds, and world coordinates. Explicitly report unsupported content and never claim exact parity when it prevents a faithful preview.
2. **UI second.** Test with the example Iris Overworld pack to ensure all supported resource editing works correctly, including saving and reopening edited resources.
3. **Renderer third.** Test that rendering correctly displays the engine's output. Verify that rendered blocks and biomes agree with the engine's generated data.
4. **Final integration testing.** Test the complete program with the example Iris Overworld pack and fix remaining issues. Include these end-to-end scenarios:
   - Open a pack, edit a biome, regenerate its preview, save it, reopen it, and confirm the changes persist.
   - Rename or move a resource and verify that its references remain valid.
   - Import and export a ZIP and verify that unsupported properties and resources are preserved.
   - Make several rapid edits with automatic regeneration enabled and verify that the final preview reflects the latest configuration.
   - Switch between dimension context and isolated resource previews, and confirm that temporary preview overrides are never written to the pack.
   - Save and export a pack with preview-validation errors, then reopen or reimport it and confirm that its data is preserved and the errors did not prevent either operation.
   - Open and save a pack without editing a resource, and confirm that untouched files remain byte-for-byte identical and absent optional fields remain absent.

## 6. Performance

- Implement straightforward performance optimizations where possible, especially in rendering.
- Use hidden-face culling, frustum culling, greedy meshing for compatible adjacent voxel faces, and mesh caching when appropriate.
- Perform expensive generation work asynchronously so the UI remains responsive.
- Optimizations must not alter generated block or biome data or compromise deterministic compatibility with Iris.

### Preview limits and defaults

| Item | Initial default or requirement |
| --- | --- |
| 3D terrain preview area | 128 × 128 blocks horizontally. |
| Vertical generation range | The full configured dimension height; do not silently truncate it. |
| 2D terrain, biome, and noise map output | 512 × 512 samples, with configurable world-space sampling scale. Original-image previews retain the source image's dimensions. |
| Generation scheduling | At most one active generation task per preview. |
| Memory | Explicitly bounded generation and mesh caches. |

- Confirm the initial preview defaults and establish concrete cache budgets during the feasibility proof of concept. Report any necessary adjustments before changing the specified defaults.
- If a preview request exceeds the available memory budget, report the limit and let the user explicitly reduce its area or resolution. Do not silently omit generation features or change generation semantics.
- Sampling scale changes how a map is displayed and sampled; it must not change the underlying Iris generation behavior.

## 7. UI Layout and Style

- Look at the 2 provided images for a general example look and feel of the UI, these images are just general examples not a request to make a 1:1 representation of them.
- Use a **white, seamless, simple** interface.
- Do not add needless or redundant UI elements or text.
- Use a three-panel layout:
  - **Left:** Resource management.
  - **Middle:** 2D and 3D visualization.
  - **Right:** Editing the selected resource.

### 7.1. Left Panel — Resource Management

Display pack resources in a hierarchical tree. Users can create, duplicate, rename, move, and delete editable resources.

| Action | Interaction |
| --- | --- |
| Move a resource | Drag it within its base resource folder. |
| Rename a resource | Select it and press **F2**. |
| Delete a resource | Select it and press **Delete**. |
| Create a resource | Hold **Ctrl** and right-click a base resource folder or a subfolder within that base folder. |
| Duplicate a resource | Hold **Ctrl** and left-click an existing resource. |
| Create a subfolder | Hold **Ctrl** and middle-click a folder. |

Resource safety and restrictions:

- Keep references valid when renaming or moving resources, and handle references safely when deleting them.
- Use Iris's typed resource-reference rules when updating references; do not replace matching strings indiscriminately throughout the pack.
- Automatic maintenance of known reference fields is permitted in otherwise read-only JSON when required by an allowed rename or move. Change only those reference fields and preserve all unrelated data.
- If references cannot be resolved confidently, block the rename or move and explain why.
- Block deletion of a referenced resource until its references have been removed or redirected. Do not silently delete referencing content.
- Follow the current Iris pack layout and resource-key rules when creating or moving resources. In particular, dimension JSON files must remain directly inside the base `dimensions/` folder, not nested in subfolders. Prevent operations that would produce an invalid Iris resource location.
- Resources that do not affect voxel generation must remain visible but read-only, including their file operations, except for the explicitly permitted binary/image file operations and automatic reference maintenance described here.
- Binary Iris objects (`.iob`) and image files may be managed using file operations, but their contents must not be edited by the application.

#### Resource Types

- Every resource must have a resource type determined by its base resource folder.
- Resources inside subfolders inherit the resource type of their base folder.
- Each resource type must have its own set of configuration elements, derived from the corresponding Iris definitions and schemas.
- Use the same pinned Iris version and source revision as the generation backend when obtaining resource-type definitions and configuration schemas. Reuse official Iris definitions or schema-generation mechanisms wherever practical, maintaining a single authoritative source rather than a separate manually duplicated set of configuration elements.
- When a resource is selected, the resource editor must use its type to determine which elements to display, including optional fields and their default values.
- UI-to-engine communication must identify the resource type and resource key so the engine can correctly interpret the resource.
- Resource type definitions and editor element handling belong in the UI. Do not introduce unnecessary resource-editing functionality into the generation engine.
- All configuration elements supported by the current Iris version must be accounted for, while unknown properties remain preserved.

#### Editable-field classification

- Derive field structure, types, defaults, and constraints from the official Iris definitions and schemas.
- Maintain a small, audited UI-side editor policy that classifies known fields as generation-related and editable, or unrelated to generation and read-only, with a reason for each classification.
- Store only classifications and reasons in this policy. Do not duplicate Iris schemas, defaults, or constraints in it.
- Include a development-time coverage check that reports every known field lacking a classification. This checks editor implementation coverage, not whether a user's pack may be saved or exported.
- Preserve unknown properties and display them read-only; do not guess their generation semantics.
- Keep absent optional fields absent until the user explicitly enables or edits them. Displaying a default must not insert that value into the JSON.
- Distinguish an inherited or implicit default from an explicitly stored value. Opening, inspecting, or saving an otherwise unedited resource must not materialize defaults or otherwise rewrite it.

### 7.2. Middle Panel — Visualization

#### Top controls

- **Top left:** Two tabs labeled **2D** and **3D**. Clicking a tab switches to the corresponding preview.
- **Top middle:** Compact, configurable preview seed and world-coordinate controls, displayed only when relevant to the selected resource. These must allow users to reproduce the same generation result while editing.
- **Top right:** An **Update Preview** button, immediately followed on its right by a checkbox for automatic regeneration.

#### Preview context and isolated resources

- Use a selected dimension as the context for previews that depend on world-generation configuration. Retain its seed, coordinates, height limits, and dependencies.
- Automatically select the dimension when only one is applicable. When several are applicable, provide a compact dimension-context selector alongside the relevant preview controls.
- For biome and region previews, reuse Iris's native isolation controls where available through temporary, in-memory preview overrides. Do not reimplement biome or region selection in the renderer.
- Keep preview overrides separate from editable pack data and never include them in saved or exported packs unless the user independently edits the corresponding pack setting.
- Clearly identify isolated previews and distinguish them from natural placement in the selected dimension. Explain relevant native isolation limitations, such as a forced biome being evaluated in a different placement role.
- When a generator, noise expression, procedural tree, or other selected resource requires additional context, use its applicable context and dependencies from the selected dimension. Do not silently invent missing configuration.
- If the necessary context or dependency is missing, explain what is required and disable only the affected preview. Saving and exporting must remain available.

#### Preview navigation

- **2D:** Displays terrain or biome maps and 2D noise maps, depending on the selected resource. Left-click and drag to pan around the preview.
- **3D:** Displays generated voxel data with a movable camera. Depending on the selected resource, this may show the generated world, 3D noise, procedural objects, or procedural trees.

#### Preview content by resource type

| Selected resource | 2D preview | 3D preview |
| --- | --- | --- |
| Dimensions | Terrain and biome map | Generated world |
| Regions | Biome distribution | Generated terrain for the region |
| Biomes | Terrain heightmap | Generated biome terrain |
| Generators | Noise map | Generated terrain shape |
| Noise styles and expressions | Noise map | 3D noise visualization |
| 3D terrain profiles | Not required | Volumetric terrain |
| Objects and procedural trees | Not required | Individual object or generated tree |
| Images | Original image | Not required unless an applicable generation resource uses the image |

- Keep both tabs visible. If a resource has no meaningful preview in one mode, disable that tab and do not show stale content from a previously selected resource.
- Use the official Iris core to calculate and sample 3D noise. The renderer must visualize the resulting data without independently reimplementing noise generation or changing Iris's generation semantics.

#### Preview regeneration

- Clicking **Update Preview** manually regenerates the preview to reflect current changes.
- When the adjacent checkbox is checked, enable automatic regeneration.
- Poll for relevant changes no more than **10 times per second**. Regenerate only when a relevant change is detected; do not regenerate unnecessarily when nothing has changed.
- Prevent overlapping regeneration tasks for the same preview.
- Combine rapid edits into the latest requested update, discard results based on outdated settings, and ensure the displayed preview ultimately reflects the newest configuration without freezing the UI.

### 7.3. Right Panel — Resource Editing

- Show controls for the selected resource, such as dropdown selectors, text fields, numeric fields, and sliders.
- For numeric values with a small minimum-to-maximum range, use a slider; otherwise use a numeric field.
- Group similar settings together.
- Generate controls from current Iris configuration definitions and schemas, respecting property types, defaults, valid ranges, references, optional fields, nested objects, and arrays.
- Show fields unrelated to voxel generation as read-only, subject only to automatic reference maintenance allowed in Section 7.1.
- Apply the editable-field classifications and optional/default-value behavior defined in Section 7.1.
- Treat Iris binary object geometry (`.iob`) and image contents (`.png`) as view-only. Do not edit their voxels or pixels.
- Keep editable the JSON settings that affect voxel generation through those assets, such as object placement or image-driven terrain.

## 8. Pack Files and Data Integrity

- Preserve unknown JSON properties, unsupported resources, and the original pack structure when opening, editing, saving, importing, or exporting.
- Validate pack configurations and references strictly to determine whether a preview can be generated faithfully. Validation results must not prevent opening, editing, saving, importing, or exporting a pack.
- Clearly report preview-validation errors and warnings, identifying the affected resources or fields. Block only previews that cannot be generated meaningfully; preserve all pack data and keep Save and Export available.
- Do not require users to fix validation errors, remove unsupported content, or pass an additional validation-confirmation step before saving or exporting.
- Preserve untouched files byte-for-byte. In edited JSON, preserve unknown properties and unrelated values. Preserve asset bytes, resource paths, and the pack's directory structure except where the user explicitly changes them.
- ZIP exports must preserve file contents and directory structure; identical ZIP compression, entry ordering, and archive metadata are not required.
- Do not silently repair, normalize, discard, or rewrite invalid or unsupported content as a condition of saving or exporting.
- Confirm before closing or replacing a pack with unsaved changes.
- Do not implement file history of any kind, including backups, version snapshots, autosave recovery, crash-recovery copies, or temporary recovery files. These exclusions also apply to saving and exporting.

## 9. File Menu

Place a menu bar at the top with a **File** menu containing exactly these five actions:

1. **New:** Create a new Iris pack.
2. **Open:** Open an existing Iris pack.
3. **Save:** Save the current Iris pack.
4. **Import:** Open a zipped Iris pack.
5. **Export:** Save the current Iris pack as a ZIP.

