# Implementation batches

Follow the attached specification. Keep batches small, with one concrete deliverable and relevant checks at the end of each batch. The standalone feasibility gate must pass before full engine development.

| Batch | Deliverable | Verification |
| --- | --- | --- |
| 1 — implemented | Pinned official-core feasibility harness and native-boundary report | Windows core generation proved; shutdown issues identified |
| 2 — verified | Windows storage compatibility and cleanup | 17 contract assertions, 32 chunk runs with repeatability, source integrity and native boundary checks |
| 3 — verified | Native registry/terrain feasibility fixture | 35,723 states; full-height 128 × 128 terrain; fresh-JVM repeatability; no history directory |
| 4a — verified | Real Overworld native context and world-preview readiness gate | 12 regions/378 biomes; typed ancient-city reference; 96 MiB index allocation; 24 contract assertions; preview correctly blocked |
| 4b.1 — verified | Real Overworld native structure starts and references | Two ancient-city start NBT comparisons, ownership, 289-origin halo, missing-origin rejection, no history and cleanup |
| 4b.2 — boundary verified | Real-pack structure block service boundary | Both real ancient-city calls reach ServerLevel dependencies before writing blocks; exact failure stacks, unchanged inputs and cleanup |
| 4b.3a — verified | Explicit native template services | Two native eroded carves without ServerLevel (9,616/9,740 blocks), full-height legacy comparison, 26 assertions and overlay provenance |
| 4b.3b | Complete native world context and Overworld comparison | Resolve the remaining piece/world services, finish native features, confirm total memory budgets and compare an independent native baseline |
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

The editor and renderer are pending that gate. The supplied UI images are general design guidance and do not establish generation or rendering parity.
