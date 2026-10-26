# Native terrain fixture proof

Batch 3 checks whether the official Iris engine can generate terrain using native Minecraft block and biome implementations without a running client/server or a file-history workflow. It is a development proof for the exact `terrain3d-pack` fixture from the pinned Iris source revision. It is not a general-purpose pack backend, an Overworld parity claim, or the desktop application.

## Run on Windows x64 / Java 25

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-NativeFixture.ps1
```

The launcher verifies source revisions, the compatibility helper hash and the fixture fingerprint, then invokes a fresh Java process for each of two runs. Reports and the input lock are written under `build/native-fixture/`. Existing output is never silently overwritten.

## Adapter boundary

The proof reuses the official `HeadlessNativeRuntime` bootstrap, native block parsing, decorators, state rotation/merging, tile handling, compiled datapack registries and `IrisEngine`. The official prepared-workspace helper supplies the excluded gameplay-service implementations. No terrain, noise, biome-selection or placement algorithm is reimplemented.

The upstream helper's `createHistory` workflow is never called. A `GenerationRegistryContract` is created and bound in memory using the official factory, with no generation-history epochs or pack snapshots. The proof rejects creation of the native history directory and deletes its disposable generation workspace. The source fixture is fingerprinted before and after execution.

This fixture has ordinary volumetric terrain and vanilla biome references. Its exact fingerprint is required before generation. Native structure planning and placed-feature completion are not covered; the proof cannot be pointed at an arbitrary user pack and silently omit those features.

## Checks at the end of the batch

- Round-trip every state in Minecraft's built-in block registry through the official state serializer/parser; reject an external block and invalid state properties.
- Verify Minecraft's native version matches the pinned asset target and `Bukkit.getServer()` remains null.
- Generate 64 chunks spanning negative and positive coordinates: 128 × 128 columns and the fixture's full configured vertical range.
- Require every non-null generated block and biome to use the native implementation. Preserve null/unwritten hunk values as explicit `<null>` samples, as in the existing proof format.
- Retain both full-volume primitive index arrays while generating, enforce the proposed generation budget, and record their actual byte count and palettes.
- Compare full block and biome hashes across fresh JVM runs.
- Require clean engine, registry and workspace closure, source-byte integrity, and absence of the history directory.

The report remains `nativeParity=NOT_TESTED`, `completeNativeWorld=false` and `cacheBudgetsConfirmed=false`. Native registry round-tripping and fixture repeatability do not substitute for independent complete native Overworld generation. The index arrays do not measure total engine/native memory or mesh-cache costs. The 512 × 512 map default is retained but map generation is outside this batch.

## Verified result

[Windows verification passed](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40352382975) for code commit `30192be01929456cbd66dfa540031856d4591213`.

- 35,723 native vanilla block-state combinations round-tripped in each JVM. The three invalid/external examples were rejected.
- Each run generated 64 full-height chunks across 128 × 128 columns. The fixture configures Y = [0,256), so the two retained index arrays occupy 33,554,432 bytes (32 MiB), excluding palettes and engine/native memory.
- Full block and biome hash tables matched between the two fresh JVMs: 128 chunk generations total.
- Native block/biome implementation checks, the Minecraft-version check, the no-server check, source-byte preservation, history-directory absence and workspace cleanup all passed.
- The existing 17 contract assertions, Overworld stub-repeatability test and selected native boundary tests remained green.

This verifies a native terrain path for the pinned fixture. Independent complete Overworld parity, actual 512 × 512 map generation, native structures/features and total cache/memory budgets remain pending.
