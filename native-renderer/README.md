# Pinned native renderer bridge

This source adapter reads the official Iris/VolmLib native objects into detached renderer data. It does not create an engine, bootstrap registries, execute client classes, generate missing chunks, start a server, alter a pack or enable a preview. The existing renderer endpoints can consume its successful snapshots/maps.

**Validation status:** the dependency-free renderer/OpenGL sources compile. These thirteen native adapter sources have been reviewed against pinned upstream APIs but have **not yet been compiled against the genuine Windows native dependency classpath or executed**. That classpath is not installed in the current workspace. Native compilation, native comparisons and renderer-stage tests remain pending. No substitute native classes or mocked compilation are used as verification.

## Context and ownership

`NativeRenderContext` borrows a prepared `Engine`, its actual `Registry<Biome>`, the exact `WorldSnapshot.Source`, and a mandatory live `currentAndHealthy` gate from the native session owner. The gate must reject closed/obsolete epochs and all reported native errors, including logging reports that do not set `engine.hasFailed()`. It must cover the actual pack fingerprint, isolation overrides and resource context for that receipt. Callers must freeze native chunks/resources and hold the session's ownership/serialization guard through the whole read; a BooleanSupplier is not a lock or generation-completion proof.

The bridge checks Windows x64 / Java 25, native Minecraft version, pinned declared backend build, native engine seed/dimension, engine failure and the host's session gate. Use `NativeWorldCapture.PINNED_BUILD` when constructing this adapter's receipt. Matching receipt text does not independently attest a loaded binary or prove the pack fingerprint; source preparation/build provenance and the session owner remain responsible for those identities. Successful output grants no `PreviewReadiness` capability.

All methods are synchronous worker-side operations. The host must serialize them with generation/hotload/native context work and perform at most one active generation task per preview. The adapters create no extra executor. They borrow the native session and never close it. Final detached results retain no native registry, engine, chunk, generator, stream or callback.

## Full native volume capture

`NativeWorldCapture.capture(context, extent, chunks, limits, cancelled)` accepts completed native chunk records containing actual `Hunk<NativeBlockState>` / `Hunk<NativeBiome>` arrays. Requested extents must retain the engine's entire configured vertical range and fit signed 32-bit native block coordinates. Chunk inputs must cover exactly the intersecting chunk rectangle, with no duplicate/out-of-area entries and 16 × full height × 16 arrays. Partial horizontal edge chunks are read only within the explicit requested area. Negative coordinates use floor division/modulo.

The adapter reads native cells in the renderer's X-fastest, then Z, then Y order. Hunk Y is the native engine's relative height index; the detached extent retains the actual minimum Y. Missing blocks/biomes are errors; neither air nor a default biome is substituted. Native generation itself must establish completed chunk data before capture.

Per-operation identity caches detach each used registry state/biome once. A vanilla block retains `Block.getId`, exact `ModdedBlockState` serialization, registry block ID, actual property values and complete property domains. Registry ID round-trip, serialization and namespace checks run before publication. Deferred/custom placement is rejected instead of rendering its base block. Iris aliases already resolved to genuine vanilla states retain the resulting actual state; this layer does not resolve aliases itself.

Biome output retains the supplied registry's numeric ID, actual key and original `ModdedBiome` key, with equality checks against the native handle. Custom native biome registry keys are retained; unsupported external **blocks** remain excluded. Cache hits still reject inconsistent original keys. Native object identity caches are bounded per operation; no global table or per-voxel domain duplication is introduced.

`WorldCapture` performs bounded streaming detachment, preserves palette IDs, checks conflicting identities/text/domain budgets, and then invokes the immutable snapshot capture API. It checks cancellation through reading/copying and invokes native completion guards before and after the detached copy. Failed results contain detached receipts/problems only, with no partial snapshot or native reader graph. Unsupported vanilla visuals are handled later by the existing model/visual placeholder path, preserving these original states.

## Native maps

`NativeMapSampler` uses the prepared engine's official methods. Its `MapSamples.Context` must match the native source receipt and the selected sampling-policy constant exactly.

| Method / policy | Native input and coordinate convention |
| --- | --- |
| `terrain`, `TERRAIN_DRY` | `Engine.getHeight(floor(x), floor(z), true)` plus native minimum Y |
| `terrain`, `TERRAIN_FLUID` | `Engine.getHeight(floor(x), floor(z), false)` plus native minimum Y |
| `biomes`, `BIOMES` | Official `trueBiomeDerivativeStream.get(x,z)` at exact fractional sample coordinates, then actual native biome registry detachment |
| `generator`, `GENERATOR` | Loaded `IrisGenerator.getHeight(x,z,seed)`, using the native two-dimensional resource policy |
| `expression`, `EXPRESSION` | Loaded `IrisExpression.stream(new native RNG(seed)).get(x,z)`; native expression evaluation and stream caching remain unchanged |
| `noiseLayer`, `NOISE_LAYER` | A specifically resolved inline `IrisNoiseGenerator.getNoise(seed,x,z,engine.getData())`; caller supplies its exact owner/resource pointer |

Generator/expression resources must have the selected resource type/key and belong to the exact native engine data loader. An inline noise layer is explicitly distinct from a complete top-level generator. No noise generator, selector, distribution, interpolation rule, seed mixer or registry is reimplemented. Original finite scalar values remain available for inspection without normalization or clamping; display colorization occurs separately in `MapRaster`.

The terrain policy samples the native integer height API with explicit floor quantization; fractional map scale is retained in the grid. Native relative heights are translated to world Y, matching the upstream cursor/generator convention. Native surface derivative biome maps represent that stream's policy; they are not substituted for generated 3D biome cells. Isolation and dimension-stack behavior remain in the prepared native engine. Resource/session ownership must match the requested map context.

`MapCapture` preflights grid and input/copy bytes before sampling, collects exact native biome identities, and checks explicit presence, finite noise, full-height terrain bounds, text/count limits and identity conflicts. It calls native completion guards before and after immutable capture. Native samplers mark every successful queried point present; native exceptions or session faults fail the complete result. The generic sampler API can carry explicit absent samples, but an all-absent biome map without a supplied identity fails instead of inventing a palette. Cancellation propagates separately.

Pass successful maps to `MapRasterWorker` and `GlMapQueue`; pass successful volume snapshots plus explicit native visual choices/visibility to `WorldVisuals` / `WorldRenderWorker`. Native block/fluid/special visual geometry, native tint factory/output validation and optional lighting producers remain separate pending work. The explicit weighted-selection path below supplies model choices with a caller-verified native random policy; its genuine API/runtime validation remains pending. These adapters supply no rendered approximation for them.

## Native model input decisions

`NativeModelInputs.capture` now detaches actual native render-shape decisions for the original snapshot palette, guarding fluid contents, block entities and missing native offsets before accepting a model or invisible shape. The combined scene path captures actual per-cell offsets first; the legacy overload keeps offset-dependent states guarded. Its immutable output can feed `WorldRenderWorker.ModelRequest` after the native session guard is released. The same CPU worker inspects deterministic unit-weight models, guards missing native tint and other unsupported visuals, then assembles the complete scene. No PRNG, fluid/special renderer, offset algorithm or neighbor visibility is invented. See [MODEL-INPUTS.md](../renderer/MODEL-INPUTS.md). Native model-decision API compilation and runtime fidelity remain pending with the other native adapter sources.

## Native block visibility and combined scene input

`NativeVisibility.capture` now calls the vanilla three-argument `Block.shouldRenderFace` for exact captured neighbor states, retaining all unknown boundary faces. It streams a single privately owned mask, memoizes bounded state-pair/direction answers and checks native-query/probe budgets without replacing native shape logic. `NativeSceneInputs.capture` combines detached native model decisions and this visibility input under a shared catalog/world/offset/mask/choice-copy budget, returning a request for the single CPU scene worker without retaining the native session. See [NATIVE-VISIBILITY.md](../renderer/NATIVE-VISIBILITY.md). Genuine native compilation and actual visibility comparisons remain pending; fluid-specific culling and visual producers are separate.

## Native model offsets

`NativeOffsets.capture` calls vanilla `BlockState.getOffset` at exact original positions under the borrowed native session. It checks original registry identities and full configured height, then streams double translations into a bounded private palette and per-cell index. A no-offset palette uses the explicit zero-storage mode. `NativeSceneInputs` captures offsets before native model decisions and visibility, deducts their payload from later phase allowances and returns only detached data. Shared model prototypes stay unchanged; world meshing applies the captured translations. See [OFFSETS.md](../renderer/OFFSETS.md) for placement, source references, limits and deferred verification. No native offset formula or random policy is reproduced.

## Native weighted scene inputs

`NativeWeightedInputs.capture` now combines actual native offset/shape/visibility capture with bounded per-cell weighted/multipart model selection. The caller supplies an exclusively owned native random source whose factory must match the pinned renderer; class names and receipts do not establish that match. Native `BlockState.getSeed`, native weighted lists and the reviewed vanilla multipart seed-reset contract determine selected alternatives. Canonical interval tickets identify those selected models, and repeated combinations share one choice/prototype. Full-volume assignment/copy admission, native list-weight/query limits, cancellation and final healthy-session checks publish no partial request or retained native graph. See [WEIGHTED-CHOICES.md](../renderer/WEIGHTED-CHOICES.md) for source references, random-source responsibility, guards and deferred verification. The legacy deterministic path keeps its weighted guard. The dedicated selection source stays separate from generation. Client execution and preview gates remain unchanged.

## Budgets and exclusions

| Resource | Default / hard bound |
| --- | --- |
| Detached native states / biomes | 65,536 / 8,192 |
| Volume cells | 16,777,216, full configured height |
| Volume capture primitive peak, including detached index copy | 256 MiB default; lowerable, 512 MiB hard |
| Aggregate native volume palette text/domains | 16 Mi characters |
| Supplied chunk records | 4,096 default; 65,536 hard |
| Default map grid | 512 × 512 samples, configurable scale |
| Map grid/count | 4,096 per axis / 4,194,304 total |
| Map captured primitives | 64 MiB; 5 bytes/sample terrain/biome or 9 bytes/sample noise |
| Map sampling arrays plus detached copy | 128 MiB |
| Native map biome identity text | 1 Mi characters |

The volume peak preflights two integer input arrays and their immutable snapshot copies, 16 bytes/cell. Identity maps/palettes have explicit count/text bounds but are not heap-size measurements. Existing live native chunk arrays/objects, engine and stream caches, generated resources, parser scratch, logging/session infrastructure and other native/JVM memory are outside these primitive totals. They require combined application admission together with renderer/GPU jobs and external owners. Over-budget requests fail; configured height, area, samples and features are never silently reduced.

## Pinned source/build wiring

The adapter targets `iris.lock.json`: Iris `c3359e0a16438b1bb8468901c6171dcefdf63f7f`, VolmLib `210705c5ab1321dc74b86b48457fd6d5e0452944`, Minecraft 26.3 / Windows x64 / Java 25. [bridge-sources.json](bridge-sources.json) records every adapter file hash and the reviewed VolmLib source blobs. Native state serialization/handle APIs were inspected in the pinned [ModdedBlockState](https://github.com/VolmitSoftware/VolmLib/blob/210705c5ab1321dc74b86b48457fd6d5e0452944/native/minecraft-common/src/main/java/art/arcane/volmlib/nativelib/minecraft26_2/modded/ModdedBlockState.java) and [ModdedBiome](https://github.com/VolmitSoftware/VolmLib/blob/210705c5ab1321dc74b86b48457fd6d5e0452944/native/minecraft-common/src/main/java/art/arcane/volmlib/nativelib/minecraft26_2/modded/ModdedBiome.java). Native height, surface derivative and noise paths were inspected in the pinned Iris `Engine`, `IrisComplex`, `IrisGenerator`, `IrisNoiseGenerator` and `IrisExpression` sources.

On the prepared Windows source environment, the compile-only command is:

```powershell
./scripts/Compile-NativeRenderer.ps1
```

It checks pinned clean upstream checkouts and the compatibility source hash, then applies the existing feasibility source overlays and the new renderer-native init script to the genuine offline native source set. The init script checks bridge manifest pins, exact source coverage and hashes. The only requested task is `:adapters:bukkit:nms:v26_3_R1:compileOfflineJava`; no test, JavaExec or capture/sampling task is invoked. Existing probe sources may compile as part of that source set. No native JAR is downloaded by this adapter or added to the editor package. This command has not been run in this workspace.

## Deferred validation

Renderer-stage validation must compile against the genuine pinned native API and compare actual generated chunks/maps for the same pack, source revisions, seed, dimension/isolation, coordinates and full height. Cover every native vanilla state/property domain, native/custom biome IDs, resolved aliases, deferred/external blocks, null native cells, duplicate/missing/edge chunks, negative/signed endpoints, relative/world Y, full-height limits and source immutability. Check genuine generator/expression loader identity, fractional/negative samples, zero/nonfinite noise, fluid height policies and surface derivative identities.

Exercise report/epoch faults during sampling, during immutable copy and at final adoption; cancellation and every primitive/palette/text boundary must publish no partial output or retained native graph. Verify the host serializes sampling with generation/hotload and releases borrowed sessions only after operations unwind. Compare final rendered block/biome inspection against these native outputs before enabling previews. Independent complete native-world parity, real-driver/native visual checks, total application admission and final editor integration remain pending.

## Explicit native tint resolver boundary

`NativeTintInputs` detaches bounded RGB layer tables from a host-supplied native resolver using original registry states, biomes and world positions. The deterministic/weighted scene capture overloads share those inputs through scene preparation; missing tint coverage remains guarded. See [TINT-INPUTS.md](../renderer/TINT-INPUTS.md) for identity, resource/blending responsibility, primitive admission and GPU handoff. No default native color factory is implemented or verified. Genuine pinned Windows API compilation and actual native/pixel comparisons remain pending.

## Streaming native visual producer handoff

`NativeVisualInputs` visits a complete captured scene request under a borrowed native session and supplies actual original state, biome and position to an explicitly provided producer. Its detached edited request preserves the baseline mask, offsets, tint inputs and scene limits. [VISUAL-EDITS.md](../renderer/VISUAL-EDITS.md) describes streaming quads, complete replacement versus layering, packet/assignment-copy admission and producer/resource responsibilities. No fluid/special renderer factory is inferred; genuine native API compilation and actual native/GPU output remain unverified.

## Admitted complete native capture

`NativeCapture` now composes existing full-height native volume capture with deterministic or weighted scene inputs, optional explicit tint and native geometry edits. Terrain/biome/noise/generator/expression captures return owned map-worker requests; biome display colors may be keyed by exact native identity. [NATIVE-CAPTURE.md](../renderer/NATIVE-CAPTURE.md) describes phase reservations, detached `RenderInput` ownership and consuming world/map handoffs. The caller still freezes/owns the native session and external generation/chunk/provider memory. Genuine Windows API compilation and actual native/GPU fidelity remain pending; no unsupported preview is enabled.

## Loaded catalog ownership

`NativeCapture.models` and `weighted` now also consume `RenderInput<ClientAssetSnapshot>` from the [selected-client loader](../renderer/ASSET-WORKER.md). A tracked catalog requires the same shared coordinator and stays retained through native capture and destination request admission. Retain a separate catalog owner for future regeneration or retry; the passed wrapper closes on failure as well as success. The existing raw catalog APIs, native provider/factory responsibilities and preview guards remain unchanged. These overloads are syntax-parsed only until genuine Windows native classpath compilation.

## Native three-coordinate scalar resources

`NativeNoiseSampler` and `NativeCapture.generatorVolume` / `expressionVolume` now supply admitted [full-height scalar volumes](../renderer/NOISE-VOLUME.md). They validate exact resource/loader/policy/session identity and preserve original finite doubles, configured world Y and native dimensional behavior. The pinned generator ignores Y internally; expression streams use their actual three-coordinate evaluator. Inline two-coordinate noise layers gain no invented 3D algorithm. Results contain owned detached samples, with no native resource/stream retained. Scalar display surfaces are distinct from Minecraft block IDs. Dedicated scalar GPU rendering, genuine native compilation and runtime fidelity remain pending.
