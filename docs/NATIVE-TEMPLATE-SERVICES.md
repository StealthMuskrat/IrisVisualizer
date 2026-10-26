# Standalone native template service injection

This batch resolves the template-manager lookup boundary for native terrain preparation by supplying the real template manager already owned by `HeadlessTerrainContext`. It adds overloads to two pinned official native classes; it does not replace their terrain calculations. The original world-based entry points stay available and retain their earlier missing-service behavior.

## Run and scope

On Windows x64 and Java 25:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-OverworldContext.ps1 -StructureBlocks
```

The extended block proof writes `template-preparation.tsv` under `build/overworld-blocks/`. It also repeats the earlier structure planning, ownership, origin-reference, missing-service, source-integrity and cleanup assertions. The original piece call still reports its CraftBukkit/`ServerLevel` dependency, and full preview readiness remains false.

`compatibility/volmlib/template-services.json` pins each original source, exact edit and generated output. Gradle generates only the two replacement source files in the dependency build directory and compiles them alongside the other unchanged native classes. The source checkout is not edited. The adapter passes an explicit template supplier through native carving, legacy-air clearing and support-bridge calls. Existing entry points continue to construct their world-based supplier.

## Verification design

Each real ancient-city start, at negative and positive coordinates, is restored into fresh native objects for separate reference and standalone calls. Fresh full-height fixtures contain solid stone in the target chunk, air neighbors and official biome samples. They are artificial inputs for measuring carving, not generated Overworld terrain.

The standalone overload receives the actual context template manager. Its finite world still throws if `getLevel()` or `getMinecraftWorld()` is called; a successful carve therefore cannot rely on a server world. A counted supplier must be used, and the native carve must change some, but not all, target blocks.

Only the legacy-reference call receives a Mockito `ServerLevel` test double providing the same real template manager. Verification requires template lookups and rejects any other interaction with that double. It then compares every target block over the full configured height: carved counts and SHA-256 values must match the standalone result. The fixture allows only stone and air here, so the digest uses an unambiguous byte per voxel. This comparison checks the service adapter; it is not an independently captured complete native-world baseline.

Original start NBT and chunk statuses must remain unchanged. All generated fixture results are discarded; no server runs and no history is attached or created. Input metadata includes the template-service manifest hash, and baseline comparison rejects missing or different template overlays. This is backend-build evidence, not a baseline requirement imposed on user edits.

## Verification status

[Windows x64/Java 25 integration passed](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40366896998) for executable code commit `d2e8e151062657c1784b6812cf5ea49adca2b00c`.

| Ancient-city origin chunk | Standalone carved blocks | Explicit template requests | Full-height result |
| --- | ---: | ---: | --- |
| -99, -21 | 9,616 | 60 | SHA-256 and count match the legacy method |
| 42, 19 | 9,740 | 73 | SHA-256 and count match the legacy method |

Each comparison covers the target chunk's full 768-block configured height at Y = [-256,512). Both standalone calls succeeded with server getters unavailable. The legacy test double was used only for the reference's template-manager access, with other interactions rejected. Planned NBT/status preservation, no-history assertions, source integrity and cleanup passed. All 26 contract assertions passed locally and on Windows.

The local offline Gradle check also generated the exact pinned source bytes and verified that Java compilation selects each overlay once while excluding its original. Neither upstream source checkout was edited.

The earlier world-based preparation call and native piece call still reproduce their expected `ServerLevel` dependencies. The new overload resolves the template lookup for this tested eroded-carve path; it does not remove the CraftBukkit piece callback dependency or complete other world services. Full-world preview remains blocked. Native piece placement, features, an independent complete native baseline and total memory validation remain unfinished.

All five Windows suites passed: core repeatability, native boundaries, full-size native fixture repeatability, Overworld context and the extended structure/template-service proof.
