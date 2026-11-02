# UI implementation closure and validation scope

UI feature implementation is closed at a22a62e117211db06f2a1c38f556082d1b3ae07e. The project remains in the UI stage while its complete regression, example-pack and Windows workflow validation runs. Native generation/rendering and automatic generation remain disabled under the user's agreed scope. Renderer work has not started.

The original SPECIFICATION.md sections 7–9 are implemented through the existing white three-panel shell, exact five-action File menu, typed hierarchical file operations, generation-only controls, optional/default omission, original-image inspection, preview request controls and guarded storage workflows. Current coverage accounts for 1,462 pinned fields across 150 models: 1,237 implemented generation fields and 225 read-only fields, with zero unclassified or missing policies. Known unsupported shapes, lookup modes, contexts and external recipe semantics retain the documented guards in RESOURCE-OPERATIONS.md and FIELD-POLICY.md. Preview dependency/native readiness remains explicitly unavailable; Save and Export do not depend on it.

Validation now starts, as authorized after implementation completion:

1. Compile and execute the complete WorkspaceTests suite, fixing any regressions it exposes.
2. Exercise the exact locked Overworld pack through representative supported edits, untouched-byte checks, Save/reopen, Save Copy, Save As and ZIP import/export. Report protected or unavailable cases explicitly.
3. Run the complete Windows editor workflow, including pinned source regeneration/drift checks, the 1,778-source consumer audit, field policy, all Java contracts, Windows-only packaging and launcher/runtime/native-library checks.
4. Run the repository's Windows feasibility workflow and record every matrix result without claiming native generation parity from UI tests.

Interactive Windows mouse/keyboard, mixed-DPI, image GPU and visual layout behavior still requires a suitable Windows desktop. Automated and source-level results will be reported separately. Successful compilation alone is not a validation pass, and the UI stage remains open until its validation results are resolved and recorded.
