# Native per-cell weighted model choices

`WorldModelChoices` adds bounded model-selection metadata and streaming capture of per-cell choices. `NativeWeightedInputs` connects that path to native position seeds, native weighted lists and vanilla multipart seed resets, then hands a detached complete request to the existing scene worker. This is source implementation. Genuine Windows native compilation, random-policy matching and native/pixel comparisons remain pending; unsupported previews stay disabled.

## Native policy and caller ownership

The caller supplies an exclusively owned native `RandomSource` whose algorithm matches the pinned renderer, plus the existing healthy frozen `NativeRenderContext`. The adapter accepts native-class sources and records the actual class name. A matching namespace/class label does not prove the correct algorithm or loaded binary. The host must verify its factory against the genuine pinned renderer and serialize the whole capture with native generation/hotload/session work. This path deliberately has no inferred random factory.

Use a dedicated model-selection source. Capture reseeds it for each original cell and multipart part; its post-call state is unspecified. Sharing an engine generation RNG would violate this ownership contract. Native state/biome output, generation seeds, configured height and pack data remain unchanged.

The adapter calls the actual original `BlockState.getSeed` at the exact original block position and sets the supplied native source to that seed. For a variants model it asks native `WeightedList.getRandomOrThrow` to select the weighted entry. For multipart it takes one native `nextLong` after the position seed, then resets to that part seed before each matching part. The existing resolver establishes matched parts using the exact native property values/domains and preserves original case order.

The source review uses unchanged vanilla context from pinned Minecraft 26.3 NeoForge sources:

| Contract | Primary source |
| --- | --- |
| Weighted list selects the child model | [WeightedVariants](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/renderer/block/dispatch/WeightedVariants.java.patch) |
| One multipart nextLong and per-part seed reset | [MultiPartModel](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/renderer/block/dispatch/multipart/MultiPartModel.java.patch) |
| Native weighted-list builder and build methods | [WeightedList](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/util/random/WeightedList.java.patch), [native builder call sites](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/src/main/java/net/neoforged/neoforge/common/world/StructureSettingsBuilder.java) |
| Block-state position seed used for model-part collection | [LevelRenderer](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/renderer/LevelRenderer.java.patch) |

Only common native APIs are called. No client renderer class, NeoForge extension, custom block model or selected-client executable code is loaded. The seed call site and selection/reset contracts above do not establish the renderer's random-source factory or prove full terrain visual parity; those still require genuine pinned source/runtime validation.

## Inspection and canonical selections

`WorldModelChoices.inspect` processes one exact state/model plan at a time from the immutable selected archive, then releases its JSON/model graph. The retained plan contains bounded state/part/alternative descriptors: original ordered weights, face counts, first unsupported tint problem and private cumulative-weight arrays. The original state/asset objects remain authoritative. Geometry, UVs and texture baking continue later through `WorldVisuals` on the same existing CPU scene worker.

The capture reader receives the current source cell/state and returns a valid selection ticket for each part. Core code validates its exact range, finds the selected interval and stores that interval's first ticket as a canonical representative. This represents the exact selected model/rotation/UV-lock alternative. It is not a trace of the native random integer draw. Native weighted lists return these canonical representatives directly; a part with one alternative uses its unique model, including when its declared weight exceeds one. The exclusive source is reseeded at the next cell/part, so unused single-alternative draws do not affect later visual choices.

Per-cell combinations are interned by original state index plus canonical part selections. Repeated combinations share one `WorldVisuals.ModelChoice` and later one baked prototype. A reusable ticket buffer serves cell probes; only new variants allocate private key arrays. Different original states/case choices remain distinguishable. Source assignment arrays stay private and cover the whole volume in X-fastest, Z, Y order.

Tint checks apply to the selected alternatives. A selected tinted model without complete native tint-layer coverage produces an original-state placeholder; an untinted alternative can proceed. A selected combination with no verified faces remains a placeholder. Existing geometry/texture failures are still handled by the baker/atlas path. Fixed native invisible decisions remain empty, while fluid/waterlogged, block-entity/special and external visual guards remain active. A failed model plan retains its source diagnostics; no partial multipart geometry is published.

Fatal resource/version/memory/atlas limits fail the whole request. Weighted choices, area, height, states and alternatives are never silently removed or substituted to fit a budget. The older `NativeSceneInputs`/`WorldModelInputs` path retains its deterministic unit-weight guard when no explicit weighted capture is used.

## Full native handoff

`NativeWeightedInputs.capture` verifies the current native source, original registry metadata and full configured height through the existing capture adapters. It captures exact native offsets, detached render decisions, selection descriptors and native face masks under one shared primitive working ceiling. The dedicated native reader then captures per-cell selections, checking cancellation/session health during registry/list preparation and cell/part traversal. Completion guards run before and after immutable choice-index capture and again before the final handoff.

A successful result contains `WorldRenderWorker.Request`, native selection/visibility counters and a bounded policy/class label. It retains the immutable catalog, original snapshot, offsets, optional exact-snapshot tint palette, masks and frozen choice palette/indices. Native registries/states, mutable positions, weighted-list objects, the random generator, borrowed session, reader callbacks and descriptor scratch do not escape. Failures contain detached receipts and diagnostics, with no partial request.

The method is a synchronous background/native-worker operation. The caller must keep the native session/registry/resource data frozen until it returns, then release its guard and enqueue the request. No additional native executor or overlapping generation task is created. Later source edits still require normal revision invalidation before scene/GPU/frame adoption.

## Admission

| Resource | Default / hard bound |
| --- | --- |
| Inspected model states | Existing 1,024 ceiling |
| Parts / alternatives across descriptor states | 65,536 / 65,536 |
| Parts per selected model combination | 1,024 |
| Faces inspected across all alternatives | Existing 262,144 ceiling |
| Full source cells | Existing 16,777,216 ceiling, full configured height |
| Part visits during cell capture | 67,108,864 default; 268,435,456 hard |
| Published choices / distinct selection combinations | Existing 65,536 / 1,024 ceilings |
| Canonical ticket references across new combinations | 65,536 |
| Aggregate weight units in native multi-alternative lists | 1,048,576 |
| Native weighted selections plus multipart nextLong calls | 16,777,216 |
| Combined known CPU job primitives | Existing 512 MiB ceiling |

Cumulative arrays cost four bytes per alternative. Selection preflight admits those descriptors, a four-byte scratch entry per maximum part, four bytes per state for fixed-choice indices, up to four bytes per retained canonical-key ticket, and both full-volume assignment arrays during immutable capture. Catalog, original native indices, retained offsets/tints and the visibility mask remain charged in the applicable phase. Descriptor/key/scratch arrays unwind before scene-worker visual preparation; frozen requests retain only their actual input payload and normal bounded metadata.

Native weighted-list implementation objects/storage, state references, Java headers/boxed values, JSON/parser scratch and the borrowed engine remain outside known primitive totals. Alternative/part/aggregate-weight counts bound the native selection structures; final application admission must include their genuine runtime footprint alongside other native and GPU jobs. Existing cache/GPU admission still charges pinned old scenes and caller-held images. No default area, full height, map resolution or global renderer budget is increased.

## Deferred stage validation

All 99 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1. Ten native adapter sources are syntax-parsed and source-manifest checked only; genuine Windows native API type checking, factory verification and execution remain pending. No renderer tests, native selections, random draws, GL context, pixel comparisons or workflows ran for this batch.

At renderer completion, verify all ticket boundaries, large/unequal weights, duplicate alternatives, canonicalization, original dispatch indices, empty/tinted alternatives and mixed variant/multipart states. Compare every selected native model against the actual pinned client selection for the same verified random-source algorithm, original state/position and resource archive. Include negative coordinates, full native Y endpoints, repeated capture, fresh sessions, multiple matching parts and single-alternative parts with large declared weights.

Exercise descriptor/face/palette/key/ticket/weight/query/visit/working limits, high-collision interning, reusable scratch mutation, defensive assignment copy, reader failures and completion/session faults before/after freezing. Verify one prototype per repeated selection, exact original cell/state/biome/offset/face-mask identity through geometry and picking, and absence of native handles after capture. Check stale revision rejection, pinned prior scenes, cancellation during every phase and lower model/scene budgets. Compare real Windows rendered pixels using the completed-image readback path before enabling a supported weighted preview. Native tint factory/output validation, fluid/special producers, remaining rendering fidelity, genuine native compilation, real-driver validation, combined application admission and final editor integration remain pending.

Explicit [native tint inputs](TINT-INPUTS.md) may be supplied before descriptor inspection. Required layers are checked per selected alternative, while RGB remains per original cell and shares the same baked geometry. `NativeTintInputs.weighted` combines host-supplied tint capture with the existing native selection/offset/visibility path; native resolver and RNG factories still require independent verification.
