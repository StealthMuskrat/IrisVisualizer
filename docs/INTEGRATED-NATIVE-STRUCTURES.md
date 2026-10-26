# Integrated official native structure stage

This milestone combines native stage preparation, piece placement, foundation/stilt palettes, surface support/repair and deferred-record invalidation. It reuses `NativeBukkitStructureStage` rather than duplicating its stage ordering or generation algorithms. Three hash-pinned native source overlays provide explicit template, ownership, palette and piece services while preserving the original entry point.

## Implementation

`NativeStructureStage` binds an official Iris engine and native chunk generator to the official `BukkitStructureStagePolicy`. The policy retains footprint/write guards, protected positions, host heights, palette sampling, changes tracking and invalidation. Configured starts must have matching ownership in the current session; missing or mismatched ownership fails rather than inventing historical state. No generation history is attached or created. Calls require matching engine/world seed and full configured height, and input chunks at `TERRAIN` status.

The native stage retains its registry/decoration ordering, stable start ordering, heightmap priming, bounded world access, vegetation cleanup, surface integration, template carving, monument alignment, native pieces, foundation/stilt placement, repair and error/finally handling. Explicit host services supply native block-state handles and the earlier standalone piece dispatcher. The original world-based method continues to use its original server/callback services.

Stage calls mutate caller-owned staging chunks. A failure invalidates deferred records through the native finally path and must cause the caller to discard the staging result. A successful structure call does not mark the chunk as a completed feature/world result or bypass preview readiness.

## Verification milestone

On Windows x64 and Java 25:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-OverworldContext.ps1 -StructureBlocks
```

The larger integrated proof runs four reference comparisons across the negative and positive configured ancient-city starts:

- Generate each start's 3 × 3 full-height terrain neighborhood using the actual pinned Iris engine with native block/biome types (18 chunk generations total), then place the selected city through the full native structure stage.
- Repeat with explicit air-gap/floor fixtures to force real pack-defined stilt placement and native palette sampling. The pack's source settings remain unchanged.
- Compare all blocks, including state properties and distinct air states, across every prepared chunk at full Y=[-256,512). Compare all stored native biome cells and require biome preservation. Require changed block output and positive palette use in both stilt cases.
- Inject a piece-dispatch failure at each origin and require the original native finally path to invalidate deferred records. Failures must propagate, original start NBT/chunk statuses must stay unchanged, and partial outputs are discarded.

The reference uses the original complete native stage method, a template-only test world service and inactive plugin-callback constructor interception. Its palette bridge converts the same official Iris palette results into the Craft block handle expected by the original method. The standalone route uses no server-world or callback substitute. Both routes use fresh native starts and fresh staging chunks from identical inputs.

This milestone explicitly isolates the configured ancient-city placement; it does not silently remove other structures from a world preview. All-world structure/reference selection, remaining feature/world services, independent complete native baseline capture, full default-preview volume and total memory validation remain unfinished. The normal preview gate remains blocked. Comparison to the instrumented reference is adapter evidence, not independent full-world parity.

## Provenance and status

The three overlay sources and generated outputs are hash-checked. The standalone stage adapter's source SHA-256 is checked by Gradle/scripts, recorded as `nativeStructureStageAdapterSha256`, and required by native baseline comparison. The earlier dispatcher hash and source/cleanup checks remain in force.

Local verification passed: 30 contract assertions, all three overlay/source hashes and the actual offline Gradle generation/source-selection check. Windows native integration is pending for this larger milestone.
