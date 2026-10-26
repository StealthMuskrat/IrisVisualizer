# Standalone native piece dispatch

This small adapter dispatches the existing native pieces without entering CraftBukkit's plugin-event wrapper. It retains piece order, intersection checks, the native reference position and `Structure.afterPlace`. Piece geometry, processors, random source, loot/block-entity work and after-placement algorithms remain native calls. No piece or unsupported generation step is silently skipped.

## Source and supported context

The orchestration follows the native loop shown in the [official Paper patch](https://github.com/PaperMC/Paper/blob/28f8027abbe02be5d3dff1b322c6fadbccb4d658/paper-server/patches/sources/net/minecraft/world/level/levelgen/structure/StructureStart.java.patch). That patch adds plugin-event transformation around the native loop. The dispatcher is for the standalone application's context without Bukkit plugins. Plugin block/entity transformers are not part of this context. The development runtime remains the pinned `26.3.build.25-alpha` bundle; the inspected Paper patch is source context, and tests compare the actual runtime method rather than assuming that source context proves compatibility.

The adapter source hash is pinned in `iris.lock.json`, checked by Gradle/scripts and recorded as `nativePieceAdapterSha256` in reports. Native baseline comparison rejects absent or mismatched dispatcher inputs. The proof also records the actual `StructureStart` class-byte SHA-256. The existing template-service overlay stays unchanged.

## Verification

On Windows x64 and Java 25:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-OverworldContext.ps1 -StructureBlocks
```

`native-piece-dispatch.tsv` records target-block counts, state diversity and full-height state hashes for the real negative and positive ancient-city starts. Each call restores fresh native NBT and uses an isolated air-input fixture with native biome samples over the configured Y=[-256,512) bounds. The original native decoration-seed selection is shared between the reference and standalone calls.

The standalone dispatcher uses the finite world with both server getters still throwing. Only the test reference intercepts construction of `CraftStructureTransformer` to model inactive plugin transformations, then calls the original runtime `StructureStart.placeInChunk`. The reference must exercise exactly one callback wrapper and its cleanup. No callback interception, mock server or plugin substitute enters the standalone adapter.

All target states, including properties and distinct air states, are hashed in fixed coordinate order. Native block placement must produce non-air output and more than one block state, and the complete target hashes/counts must match the reference. Source-start NBT and chunk status stay unchanged; partial fixture results are discarded.

An additional dispatch-contract check requires intersecting pieces to run in original order with the correct reference position, excludes an out-of-area piece, retains the entire piece list for native `afterPlace`, and verifies that an empty start does nothing. It tests orchestration contracts; the two real city cases test native output.

This phase is isolated native piece placement, not a complete structure stage. Pack terrain preparation, foundations/stilts, features, complete world services, independent full-native comparison and memory validation remain separate requirements. Full preview readiness remains false, and no complete native parity is claimed.

## Status

All 28 dependency-free contract assertions passed locally. Windows native verification is pending.
