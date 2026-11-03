# Renderer foundation

Java 25 library, separate from the editor and generation engine. UI implementation and automated validation finished before this stage; see [UI-VALIDATION.md](../docs/UI-VALIDATION.md).

The renderer currently implements read-only capture of a **user-selected** client JAR/ZIP, an immutable vanilla asset catalog, bounded blockstate/model resolution, CPU geometry baking, PNG decoding, animation schedules, conservative mapped-area alpha classification, shared CPU atlas layout/rasters, atlas-bound mesh buffers, bounded world sections, complete CPU scene coverage, leased section caching and latest-request assembly for the pinned 26.3 profile. It does not open a preview, download assets, execute client classes, modify a pack or change generation readiness.

Build sources with a Java 25 JDK:

```powershell
gradle -p renderer classes
```

The module has no external dependencies. Its seventy-four main Java sources compile with `javac --release 25`. Renderer tests have not been executed; stage-wide testing remains deferred until renderer implementation is complete.

## Capturing client assets

`ClientAssets.load(selectedArchive, pinnedMinecraftVersion, cancelled)` is a worker-side API. The caller must supply the Minecraft target from `iris.lock.json`, not infer it from a pack field, archive filename or latest installed release. No installation scan or automatic download occurs.

The reader creates a bounded private temporary capture, computes its SHA-256, and rejects source size/identity/modified-time changes during capture. ZIP metadata is preflighted **before** `ZipFile` creates its index: classic/ZIP64 end records, single-disk consistency, central-directory byte/count/name bounds and actual central entry counts. Selected assets are copied with exact declared-size and CRC checks. Only the private capture is removed; selected archives remain read-only. Cancellation is cooperative through copying, directory scans, asset reads and version parsing.

A strict, bounded UTF-8 `version.json` with an exact matching string `id` is required. Missing/mismatched/ambiguous metadata and server or incomplete archives fail explicitly. This establishes the archive's declared version, not Mojang authenticity or complete visual-model compatibility.

The catalog keeps canonical lowercase `minecraft` identities for:

- `assets/minecraft/blockstates/*.json`
- `assets/minecraft/models/*.json`
- `assets/minecraft/textures/*.png`
- Texture animation metadata, `*.png.mcmeta`

Other entries are counted as ignored and are never executed. Paths are not extracted or normalized into another resource. Duplicate selected identities are rejected. Model/texture paths retain relative subfolders. Each asset has its own SHA-256 and private captured bytes; public streams and copies cannot hand a caller the catalog's backing arrays. Later source edits/deletion do not change a completed snapshot.

Default limits are 256 MiB archive bytes, 256 MiB captured asset bytes, 16 MiB per asset, 250,000 archive entries, 16 Mi UTF-16 entry-name characters and 32 MiB central-directory metadata. Version metadata is separately bounded to 64 KiB, 4,096 JSON nodes, depth 32 and 16 Ki decoded string characters. These are source-capture limits, **not** confirmation of combined generation, image, atlas, native or mesh budgets.

## Blockstate and model plans

`BlockModels.resolve(assets, nativeState, cancelled)` is a worker-side API. `RenderBlockState` requires the original native state ID/text, exact block ID/properties and complete native property-value domains. The resolver does not query a replacement registry, invent missing properties or rewrite the engine's state representation. The returned `ModelPlan.state()` retains that exact input object.

The resolver handles literal variants and multipart predicates, including nested `OR`/`AND` and negated/alternative property values. All selectors are checked against the supplied native domains. Weighted choices remain ordered alternatives; each output part retains its original dispatch pointer/case index. `Part.choose(ticket)` only consumes an externally supplied valid weight ticket. It implements no PRNG or native random-seeding replacement.

Model parents are traversed with cycle/depth checks. Ancestor geometry uses descendant texture overrides and inherited ambient-occlusion settings. Each resolved element retains its declaring model/pointer. Texture-variable chains resolve against the complete inherited binding map, with cycle/missing-variable checks. String sprite declarations and the newer `sprite`/`force_translucent` material objects are supported. A referenced direct PNG must be captured; generated/atlas-only sprites remain unsupported.

The plans retain X/Y/Z blockstate quarter turns and UV lock; element bounds and legacy or multi-axis rotations; optional face UVs/quarter turns/cull directions/tint indices; light emission and `shade_direction_override`. They remain **unbaked** descriptions; the separate geometry baker consumes supported transforms/UVs and retains the remaining visual metadata. Each plan now carries its captured client-archive receipt. Native render-shape, fluids and special block renderers still need integration.

Missing, malformed, unsupported or over-budget assets produce a structured placeholder problem, retaining the original native state and publishing no partial multipart geometry. Cancellation propagates separately. Ambiguous variants, built-in models, unknown fields and explicit empty child geometry with a parent are guarded; the latter remains unresolved until native inheritance behavior is verified. No global cross-snapshot cache is used.

Default per-resolution limits are 256 source assets, 8 MiB JSON, 262,144 parsed JSON nodes, parent depth 64, 4,096 declared/resolved elements, 24,576 declared/resolved faces, 4,096 dispatch cases, 1,024 choices, 16,384 texture declaration/overlay operations, and 4,096 condition nodes at depth 32. Individual model JSON is limited to 1 MiB. See [the supported format profile](FORMAT-PROFILE.md) for source references and boundaries. These limits do not confirm the combined native/world/mesh budget.

## CPU geometry and packed buffers

`GeometryBaker.bake(plan, nativeTickets, cancelled)` selects one model per part using supplied native-policy tickets and returns a `BlockMesh`. It bakes stable outward-wound quads, default/explicit UVs, face UV quarter turns, UV lock, element X/Y/Z rotations, blockstate rotations, normalized normals and local-block bounds. Cull directions receive dispatch rotations while original model-face labels remain available. Source geometry, dispatch choices, native state identity and asset provenance are retained.

`MeshPacket` packs position XYZ, normal XYZ and sprite UV into eight floats per vertex, with four vertices/six indices per quad and separate immutable face/material/light/tint metadata. Its buffers are read-only heap views; GPU staging is not allocated. `requireAssets` checks the captured archive SHA/version before a later texture-binding stage can mix asset epochs. No neighbor faces are dropped and no world placement, tint, lighting or alpha pass is inferred.

The baker withholds all partial output on unsupported geometry, invalid tickets or exceeded budgets. Rescaling, reversed element endpoints and UVs outside their source sprite are guarded pending pinned-client verification. Default limits are 1,024 parts, 4,096 selected elements, 24,576 quads and 16 MiB of packed arrays, local coordinates within ±8 blocks, origins within the corresponding model-coordinate range and element angles within ±360 degrees. These CPU limits do not confirm total heap/native/world/mesh memory. See [GEOMETRY.md](GEOMETRY.md) for the contract and deferred verification matrix.

## CPU textures and material preparation

`TextureMaterials.prepare(assets, mesh, cancelled)` binds a complete CPU mesh to the exact captured archive SHA and declared/expected version. Only referenced sprites are decoded. The atomic `TexturePreparation` retains the mesh/native identity, immutable source RGBA sheets and SHA receipts, frame schedules, face order/source pointers, per-face pixel regions and conservative opaque/cutout/translucent candidates. Failures publish explicit problems without a partial texture bank; cancellation propagates separately.

The bounded PNG decoder handles grayscale, RGB, palette and alpha images, legal 1/2/4/8/16-bit samples, transparency keys, all five filters and Adam7. It validates chunks/CRCs, ordering, palettes, exact zlib output and trailing data before publishing immutable RGBA8. Color profiles are not applied; 16-bit samples retain their high byte. This source-sample convention still needs pinned-native comparison.

Texture metadata retains blur/clamp, mipmap strategy and cutoff bias. Animation schedules preserve explicit frame order/repetitions/durations, default vertical strips, explicit two-dimensional tile grids and interpolation intent. Static images use the full image. Single-axis animation sizes, implicit horizontal layouts, explicit empty frame lists and unsupported metadata fields are guarded pending pinned-client verification. Tick lookup consumes an external integer clock and partial tick; no native clock or pixel interpolation is implemented.

Alpha scans cover only each face's conservative UV pixel rectangle across all distinct referenced frames, with shared-region caching and aggregate pixel-visit limits. Force-translucent materials override classification; potentially blended binary animation masks are conservatively marked. This does not certify the native UV boundary, mipmap, interpolation or render-pass policy. No alpha test, blending, atlas or GPU upload runs. Defaults cap unique sprites at 256, faces at 24,576, dimension at 8,192, pixels per image at 16,777,216, aggregate RGBA at 128 MiB, encoded assets at 32 MiB, metadata at 1 MiB/65,536 nodes, schedule entries at 16,384, regions at 8,192 and alpha visits at 64 Mi. See [TEXTURES.md](TEXTURES.md) for the contract, exclusions and deferred validation matrix. These are CPU preparation budgets, not combined generation/GPU memory approval.

## Shared CPU atlas and frame updates

`AtlasBuilder.plan(preparations, cancelled)` admits bounded texture preparations from the same archive, shares matching sprite IDs and creates deterministic multi-page power-of-two layouts with edge gutters. Source images/mesh arrays are charged by retained object identity, including independent duplicate decodes; aggregate schedule counts and source/prospective bound mesh/page payloads are checked together. No texture is rescaled or omitted to make a layout fit.

`AtlasMeshes.bind(layout, admittedPreparation, cancelled)` retains position/normal/index/face order and original native metadata while mapping sprite-local UVs into the assigned page. Consecutive page/alpha candidate ranges are recorded without reordering faces. Read-only heap buffers remain separate from the source packets. Bound meshes and rasters must share the exact layout object, not merely a matching archive hash.

`AtlasImages.capture(layout, tick, partialTick, cancelled)` creates immutable base-level CPU RGBA pages with frame-edge/corner extrusion. `updates` creates bounded whole-slot patches only for changed scheduled tiles; `apply` checks the exact prior raster identity and creates a new snapshot by cloning changed pages once and sharing untouched pages. All partial output is withheld on failure, and cancellation is separate. Multi-frame native interpolation stays guarded; filtering/mipmaps and GPU uploads are not implemented. See [ATLAS.md](ATLAS.md) for the contracts, numerical budgets, exclusions and deferred verification matrix.

## Native world snapshots and section meshes

`WorldSnapshot.capture` detaches native state/biome palettes and full-volume compact indices while retaining exact IDs, original texts/properties, generation context and configured height. `WorldModels.capture` checks one externally supplied visual choice per cell against that exact state; it does not replace position-dependent native model selection with one mesh per state. Visual entries distinguish successful meshes, explicitly empty cells and unsupported-model placeholders.

`WorldVisibility` either keeps every face or captures explicit native per-cell direction masks tied to the exact snapshot and atlas. Only declared cull directions can be suppressed; the renderer does not infer native opacity/neighbor shapes. `WorldMesher.assemble` counts and budgets a requested section before emitting exact-sized arrays, retaining source face order and native picking provenance. Float geometry stays relative to an exact integer world origin. Unsupported visuals produce unit-cell marker records for a future diagnostic draw pass, preserving generated state/biome data.

World inputs, prototypes, atlas payload and section arrays are checked together; failures publish no partial section and cancellation propagates separately. These primitive-byte/count limits do not confirm total native/cache/GPU memory or complete volume coverage. See [WORLD-MESH.md](WORLD-MESH.md) for the input contract, numerical limits, exclusions and deferred verification matrix. No engine or UI bridge is enabled.

## Complete CPU scenes, cache admission and latest requests

`WorldScenePlan.plan` counts every section covering the full native volume and checks aggregate geometry/metadata/working limits before world-section output allocation. `WorldMesher.plan` exposes immutable section counts for this admission pass. Fully hidden cells now avoid redundant picking-span records while remaining unchanged in the native snapshot.

`WorldSectionCache` reserves arrays/counts before section emission, charges source payload once per exact native/atlas epoch within a bounded multi-epoch aggregate, reuses matching sections and evicts only unleased LRU entries. `WorldScene.build` publishes a complete immutable scene through an `AutoCloseable` result owning all section tickets; failure/cancellation releases the tickets without publishing a partial scene. Cache shutdown retires unleased data immediately and keeps outstanding leases charged until their owners release them.

`WorldSceneWorker` uses one active worker and one replaceable pending request, cooperatively cancels superseded work, checks the latest revision before adoption and transfers completed result ownership exactly once. A consumer must close transferred results and check their revision before presentation. Old leases can coexist with a new complete scene when their combined budgets fit; insufficient pinned capacity still blocks admission explicitly. The default aggregate section mesh cap is 256 MiB; the primitive source/output working ceiling is 512 MiB, with metadata count limits and existing section budgets still applied. See [SCENES.md](SCENES.md) for lifecycle contracts, excluded memory and deferred cache/concurrency validation. No UI or GPU readiness flag is enabled.

## Separate OpenGL backend

The [OpenGL module](../renderer-gl/README.md) now adds twenty-nine LWJGL 3.4.1 sources for context-owned GPU admission, bounded native staging, incremental atlas/mesh/marker uploads, initialized offscreen targets, opaque/cutout/translucent shaders with native vertex tint inputs and an instanced diagnostic marker pass. It retains CPU scene leases through fenced resource retirement, including failed uploads, with shared resource dependencies and quarantined cleanup failures kept charged. All 98 CPU/backend Java sources compile; no GL context, shader or runtime test has been executed.

The [incremental upload layer](../renderer-gl/UPLOADS.md) validates and transfers across explicit per-step byte/action/work limits with a cooperative time target. It charges paused CPU input, restores state at each yield, and permits exactly-once complete-scene adoption guarded by the application's latest revision. Synchronous upload delegates to the same state machine. Driver allocation/cleanup latency is not a hard frame-time guarantee.

This backend requires an externally owned GLFW OpenGL 3.3 core context. It is not added to the editor package and changes no preview gate. Explicit native tint samples and a successful camera-bound global translucent order are required for those material paths; native production, lighting/filtering/interpolation and full visual verification are pending. See [material contracts](../renderer-gl/MATERIALS.md). See its [ownership and verification contract](../renderer-gl/README.md) before integration.

`AtlasFramePlan.prepare` now prepares immutable changed-page CPU rasters and grouped patches on a worker. The [GPU animation layer](../renderer-gl/ANIMATION.md) copies changed pages into fresh textures, applies bounded patch rows, shares unchanged pages and checks the exact displayed baseline plus scene/frame revisions before adoption. A frame-aware draw overload reuses geometry. Dependencies hold source framebuffer/page resources through actual retirement; interpolation/mipmap/filtering and application scheduling remain pending.

`WorldLighting.capture` now detaches optional final native per-vertex RGB modulation, and the GPU upload selects a bounded 48-byte vertex layout when supplied. Simple lighting remains the initial default. The backend also culls actual geometry/marker section bounds against the current frustum, supports vertical cameras, batches consecutive transparent indices without reordering, and returns successful-frame submission counts. See [lighting and view contracts](../renderer-gl/LIGHTING-VIEW.md). These paths have compiled but have not run in a GL context.

## Native 2D map rendering

`MapSamples` now captures exact native terrain heights, biome identities/indices and finite noise values for a 512 × 512 default grid with explicit world-space sampling scale. `MapRaster` colorizes detached samples using stable scalar ramps or explicit native-palette colors, retaining original values for inspection and transparent empty samples. `MapView` provides display-only fit/pan/zoom and pixel hit testing. A bounded latest-request worker moves colorization off the UI thread. The GPU map layer streams whole rows under the existing upload budgets and transfers complete revision-checked immutable textures, with source payload held through retirement. See [MAPS.md](MAPS.md) for native input, lifecycle, numerical and deferred verification contracts. No engine sampler, editor integration or preview gate is enabled.

## Connected native map images

`GlMapView` now connects one background `MapRasterWorker` to bounded texture upload, offscreen `GlMapRenderer` fit/pan/zoom drawing and exact-revision completed-image transfer. `GlMapFrame` and retained `GlMap` owners preserve original images/native rasters through replacement and closure. `MapPick` reports original coordinates, presence, heights, noise and native biome identity using the displayed frame's transform. See [MAP-VIEW.md](../renderer-gl/MAP-VIEW.md) for tokens, orientation, admission, ownership and deferred verification. Renderer tests, shaders/pixel comparison and native validation remain deferred; no editor or readiness change is introduced.

## Camera navigation and native inspection

`WorldScene.Result.retain` now shares exact scene ownership safely across GPU use and inspection. `WorldPickIndex` consumes a fresh CPU owner, builds a bounded section-bound hierarchy, and tests actual mesh triangles/atlas alpha or diagnostic marker boxes while retaining original block/state/biome identity. Queries report explicit limits without partial nearest hits; closing cancels active queries and defers source release until they unwind. A latest-pointer worker prevents stale inspection adoption. `GlCamera` shares its orientation basis with clipped viewport rays, and `GlOrbitCamera` provides fit, orbit, pan, dolly, focus and exact-anchor rebase. See [PICKING.md](PICKING.md). No voxel editing, native collision replacement, editor integration or readiness change is introduced.

## Complete-frame replacement

The [completed frame layer](../renderer-gl/FRAMES.md) renders into fresh private targets and retains exact source/atlas/camera receipts. A failed redraw or resize can leave the prior caller-owned image intact. Reference-counted image owners pin source dependencies and retain CPU inspection context; a latest-revision queue transfers only complete current frames. Multi-epoch cache preflight now admits the complete replacement alongside pinned older scenes under unchanged aggregate limits. Upload queues also require explicit active/READY phases after cleanup failures. This layer is not wired to editor presentation or readiness.

## Completed-image pixel readback

`GlReadback` and its latest-revision queue now read immutable completed world/map images through bounded pixel-pack transfers, zero-timeout fence polling and top-left CPU row copies. The new `RgbaImage.Builder` seals one private output array without a second full-image copy; ready output stays charged until transfer/drop. Source owners and transfer fences remain journaled through retirement, with pack state restored at every yield. See [READBACK.md](../renderer-gl/READBACK.md) for inputs, bounds, ownership and deferred pixel-comparison validation. No tests or GPU operations ran; unsupported previews remain disabled.

## Native visual geometry and layer composition

`NativeGeometry.capture` now accepts supplied vanilla native block/fluid/special quads with block-local positions, source UVs, normals/material metadata and detached exact capture/position provenance. `combine` preserves a JSON base and native layers atomically, including waterlogged-style compositions without replacing the native block/state. World assignments reject cross-capture/cell reuse; texture/atlas/world/GL/picking stages preserve native face provenance. `AtlasBuilder.empty` supports all-empty/placeholder scenes without a dummy mesh or texture. See [NATIVE-GEOMETRY.md](NATIVE-GEOMETRY.md). Native geometry producers and actual fluid/special visual verification remain pending; this adds no renderer simulation, client execution or preview readiness.

## Complete native-choice visual assembly

`WorldVisuals` now connects explicit per-cell native model tickets, captured native geometry/layers, empty decisions and visual problems to model resolution, shared texture decoding, atlas binding/rasterization and complete `WorldModels` assignments. It resolves each model state once per request, shares sprites/alpha scans across variants, preserves original native identity and isolates unsupported visuals as placeholders. Fatal resource/version/atlas errors withhold the whole result. Its frozen request and `WorldVisualWorker` add combined CPU payload admission, cancellation, revision-checked exactly-once transfer and invalidation without enabling a preview. See [VISUALS.md](VISUALS.md) for inputs, limits, ownership and deferred verification. Native producers and application integration remain pending.

## Connected CPU and GPU world path

`WorldRenderWorker` now runs visual preparation, frozen native visibility binding and complete leased scene assembly on one CPU thread, with one latest pending request and phase budgets that include resident scenes and retained native input. `WorldOrderWorker` sorts transparent faces asynchronously while owning its exact source lease. The separate `GlWorldView` endpoint connects bounded GPU uploads, camera revisions, sort adoption and immutable completed-frame transfer; navigation does not regenerate the world, and caller-held images survive replacements. See [PIPELINE.md](PIPELINE.md) for the handoff, admission, lifetime and deferred verification contracts. Native producers, combined application admission and editor integration remain pending; unsupported previews stay disabled.

## Connected world atlas animation

`AtlasFrameWorker` now prepares immutable sprite patches and changed page copies off the context thread, with one active/latest pending clock, exact preflight and cancellation reservations. `GlWorldAnimation` connects those plans to immutable GPU updates; `GlWorldView.clock` advances the externally supplied clock, coalesces repeated frames and redraws only after a complete atlas adoption. Camera navigation keeps the current complete atlas, transparent geometry order is reused, and transferred images retain their original pixels/clock/picking raster. See [WORLD-ANIMATION.md](../renderer-gl/WORLD-ANIMATION.md). Interpolation/layout guards remain; renderer tests and real-driver/native comparison are deferred.

## Native capture adapters

`WorldCapture` and `MapCapture` now detach streamed native identities and samples with full-volume/grid preflight, bounded palette text, cancellation and completion guards. The separate [native renderer bridge](../native-renderer/README.md) adds typed Iris/VolmLib chunk/registry capture and native terrain, biome, generator, expression and noise-layer sampling. Its twelve native adapter sources require the genuine pinned Windows offline classpath and have not yet been compiled or executed; they are separate from the 105 compiled CPU/GL sources. Native visual producers, native validation and application admission/integration remain pending; no preview gate changes.

## Native deterministic model requests

`NativeModelInputs.capture` now checks actual native render shape, fluid/entity guards, exact offset input and registry metadata, then detaches state decisions. `WorldModelInputs` admits unit-weight JSON model parts with complete explicit tint coverage while preserving other visuals as explicit original-state placeholders. `WorldRenderWorker.ModelRequest` performs this inspection, visual preparation and complete scene assembly on the same CPU thread; no native session is retained by the queue. See [MODEL-INPUTS.md](MODEL-INPUTS.md) for unsupported paths, ownership, limits and deferred validation. Native API compilation, actual native/GPU verification and preview readiness remain pending.

## Native hidden-face masks and scene capture

`NativeVisibility` now produces block-direction masks from the vanilla native face predicate, with explicit boundary retention and bounded primitive memoization. `WorldVisibility.Input.read` streams the privately owned mask without an additional source-array copy. `NativeSceneInputs` combines model and visibility capture into a detached scene-worker request under one primitive working budget; no native context reaches the render queue. See [NATIVE-VISIBILITY.md](NATIVE-VISIBILITY.md). Twelve native adapter sources still require genuine Windows compilation and runtime validation; no preview gate is enabled.

## Native model offsets

`NativeOffsets` now captures actual native `BlockState.getOffset` translations into bounded immutable `WorldOffsets` tied to the exact native snapshot. `NativeSceneInputs` supplies them through model inspection and visual preparation; meshing translates shared prototype vertices during placement. Actual translated bounds drive culling, transparent ordering and picking while original state/biome/cell identity and diagnostic marker locations remain intact. Offset arrays are included in pending-input, source/cache and phase budgets. See [OFFSETS.md](OFFSETS.md). Native API compilation and runtime/pixel comparison remain pending; other model guards and preview gates still apply.

## Native weighted model choices

`WorldModelChoices` now inspects bounded alternative descriptors and streams per-cell native selections into an interned choice palette. `NativeWeightedInputs` uses actual native position seeds/weighted lists and multipart seed resets with an explicitly supplied, renderer-matching dedicated native random source. It captures offsets and visibility under shared admission and returns a detached request for the existing CPU scene worker. Selected tint/empty/unsupported visuals remain guarded. See [WEIGHTED-CHOICES.md](WEIGHTED-CHOICES.md) for canonical tickets, random-source responsibility, budgets, ownership and deferred validation. Genuine Windows API compilation, factory/native/pixel comparison and readiness remain pending.

## Still to implement

Native geometry/UV/texture comparison and guarded cases, native weighted-choice factory/output validation, offset and visibility validation, native tint factory/output validation and optional native lighting production/validation, transparent intersection/OIT handling, fluids and special render shapes, native animation interpolation/mipmap/filtering, real-driver scheduling validation, combined application/GPU admission, native map sampling adapters, full generation/scene integration and final map/3D editor presentation remain pending. The backend marker/mesh/animation code still requires real-context/native comparison and visible verification. There is currently no editor integration or ready-for-preview flag in this module.

End-of-stage validation must cover normal and ZIP64 archives, duplicated/truncated/corrupt directory data, malformed/duplicate-key UTF-8 JSON, version mismatch and incomplete archives, exact resource identities, size/CRC and budget failures, cancellation/source changes/private cleanup, and captured stream/copy immutability (including `transferTo` with an output stream that modifies its input). Model contracts must cover exact native domains, selector ambiguity/negation/nesting, weighted boundaries and original case indices, parent/texture cycles and overlays, geometry provenance and stable face order, modern material/rotation/shading declarations, every budget boundary, cancellation and whole-result placeholders. Geometry tests must compare the renderer's inputs with the engine's actual block/state/biome data. Visible Windows/GPU verification remains separate from headless contracts.

## Captured native tint layers

The [native tint input path](TINT-INPUTS.md) now carries bounded, exact-snapshot RGB palettes through deterministic/weighted model inspection, shared mesh prototypes and scene/GPU admission. A zero-copy scene view supplies GPU colors, and missing layers retain original-state placeholders. The host must supply and verify the native resolver, resources and blending policy; genuine native compilation and pixel comparisons remain pending.

## Streaming native visual producers

The [visual-edit handoff](VISUAL-EDITS.md) now streams explicit native producer decisions over a complete frozen model request, interns shared choices, preserves original native state/biome/position and transfers the edited request to the existing scene worker. Native quads can stream without a separate input list. Original/scratch/copy indices and new packets share bounded admission; producer selection and actual native/GPU fidelity remain pending.

## Shared world/device admission

[RenderMemory](SHARED-MEMORY.md) now provides an optional common byte ceiling for world preparation, complete cache admission and all journals on a participating GPU device. Cancelled jobs, pinned scenes, missing section output, fences and failed cleanup retain their charges. Other preparation/native/external owners still require host admission; whole-application memory validation and editor integration remain pending.

## Preparation-worker memory ownership

[Shared worker holds](WORKER-MEMORY.md) now admit connected map colorization, atlas preparation and camera sorting before submission, keep cancelled work charged until unwind, and transfer charged completions to explicit owners. Map/atlas idle replacement is atomic; GPU views inherit their device coordinator and close each CPU completion after destination admission or failure. Independent picking, legacy standalone and native/external owners still require host admission.

## Picking and standalone worker admission

[Picking and standalone preparation](PICKING-MEMORY.md) now optionally share the renderer coordinator: index build/source lifetime and animated alpha-query pages, queued pointer requests, visual completion holds and managed standalone scene/cache output. Visual-to-scene handoff admits the destination before closing its source hold. Scene invalidation and revision-specific transfer now reject stale adoption explicitly. Native/external scratch and whole-application profiling remain pending.

## Admitted native capture to renderer queues

[NativeCapture and RenderInput](NATIVE-CAPTURE.md) now carry admitted native volume/model/visibility and terrain/biome/noise capture into the CPU world/map queues. Owned input retains its byte charge until destination admission, with exact coordinator checks and consuming failure semantics. Biome display colors can be keyed by exact native identity and ordered after capture. Native session ownership, genuine Windows compilation/fidelity and unsupported preview guards remain explicit.

## Geometric display clipping

[WorldClip](CLIPPING.md) now limits drawing and picking to an explicit world-space box while preserving the full captured native volume and shared geometry. Clip changes redraw with a new view revision and reuse camera ordering; completed frames/readback and hit receipts retain the exact scope. Clipping adds no cut-plane caps or inferred faces. Windows shader/pixel validation and preview readiness remain pending.
