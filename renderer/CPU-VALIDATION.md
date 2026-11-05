# Prepared CPU renderer validation

Prepared 2026-11-04; updated 2026-11-05. The `RendererCpuContracts` harness has 18 deterministic contract groups. [World-pipeline contracts](WORLD-CPU-VALIDATION.md) add 19 groups, [context-free display math](../renderer-gl/MATH-VALIDATION.md) adds 13 and [animation ownership/contracts](ANIMATION-OWNERS.md) adds 11, for 61 prepared groups total. All four harnesses compile; they have **not been executed**, and no group is claimed to pass. Renderer implementation is still in progress and execution remains deferred under the repository workflow. Unsupported previews remain disabled.

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

## Initial contract scope

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
| Map navigation and hits | Letterbox/half-open bounds, fit/pan/zoom anchor invariance and absent versus real-zero inspection |
| Full-height scalar capture | X-Z-Y ordering, fractional native coordinates, copied data and rejection instead of height truncation |
| Exposed-face coverage | A separate voxel-neighbor enumerator compared with expanded CELLS/GREEDY rectangles over 18 fixed-seed fixtures, plus a uniform cuboid |
| Scalar recoloring | Source-only equal-color admission, exact native volume, unchanged geometry, prepared colors and atomic insufficient-peak failure |
| Scalar front-face picking | Analytic single-bin plane distance, original value/direction, no partial budget hit, clipping and inside/back-face exclusion |
| RGBA builder | No incomplete publication, reversed source rows without cursor mutation, immutable pixel views and one-shot sealing |
| Original PNG pixels and receipts | Standard-library-built tiny RGBA fixture, straight alpha/transparent RGB, unchanged original dimensions, identical-content receipts and bad CRC/limit rejection |
| CPU admission lifetime | Retained input charge across original-input/coordinator close, lowered scratch and final account release |
| Shared hold replacement | No credit for external owners, atomic rejection, duplicate handles, foreign coordinator rejection and bounded account reuse |
| Completion inputs | Map/image completion ownership survives closure in an independently retained input without copying payload |

The exposure oracle enumerates occupied original cells and their six neighbors; it does not reuse the surface mesher to determine expected faces. Each expanded rectangle must cover each expected unit face once and retain its original sample/value/color. The relief oracle uses the known normal of the fixture plane rather than the production shading helper. The PNG fixture is written using standard chunk/CRC/zlib primitives, without a production decoder/encoder generating expected pixels. Synthetic source receipts explicitly identify the fixture and do not claim a native engine capture.

## Current verification evidence and remaining work

Compilation succeeded for all 129 CPU/OpenGL production sources plus four contract sources (133 total) with Java 25 and LWJGL 3.4.1. The default runner set also compiles without external dependencies: 88 CPU production sources, four display helpers and four harnesses (96 total). No contracts, worker/concurrency checks, native query, GL context, GPU upload/readback or workflow ran. The Windows PowerShell runner was inspected only.

This is an initial suite, not renderer-stage completion. Broader world block/model/fluid/material/atlas coverage beyond the prepared synthetic world suite, worker races/failure injection, full PNG/JPEG matrices, broader camera math beyond the prepared context-free suite, GPU state/upload/draw/retirement, real pixel comparisons and native source parity still require their planned verification. Sixteen native adapters still require genuine pinned Windows compilation/execution. Final UI integration and combined application memory/GPU QA remain pending. Do not infer readiness or enable supported previews from successful compilation or from a future synthetic CPU suite result alone.
