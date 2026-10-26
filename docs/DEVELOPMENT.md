# Implementation batches

Follow the attached specification. Implement substantial batches, then run each batch's checks at the end. The standalone feasibility gate must pass before full engine development; postponing tests must not bypass that architecture decision.

| Batch | Deliverable | End-of-batch evidence |
| --- | --- | --- |
| 1 — current | Pinned official-core feasibility harness, native limitations, input fingerprints, complete-height hashes, proposed budgets, Windows checks | Contract checks, Windows official-core run, native capability tests; no parity claim without an independent baseline |
| 2 — after gate | Official-core adapter and vanilla registry handling; typed preview requests; dimension/isolation context; bounded caches; latest-only asynchronous regeneration | Native block and biome comparisons over the current Overworld pack; rapid-edit coalescing; memory and full-height checks |
| 3 | Lossless pack workspace and schema-driven ImGui editor, audited field-classification policy, typed references and safe file operations | Save/reopen edits; absent defaults; untouched bytes; unknown fields/assets; safe rename/move/delete; ZIP roundtrips and invalid-pack preservation |
| 4 | LWJGL 2D/3D rendering from engine output; installation-selected vanilla assets; model/state/material support; culling, meshing, bounded caches | Engine-to-renderer block/state/biome agreement; missing-model placeholders; transparent/cutout/fluid geometry; camera/map navigation |
| 5 | Windows x64 distribution without bundled runtime | Full specification §5 scenarios, runtime errors, launch instructions and reproducible parity manifest |

The editor and renderer are deliberately pending the feasibility decision. A white three-panel mockup or synthetic heightmap must not be presented as the completed application. Required reference images are general design guidance; they do not demonstrate rendering fidelity or generation parity.
