# Prepared Windows OpenGL stage validation

Prepared 2026-11-06. `GlGpuContracts` contains 24 synthetic GPU groups. Java 25/LWJGL compilation passes for all 143 renderer/validation sources, including the previously pending initial-atlas change and current owned-lighting/retry changes. **None of these groups has been executed**. The four earlier CPU/context-free suites contain 76 prepared, unexecuted groups, making 100 across five harnesses. Renderer implementation and genuine native/GPU validation remain pending. Unsupported previews stay disabled.

## Build and execution gates

The existing `renderer-gl` Java-library project retains its separate CPU-module dependency and application-supplied context/natives. A dedicated `gpuContractNatives` configuration adds the three pinned Windows x64 bundles only to the explicit `gpuContracts` JavaExec task. The production runtime/API configurations gain no native bundle dependency. Ordinary builds do not run this harness; the standard Gradle `test` task is disabled, and no workflow invokes the new task.

From PowerShell 7 on Windows x64 with Java 25 x64 JDK and a compatible installed Gradle:

```powershell
./scripts/Validate-RendererGpu.ps1
# Supply an existing Gradle command explicitly when needed:
./scripts/Validate-RendererGpu.ps1 -Gradle C:/tools/gradle/bin/gradle.bat
```

The default removes earlier owned GPU success evidence, checks the supported JDK, temporarily selects that JDK for Gradle, cleans module build output and compiles test sources. It creates no GLFW context and executes no renderer assertions. It restores the previous `JAVA_HOME` even after a build failure. Dependencies resolve through the existing Maven Central repository; no Java runtime is bundled or installed by the script.

Only after renderer implementation is complete and stage execution is permitted:

```powershell
./scripts/Validate-RendererGpu.ps1 -Execute
```

The explicit path compiles first, then launches the GPU harness in its own JVM. It needs an actual Windows graphics session with a working OpenGL 3.3 core driver. It creates one hidden 64×64 GLFW window, makes its context current and destroys it after all device owners have closed. Missing GLFW/native libraries, wrong OS/JDK, context creation, capability/shader/pixel failures, timeout, cleanup failure or a recorded GLFW error fail the attempt. There is no skip-as-pass path and no software/mock context substitute. A software driver that the system actually selects is recorded by its real driver identity; success would apply only to that tested environment.

## Prepared groups

| Group | Contract scope |
| --- | --- |
| Context provenance and boundaries | Required driver metadata, immutable detached features, hardware limits, foreign-thread/capabilities guards and rejection before target allocation |
| Transfer replacement admission | World/map/image ACTIVE and READY work, connected world/view tokens, deficient initial admission, fresh lease consumption and successful subsequent replacement |
| Output replacement admission | Readback/animated-atlas ACTIVE and READY output, exact original pixels/receipts, fresh plan consumption, host state and subsequent replacement |
| World material copies | Separate immutable streams, full/copy pixels, both lighting modes, exact transfer totals, source closure, repeated copies, empty/marker/cutout and translucent provenance |
| World material recovery | Stale/owned inputs, deficient admission, cancellation across copy phases, queue preservation and connected-view material adoption |
| Initial world atlas | Requested clock before first presentation, material refresh, scheduled/interpolated pixels, unchanged samples and failed initial animation admission and explicit retry gates/recovery |
| Owned world lighting | Queue/view consuming handoffs, exact tracked overlap, stale/foreign/closed rejection, immutable frame delivery and shutdown |
| Shader programs | Real compilation/linking of map/image, scalar and world renderer programs |
| Original image pixels | Tiny-step row upload, complete-only take, exact top-left RGBA readback, transparent RGB/alpha and original decode receipts |
| Map pixels | Supplied scalar samples, exact raster RGBA/orientation, absent-value inspection, source UUID and completed-image readback receipt |
| Navigation | Nearest 2× magnification, panned clear margins, original-pixel hit scope and immutable earlier frames |
| Upload recovery | Cancellation without partial take, invalid replacement preserving progress, explicit token invalidation and complete resubmission |
| Readback recovery | Invalid replacement preserving READY output, exact owned pixel transfer and single delivery |
| Host state | Unusual viewport/scissor, blend/depth/cull/color masks, sRGB/discard/clip distance, active texture and pack/unpack row state restored across upload/draw/readback |
| Scalar display | Supplied full-height samples, bounded instance upload, nonempty rendering, disjoint clip culling, original volume/range identity and exact frame readback receipts |
| Scalar GPU recoloring | Exact shared geometry, bounded GPU copies/four-byte CPU colors, full-upload pixel equivalence, batch offsets, copy bindings, cancellation/ownership and connected rapid recolor/histogram adoption |
| World geometry and identity | Textured neighbor-cull meshes, exact long native coordinates/state/biome picking, clipped submission and independent CPU inspection |
| World alpha | Exact cutout coverage/inspection, ordered two-layer analytical blending, missing/stale camera order rejection |
| World animation | Scheduled/interpolated red/blue textures, bounded atlas page/patch updates, immutable earlier frames and stale baseline rejection |
| World materials | Explicit tint/final lighting, consuming/foreign material owner guards, exact lighting policy and hostile host-state restoration |
| World diagnostics/recovery | Empty/placeholder geometry, original diagnostic picking, upload cancellation and completed-frame preflight/retry/lifetime |
| Shared world group culling | Separate immutable enabled/disabled pixel results, exact sorted-face coverage, cache reuse across material passes, draw-scratch admission/release and deficient-budget target/state preservation |
| Scalar draw admission | Separate enabled/disabled clipped pixels, full original face coverage, source/style receipts, temporary CPU charge, deficient shared memory and precision-failure target/state preservation |
| Budgets and lifetimes | GPU-budget rejection without allocation, independently owned readback surviving frame/device closure and retained wrapper release |

The synthetic PNG has four explicit RGBA texels, including transparent color. Pixel expectations use those original values rather than a second renderer run. Navigation expectations come from integer nearest-neighbor replication and an explicit clear border. Scalar checks distinguish rendered interior from the same target's fully clipped clear output; they do not claim a native shading/pixel baseline. All loops have a twenty-second deadline plus a step ceiling. Hard transfer/action/work limits are checked separately from cooperative elapsed-time targets.

Each group uses a fresh bounded `GlDevice` and shared `RenderMemory`; device shutdown must release all known charges/accounts. A single original context is used across the groups. This does not exercise simultaneous devices on different contexts or every extension-specific host-state branch.

## Driver diagnostics

`GlDevice.driver()` returns a detached immutable `Driver` record captured once at construction, before staging/admission allocation. It includes actual vendor, renderer, OpenGL/GLSL strings, numeric version, profile/flags, hardware texture/renderbuffer/attribute/combined texture-unit limits and the optional features used by backend state isolation. Strings/features have explicit diagnostic bounds. The receipt contains no context/native handle and remains readable off-thread or after device closure. It neither changes capability selection nor grants preview readiness. GPU operations retain their original thread/context/capabilities guards.

## Successful execution evidence

The wrapper first removes `renderer-gl/build/validation/gpu-contracts.properties`, including for compile-only or rejected runtime/build attempts. The harness also removes its configured report before checking execution prerequisites. It publishes that report atomically only after all 18 groups and context cleanup finish without error. Individual PASS log lines during a failed run are not a complete-stage success report. No report is supplied or claimed from this implementation batch.

A real report records the synthetic scope, successful group names/count, start/completion instants, OS/architecture, Java and LWJGL versions, and the actual driver receipt. It is a narrow run result, not a source/dependency fingerprint manifest, independently captured native baseline, final renderer approval or an authorization to enable previews. Use fresh execution with the intended checked-out sources and retain the corresponding build/commit information when collecting final validation evidence.

## Actual preparation and remaining checks

Direct Java 25 compilation passed for all 136 CPU/OpenGL production sources plus five harness sources and one test fixture (142 files). The 104-file dependency-free set also compiles and continues to exclude this LWJGL/GLFW harness. No Java harness, GLFW initialization, shader, upload, readback, native adapter or CI workflow was run. Gradle/PowerShell task execution remains source-inspected only in this workspace.

The GPU harness is an initial real-driver path. The new [synthetic world groups](WORLD-GPU-VALIDATION.md) prepare small mesh/material/transparency/animation cases; broader world coverage and native visual comparisons, broader connected-view asynchronous workers and histogram frame submissions, hostile indexed/extension state, wrong native contexts/devices, allocation/fence/restoration fault injection, interactive Windows GUI/DPI behavior and performance/total-memory measurements still require their planned renderer/integration validation. The 18 groups do not replace those checks or the earlier CPU suites.
