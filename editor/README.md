# Editor foundation

Windows x64 / Java 25 only. Native previews remain unavailable.

Build with Gradle 9.5.1: `gradle -p editor workspaceTest run`.

The shell has five File actions, three panels, folder/ZIP storage and JSON inspection. Existing top-level dimension `fluidHeight` and `dimensionAngleDeg` fields now have numeric edit/apply controls. Constraints/default descriptors are extracted from the pinned official IrisDimension definitions, with reproducible extraction checked in Windows CI. An audited UI allowlist permits only these terrain-affecting fields; other and unknown fields remain read-only and their coverage count is shown. This is initial field-definition integration, not full schema coverage.

Edits splice only the selected value token. Unknown properties, whitespace, line endings and absent optional fields remain unchanged. Invalid/ambiguous JSON stays inspectable and saveable; structured editing rejects it. Preview errors do not gate save/export.

Workspace and edit contracts pass locally. Windows compilation, workspace/edit contracts and pinned field-definition regeneration passed in run 40378433658. Full official schemas, optional-field enablement, nested/array controls, typed resource operations, image viewing, valid New starter-pack generation, Windows-only distribution packaging and visual verification remain unfinished.

Resource browsing groups files into folders (including empty folders), filters paths without regard to case, and marks applied changes. Closing or replacing a dirty workspace offers Save / Discard / Cancel; cancelling a save or encountering a save error keeps the workspace open. Numeric text must be applied before it becomes a workspace change. Unsupported preview controls are visibly disabled. Windows compilation and resource-index/workspace/edit contracts passed in run 40379021579. These UI interactions still require visual Windows verification.
