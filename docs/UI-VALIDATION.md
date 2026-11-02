# UI-stage automated validation

Date: 2026-11-02. Tested application/source commit: `5d487ec49ce619439fa18962b83e15efd23a67eb`.

UI implementation and the complete automated editor acceptance checks passed. Rendering is the next development stage. Unsupported generation/rendering previews remain disabled; editor tests do not establish complete native-world parity. Interactive Windows visual/GPU checks remain separately unverified below.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Java 25 compilation | Passed, 389 main/test source files | Local compile and Windows editor workflow |
| Complete editor regression suite | Passed, all 176 reported groups locally and on Windows | [Windows editor run](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313042); final local rerun 149.20 seconds |
| Pinned source contracts and regenerated definitions/policy | Passed; 93 Python test commands, complete 1,778-production-source consumer audit, shared field inventory and coverage | Same Windows run; all drift checks and generated-file comparisons passed |
| Real locked Overworld editor acceptance | Passed, 8,112 files, four edited resources, exact full-pack round trips | [Acceptance artifact](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313042/artifacts/13177708097) |
| Windows x64 distribution | Passed; five x64 Windows DLL entries, external Java runtime, package verification | [Windows ZIP artifact](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313042/artifacts/13177583655) |
| Launcher and native installation | Passed; Java 25 x64 detection, explicit missing-runtime error, packaged ImGui context, definitions/templates and GLFW loading | Same Windows run |
| Complete native feasibility matrix on tested commit | Passed all five suites: core, native boundaries, native fixture, Overworld context and Overworld structures | [Complete matrix](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313120), with five diagnostic artifacts |

## Real-pack acceptance

Fixture: IrisDimensions/overworld at locked revision `2e85a52133ad1206c88cad2f50b6f622bea60cb9`, downloaded by exact commit archive. The original fixture remains untouched. The harness first saves a pristine copy, verifies every loaded path and SHA-256, then edits a separate working copy through the same typed helpers used by the editor.

| Resource | Field | Before | After |
| --- | --- | --- | --- |
| `dimensions/overworld.json` | `fluidHeight` | `50` | `63` |
| `regions/estranged.json` | `rarity` | `1` | `2` |
| `biomes/carving/amethyst-child.json` | `rarity` | Absent | `2` |
| `generators/canyon-steep.json` | `offsetX` | Absent | `17.25` |

Every edit checks the exact new token, unchanged sibling/unknown tokens and byte-exact Undo/Redo. Save Copy and ZIP leave the pending baseline intact. Save and Save As acknowledge the appropriate baseline without clearing applied history. Save/reopen, Save Copy/reopen, Save As/reopen and ZIP export/import all reproduce the expected complete path/hash map, including opaque assets and unknown files. Exactly those four resources change; the other 8,108 files and original fixture remain byte-exact.

The real pack acceptance covers representative edits and storage integration. The 176-group synthetic suite supplies the broader supported-context, list, reference-operation, read-only, malformed-data, cancellation, budget and failure-injection coverage; four real-pack edits alone are not full field coverage.

## Scope and remaining desktop QA

The pinned inventory remains 1,462 fields across 150 models: 1,237 implemented generation fields, 225 read-only fields and zero unclassified/missing policies. Unsupported stored shapes, external/qualified lookup semantics and native readiness retain their existing guards. Save and Export remain independent of preview readiness. No pins or generation algorithms changed during UI validation.

CI does not verify a visible application window, mouse/keyboard interaction, mixed-DPI fonts/dividers, native tab selection, original-image GPU uploads or visual layout. Those checks still require a Windows desktop with suitable graphics and belong in the remaining desktop/final-integration QA. The installation check loads an ImGui context and GLFW; it is not a rendered-window or GPU correctness claim.

The Windows artifact requires a separately installed Java 25 x64 runtime. The outer GitHub artifact archive has SHA-256 `442213e88e691b8c2c2ab8ea21d78620ab2616ea390e1363a1ff51d262f71bb9`; the distribution contains its own `SHA256SUMS.txt`. These are different archive/checksum scopes.

Validation fixes and previous failed attempts are recorded in [UI-IMPLEMENTATION-REVIEW.md](UI-IMPLEMENTATION-REVIEW.md). The final documentation-only record may follow the tested commit; it must not be represented as a new application binary or a separate runtime test.

The machine-readable result is [ui-validation-2026-11-02.json](ui-validation-2026-11-02.json), including tested pins, workflow/job IDs, artifact metadata and the remaining verification boundaries.
