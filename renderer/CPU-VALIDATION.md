# Prepared CPU renderer validation

Prepared 2026-11-04; updated 2026-11-06. The `RendererCpuContracts` harness has 23 deterministic contract groups. [World-pipeline contracts](WORLD-CPU-VALIDATION.md) add 34 groups, [context-free display math](../renderer-gl/MATH-VALIDATION.md) adds 16 and [animation ownership/contracts](ANIMATION-OWNERS.md) adds 14, for 87 prepared CPU/context-free groups total. All four harnesses compile; they have **not been executed**, and no group is claimed to pass. Renderer implementation is still in progress and execution remains deferred under the repository workflow. Unsupported previews remain disabled.

## Compile now, execute at stage validation

On the supported Windows x64 environment with a Java 25 JDK:

```powershell
./scripts/Validate-RendererCpu.ps1
```

The default compiles CPU production sources, four context-free display helpers and all four contract harnesses into `build/renderer-cpu-validation/classes`. It uses an explicit UTF-8 source argument file, including quoted paths for repository locations with spaces. It executes no contract, includes no native adapter or driver-dependent backend source and does not require LWJGL, pinned native checkouts, a client asset JAR, a pack or a display server.

Once renderer implementation is complete and stage testing begins:

```powershell
./scripts/Validate-RendererCpu.ps1 -Execute
```

Execution is explicit rather than a CI trigger or default script side effect. Assertions use ordinary checks that throw `AssertionError`, so they do not depend on JVM `-ea`. Each suite runs in a separate JVM; the runner stops at the first failing group or process and exits unsuccessfully; only executed successful groups print PASS. A final count describes this synthetic CPU suite, never native or GPU parity. The runner is newly prepared and has not itself been run or validated on PowerShell/Windows.

## CPU contract scope

| Contract group | Independent expectation |
| --- | --- |
| Exact extents and clipping | Cell round trips beyond double's exact integer range, exclusive full-height endpoint and small exact anchor-relative clip offsets |
| Detached map capture | Original zero/absence, copied arrays, native spacing and distinct capture identity |
| Atomic map failure and cancellation | Invalid height/presence/nonfinite samples publish no partial source; cancellation stays separate |
| Gradient equality and RGBA arithmetic | Equal immutable ramp values/hash lookup, channel rounding including alpha and endpoint/nonfinite guards |
| Scalar range pixels | Extreme finite range midpoint without subtraction overflow and explicit constant-range pixels |
| Retained map recoloring | Exact source reuse for equal styles, immutable old pixels, old/new peak boundaries, lowered limits and wrong style |
| Native biome identity and alpha | Palette order preserves original numeric IDs, transparent present values remain inspectable and missing palette colors fail |
| Terrain relief | A plane with known slopes and upward light has independently calculated diffuse shading; edges/missing stencils stay flat |
| Map navigation and hits | Letterbox/half-open bounds, fit/pan/zoom anchors, exact identity navigation including signed zero, small zoom versus an exact affine oracle, argument/scale guards and absent versus real-zero inspection |
| Map/image boundary precision | Analytic half-open edge ownership after cancellation-prone pan/zoom, positive internal edges, upper interior corners, coarse screen coordinates and unchanged map/transparent-image samples |
| Full-height scalar capture | X-Z-Y ordering, fractional native coordinates, copied data and rejection instead of height truncation |
| Exposed-face coverage | A separate voxel-neighbor enumerator compared with expanded CELLS/GREEDY rectangles over 18 fixed-seed fixtures, plus a uniform cuboid |
| Scalar recoloring | Source-only equal-color admission, exact native volume, unchanged geometry, prepared colors and atomic insufficient-peak failure |
| Scalar front-face picking | Analytic plane distances, all six clipped normal planes in unit/greedy modes, closed near/far endpoints, decimal far-origin receipts, merged original-sample ties, original values and atomic face allowance |
| RGBA builder | No incomplete publication, reversed source rows without cursor mutation, immutable pixel views and one-shot sealing |
| Original PNG pixels and receipts | Standard-library-built tiny RGBA fixture, straight alpha/transparent RGB, unchanged original dimensions, identical-content receipts and bad CRC/limit rejection |
| CPU admission lifetime | Retained input charge, coordinator close and final release; direct account registration rollback for OOM/other Error, suppressed removal/close failures, replacement registry-copy failures, unchanged replaced handles and bounded-slot retry; atomic coordinator-close preparation failure/retry, unchanged open gate/owner flags, zero-byte account release, retiring reservation moves and last independent hold release |
| Shared hold replacement | No credit for external owners, atomic rejection, duplicate handles, foreign coordinator rejection, bounded account reuse; mixed fully/partially supplied groups, copy failure preserving every owner, and successful commit without live-registry removal callbacks |
| Completion inputs | Map/image completion ownership survives closure in an independently retained input without copying payload |

The exposure oracle enumerates occupied original cells and their six neighbors; it does not reuse the surface mesher to determine expected faces. Each expanded rectangle must cover each expected unit face once and retain its original sample/value/color. The relief oracle uses the known normal of the fixture plane rather than the production shading helper. The PNG fixture is written using standard chunk/CRC/zlib primitives, without a production decoder/encoder generating expected pixels. Synthetic source receipts explicitly identify the fixture and do not claim a native engine capture.

## Current verification evidence and remaining work

Compilation succeeded for all 134 CPU/OpenGL production sources plus four contract sources (138 total) with Java 25 and LWJGL 3.4.1. The default runner set also compiles without external dependencies: 93 CPU production sources, four display helpers and four harnesses (101 total). No contracts, worker/concurrency checks, native query, GL context, GPU upload/readback or workflow ran. The Windows PowerShell runner was inspected only.

This is an initial suite, not renderer-stage completion. Broader world block/model/fluid/material/atlas coverage beyond the prepared synthetic world suite, worker races/failure injection, full PNG/JPEG matrices, broader camera math beyond the prepared context-free suite, GPU state/upload/draw/retirement, real pixel comparisons and native source parity still require their planned verification. Sixteen native adapters still require genuine pinned Windows compilation/execution. Final UI integration and combined application memory/GPU QA remain pending. Do not infer readiness or enable supported previews from successful compilation or from a future synthetic CPU suite result alone.

[Detached scalar histograms](SCALAR-HISTOGRAMS.md) add the twentieth CPU group, preparing original-value/missing coverage, automatic/explicit ranges, stable legends, map/volume identity, display-range matching, complete-source admission and owned cleanup. All 72 groups remain unexecuted.

The separate [Windows GPU harness](../renderer-gl/GPU-VALIDATION.md) adds 18 prepared groups and its own explicit runner. It does not enter the dependency-free source set or change the four-suite CPU runner. Full compilation now covers 136 production files, five harnesses and one shared test fixture (142 files); the default CPU set contains 104 files, explicitly including the sixth context-free helper and CPU-only world fixture. All 92 groups across the five harnesses remain unexecuted.

[Admitted histogram reuse](HISTOGRAM-REUSE.md) adds one world CPU group: 75 CPU/context-free plus 18 GPU groups, all 93 unexecuted. Both compile sets retain their 142/104 source counts.

[Two-array face sorting](BOUNDED-FACE-SORT.md) reduces verified temporary sort payload and adds one world CPU group. All 94 groups remain unexecuted (76 CPU/context-free, 18 GPU); full/default compilation now covers 143/105 files, including 94 dependency-free CPU production sources.

[GPU transfer replacement admission](../renderer-gl/REPLACEMENT-ADMISSION.md) adds two GPU groups. The four CPU/context-free suites remain at 76 groups; the total is now 96 with 20 GPU groups, all unexecuted.

[World material refresh](../renderer-gl/WORLD-MATERIAL-REFRESH.md) adds two GPU groups. All 98 groups remain unexecuted: the unchanged 76 CPU/context-free groups plus 22 GPU groups. Full compilation remains 143 files; the dependency-free set is unchanged at 105.

[Initial world atlas adoption](../renderer-gl/INITIAL-ATLAS.md) adds one source-inspected GPU group. All 99 groups remain unexecuted (76 unchanged CPU/context-free, 23 GPU). The initial source-only 2021328 compilation gap has now been resolved by a successful full 143-file compilation; current group totals appear in the following update.

[Owned world controls](../renderer-gl/WORLD-CONTROL-RECOVERY.md) add one GPU group and extend initial-atlas retry coverage. All 143 renderer/validation files now compile, resolving the source-only 2021328 gap. All 100 groups remain unexecuted: 76 unchanged CPU/context-free and 24 GPU. The unchanged dependency-free set remains 105 files.

[Displayed atlas material refresh](../renderer-gl/DISPLAYED-ATLAS-REFRESH.md) adds two GPU groups and extends the connected initial-clock group. Full compilation remains 143 files, including the unchanged 105-file dependency-free set. All 102 groups remain unexecuted: 76 CPU/context-free and 26 GPU.

[Worker abandonment recovery](WORKER-ABANDONMENT.md) adds one world CPU group. Both full and dependency-free compilation pass (143/105 files). All 103 groups remain unexecuted: 77 CPU/context-free and 26 GPU. The new asset READY fixture opens only its own tiny generated ZIP during eventual execution, never a user archive.

[Sparse atlas clocks](SPARSE-ATLAS-CLOCKS.md) add three animation groups, bringing that harness to 14 and the CPU/context-free total to 80. The separate GPU harness contains 32, for 112 prepared groups overall. Full/dependency-free compilation passes (143/105 files); all assertions remain unexecuted.

[Hierarchical original-range picking](PICKING-RANGE-HIERARCHY.md) adds three world groups for differential traversal, markers/alpha/ties and exact admission/ownership. There are now 83 CPU/context-free and 32 GPU groups (115 total), all unexecuted. Full/default compilation covers 143/105 files.

[Scalar inspection hierarchy and visited-face budgets](SCALAR-RANGE-HIERARCHY.md) add three groups, bringing this harness to 23. Current totals are 118 prepared, unexecuted groups: 86 CPU/context-free and 32 GPU. Full/default compilation covers 143/105 files; no renderer/worker/GPU assertions have executed.

[Scalar draw hierarchies](../renderer-gl/HIERARCHICAL-SCALAR-CULLING.md) add the sixteenth context-free math group and the seventh context-free GL type/helper in the compile-only runner. Full/default compilation covers 144/106 files; all 119 groups remain unexecuted (87 CPU/context-free, 32 GPU). The PowerShell runner change was source-inspected only.

[Dirty atlas rectangles](ATLAS-DIRTY-RECTANGLES.md) add three animation groups, bringing that harness to 17 and the CPU/context-free total to 105. Together with 36 GPU groups, all 141 prepared groups remain unexecuted. Full/default compilation passes (144/106 files).

[Bounded GPU retirement](../renderer-gl/BOUNDED-RETIREMENT.md) adds two GPU groups. The CPU/context-free suites remain at 105 groups; GPU groups now total 38 for 143 overall, all unexecuted. Both compilation sets pass (144/106 files).
