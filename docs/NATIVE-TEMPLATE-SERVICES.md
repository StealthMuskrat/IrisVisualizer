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

Local contract and source-overlay checks are in progress; Windows native integration is pending. Native piece placement, features, an independent complete native baseline and total memory validation remain unfinished.
