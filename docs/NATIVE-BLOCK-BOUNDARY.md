# Native structure block service boundary

This small development audit follows the verified real Overworld structure planning proof into native terrain preparation and native piece placement. It uses the unchanged pinned pack and a real configured ancient-city start, loaded into fresh native structure objects through official NBT serialization.

## Run

On Windows x64 and Java 25:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-OverworldContext.ps1 -StructureBlocks
```

This includes the earlier start, ownership and reference assertions. The planning-only switch remains available. Output is written under `build/overworld-blocks/`, including `structure-block-boundaries.tsv` and full failure stacks for recognized missing services.

The exact official `OfflineFeatureWorld.java` from the pinned Iris source checkout is compiled unchanged into the development harness. It is a finite test fixture, not a production server adapter. Its unavailable services throw explicitly. The fixture's native dimension type must match the pack's full configured minimum and height. Each isolated phase gets a fresh 3 × 3 chunk area containing air and official biome samples, not generated terrain. This is a capability experiment, not a terrain comparison.

## Scope and assertions

- Invoke the official `NativeStructurePostProcessor.prepareTerrain` with the real city's retained terrain settings. The pack requests `FORCE_CARVE` with an eroded footprint, whose template path obtains `ServerLevel` through the world interface.
- Independently invoke the native start's `placeInChunk` with the official decoration-seed selection. This tests native pieces in isolation; it does not skip preparation in a preview or implement the complete structure stage, stilts or feature decoration.
- Record whether each call completes in isolation or reaches the exact missing `ServerLevel` service from the official fixture. Unrecognized exceptions fail the audit. Preserve original planned-start NBT and leave all fixture chunks at their input `TERRAIN` status.
- Discard every partial result. Retain `structureStageComplete=false`, `previewAllowed=false` and `nativeParity=NOT_TESTED`. Preserve the earlier source-integrity, no-history and resource-cleanup assertions.

The air input intentionally does not measure native terrain behavior, fluid/lighting completion, total memory, or complete generation parity. Any isolated success must not be interpreted as support for full world generation. This batch does not introduce a fake server or change any generation algorithm.

## Verification status

[Windows x64/Java 25 verification passed](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40363924685) for executable code commit `a1975bd4dac2165d61d06dd5b80c6555c9e65d4f`. Passing means the boundary was reproduced and handled correctly; both native calls are blocked.

| Isolated call | Observed result | Native dependency |
| --- | --- | --- |
| Ancient-city terrain preparation | `BLOCKED`, zero non-air blocks | `NativeStructureTerrainIntegrator.integrateTerrain` obtains the template manager through `WorldGenLevel.getLevel()` while resolving the eroded carve footprint |
| Ancient-city native pieces | `BLOCKED`, zero non-air blocks | `StructureStart.placeInChunk` constructs `CraftStructureTransformer`, which calls `getMinecraftWorld()` |

The exact failure stacks are retained in `terrain-preparation-failure.txt` and `native-pieces-failure.txt`, alongside resource/field locations in the TSV. Both missing services are `ServerLevel`. Input chunks remained at `TERRAIN`, planned source NBT was unchanged and no partial result was published. The native dimension type matched all 768 configured vertical blocks at Y = [-256,512). Source integrity, no-history assertions, resource closure and scratch deletion passed; all 24 contract assertions passed locally and on Windows.

This identifies a template-service dependency and a CraftBukkit callback dependency in the current development runtime. It does not prove a server must run in the eventual application, and it does not justify replacing native generation or supplying dummy services. The next work must resolve those service boundaries through a valid standalone native path before completing feature generation, independent comparison and total memory validation.

All five Windows suites passed on this code: core repeatability, native boundaries, native fixture repeatability, Overworld context and the extended real-pack structure audit.
