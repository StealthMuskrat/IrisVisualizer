# Standalone feasibility gate

Inspection date: 2026-10-26. **The official core generated eight full-height Overworld chunks on Windows. The overall probe failed during shutdown, so repeatability and native parity remain unverified.**

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

Verification is pending for this batch. It must complete both seeds and their fresh-session repeats, close the engine and disposable workspace successfully, preserve the input pack fingerprint, and pass the selected native boundary tests on Windows.
