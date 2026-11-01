# UI completion against the original project brief

The authoritative requirements are [SPECIFICATION.md](SPECIFICATION.md), particularly sections 5, 7, 8 and 9. Development order is **Engine → UI → Renderer → final integration**. The application is currently in the UI stage. A work batch is not a new stage.

Continue UI work in large batches. Compilation and code inspection may continue, but defer test execution until UI implementation is complete, then run the full UI-stage and GitHub validation before renderer work. Keep the complete current project on `main` and commit ongoing UI work there, with skip-CI commit messages and no test-triggering pull request or workflow dispatch. Branches may be used for actual development stages when necessary; integrate them into `main` and delete them immediately afterward. Do not create a branch for each work batch. Do not start the renderer merely because one UI batch passes. Unsupported generation/rendering previews remain disabled while their native support is incomplete.

## Completion checklist

| UI requirement | Current state | Work still required before the UI stage is complete |
| --- | --- | --- |
| White, simple three-panel desktop shell | Fixed resource / visualization / editor panels; borderless white shell implemented locally | Interactive layout, resize, keyboard/focus and accessibility review |
| File menu with exactly New, Open, Save, Import, Export | Exact five-action menu implemented locally; supporting commands live in separate menus | Interactive menu and shortcut verification |
| Hierarchical resource browser and prescribed create/duplicate/subfolder gestures | Implemented with paged browsing and draft guards | Exercise the full supported resource set with the example Overworld pack |
| Typed reference-safe resource F2 rename, drag move and Delete | Review/controller/browser flow and exact persistence implemented locally for a **closed seven-owner subset**; other JSON owners and unknown fields block operations | Complete dimension/region/biome ownership and target coverage, including all nested and context-dependent references; verify Overworld rename/move/delete workflows and interactive gestures |
| All current Iris configuration elements accounted for | Audited bounded direct-field coverage exists; full inherited/nested/schema coverage is **incomplete** | Complete the authoritative field inventory and UI classifications/handling beyond the current bounded inventory |
| Optional/default values, unknown data and generation-only editing | Existing lossless typed controls and policies | Complete coverage review and Overworld round trips; verify small-range numeric slider presentation |
| Preview toolbar, meaningful 2D/3D modes and context selection | UI-only resource mode, dimension context, exact seed and world X/Z state implemented locally | Complete applicable native-isolation/context controls and their UI contracts; no generation while unsupported |
| Image/object contents remain read-only | Original-image viewing and opaque object byte inspection exist | Interactive image navigation and file-operation verification; object rendering belongs to the renderer stage |
| Responsive browsing, source inspection and advisory reviews | Background source/index/search/link/disk work implemented | Interactive responsiveness and complete workflow review; avoid further unrelated review-feature expansion |
| Strict preview errors without blocking Save/Export | Save/Export are independent of preview readiness | End-of-UI Overworld checks for malformed/unsupported content, error reporting and preserved bytes |
| Open, edit, save/reopen, ZIP and untouched-byte preservation | Local contracts exist | Comprehensive UI-stage testing with the example Overworld pack, then the complete GitHub workflows |

## Explicit boundaries

- Preview choices are UI state. They do not modify pack JSON, create temporary pack overrides, select a replacement engine, or authorize native preview output.
- Partial reference reports never authorize rename/move/delete. The new operation planner uses a separate closed source-bound schema: structures, pools, pieces, generators, expressions, image-maps and blocks, with 21 supported model classes bound to 44 pinned Iris/VolmLib sources. These seven JSON families plus objects/images support mutation planning; any other JSON owner, unknown field, unsupported nested value, malformed/limited JSON or exceeded scan budget blocks operations. PNG sources use the native image loader extension; JPEG operations do not inherit PNG references. Bare block aliases use the block loader before vanilla IDs; namespaced vanilla IDs, state/tile maps and categorical surface labels are preserved. Implicit air/STONE/default-palette references block an operation that would require inserting absent settings. Image-map region/biome targets retain their binding-dependent classification. A pack containing dimensions currently blocks these mutations. Broader coverage remains required.
- Resource Apply changes only memory. Save preflights all planned writes/removals, publishes destinations and reference rewrites, then removes baseline/identity-bound old regular files without recursive deletion. Partial-save progress remains explicit and retryable. Pending removal state retains hashes, never old file bytes. Save Copy/As/ZIP use the current graph; fresh-folder adoption leaves the former folder intact.
- Full rendered preview output, camera/mesh/model/texture rendering, fidelity checks and engine-output rendering comparison belong to the renderer stage after UI completion.
- No backup, autosave recovery, crash recovery or disk file-history feature is authorized.

## Validation status

Local Java 25 validation compiled all 355 editor/test sources in 11.79 seconds and passed the complete headless WorkspaceTests suite in 120.31 seconds (154 PASS groups). Closed-schema regeneration/drift checks and workflow YAML validation also passed locally. No GitHub workflow or Windows/native/interactive GUI test was run for this batch. The UI stage remains unfinished and unsupported previews remain disabled.

The expanded ownership batch has prepared new end-of-UI regression contracts and source-drift cases. They have not been executed under the current instruction to defer tests. The successful 154-group result above belongs to the preceding commit, b17efd0, and does not validate the expanded implementation. All 356 editor/test sources compile successfully on the local Java 25 runtime. No regression tests, source-drift test scripts, GitHub workflows or interactive GUI tests were executed for this expanded batch.
