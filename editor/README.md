# Editor foundation

Windows x64 / Java 25 only. Native previews remain unavailable.

Build with Gradle 9.5.1: `gradle -p editor workspaceTest run`.

The shell has five File actions, three panels, folder/ZIP storage and JSON inspection. Existing top-level dimension `fluidHeight` and `dimensionAngleDeg` fields now have numeric edit/apply controls. Constraints/default descriptors are extracted from the pinned official IrisDimension definitions, with reproducible extraction checked in Windows CI. An audited UI allowlist permits only these terrain-affecting fields; other and unknown fields remain read-only and their coverage count is shown. This is initial field-definition integration, not full schema coverage.

Edits splice only the selected value token. Unknown properties, whitespace, line endings and absent optional fields remain unchanged. Invalid/ambiguous JSON stays inspectable and saveable; structured editing rejects it. Preview errors do not gate save/export.

Workspace and edit contracts pass locally. The previous shell's Windows compilation/workspace CI passed; the new control build is pending. Full official schemas, optional-field enablement, nested/array controls, typed resource operations, image viewing, unsaved-close handling, valid New starter-pack generation, Windows-only distribution packaging and visual verification remain unfinished.
