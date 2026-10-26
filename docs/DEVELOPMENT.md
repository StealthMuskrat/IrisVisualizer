# Implementation batches

Follow the attached specification. Keep batches small, with one concrete deliverable and relevant checks at the end of each batch. The standalone feasibility gate must pass before full engine development.

| Batch | Deliverable | Verification |
| --- | --- | --- |
| 1 — current | Pinned official-core feasibility harness and native-boundary report | Contract checks, Windows build, core repeatability and native capability tests |
| 2 | Minimal standalone native platform adapter for representative terrain | Full-height block/state/biome output, explicit unsupported services |
| 3 | Current Overworld parity capture and comparison | Same pack bytes, Iris/dependency pins, seeds, coordinates and independent native baseline |
| 4 | Typed preview context, in-memory isolation and generation scheduler | Latest-edit result, one active task, at most 10 polls/second, bounded caches |
| 5 | Lossless pack directory and ZIP workspace | Unknown/invalid data and asset preservation; untouched bytes; no file history |
| 6 | Typed resource operations and reference maintenance | Safe create/duplicate/rename/move/delete; dimension-location rules |
| 7 | Official schema integration and audited UI policy | Field classification coverage; absent defaults; unknown fields read-only |
| 8 | White three-panel ImGui desktop shell and file menu | Windows runtime launch, exact five menu actions, unsaved-change confirmation |
| 9 | Resource tree and schema-driven editors | Supported control types, nested arrays/objects, save/reopen edits |
| 10 | 2D maps and original-image viewing from engine/assets | Sampling scale, panning, full configured map resolution, stale-view rejection |
| 11 | Vanilla asset loading and 3D block-state geometry | User-selected matching client assets, orientation, cutouts, fluids and tints |
| 12 | Terrain renderer and bounded mesh caches | Engine agreement, hidden-face/frustum culling, compatible greedy meshing |
| 13 | Windows x64 distribution and final integration | All specification §5 scenarios, runtime errors and reproducibility manifest |

A failed feasibility check must stay visible. If complete standalone generation requires substantial reimplementation, report that boundary and obtain direction under specification §4. Do not substitute a custom generator or silently omit generation features.

The editor and renderer are pending that gate. The supplied UI images are general design guidance and do not establish generation or rendering parity.
