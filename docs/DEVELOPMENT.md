# Implementation batches

Follow the attached specification. Work in larger milestone batches, implementing related engine capabilities together and running integrated checks after implementation. The standalone feasibility gate must pass before full engine development.

| Batch | Deliverable | Verification |
| --- | --- | --- |
| 1 — implemented | Pinned official-core feasibility harness and native-boundary report | Windows core generation proved; shutdown issues identified |
| 2 — verified | Windows storage compatibility and cleanup | 17 contract assertions, 32 chunk runs with repeatability, source integrity and native boundary checks |
| 3 — verified | Native registry/terrain feasibility fixture | 35,723 states; full-height 128 × 128 terrain; fresh-JVM repeatability; no history directory |
| 4a — verified | Real Overworld native context and world-preview readiness gate | 12 regions/378 biomes; typed ancient-city reference; 96 MiB index allocation; 24 contract assertions; preview correctly blocked |
| 4b.1 — verified | Real Overworld native structure starts and references | Two ancient-city start NBT comparisons, ownership, 289-origin halo, missing-origin rejection, no history and cleanup |
| 4b.2 — boundary verified | Real-pack structure block service boundary | Both real ancient-city calls reach ServerLevel dependencies before writing blocks; exact failure stacks, unchanged inputs and cleanup |
| 4b.3a — verified | Explicit native template services | Two native eroded carves without ServerLevel (9,616/9,740 blocks), full-height legacy comparison, 26 assertions and overlay provenance |
| 4b.3b — verified | Native piece dispatch without plugin callbacks | 2,528/2,240 native blocks, 88/63 states, full-height runtime comparison, dispatch contracts and 28 metadata assertions |
| 4c — verified | Integrated official native structure stage | 18 generated chunks; four full-height state/biome comparisons; native palette stilts; two failure invalidation checks; 30 contract assertions |
| 4d | Complete native world and independent comparison | Finish remaining world/feature services, confirm memory budgets and compare an independent full native baseline |
| 5 | Typed preview context, in-memory isolation and generation scheduler | Latest-edit result, one active task, at most 10 polls/second, bounded caches |
| 6 | Lossless pack directory and ZIP workspace | Unknown/invalid data and asset preservation; untouched bytes; no file history |
| 7 | Typed resource operations and reference maintenance | Safe create/duplicate/rename/move/delete; dimension-location rules |
| 8 | Official schema integration and audited UI policy | Field classification coverage; absent defaults; unknown fields read-only |
| 9 | White three-panel ImGui desktop shell and file menu | Windows runtime launch, exact five menu actions, unsaved-change confirmation |
| 10 | Resource tree and schema-driven editors | Supported control types, nested arrays/objects, save/reopen edits |
| 11 | 2D maps and original-image viewing from engine/assets | Sampling scale, panning, full configured map resolution, stale-view rejection |
| 12 | Vanilla asset loading and 3D block-state geometry | User-selected matching client assets, orientation, cutouts, fluids and tints |
| 13 | Terrain renderer and bounded mesh caches | Engine agreement, hidden-face/frustum culling, compatible greedy meshing |
| 14 | Windows x64 distribution and final integration | All specification §5 scenarios, runtime errors and reproducibility manifest |

A failed feasibility check must stay visible. If complete standalone generation requires substantial reimplementation, report that boundary and obtain direction under specification §4. Do not substitute a custom generator or silently omit generation features.

The user authorized editor development while unsupported previews stay disabled. The editor workspace/shell is now underway; the renderer remains pending native readiness. The supplied UI images are general design guidance and do not establish generation or rendering parity.

Editor track authorized: desktop shell/workspace implemented; initial lossless dimension field controls and pinned definition extraction implemented. Full schema coverage and audited resource editing remain in progress. Unsupported previews stay disabled.

Editor field-control verification: Windows run 40378433658 passed compilation, workspace/edit contracts and regeneration of the two pinned source-derived field definitions at cb79133c33af836b1c7171b9983a152bd46837fb. Visual GUI verification and broader schema coverage remain pending; previews remain unavailable.

Editor navigation batch: hierarchical searchable resources, per-resource dirty markers, Save/Discard/Cancel for close/replacement, and visibly disabled preview controls. Pure workspace/resource-index tests pass locally. Windows run 40379021579 passed compilation, workspace/edit/resource-index contracts and pinned definition regeneration at 470a9b0c22d66d352c296499ef19019d141aec68. No preview backend is invoked.

Expanded scalar editor batch: eleven audited dimension fields (four numeric, seven boolean), source-derived types/defaults/bounds, explicit absent-field enablement, token-preserving boolean updates and insertion. Unknown fields remain read-only; no preview generation. Windows run 40379509569 passed editor compilation, workspace/edit/resource-index contracts and source-definition regeneration at dc0275b42ba6e848d42e4a42fff145da86b7763d. Visual GUI verification remains pending.

Nested editor batch: three audited imported-structure/feature scalar paths, source-derived defaults/constraints/help with per-source SHA-256 metadata, explicit missing-parent insertion, and nested token-preserving edits. Snippet/non-object parents remain read-only. Windows run 40380141923 passed compilation, workspace/edit/resource-index contracts and regeneration from all three pinned source files at db33cd0ff529d53d08d65357c1b07d34195f4fad. Visual GUI verification remains pending; no preview backend invoked.

Filter-array batch: four source-derived native structure/feature deny lists, item-level add/edit/remove, explicit absent-list enable, syntax-only registry key validation. Unknown and mixed-type data remain preserved/read-only. Windows run 40380674339 passed compilation, workspace/edit/resource-index contracts and source-definition regeneration at cc17153a4918e4198712394ca4617745a44a8bb8. Visual GUI verification remains pending; unsupported previews remain disabled.

Inspector draft batch: transient unapplied-text tracking, guarded navigation/close/replacement and save/export, preservation of other row drafts on Apply, structural-array draft warnings, and named read-only field coverage. No autosave/history/recovery and no preview backend. Windows run 40381304899 passed compilation, workspace/edit/browser contracts, draft-state/coverage checks and pinned-definition regeneration at 7bc50d5a4ea88595c12e57da564e030e8fea8101. Interactive dialog and visual layout verification remain pending.
