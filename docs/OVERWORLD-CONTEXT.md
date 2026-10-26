# Overworld native context and readiness

This small batch loads the real pinned Overworld pack through the official native registry and in-memory contract path. It audits readiness without generating partial world terrain. Passing the audit means native resource loading and the readiness gate work; it does not mean a complete preview or native parity has passed.

## Run

On Windows x64 with Java 25:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-OverworldContext.ps1
```

Reports, the input lock, typed native placement references and preview blockers are written under `build/overworld-context/`. Existing reports are not silently overwritten. The source pack is fingerprinted before and after the audit. Disposable generation input excludes Git metadata, preserves all pack-data bytes and is deleted after native resources close.

## Native resource context

The audit reuses official Iris resource loaders, native datapack compilation and the native generation-registry contract factory. It loads the current dimension's reachable regions and biomes, then inspects their typed `IrisStructurePlacement` definitions. No schema, generation defaults, noise algorithms or reference rules are reimplemented. Native structure references are checked against the loaded registry.

The declared ancient-city placement is reported with its resource type, resource key and exact JSON pointer. The gate also accounts for unfinished native world phases when no explicit native placement is found, since an empty explicit list is not proof that the whole native world path is complete.

## Readiness policy

`PreviewReadiness.evaluateWorld` accepts backend capabilities and verified build evidence, not editable pack flags. Baseline evidence concerns the pinned backend build and its development comparison suite; it must not require users to capture or approve a new baseline after each pack edit. It reports separate blockers for incomplete native world generation, explicit native placements requiring that path, a missing independent complete native baseline, and unconfirmed total memory budgets. Its result is immutable.

This backend currently supplies all three capabilities as false. The expected real-pack result is `previewAllowed=false`. The audit must identify `minecraft:ancient_city` and keep all four blocker codes visible. File operations and editor workflows have no dependency on this gate; save/export implementation remains a later batch. This code must never be used to require pack changes or validation confirmation before saving.

## Memory scope

The audit allocates and touches both 128 × 128/full-configured-height primitive index arrays under the proposed 256 MiB generation budget. It reports their actual byte count separately from native registry/engine memory. This is allocation evidence only: no terrain or meshes are generated, and total/native/mesh budgets remain unconfirmed. The default area and configured height are never reduced silently.

## Remaining work

The pinned offline native path stops at TERRAIN and does not complete all native structure/placed-feature behavior. Some paths need `ServerLevel` or CraftBukkit services, as confirmed by the existing upstream tests. Complete native generation and an independent matching Overworld baseline are required before this readiness gate can allow a faithful world preview. No custom replacement generation or file-history workflow is added here.

## Verified result

[Windows verification passed](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40356846358) for executable code commit `8665947c9480376bd855eb7bf2bf7b65853c6b74`.

- All 24 contract assertions passed on Windows x64/Java 25 and locally.
- Native registries and the in-memory generation contract loaded for 12 reachable regions and 378 biomes.
- The declared `minecraft:ancient_city` reference was found and resolved in the native registry, with its exact dimension/resource field location.
- Both 128 × 128/full-height index arrays were allocated and touched: 100,663,296 bytes (96 MiB) for Y = [-256,512). This is allocation evidence, not terrain generation or a total-memory measurement.
- Source bytes were preserved, no world generation was attempted, no server was running, and the native context/workspace closed and was deleted successfully.
- All four expected blocker codes remained present and `previewAllowed=false`.
- Core repeatability, native-boundary tests and the native fixture's 35,723 state checks/128 chunk runs also remained green.

The four blockers identify unfinished native world completion, the explicit native placement, missing independent native comparison evidence for the backend build, and unconfirmed total memory budgets. Full native generation and comparison remain the next part of batch 4.
