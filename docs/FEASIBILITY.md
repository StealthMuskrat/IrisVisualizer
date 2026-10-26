# Standalone feasibility gate

Inspection date: 2026-10-26. **Windows core generation, fresh-session repeatability, cleanup and the selected native boundary checks passed. Native parity and full-preview cache budgets remain unverified.**

## Pinned inputs

| Input | Pin |
| --- | --- |
| Iris latest stable release | 4.3.5-26.1.2-26.3 |
| Iris source | c3359e0a16438b1bb8468901c6171dcefdf63f7f |
| Minecraft target | 26.3 |
| VolmLib source candidate | 210705c5ab1321dc74b86b48457fd6d5e0452944 |
| Official Windows I/O helper | 0eb8a9282cd549e437ddd7ca0457a7ab79917a5f |
| Latest Overworld pack | 2e85a52133ad1206c88cad2f50b6f622bea60cb9 |

The initial VolmLib candidate selected by source date failed Windows compilation: Iris requires `intersectsInfluenceRect`, `surfaceAdjustment` and `surfaceCutY`. The API-compatible pin (`210705c`) supplied those methods but exposed a Windows directory-flush failure during shutdown. The current build retains that compatible pin and compiles the single unchanged upstream I/O helper from `0eb8a92`. Its exact SHA-256 is recorded separately. A whole-dependency upgrade was rejected because it changes the native terrain API. The dependency combination has not yet been verified against a released Iris binary. Native comparisons must match both the source revision and compatibility-source hash. Iris's build uses changing `master-SNAPSHOT` dependencies by default; the harness substitutes a fixed local checkout. A release dependency stamp and resolved dependency verification metadata are still needed for complete binary reproducibility. The latest pack is newer than the latest stable Iris release and includes hydrology-schema changes. Its compatibility is an explicit test, not an assumption; do not downgrade or repair it silently.

## Source findings

The pinned Iris source supplies a platform SPI, `IrisEngine`, `RealPackProbeSupport`, and `HeadlessNativeRuntime`. `RealPackProbeSupport` initializes the real engine, generates block and biome buffers without a running server, and has a portable Windows file-copy path. The integration in this repository adds a probe to the upstream module with a Gradle init script, without editing upstream Java sources.

However, `StubPlatform` approximates solidity and occlusion (`isOccluding()` delegates to a simple solidity test), block registries, state handling and decorator hooks. A successful stub probe demonstrates core initialization and repeatability only. It cannot establish correct vanilla generation.

The official native offline generator explicitly publishes **TERRAIN**, not a completed native world. Its checkpoint report says native features, population and other completion phases remain outstanding. Some of these phases affect voxels, even though entities and gameplay are outside this project's scope. Omitting a placed feature or structure write would invalidate a faithful preview.

The pinned upstream native feasibility tests identify specific boundaries:

| Case | Upstream expectation |
| --- | --- |
| Direct configured spring feature | Works offline and captures fluid ticks |
| Direct simple-block feature | Works offline and creates a native chest block entity |
| Placed-feature wrapper, even without modifiers | Requires `ServerLevel` |
| Selected stronghold piece | Writes offline across prepared chunks |
| Native loot-chest structure path | Requires CraftBukkit world services |
| Swamp-hut population | Requires `ServerLevel` |
| Out-of-bounds writes and unavailable chunk status | Fail explicitly |

The selected upstream native feature, bootstrap and state test classes passed on Windows x64/Java 25 in [the verification run](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40343283902). These tests confirm the listed supported paths and explicit limitations; they do not prove complete native generation parity. They make a minimal terrain adapter plausible but do not prove a complete vanilla-compatible adapter. Do not reimplement placement algorithms, run a hidden Minecraft server, or label partial terrain as full parity without a scope decision.

## Budgets and defaults

Keep 128 × 128 horizontal 3D previews, full configured Y extent, and 512 × 512 map samples. Proposed application-owned budgets are 256 MiB for generated palette indices and 256 MiB for meshes. At Y = [-64,320), two per-voxel integer arrays use 48 MiB for a 128 × 128 preview. Palette storage, engine internals, mesh construction and native runtime overhead are additional; the proposal is not a measured total memory bound. Peak heap is recorded by the probe. Native/RSS measurements, explicit Iris internal cache bounds and full-area performance measurements remain prerequisites to confirming these budgets.

The smoke test hashes eight complete-height chunks at each of two seeds, repeated in fresh sessions. It does not reduce the application's required default area and does not establish full-area performance. Oversized index-array requests fail instead of truncating Y or lowering resolution silently.

## Verification needed before engine/UI/renderer work

1. Build all pinned dependencies and complete the official-core probe on Windows x64/Java 25.
2. Run upstream native boundary tests; inventory applicable platform services.
3. Implement only minimal native adapters where feasible, preserving official generation code.
4. Obtain independently captured native block/biome baselines for identical content, seed, coordinates and configured height. Compare hashes using the documented traversal.
5. Confirm 128 × 128/full-height and 512 × 512 performance, cache budgets and cancellation/coalescing behavior.
6. If full native generation requires substantial reimplementation, seek a scope decision as required by specification §4 before proceeding.

## Baseline format

UTF-8 TSV header: `seed`, `chunkX`, `chunkZ`, `minY`, `maxYExclusive`, `blocksSha256`, `biomesSha256`, separated by tabs. Hash the full chunk in X, then Z, then relative-Y ascending order. Encode each original key/state string as UTF-8 followed by a zero byte. Encode absent data as `<null>`; do not substitute a block or biome. Both hashes are full SHA-256 hex. Absolute Y is `minY + relativeY`.

The native properties file must include matching `irisRevision`, `volmlibRevision`, `volmlibCompatibilitySha256`, `packRevision`, `packSha256`, `dimension`, `minY`, `maxYExclusive`, plus `backend=native-iris`, `complete=true` and a nonempty `captureProvenance` describing the capture. These declarations require independent verification; the comparator cannot prove the origin of a user-supplied baseline. Never produce a fake baseline by changing a stub report's backend label.

## Source references

- [Latest stable Iris release](https://github.com/VolmitSoftware/Iris/releases/tag/4.3.5-26.1.2-26.3)
- [Official core probe helper](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/probe/src/main/java/art/arcane/iris/probe/RealPackProbeSupport.java)
- [Stub platform](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/probe/src/main/java/art/arcane/iris/probe/StubPlatform.java)
- [Native offline generator and limitations](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/adapters/bukkit/nms/v26_3_R1/src/offline/java/art/arcane/iris/probe/OfflineRegionGenerator.java)
- [Native feature feasibility tests](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/adapters/bukkit/nms/v26_3_R1/src/test/java/art/arcane/iris/probe/OfflineNativeFeatureFeasibilityTest.java)
- [Iris documentation](https://github.com/VolmitSoftware/docs/tree/master/iris)

## Batch 1 verification result

[Windows verification run](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40343283902):

- All 15 dependency-free contract assertions passed on Windows x64/Java 25 and locally.
- Official Iris and integration sources compiled with VolmLib `210705c5ab1321dc74b86b48457fd6d5e0452944`.
- The current Overworld pack passed official offline validation.
- The official core initialized and emitted full block and biome hashes for eight chunks at seed 1337, including negative/distant coordinates. The configured Y range was [-256,512), with no truncation. This supports initial core-generation feasibility using the upstream stub platform.
- All three selected native test classes passed.
- Shutdown failed because the pinned storage implementation attempts directory `FileChannel` flushing on Windows. Cleanup also failed on read-only Git metadata copied into the disposable workspace. The first session therefore did not close successfully and subsequent seeds/repeatability checks did not execute. The probe correctly stayed red.
- Source-pack integrity, peak heap and failure details are emitted in the run's `report.properties`; no native parity or final cache-budget claim is made.

The next small batch should use an upstream dependency revision containing both the required structure-volume API and the Windows atomic-write fix, and keep checkout metadata outside generation inputs. Do not suppress the shutdown error or weaken repeatability checks to make CI pass.

## Batch 2: Windows lifecycle compatibility

This batch compiles the unchanged official `IOWorkerSupport.java` from [the Windows atomic-write fix](https://github.com/VolmitSoftware/VolmLib/commit/0eb8a9282cd549e437ddd7ca0457a7ab79917a5f) alongside the API-compatible `210705c` dependency. The helper is byte-for-byte equal to upstream Git blob `4c6fd0dc792e492a6b5f29357ff2750616659987`; source provenance is recorded in `compatibility/README.md` and `iris.lock.json`. No Iris generation source or algorithms are patched.

Source preparation now places the pack checkout's Git object database at `.work/pack-repository`, outside the generation input. Existing clean, verified test checkouts are migrated with Git's `--separate-git-dir`; conflicting metadata is never overwritten. Resource paths and file contents are unchanged. Checkout metadata location is checked before generation.

Per-session TSVs now record and flush completed chunk hashes as they are generated, preserving useful diagnostics if a later chunk or shutdown fails. Partial output never counts as repeatability or native parity. The failing state and strict shutdown checks remain in place.

[Windows verification passed](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40348282580) for code commit `c837d5868c676a233d1b1775bd1c6463e8ff3786`:

- All 17 contract assertions passed on Windows x64/Java 25 and locally, including rejection of missing or mismatched compatibility-source hashes.
- Both seeds (1337 and -8675309) completed eight full-height chunks and fresh-session repeats: 32 chunk generations total. Block and biome hashes matched within each seed across sessions.
- Engine shutdown and disposable-workspace cleanup completed, and the source-pack fingerprint remained unchanged.
- All selected native feature, bootstrap and state test classes passed.
- All eight block and biome hash rows for the first seed matched the earlier batch-1 generation output. This is an I/O-change regression check, not native parity.
- The published compatibility helper's Git blob exactly matches upstream (`4c6fd0dc792e492a6b5f29357ff2750616659987`).

Generation used the full configured Y range [-256,512). The smoke test still does not establish 128 × 128 performance, 512 × 512 maps, total engine/native memory bounds, or production native parity. The report continues to state `nativeParity=NOT_TESTED` and `cacheBudgetsConfirmed=false`.

## Batch 3: native terrain fixture

The [native fixture proof](NATIVE-FIXTURE.md) passed on Windows in [verification run 40352382975](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40352382975). It checks every one of the runtime's 35,723 vanilla block states, uses real native block and biome implementations in the official engine, generates the complete 128 × 128 fixture at its configured 256-block height, and reproduces all 64 chunk hashes in a fresh JVM. The two retained index buffers use 32 MiB; this excludes palettes and engine/native memory.

The registry contract is bound in memory. No running server or generation-history directory is present, all source bytes remain unchanged, and temporary generation workspaces close and are deleted. The original core and native-boundary suites also remained green.

This clears the representative native-terrain fixture check. It does not clear production Overworld parity, native feature/structure completion, map output or total preview memory/cache confirmation. No custom terrain or noise implementation was introduced.

## Batch 4a: real Overworld native context

The [Overworld context audit](OVERWORLD-CONTEXT.md) passed in [Windows run 40356846358](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40356846358). It loaded the native registries and in-memory contract for 12 regions and 378 biomes, resolved the explicit native ancient-city placement, and exercised the 96 MiB full-height index allocation for a 128 × 128 area at Y = [-256,512). Source bytes and temporary-context cleanup checks passed. All 24 contract assertions and existing generation/native suites remained green.

World preview readiness remains false. The report identifies native world completion, explicit native placement completion, independent backend-build parity evidence and total memory confirmation as separate blockers. The allocation test does not produce terrain and is not proof of total engine/native or mesh-cache bounds. Saving/exporting remains a separate later workflow and must never be gated on these development checks or on per-edit baseline capture.

## Batch 4b.1: native structure starts and references

The [real Overworld structure-planning proof](OVERWORLD-STRUCTURES.md) passed on [Windows x64/Java 25](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40360484125) at code commit `ef969e74f5dfa9414ff47040b95478f120264a98`. With the pinned pack unchanged and an explicit recorded developer world configuration, the official planner produced configured ancient-city pieces at negative and positive coordinates. Both complete start NBT values matched direct calls to the official native factory, and configured ownership fingerprints matched. A complete 289-origin halo produced the expected city reference; a missing corner was rejected before target mutation. Resources closed, source/configuration bytes were preserved, scratch data was removed, and no history router or history directory was created.

This is structure-start/reference integration evidence, not a complete native baseline. Native structure block placement and features remain incomplete, total memory is unconfirmed, and all four world-preview blockers remain active. No generation algorithm was replaced or user pack repaired.

All five Windows suites passed: core repeatability, native boundaries, full-size native fixture, Overworld context audit and real Overworld structure planning.

## Batch 4b.2: actual ancient-city block service boundaries

The [block-service audit](NATIVE-BLOCK-BOUNDARY.md) passed on [Windows x64/Java 25](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40363924685) at code commit `a1975bd4dac2165d61d06dd5b80c6555c9e65d4f`. Both actual native calls were blocked before writing non-air blocks. Eroded terrain preparation requests a template manager through `WorldGenLevel.getLevel()` in `NativeStructureTerrainIntegrator`; native `StructureStart.placeInChunk` enters `CraftStructureTransformer`, which requests `getMinecraftWorld()`. Both reach the official finite fixture's explicit missing `ServerLevel` service.

Each call used a fresh native NBT copy of the real configured city and disposable full-height chunks with official biome samples. This air-input capability experiment is not generated Overworld terrain or a native baseline. The input chunk statuses and planned-start NBT remained unchanged, all partial results were discarded, no history was attached or created, source bytes were preserved and resources/scratch data closed cleanly. Full stacks and exact resource/field references are retained in CI artifacts. The unchanged official fixture was reused; no fake server or replacement generation algorithm was added. World-preview readiness remains blocked.

All five Windows suites passed on this code: core repeatability, native boundaries, native fixture repeatability, Overworld context and the extended real-pack structure audit.

## Batch 4b.3a: explicit native template service

The [standalone template-service adapter](NATIVE-TEMPLATE-SERVICES.md) passed on [Windows x64/Java 25](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40366896998) at code commit `d2e8e151062657c1784b6812cf5ea49adca2b00c`. Two source-hash-checked overloads pass an explicit template-manager supplier through existing native preparation code. Their terrain calculations and legacy world-based entry points are retained; original source checkouts stay untouched. All reports now record the template manifest SHA-256, and baseline comparison rejects missing or different template overlays.

Real ancient-city starts at chunk origins (-99,-21) and (42,19), seed 1337, carved 9,616 and 9,740 blocks respectively from solid fixture inputs. The standalone calls made 60 and 73 explicit template requests without accessing a server world. For each fresh start, every target voxel across Y=[-256,512) matched the legacy method's count and digest. A template-only ServerLevel test double was confined to the reference call, with other interactions rejected. This is adapter comparison evidence on artificial inputs, not complete native-world parity. Original NBT/status, source integrity, no-history and cleanup checks passed. All 26 contract assertions and local Gradle source-generation/selection checks passed.

The original world-based preparation and native piece calls still reach their known ServerLevel boundaries. The explicit service route resolves this eroded-carve template dependency only. The CraftBukkit piece callback, remaining world services, complete native features, an independent full native baseline and total memory validation remain unfinished; world-preview readiness stays blocked.

All five Windows suites passed: core repeatability, native boundaries, full-size native fixture repeatability, Overworld context and the extended structure/template-service proof.

## Batch 4b.3b: standalone native piece dispatch

The [native piece dispatcher](NATIVE-PIECE-DISPATCH.md) passed on [Windows x64/Java 25](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/40370040842) at code commit `94c233f6b22ad7fc3aa22c7fa46bdf5803a69513`. The small adapter retains native piece order, bounding intersection, reference position and `Structure.afterPlace`, calling existing native algorithms in the standalone context without Bukkit plugin callbacks. The source SHA-256 is pinned/recorded and required during baseline comparison, and LF checkout preserves those source bytes on Windows. The actual runtime StructureStart class hash is also recorded.

At seed 1337, real ancient-city starts at origins (-99,-21) and (42,19) placed 2,528 and 2,240 non-air blocks, with 88 and 63 distinct target states, in isolated air-input fixtures. Both complete target-state hashes over Y=[-256,512) matched the original runtime method with inactive plugin callbacks. Only the reference intercepted the callback constructor; the standalone path kept both server getters unavailable and used no callback substitute. Separate native dispatch contracts verified piece order, anchor, clipping, complete afterPlace input and empty-start behavior. Original NBT/status, source integrity, no-history and cleanup passed. All 28 dependency-free contract assertions passed locally and on Windows.

This establishes the tested native piece route on artificial inputs, not an assembled structure stage or complete world parity. Native preparation integration, foundations/stilts, features, remaining world services, independent full-native comparison and total memory checks remain unfinished. World-preview readiness stays blocked.

All five Windows suites passed: core repeatability, native boundaries, full-size native fixture repeatability, Overworld context and the extended structure/template/piece proof.
