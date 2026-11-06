# Prepared context-free display math contracts

Prepared 2026-11-04; updated 2026-11-06. `GlMathContracts` contains 14 groups for projection, pointer rays, navigation, clipping and conservative scalar/world culling. Together with 20 CPU renderer groups, 28 world-pipeline groups and 11 animation groups, the four context-free suites contain 73 prepared groups. The separate GPU harness adds 17 for 90 total. All sources compile; none has been executed. Renderer-stage completion, native parity and GPU correctness remain unverified, and unsupported previews stay disabled.

## No context or driver required

The suite uses five backend production helpers: `GlFailure`, `GlCamera`, `GlOrbitCamera`, `GlFrustum` and `GlWorldCulling`. They depend only on the CPU module and Java standard library. The harness invokes no LWJGL class, window/context creation, native library loading, shader, upload, draw or readback. Synthetic scalar fixtures supply original detached samples rather than evaluating a generator.

`Validate-RendererCpu.ps1` includes these five helpers, this harness and the shared CPU-only `WorldGpuFixture` by explicit path, alongside CPU production/tests. It deliberately does not sweep all backend/test sources, so future driver-dependent suites cannot silently enter this context-free runner. The default still compiles only. After renderer implementation is complete, explicit `-Execute` runs the four suite classes in separate JVM processes, stopping on the first failure.

## Prepared checks

| Group | Independent expectation |
| --- | --- |
| Camera bases | Unit orthogonal forward/right/up axes, consistent handedness and vertical-view fallback |
| Perspective projection | Analytic 90-degree near/far NDC planes, centered target and aspect-two near-plane edges |
| Center/corner rays | Closed-form normalized center/top-left directions and oblique clipping distances |
| Pointer projection | Multiple pixel-center rays project back to the original top-left coordinates; near/far ray endpoints agree with projection planes |
| Exact offsets | Small integer differences beyond double's exact integer range survive float conversion; rejected relative spans do not partially write output |
| Orbit navigation | Cardinal orbit poses, analytic pan distance/direction, pitch clamps, dolly limits and exact identity orbit/pan/dolly/equal-anchor rebase with argument guards |
| Anchor rebasing | Fractional target and eye offsets preserve world-relative position under exact integer-anchor changes; round trip and overflow rejection |
| Input guards | Invalid aspect, pointer, pose and frustum inputs fail explicitly; viewport edges remain half-open |
| Canonical frustum | Identity-volume inside/outside/translated bounds, closed plane contact and outward numerical allowance |
| Corner culling oracle | 256 fixed-seed AABBs compared with direct homogeneous transformation of all eight corners rather than plane extraction |
| Scalar range clipping | Shader-style scaled ranges reject disjoint frustum/clip scopes while retaining closed clipping contact |
| Full/empty fit | All eight configured scalar-bound corners project within the fitted view; empty output uses the full captured extent |
| Clipped fit | Known clip intersection centers and closer fit, retained exact source anchor, boundary-plane contact and explicit no-intersection failure |

The culling oracle treats clearly separated outside boxes independently from near-boundary numerical tolerance. It requires potentially visible boxes to remain admitted; this is conservative AABB testing, not a proof that each admitted box contributes a pixel. Projection/ray comparisons allow the expected float-matrix rounding while analytic double navigation checks use tighter tolerances. No measured performance claim is made.

## Compilation evidence and remaining scope

All 135 production sources, five harness sources and one fixture compile with Java 25 and LWJGL 3.4.1 (141 total). The actual context-free runner source set also compiles without external dependencies: 93 CPU production sources, five display helpers, four harnesses and one CPU-only fixture (103 total). Java compilation does not initialize the harnesses or run their assertions. The Windows PowerShell runner remains source-inspected only.

No test, worker/concurrency scenario, native adapter, GL context, shader, driver-state operation, GPU draw/readback or workflow ran. The prepared suite does not exercise connected view ownership, upload recovery, GPU culling submission, native world fitting, complex model overhangs or float pixel-edge parity. Those retain their existing deferred matrices. Native Windows compilation/execution, actual GPU comparison, final UI integration and combined application memory QA remain pending.

## Distant scalar focus preparation

The existing navigation group now also prepares end-to-end [grid-aware scalar focus](CAMERA-FIT.md#fractional-scalar-focus-at-distant-origins) near both signed-32-bit coordinate limits. Decimal/tiny spacing, unit/greedy original-sample mapping, nonzero height, preserved orbit/clipping settings, rebasing, legacy metadata, invalid grid receipts and exact overflow are checked when stage execution is enabled. Produced hits retain only immutable grid metadata, never source arrays. The suite remains 13 groups and the combined total remains 64. Full and dependency-free compilation passed; no assertion or GL/native operation was executed.

[Shared world group culling](TRANSLUCENT-CULLING.md) adds the fourteenth group, preparing exact scratch sizing, original bounds, lazy/cached decisions under reverse access, analytic clip rejection and disabled/empty behavior. Full Java compilation now covers 135 production sources, five harnesses and one fixture (141 files); the dependency-free runner set contains 103 files. All 90 groups remain unexecuted.
