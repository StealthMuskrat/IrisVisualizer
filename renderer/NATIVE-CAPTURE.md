# Admitted native capture and owned queue inputs

`NativeCapture` connects the existing Iris/VolmLib volume, model/visibility and map adapters to detached `RenderInput` owners. It admits known capture payload before sampling, validates the frozen session before/after the operation, then shrinks to retained input bytes. The world and map queues can consume that owner after admitting their destination. No native rendering factory, random algorithm, tint policy or generation behavior is inferred by this path; unsupported previews remain disabled.

## Capture families

| Method | Existing native operation | Detached owned value |
| --- | --- | --- |
| `volume` | Read completed caller-frozen full-height native chunks | `WorldSnapshot` |
| `models` | Volume, optional supplied tint, native offsets/render decisions and visibility | `WorldRenderWorker.ModelRequest` |
| `weighted` | Volume, optional supplied tint, native weighted choices/offsets/visibility | `WorldRenderWorker.Request` |
| `edit` | Explicit supplied native geometry producer over an owned ready request | `WorldRenderWorker.Request` |
| `terrain` | Native engine height with explicit fluid policy | `MapRasterWorker.Request` |
| `biomes` | Native surface biome derivative and registry identity | `MapRasterWorker.Request` |
| `noiseLayer` | Explicit resolved native inline noise layer | `MapRasterWorker.Request` |
| `generator` | Exact native engine-loaded generator resource | `MapRasterWorker.Request` |
| `expression` | Exact native engine-loaded expression/stream policy | `MapRasterWorker.Request` |

World methods preserve original full configured height, exact snapshot/state/biome identity, selected client archive, native coordinates, caller clock, limits and native boundary policies. Chunks must already be complete and frozen; this path does not start generation. The `models` path keeps deterministic unit-weight JSON inspection on the existing CPU renderer worker. The weighted path still requires an exclusively supplied native random source whose factory matches the pinned renderer. Class labels are not factory verification.

`WorldOptions` optionally supplies the existing explicit native tint provider. Its archive/resources/colormaps/blending contract remains the host's responsibility. Tint working limits are lowered to the scene allowance. Missing providers retain existing tint, fluid, special-renderer and other visual guards. `edit` consumes an owned ready request, keeps its source hold through capture and delegates to the existing explicit native producer boundary; it does not add a fluid/special factory.

Map methods retain their exact native resource/receipt, fractional or floored coordinate convention, presence flags and original samples. Display ranges/colors remain explicit display settings, with normal style validation in map colorization. A biome overload accepts a bounded table keyed by `WorldSnapshot.Biome` (native ID, original text and asset identity), then builds the color list in the actual captured palette order. Missing identities fail with a PALETTE diagnostic; no color or biome is substituted. Transparent explicit colors preserve present native samples. These display colors are not native block/biome tint output.

## Borrowed native session

Run capture on an appropriate background/native thread while the host owns and freezes the engine/session, completed chunks, registry/resource data, supplied RNG and any providers for the entire call. Shared admission happens before the session gate/native sampler calls or output array allocation. Existing adapters validate exact registry serialization/domains, generation receipt, resource loader identity, signed coordinates, complete height and healthy epoch during capture.

The wrapper rechecks context health and cancellation after its complete callback unwinds, before publishing owned output. It retains no engine, chunk hunk, registry, native handle, random source, reader, provider or callback in successful detached input. The borrowed session may be released after the method returns. Admission does not serialize or acquire that session for the host, and it does not make otherwise concurrent native access safe.

Native read failures and known limits retain structured diagnostics; asset/model problems from weighted inspection remain separate. Other runtime/memory failures expose bounded operation diagnostics. Cancellation propagates separately. Failed results have no partial input/arrays. If input sealing or result publication fails, owned data and holds are closed before returning a failure. A failed or stale result cannot acquire preview readiness.

## Known peak and retained bytes

| Phase | Admitted known peak | Retained transport charge |
| --- | --- | --- |
| Standalone volume | Configured native volume capture working bound | Native state/biome index arrays |
| Complete model/weighted capture | Maximum of selected catalog + volume working bound, and scene capture working bound | Request catalog, snapshot/choice/offset/tint/mask/source-packet payload |
| Native visual edit | Lower of edit and ready-request working bounds | Edited complete request input payload |
| Native map sampling | Configured map capture working bound | Actual detached sample arrays |

The aggregate world capture ceiling remains 512 MiB. Map capture retains its lower existing 128 MiB hard working bound. Volume capture defaults to 256 MiB; in combined world capture the selected catalog is also charged while volume arrays/copies coexist. The wrapper rejects an aggregate above its ceiling rather than silently changing limits. Existing cell, height, chunk, map, palette, copy, assignment, texture and geometry limits continue to apply independently.

The callback returns before its hold shrinks, so temporary renderer-owned capture/descriptor/copy arrays have unwound. Actual output bytes must fit the admitted peak. A source owner and destination owner may conservatively overlap during handoff; data is not deduplicated across accounts. This may reject work that a future deduplicating transfer could fit.

Completed chunk storage, engine/generation caches, native generator/expression/provider allocations and geometry scratch remain borrowed/external and need separate host admission. Captured-client input catalogs independently retained by callers also need their own ownership. Java metadata/collections, native implementation overhead and driver memory are outside known primitive estimates. Shared admission is not a physical RAM/VRAM guarantee or complete application profiling.

## Owned detached input

`RenderInput<T>` owns one detached value and a reference-counted `RenderMemory.Hold`. `own(value, hold)` consumes the supplied hold after nonnull checks and keeps a fresh handle, rejecting a closed tracked hold. `retain()` returns an independently closable input/hold; closing one input clears its value reference and releases only that owner's handle. `value()` and `admission()` are borrowed access while that input remains open. Closing an input prevents future access through that wrapper, and the last hold releases the shared byte/account charge.

`NativeCapture.Result<T>` owns its successful `input()` and detached source/operation metadata. Close a successful result when discarding it, or transfer its input to a consuming endpoint. The result may then be closed again safely. If a raw value must outlive that transfer, retain another input owner first. No native/source graph remains solely because a closed result still exists.

The destination methods are:

| Destination | Consuming entry point |
| --- | --- |
| Ready world input | `WorldRenderWorker.submit(RenderInput<Request>)` |
| Deferred deterministic model input | `WorldRenderWorker.submitModel(RenderInput<ModelRequest>)` |
| CPU map colorization | `MapRasterWorker.submit(RenderInput<Request>)` |
| Connected map view | `GlMapView.submit(RenderInput<MapRasterWorker.Request>)` |

Each method consumes the passed wrapper on every outcome after nonnull checks. It first retains an independent source owner, rejecting closed input, then verifies that tracked input uses the same participating destination coordinator. Destination admission precedes releasing source handles. A foreign/untracked destination, wrong context, stale ownership, closed worker or budget failure leaves the destination's prior request/revision intact when rejected before its normal commit point. The consumed source still closes; retain another owner before transfer if it must survive rejection. Existing untracked inputs/legacy raw request APIs remain available with their original host-admission responsibility.

World renderer completion and GPU upload still follow their existing independent scene lease, tint, revision and retirement contracts. The connected map view admits its CPU worker before changing view tokens, then follows existing upload/frame replacement rules. This handoff creates no GL context, native executor, internal timer, editor presentation or readiness flag.

## Source status and deferred validation

All 102 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (73 CPU, 29 GL). Twelve native adapter sources are syntax-parsed/source-manifest checked only. The new adapter composes existing typed source APIs; genuine pinned Windows native classpath compilation, producer/resource/factory validation and execution remain pending. No renderer tests, native capture/sampling, random draws, memory/admission execution, GL operations or workflows ran for this batch.

The pinned Minecraft 26.3 primary patch context was reviewed again for native `SectionCompiler` fluid dispatch, `BlockStateModelWrapper` part collection/tints, `CuboidModel` parsing and `FaceBakery` material/winding paths. It does not establish the renderer random factory, full interpolation/rescale policy or native fluid geometry producer. A later [model-coverage review](MODEL-COVERAGE.md) adds CPU element rescale using separately reviewed method bodies; a subsequent [interpolation review](INTERPOLATION.md) adds base-level RGBA playback. Fluid producer guards and native pixel comparison remain. No client archive classes or upstream algorithms were executed or replaced.

At renderer completion, cover full-volume/native-map source equality, phase peaks including catalog-plus-volume copies, lower limits, zero/exact/over shared capacity and rejection before any native callback. Exercise every healthy/cancellation gate, failed source/asset/model input, provider failure, missing biome display colors, palette order changes and original sample immutability. Verify callback/native graph absence after return and no partial transport after input/result-publication allocation failure.

Check input retain/close races, consuming closed/foreign/untracked inputs, retained retry ownership, default untracked compatibility, world model versus ready submission, map view/token admission order, native edit composition and coordinator shutdown with outstanding inputs. Prove source holds persist through destination preflight and all references/byte charges release correctly after failure, cancellation, transfer and downstream retirement. Compare genuine native states/biomes/models/maps and real Windows GPU pixels before enabling supported previews. Final native fidelity, application integration and complete memory profiling remain pending.

## Owned selected-client catalogs

The model and weighted capture families now have consuming `RenderInput<ClientAssetSnapshot>` overloads for [background loaded catalogs](ASSET-WORKER.md). They retain a temporary source handle, reject a tracked catalog from another coordinator, and keep its charge through capture/destination request admission. The supplied wrapper closes on every outcome; retain another input first for retries or later regeneration. Raw-catalog APIs retain their existing external-ownership responsibility. Existing native session/RNG/provider contracts, 512 MiB aggregate capture bound, conservative source/destination overlap and unsupported visual/readiness guards remain unchanged. Genuine pinned native type checking remains pending.

## Full-height scalar input

`generatorVolume` and `expressionVolume` now return owned `NoiseVolume` inputs through the same admitted capture protocol. The separate [scalar-volume path](NOISE-VOLUME.md) samples every configured world Y through actual pinned resource APIs, then hands immutable samples to `NoiseSurfaceWorker` with exact coordinator checks and source retention through destination admission. No native state/biome or replacement noise formula is introduced. The [dedicated scalar GPU view](../renderer-gl/NOISE-VIEW.md) now consumes that output; native/GPU validation and final editor routing remain pending.

`WorldOptions.vanillaUnblended(maps)` now selects the concrete [vanilla colormap tint path](VANILLA-TINTS.md). Borrowed tables are charged during volume capture, native map copies/palette colors/output are preflighted during tinting, and the subsequent scene working allowance excludes still-borrowed map payload. The original two-argument options constructor and explicit-provider path remain available. Keep any `RenderInput<ClientColormaps>` owner alive through the call. Native API/GPU comparisons remain pending.
