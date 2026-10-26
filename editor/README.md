# Editor foundation

Windows x64 / Java 25 only. Native previews remain unavailable.

Build with Gradle 9.5.1: `gradle -p editor workspaceTest run`.

The shell has five File actions, three panels, folder/ZIP storage and JSON inspection. Top-level dimension numeric controls cover `fluidHeight`, `dimensionAngleDeg`, `landChance` and `objectSurfaceSupportBuffer`. Boolean controls cover `bedrock`, `decorate`, `carvingEnabled`, `postProcessing`, `postProcessingSlabs`, `postProcessingWalls` and `requireObjectSurfaceSupport`. Constraints/default descriptors are extracted from the pinned official IrisDimension definitions, with reproducible extraction checked in Windows CI. An audited UI allowlist permits only these generation fields; other and unknown fields remain read-only and their coverage count is shown. This is initial field-definition integration, not full schema coverage.

Edits splice only the selected value token. Unknown properties, whitespace, line endings and absent optional fields remain unchanged. Invalid/ambiguous JSON stays inspectable and saveable; structured editing rejects it. Preview errors do not gate save/export.

Workspace and edit contracts pass locally. Windows compilation, workspace/edit contracts and pinned field-definition regeneration passed in run 40378433658. Full official schemas, nested/array controls, typed resource operations, image viewing, valid New starter-pack generation, Windows-only distribution packaging and visual verification remain unfinished.

Resource browsing groups files into folders (including empty folders), filters paths without regard to case, and marks applied changes. Closing or replacing a dirty workspace offers Save / Discard / Cancel; cancelling a save or encountering a save error keeps the workspace open. Numeric text must be applied before it becomes a workspace change. Unsupported preview controls are visibly disabled. Windows compilation and resource-index/workspace/edit contracts passed in run 40379021579. These UI interactions still require visual Windows verification.

Absent allowlisted scalar fields show Enable, which explicitly inserts the pinned Iris default; viewing never materializes defaults. Boolean toggles apply immediately, while numeric inputs require Apply. Type-mismatched existing booleans remain read-only. Full nested/schema coverage remains pending. Windows compilation, workspace/edit/resource-index contracts and source-definition regeneration passed for this expanded batch in run 40379509569. Visual GUI verification remains pending.

Nested scalar controls cover `importedStructures.undergroundYShift`, `importedStructures.datapackOverrides` and `importedFeatures.enabled`. Explicit Enable inserts a missing leaf (and its parent object when absent), with defaults derived from the corresponding pinned official classes. Existing snippet/string/null/array parents remain read-only. Hover field help for official source descriptions. Native feature/structure settings affect saved pack configuration only; unsupported previews remain disabled. Arrays and other nested fields remain read-only. Windows compilation, workspace/edit/resource-index contracts and three-source definition regeneration passed in run 40380141923. Visual GUI verification remains pending.
