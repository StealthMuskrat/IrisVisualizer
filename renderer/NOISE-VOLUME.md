# Native scalar volumes and display boundary surfaces

Renderer implementation, 2026-11-03. All 110 CPU/OpenGL Java sources compile on Java 25 with LWJGL 3.4.1: 79 CPU and 31 GL. Thirteen native adapter sources are syntax-parsed and source-manifest checked only. Genuine pinned Windows native compilation remains pending. No native sampling, mesh/worker execution, tests, shader compilation or GL operations ran. Unsupported previews remain disabled.

This batch supplies the CPU/native input and background display-mesh portion of the specified 3D noise preview. A dedicated GPU scalar uploader/draw/frame path and final UI routing are still required. Scalar output is deliberately separate from `WorldSnapshot`, block meshes, vanilla material atlases and native block/biome IDs. No synthetic Minecraft state or biome is assigned to a scalar sample.

## Full-height immutable scalar data

`NoiseVolume.Grid` combines the existing exact horizontal sampling grid with the full configured vertical interval. The convenience constructor uses 128 × 128 horizontal samples at one block per sample. X is fastest, followed by Z and Y. Every integer world Y from minimum inclusive to maximum exclusive is sampled; there is no vertical sampling stride or height truncation. Explicit horizontal scale changes sample coordinates and display-bin width, without modifying the native sampling policy.

`NoiseCapture.capture` checks version, dimensions, sample count and source-plus-copy bytes before allocating or calling a sampler. It then invokes `Sampler.begin`, visits each coordinate, accepts an explicit present/absent result and reads the original finite double. `complete` runs before and after the validating immutable copy, with cancellation gates. `NoiseVolume.capture` is also available for caller-frozen arrays, with its output-payload limit; caller-owned inputs need separate peak admission when using that lower-level API.

The volume retains a fresh capture UUID, original generation/resource/policy context, grid, private double/presence arrays and present/absent/minimum/maximum summary. Present values, including negative and out-of-display-range values, remain unchanged. Nonfinite present values, invalid presence flags, wrong lengths, version/limit failures and native exceptions publish no partial arrays or summary. Absent samples expose no invented scalar. Cancellation propagates separately. Public access never exposes a mutable backing array.

## Native policies and actual dimensional behavior

The new `NativeNoiseSampler` calls the exact pinned Iris resource APIs. Its source/resource key, loader, engine data, seed, native healthy-session gate and complete world-height interval must match before output allocation. Every present probe checks the live native session, and both completion gates perform full context validation. The host freezes and owns the session/resources throughout; this adapter creates no native executor or concurrent generation task.

| Adapter / policy constant | Actual native call and boundary |
| --- | --- |
| `generator` / `GENERATOR` | `IrisGenerator.getHeight(x, y, z, seed)` on the exact engine-loaded generator |
| `expression` / `EXPRESSION` | The native expression's cached stream, created using its existing native `RNG(seed)`, then `stream.get(x, y, z)` |

The pinned generator's three-coordinate method currently forwards to implementation that ignores Y. This adapter preserves that actual native behavior, including Y-invariant volumes; it does not fabricate 3D variation or claim the generator computes voxel occupancy. The expression stream explicitly provides distinct two- and three-coordinate evaluators and receives actual world Y here. Native resource caches, evaluation errors and stream semantics remain unchanged.

`IrisNoiseGenerator` currently exposes the reviewed two-coordinate inline-layer path only. This batch does not invent a three-coordinate overload or reinterpret an inline layer as a complete generator. Other 3D noise/style/terrain resources still require their applicable native context and producer.

`NativeCapture.generatorVolume` and `expressionVolume` wrap these operations with the existing shared admission/healthy-session/cancellation protocol and return `RenderInput<NoiseVolume>`. Their known working bound is reserved before native resource access or arrays, then shrinks to actual detached bytes after the callback unwinds. No native engine, stream, RNG, generator, expression or sampler escapes the successful result. Existing external native/JVM memory responsibilities still apply.

Primary pinned source review: [IrisGenerator](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/core/src/main/java/art/arcane/iris/generation/noise/IrisGenerator.java) and [IrisExpression](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/core/src/main/java/art/arcane/iris/generation/noise/IrisExpression.java). Retrieved source bytes matched the local pinned source copies; Git blob IDs are recorded in the native bridge manifest. This source inspection is not native API execution or fidelity testing.

## Explicit display interval and boundary mesh

`NoiseSurface.Style` has a finite closed inclusion interval plus an explicit scalar display color range/ramp. The interval selects captured samples for visualization only. It does not classify native terrain, create blocks, alter scalar values or write pack settings. Colors use the existing bounded scalar normalization, including extreme finite ranges. This initial boundary representation requires opaque ramp stops; translucent volume ordering is unsupported and rejected explicitly.

Each included present sample occupies one display bin: `[sampleX, sampleX + horizontalScale]`, `[worldY, worldY + 1]`, `[sampleZ, sampleZ + horizontalScale]`. The scalar was sampled at that bin's minimum coordinate, with no hidden half-cell shift. These bins visualize discrete native values; they are neither interpolated isosurfaces nor a reconstruction of native generated voxels.

`NoiseSurface.prepare` scans the whole admitted volume twice. The first pass counts included samples, outward boundary faces and actual bounds, rejecting face/source/working limits before arrays. The second emits exact-sized arrays. A face shared by two included present samples is omitted. Volume edges, absent neighbors and excluded neighbors are display boundaries. No native opacity, fluid height or hidden block-face policy is inferred.

Faces are stored deterministically by original sample order and DOWN/UP/NORTH/SOUTH/WEST/EAST direction order. Each requires one original sample index and one direction byte: five primitive bytes per face, with no intermediate packed vertex/index arrays. Colors are derived from original values when needed. The surface retains its exact volume and display style. `face(index)` gives detached inspection metadata: volume UUID, original sample index/world coordinate/value, face direction and display RGBA, with no array graph retained in that receipt.

Empty/all-excluded/absent volumes produce successful zero-face surfaces with no bounds, while retaining their full captured data. A failure publishes no partial mesh/usage/bounds. Threshold/color changes rebuild only display surfaces from the same immutable samples; they do not call a native sampler. Current block/biome world meshing and material/picking paths remain unchanged.

## Background preparation and ownership

`NoiseSurfaceWorker` runs display-mesh preparation on one daemon thread, with one active and one replaceable pending request. It never samples native resources. Rejected local/shared admission preserves previous work, completion, revision and cancellation flags. Accepted submission cancels obsolete active work cooperatively, drops pending/untaken output and schedules the latest display settings. `invalidate` immediately advances the revision without replacement input. Status contains a volume UUID and bounded diagnostics, not volume arrays.

The consuming `submit(RenderInput<NoiseVolume>, style, limits)` overload retains the source through destination admission, requires the same participating coordinator for tracked input, and closes the supplied wrapper on every outcome. Retain an independent source owner first for retry or later display changes. Raw requests keep their existing external-ownership responsibility.

`take(expectedRevision)` transfers a complete `Completion` exactly once. Its `RenderInput<NoiseSurface>` owns both the surface arrays and retained source-volume charge; borrowed `surface()` access does not extend its lifetime. Retain the input for another consumer. Worker invalidation/close drops only its own work; caller-transferred owners remain live. Cancelled active reservations persist until actual stack unwind, and completed holds shrink to actual source-plus-mesh bytes afterward. Idle replacement credits no active or independently retained owner. Shutdown does not block the context/UI thread.

| Resource | Default | Hard bound |
| --- | --- | --- |
| Horizontal sample count | 128 × 128 | 4,096 per axis and aggregate sample bound |
| Full configured height | Entire native interval | 4,096 samples; higher intervals fail without truncation |
| Aggregate scalar samples | Bound only; no silent reduction | 16,777,216 |
| Detached scalar/presence arrays | 9 bytes per sample | 144 MiB |
| Sampling arrays plus validating copy | 18 bytes per sample | 512 MiB configured capture working bound |
| Display boundary faces | 262,144 | 1,048,576 |
| Display mesh arrays | 5 bytes per emitted face | Also source-plus-output working bound |
| Surface job working ceiling | 512 MiB | Same |
| Active plus pending surface jobs | 1 GiB | Same; lowerable |

Java object/collection/receipt overhead, arbitrary caller-held arrays, native evaluator/cache/session memory, future GPU storage and unrelated jobs remain outside these primitive estimates. Optional `RenderMemory` holds coordinate the known payloads only. Too many boundary faces, excessive full-height source data or shared pressure cause explicit failure; geometry, samples, area and Y are never silently dropped to fit.

## Deferred verification and remaining rendering work

At renderer completion, compare both native policies against independently queried pinned resources at positive/negative/fractional X/Z, every configured Y, extreme finite/zero values, multiple seeds and exact loader/isolation context. Confirm generator Y-invariance remains native, expression 3D coordinates are passed unchanged, native logged/session faults fail the whole output, and no replacement noise/RNG formula runs.

Cover exact grid endpoints/index order, full-height rejection, all-absent/empty selections, immutable input copies, summaries, nonfinite values, conflicting presence/lengths and every primitive bound. Compare two-pass boundary faces with a simple reference for solid boxes, holes, checkerboards, thin dimensions, interval equality and absent interior samples. Verify original scalar identity, outward directions, deterministic face order, display-bin bounds, opaque color checks and no generation calls after display-only changes.

Exercise cancellation in sampling/copy/count/emission, final native gates, latest-only worker adoption, failed admission preserving prior state, input/hold transfer, retained retries, foreign/closed coordinator, queued/active/READY shutdown and allocation failure without partial output. Dedicated GPU scalar upload/draw/completed-image/readback/picking integration is unfinished. Real Windows shader/pixel checks, native fidelity and final editor routing remain pending. No tests ran for this batch.
