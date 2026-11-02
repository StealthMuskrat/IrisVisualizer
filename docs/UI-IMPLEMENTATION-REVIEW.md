# UI implementation closure and validation scope

UI feature implementation is closed at a22a62e117211db06f2a1c38f556082d1b3ae07e. The project remains in the UI stage while its complete regression, example-pack and Windows workflow validation runs. Native generation/rendering and automatic generation remain disabled under the user's agreed scope. Renderer work has not started.

The original SPECIFICATION.md sections 7–9 are implemented through the existing white three-panel shell, exact five-action File menu, typed hierarchical file operations, generation-only controls, optional/default omission, original-image inspection, preview request controls and guarded storage workflows. Current coverage accounts for 1,462 pinned fields across 150 models: 1,237 implemented generation fields and 225 read-only fields, with zero unclassified or missing policies. Known unsupported shapes, lookup modes, contexts and external recipe semantics retain the documented guards in RESOURCE-OPERATIONS.md and FIELD-POLICY.md. Preview dependency/native readiness remains explicitly unavailable; Save and Export do not depend on it.

Validation now starts, as authorized after implementation completion:

1. Compile and execute the complete WorkspaceTests suite, fixing any regressions it exposes.
2. Exercise the exact locked Overworld pack through representative supported edits, untouched-byte checks, Save/reopen, Save Copy, Save As and ZIP import/export. Report protected or unavailable cases explicitly.
3. Run the complete Windows editor workflow, including pinned source regeneration/drift checks, the 1,778-source consumer audit, field policy, all Java contracts, Windows-only packaging and launcher/runtime/native-library checks.
4. Run the repository's Windows feasibility workflow and record every matrix result without claiming native generation parity from UI tests.

Interactive Windows mouse/keyboard, mixed-DPI, image GPU and visual layout behavior still requires a suitable Windows desktop. Automated and source-level results will be reported separately. Successful compilation alone is not a validation pass, and the UI stage remains open until its validation results are resolved and recorded.

## Local validation, 2026-11-02

- Java 25 compilation passed for all 389 editor main/test sources.
- The complete headless `WorkspaceTests` passed all 176 reported groups (165.15 seconds). This covers the full editor data/UI-state contracts, including the previously deferred pagination, exact numbers/defaults, asynchronous failure handling, reference operations and storage workflows.
- All 93 pinned-source Python test commands from the Windows editor workflow passed against the exact source cache (46.12 seconds), including complete modification-consumer evidence and shared inventory/coverage regeneration. Windows still independently downloads and verifies its sources.
- Validation fixed a real reference-scanner regression: only certified `Schema.json` metadata can index its root string array; ordinary editable JSON retains the root-object requirement. Invalid/mixed/trailing/oversized metadata and unknown owners remain guarded.
- Worker failure tests now explicitly exercise the new/batch import acknowledgement guard before applying their retried capture. Two older coverage tests now expect the shared inventory's complete declaration order; source drift tests accept the strict lexical parser's rejection type without accepting the mutated source.

The first Windows editor run at `f2451e316e7240aba7fe4c3cd4bdeb3ea1a78485` independently reproduced the now-corrected source-drift test error. Its failure is retained in [run 41516568962](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41516568962). Full Windows validation and real pinned Overworld acceptance remain pending on the corrected commit. These local results do not claim interactive GUI/GPU or native generation parity.
