# Renderer foundation

Java 25 library, separate from the editor and generation engine. UI implementation and automated validation finished before this stage; see [UI-VALIDATION.md](../docs/UI-VALIDATION.md).

The renderer currently implements read-only capture of a **user-selected** client JAR/ZIP, an immutable vanilla asset catalog, bounded blockstate/model resolution, CPU geometry baking, PNG decoding, animation schedules, conservative mapped-area alpha classification, shared CPU atlas layout/rasters, atlas-bound mesh buffers, bounded world sections, complete CPU scene coverage, leased section caching and latest-request assembly for the pinned 26.3 profile. It does not open a preview, download assets, execute client classes, modify a pack or change generation readiness.

Build sources with a Java 25 JDK:

```powershell
gradle -p renderer classes
```

The module has no external dependencies. Its eighty-eight main Java sources compile with `javac --release 25`. Renderer tests have not been executed; stage-wide testing remains deferred until renderer implementation is complete.

## Capturing client assets

[ClientAssetWorker](ASSET-WORKER.md) now schedules selected-client capture on one background reader with latest-revision adoption and optional shared memory ownership. Completed `RenderInput<ClientAssetSnapshot>` owners can transfer directly to native model/weighted capture while remaining charged. No preview gate is changed.

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

Missing, malformed, unsupported or over-budget assets produce a structured placeholder problem, retaining the original native state and publishing no partial multipart geometry. Cancellation propagates separately. Ambiguous variants, built-in models and unknown fields remain guarded. Explicit empty child geometry now overrides parent geometry; absent geometry still inherits. No global cross-snapshot cache is used.

Default per-resolution limits are 256 source assets, 8 MiB JSON, 262,144 parsed JSON nodes, parent depth 64, 4,096 declared/resolved elements, 24,576 declared/resolved faces, 4,096 dispatch cases, 1,024 choices, 16,384 texture declaration/overlay operations, and 4,096 condition nodes at depth 32. Individual model JSON is limited to 1 MiB. See [the supported format profile](FORMAT-PROFILE.md) for source references and boundaries. These limits do not confirm the combined native/world/mesh budget.

## CPU geometry and packed buffers

`GeometryBaker.bake(plan, nativeTickets, cancelled)` selects one model per part using supplied native-policy tickets and returns a `BlockMesh`. It bakes source-wound quads with canonical corner ordering for unrotated elements, default/explicit UVs, face UV quarter turns, UV lock, element X/Y/Z rotations, blockstate rotations, normalized normals and local-block bounds. Cull directions receive dispatch rotations while original model-face labels remain available. Source geometry, dispatch choices, native state identity and asset provenance are retained.

`MeshPacket` packs position XYZ, normal XYZ and sprite UV into eight floats per vertex, with four vertices/six indices per quad and separate immutable face/material/light/tint metadata. Its buffers are read-only heap views; GPU staging is not allocated. `requireAssets` checks the captured archive SHA/version before a later texture-binding stage can mix asset epochs. No neighbor faces are dropped and no world placement, tint, lighting or alpha pass is inferred.

The baker withholds all partial output on unsupported geometry, invalid tickets or exceeded budgets. Element rescale is implemented with per-axis pre-rotation scaling and inverse-transpose normals. [Reversed element endpoints](WINDING.md) now retain source geometry/UVs with winding-aware normals and conditional corner reordering. UVs outside their source sprite remain guarded pending pinned-client verification. Default limits are 1,024 parts, 4,096 selected elements, 24,576 quads and 16 MiB of packed arrays, local coordinates within ±8 blocks, origins within the corresponding model-coordinate range and element angles within ±360 degrees. These CPU limits do not confirm total heap/native/world/mesh memory. See [GEOMETRY.md](GEOMETRY.md) for the contract and deferred verification matrix.

## CPU textures and material preparation

`TextureMaterials.prepare(assets, mesh, cancelled)` binds a complete CPU mesh to the exact captured archive SHA and declared/expected version. Only referenced sprites are decoded. The atomic `TexturePreparation` retains the mesh/native identity, immutable source RGBA sheets and SHA receipts, frame schedules, face order/source pointers, per-face pixel regions and conservative opaque/cutout/translucent candidates. Failures publish explicit problems without a partial texture bank; cancellation propagates separately.

The bounded PNG decoder handles grayscale, RGB, palette and alpha images, legal 1/2/4/8/16-bit samples, transparency keys, all five filters and Adam7. It validates chunks/CRCs, ordering, palettes, exact zlib output and trailing data before publishing immutable RGBA8. Color profiles are not applied; 16-bit samples retain their high byte. This source-sample convention still needs pinned-native comparison.

Texture metadata retains blur/clamp, mipmap strategy and cutoff bias. Animation schedules preserve multi-entry frame order/repetitions/durations, default horizontal/vertical strips, single-axis dimensions, explicit two-dimensional tile grids and interpolation intent. Static images without animation metadata use the full image; zero/one-entry animation schedules use a static top-left tile. Unsupported metadata fields remain guarded. [Model coverage](MODEL-COVERAGE.md) records the reviewed semantics and native comparison boundary. Tick lookup consumes an external integer clock and partial tick; no native clock is implemented. [Pixel interpolation](INTERPOLATION.md) now uses separate canonical tick-quantized samples through CPU rasterization and background/GPU atlas replacement.

Alpha scans cover only each face's conservative UV pixel rectangle across all distinct referenced frames, with shared-region caching and aggregate pixel-visit limits. Force-translucent materials override classification; potentially blended binary animation masks are conservatively marked. This does not certify the native UV boundary, mipmap, interpolation or render-pass policy. No alpha test, blending, atlas or GPU upload runs. Defaults cap unique sprites at 256, faces at 24,576, dimension at 8,192, pixels per image at 16,777,216, aggregate RGBA at 128 MiB, encoded assets at 32 MiB, metadata at 1 MiB/65,536 nodes, schedule entries at 16,384, regions at 8,192 and alpha visits at 64 Mi. See [TEXTURES.md](TEXTURES.md) for the contract, exclusions and deferred validation matrix. These are CPU preparation budgets, not combined generation/GPU memory approval.

## Shared CPU atlas and frame updates

`AtlasBuilder.plan(preparations, cancelled)` admits bounded texture preparations from the same archive, shares matching sprite IDs and creates deterministic multi-page power-of-two layouts with edge gutters. Source images/mesh arrays are charged by retained object identity, including independent duplicate decodes; aggregate schedule counts and source/prospective bound mesh/page payloads are checked together. No texture is rescaled or omitted to make a layout fit.

`AtlasMeshes.bind(layout, admittedPreparation, cancelled)` retains position/normal/index/face order and original native metadata while mapping sprite-local UVs into the assigned page. Consecutive page/alpha candidate ranges are recorded without reordering faces. Read-only heap buffers remain separate from the source packets. Bound meshes and rasters must share the exact layout object, not merely a matching archive hash.

`AtlasImages.capture(layout, tick, partialTick, cancelled)` creates immutable base-level CPU RGBA pages with frame-edge/corner extrusion. `updates` creates bounded whole-slot patches only for changed canonical tile/progress samples; `apply` checks the exact prior raster identity and creates a new snapshot by cloning changed pages once and sharing untouched pages. All partial output is withheld on failure, and cancellation is separate. Multi-frame RGBA interpolation is implemented with explicit CPU rounding; native driver parity remains unverified. This CPU layer does not implement filtering/mipmaps or execute GPU uploads. See [ATLAS.md](ATLAS.md) for the contracts, numerical budgets, exclusions and deferred verification matrix.

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

`AtlasFramePlan.prepare` now prepares immutable changed-page CPU rasters and grouped patches on a worker. The [GPU animation layer](../renderer-gl/ANIMATION.md) copies changed pages into fresh textures, applies bounded patch rows, shares unchanged pages and checks the exact displayed baseline plus scene/frame revisions before adoption. A frame-aware draw overload reuses geometry. Dependencies hold source framebuffer/page resources through actual retirement; the existing world-view worker now schedules stepped/interpolated atlas pixels. Native mipmap/filtering parity and final application scheduling remain pending.

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

`AtlasFrameWorker` now prepares immutable sprite patches and changed page copies off the context thread, with one active/latest pending clock, exact preflight and cancellation reservations. `GlWorldAnimation` connects those plans to immutable GPU updates; `GlWorldView.clock` advances the externally supplied clock, coalesces repeated frames and redraws only after a complete atlas adoption. Camera navigation keeps the current complete atlas, transparent geometry order is reused, and transferred images retain their original pixels/clock/picking raster. See [WORLD-ANIMATION.md](../renderer-gl/WORLD-ANIMATION.md). Stepped and interpolated RGBA samples share this path; unsupported metadata guards remain, and renderer tests and real-driver/native comparison are deferred.

## Native capture adapters

`WorldCapture` and `MapCapture` now detach streamed native identities and samples with full-volume/grid preflight, bounded palette text, cancellation and completion guards. The separate [native renderer bridge](../native-renderer/README.md) adds typed Iris/VolmLib chunk/registry capture and native terrain, biome, generator, expression and noise-layer sampling. Its sixteen native adapter sources require the genuine pinned Windows offline classpath and have not yet been compiled or executed; they are separate from the 119 compiled CPU/GL sources. Native visual producers, native validation and application admission/integration remain pending; no preview gate changes.

## Native deterministic model requests

`NativeModelInputs.capture` now checks actual native render shape, fluid/entity guards, exact offset input and registry metadata, then detaches state decisions. `WorldModelInputs` admits unit-weight JSON model parts with complete explicit tint coverage while preserving other visuals as explicit original-state placeholders. `WorldRenderWorker.ModelRequest` performs this inspection, visual preparation and complete scene assembly on the same CPU thread; no native session is retained by the queue. See [MODEL-INPUTS.md](MODEL-INPUTS.md) for unsupported paths, ownership, limits and deferred validation. Native API compilation, actual native/GPU verification and preview readiness remain pending.

## Native hidden-face masks and scene capture

`NativeVisibility` now produces block-direction masks from the vanilla native face predicate, with explicit boundary retention and bounded primitive memoization. `WorldVisibility.Input.read` streams the privately owned mask without an additional source-array copy. `NativeSceneInputs` combines model and visibility capture into a detached scene-worker request under one primitive working budget; no native context reaches the render queue. See [NATIVE-VISIBILITY.md](NATIVE-VISIBILITY.md). Twelve native adapter sources still require genuine Windows compilation and runtime validation; no preview gate is enabled.

## Native model offsets

`NativeOffsets` now captures actual native `BlockState.getOffset` translations into bounded immutable `WorldOffsets` tied to the exact native snapshot. `NativeSceneInputs` supplies them through model inspection and visual preparation; meshing translates shared prototype vertices during placement. Actual translated bounds drive culling, transparent ordering and picking while original state/biome/cell identity and diagnostic marker locations remain intact. Offset arrays are included in pending-input, source/cache and phase budgets. See [OFFSETS.md](OFFSETS.md). Native API compilation and runtime/pixel comparison remain pending; other model guards and preview gates still apply.

## Native weighted model choices

`WorldModelChoices` now inspects bounded alternative descriptors and streams per-cell native selections into an interned choice palette. `NativeWeightedInputs` uses actual native position seeds/weighted lists and multipart seed resets with a dedicated native random source from the reviewed default factory or an explicitly supplied, renderer-matching source. It captures offsets and visibility under shared admission and returns a detached request for the existing CPU scene worker. Selected tint/empty/unsupported visuals remain guarded. See [WEIGHTED-CHOICES.md](WEIGHTED-CHOICES.md) for canonical tickets, random-source responsibility, budgets, ownership and deferred validation. Genuine Windows API compilation, factory/native/pixel comparison and readiness remain pending.

## Still to implement

Native geometry/UV/texture comparison and guarded cases, native weighted-choice factory/output validation, offset and visibility validation, native tint factory/output validation and optional native lighting production/validation, transparent intersection/OIT handling, special render shapes, native animation interpolation/mipmap/filtering, real-driver scheduling validation, combined application/GPU admission, native map sampling adapters, full generation/scene integration and final map/3D editor presentation remain pending. The backend marker/mesh/animation code still requires real-context/native comparison and visible verification. There is currently no editor integration or ready-for-preview flag in this module.

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

## Full-height scalar volumes

[Native scalar volume capture](NOISE-VOLUME.md) now detaches original generator/expression samples across the entire configured Y interval, separately from block/biome worlds. An explicit opaque display interval builds compact outward boundary faces with shared-face culling and original-value inspection; one latest-request worker owns background preparation and shared source/output admission. The pinned generator currently ignores Y internally, and that native behavior is preserved. The [dedicated scalar GPU view](../renderer-gl/NOISE-VIEW.md) now uploads compact face instances and produces immutable images/readback with bounded original-value inspection. Real-driver/native validation and final editor routing remain pending; unsupported previews stay disabled.

## Streamed final lighting

`WorldLighting.read` now captures scoped final RGB components directly, and `readOwned` optionally charges the exact scene plus final arrays through shared ownership. The [material-lighting path](../renderer-gl/MATERIAL-LIGHTING.md) adds the explicit native resolver bridge, owned GPU handoff, unchanged-stride simple shade/emission flags and detached frame policy receipts. No native lighting factory is inferred; real native/GPU validation remains pending.

## Selected-client vanilla tint resources

`ClientColormaps` now decodes grass, foliage and dry-foliage PNGs into immutable, version-bound ARGB tables with bounded scratch and optional shared ownership. The native bridge adds radius-zero world-model tint sources and connects them through complete deterministic/weighted capture. Native global tables remain untouched; unknown model tint layers, neighboring-biome blending and special visuals remain guarded or unfinished. See [VANILLA-TINTS.md](VANILLA-TINTS.md) for supported blocks, admission and validation boundaries.

## Native fluid surfaces and waterlogged models

[Vanilla fluid capture](FLUIDS.md) now reads actual native water/lava heights, flow, occlusion and radius-zero water color into bounded shared surface prototypes, then transfers an owned choice request to the existing scene/GPU path. Strict optional neighbors never substitute air. `NativeCapture.fluidScene` now completes weighted JSON bases and fluid layers under one admitted capture, retaining exact selections/tints and sharing base geometry across different fluid recipes. Per-face model/cell placement now keeps fluid surfaces fixed beside offset-dependent bases, and combined capture accepts admitted optional neighbor snapshots. Special bases, missing native offsets and unsupported previews remain guarded; genuine native compilation and renderer-stage tests remain pending.

## Native model factory and JSON-only entity blocks

The [model policy](MODEL-POLICY.md) now creates the reviewed dedicated native RNG inside default weighted/fluid capture and admits 23 pinned JSON-only entity blocks after actual native identity/shape checks. Furnaces, hoppers, barrels and related models retain original generated state, selected assets and all tint/offset/selection guards. Entity-rendered visuals remain explicit problems. No generation RNG or entity data is invented; Windows native compilation and end-stage tests remain pending.

## Greedy scalar boundary meshes

[Greedy scalar rectangles](SCALAR-GREEDY.md) now merge compatible coplanar display-bin faces with exactly equal native scalar bits. Bounded selected-bounds plane scratch, exact count/emission and shared worker admission preserve the entire source volume. Twelve-byte GPU instances carry extents, and picking resolves merged faces to original sample coordinates/values with deterministic edge ties. An explicit unit mode remains available for end-stage comparisons. Native sampling, textured block meshes and preview readiness are unchanged; renderer tests and Windows shader/pixel validation remain deferred.

Native fluid faces now carry [whole-source material alpha groups](FLUID-MATERIALS.md). Texture preparation admits still/flow/overlay dependencies together and shares one bounded raw-sheet alpha union per group, while preserving mapped-region diagnostics and ordinary JSON material behavior. The resulting pass follows existing atlas/world/draw/picking paths. All 119 CPU/OpenGL sources compile; 16 native adapters remain syntax-only, with runtime/native/GPU tests deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Model source alpha and terrain thresholds

[Baked model materials](MODEL-MATERIALS.md) now retain pre-transform source UVs for reviewed 26.3 pass selection, including exclusive region bounds, complete-sheet fast paths and empty source windows. Conservative mapped diagnostics remain separate; fluid groups retain their own scope. Shared alpha thresholds now drive GLSL and displayed-raster picking together: 0.5 for cutout and 0.1 for translucent. The existing scan/pixel and geometry/GPU budgets remain unchanged. Compilation/source inspection only; native/runtime/GPU comparison and renderer-stage tests remain deferred.

## Original pack images

[Source image preparation](IMAGES.md) now detaches original PNG bytes, decodes complete source dimensions asynchronously, records exact resource/revision/SHA receipts and supports original RGBA pixel inspection. The separate [GPU image view](../renderer-gl/IMAGE-VIEW.md) connects bounded row upload, fit/pan/zoom, independent immutable frames and distinct readback receipts without fabricating native map samples. All 126 CPU/OpenGL main sources compile (85 CPU, 41 GL); 16 native adapters remain syntax-only. Tests and editor integration remain deferred; unsupported previews remain disabled.

## Scalar range culling

[Scalar surface ranges](SCALAR-CULLING.md) now retain charged integer bounds for at most 1,024 original-order faces each. OpenGL frustum rejection coalesces visible runs without instance copies; ray picking skips missed ranges while retaining the full-face allowance and original sample ties. Frame/query work counters expose the skipped work. All 126 CPU/OpenGL sources compile; native adapters remain syntax-only and runtime/GPU tests stay deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Frame-bound scalar clipping

[Scalar display clipping](SCALAR-CLIPPING.md) now shares `WorldClip` across fragment/range rejection, view revisions, immutable frame/readback receipts and original-value ray picking. Clip changes redraw existing data without sampling, rebaking, upload or invented cap faces. Source-failure diagnostics and earlier unbounded APIs remain intact. All 126 CPU/OpenGL sources compile; tests and real-driver/native validation remain deferred, and unsupported previews stay disabled.

## Background scalar inspection

[NoisePickWorker](SCALAR-PICKING.md) now schedules one active and one latest pending pointer query with bounded full-source admission, cancellation ownership and detached completions. Completed scalar frames submit their exact camera/clip/image identity without scanning faces on the context thread; admitted requests survive independent GPU frame closure. Image UUID and pointer revisions guard adoption. All 127 CPU/OpenGL main sources compile; renderer-stage runtime tests and Windows/native/GPU verification remain deferred, and unsupported previews stay disabled.

## Original JPEG images

[Bounded JPEG decoding](JPEG-IMAGES.md) now extends original-image viewing to common eight-bit grayscale/RGB/YCbCr inputs, including progressive images, through the standard Java codec. Background marker/table/scan and working-memory preflight rejects unsupported or incomplete data before publication; source-format receipts follow pixel inspection, GPU frames and readback. Atlas textures remain PNG-only. All 128 CPU/OpenGL sources compile; renderer-stage runtime tests, Windows/GPU comparison and final editor routing remain deferred. Unsupported previews remain disabled.

## World picking ranges

[Primitive range culling](PICKING-RANGES.md) now skips missed, clipped and behind-nearest groups of up to 128 world faces or diagnostic markers before individual tests. Original primitive order, native hit identity, alpha/clip policy and failure atomicity remain intact; range counters and an exhaustive option support later comparisons. Bounds/offsets share existing index/source admission and close-with-in-flight-query ownership. All 128 CPU/OpenGL sources compile; runtime/performance/native/GPU checks and final editor routing remain deferred, with unsupported previews disabled.

## Background world inspection

[WorldPickIndexWorker and completed-frame handoff](INDEX-WORKER.md) now build full picking indices on one bounded CPU executor and transfer their already-admitted source/index ownership. World frames submit exact-scene camera/clip/animated-atlas requests to the pointer worker, whose image tokens and aggregate source bounds guard adoption. Accepted CPU work can outlive its GL frame; caller-owned indices remain explicit. All 129 CPU/OpenGL sources compile; runtime/concurrency/native/GPU checks and final editor integration stay deferred, with unsupported previews disabled.

## Terrain-map relief

[Optional slope lighting](TERRAIN-RELIEF.md) now colorizes native terrain heights using declared sample spacing, complete four-neighbor stencils and explicit light/ambient/blend settings. Edges and missing neighbors retain base colors; native values/extrema and alpha remain intact. Completed map/readback receipts now carry their actual color style and summary. Existing flat gradients/defaults and memory limits remain unchanged. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and final editor routing stay deferred, with unsupported previews disabled.

## Prepared scalar colors

[CPU color preparation and geometry-sharing recolors](SCALAR-COLORS.md) now remove scalar ramp evaluation from uploads/picking. Color-only requests reuse complete face/range/volume data on the existing worker, with explicit old/new color admission and retained-frame support during rapid replacements. CPU mesh accounting adds four bytes per face while GPU instances stay twelve bytes; existing ceilings and native samples remain unchanged. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and final editor routing remain deferred, with unsupported previews disabled.


## Prepared translucent centers

[Per-section transparent face metadata](TRANSLUCENT-CENTERS.md) now moves centroid calculation into mesh emission. Camera sorts visit only translucent entries while preserving exact source face keys, depth arithmetic and stable ties. The 28-byte CPU entries share existing section/cache/working limits; GPU transfer totals retain the original layouts. Sort publication failures now report the current revision and allow later requests after cleanup. All 129 CPU/OpenGL sources compile; runtime/concurrency/native/GPU checks remain deferred and unsupported previews stay disabled.

## Prepared world face bounds

[World solid-face ranges](../renderer-gl/WORLD-RANGES.md) now accumulate six bounds floats per 128 original emitted faces, charged before output allocation. Opaque/cutout drawing can reject hidden portions of visible sections while coalescing adjacent surviving material spans; picking-index preparation copies the prepared bounds without rescanning vertices. Source identity, transparent ordering and existing memory ceilings remain intact. All 129 CPU/OpenGL sources compile; runtime/native/GPU validation and editor integration remain deferred, with unsupported previews disabled.

## Prepared material counts

[Verified section/scene material totals](MATERIAL-COUNTS.md) now carry surviving tint-required and translucent face counts from CPU count/emission into GPU validation and camera-sort admission. Sorting reserves actual primitive scratch within the existing ceilings; depth/key ordering and native identity remain intact. No face-sized arrays are added. All 129 CPU/OpenGL sources compile; runtime/native/GPU comparisons and editor integration remain deferred, with unsupported previews disabled.

## Prepared picking sizes

[Verified drawable/range counts](PICKING-SIZES.md) now size picking-index worker arrays in constant time during submission. Scene construction and index preparation independently recheck counts; mesh publication also verifies drawable bounds and primitive collection coverage. Valid jobs reserve actual source/index/build payload within existing ceilings, preserving complete queries and native identity. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Captured map recoloring

[Explicit map recoloring](MAP-RECOLOR.md) now reuses exact native captures for scalar gradients, biome palettes and terrain relief. Equal value styles reuse the CPU raster; changed styles reserve old and new images, with owned handoffs and latest-capture guards in the connected view. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Retained scalar recovery inputs

[Scalar CPU ownership and upload recovery](../renderer-gl/SCALAR-RECOVERY.md) now allow complete displayed surfaces to outlive GL owners under independent shared admission. Consuming recolor/retry view overloads preserve ownership through destination admission; upload retries verify exact latest volume/style and retained output limits without resampling or remeshing. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Retained map and image inputs

[Raster source ownership and upload recovery](../renderer-gl/RASTER-RECOVERY.md) now extend CPU admission beyond displayed map/image frame lifetime without copying pixels. Image decode completions can retain independent input handles; connected GPU retries verify current source provenance and preserve navigation without native sampling, colorization or decoding. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Prepared CPU stage contracts

[The initial CPU validation harness](CPU-VALIDATION.md) now prepares 19 deterministic groups for map capture/color/relief/navigation, original PNG pixels, scalar exposure/recolor/picking and shared ownership. Independent face/light expectations supplement atomic failure and memory-boundary cases. The Windows runner compiles by default and requires explicit execution at renderer-stage validation. All 129 production sources plus the harness compile; no contracts ran and unsupported previews stay disabled.

## Prepared world pipeline contracts

[The world CPU harness](WORLD-CPU-VALIDATION.md) adds 21 synthetic groups for cube model/atlas geometry, native assignments/visibility, complete scene metadata, cache lifetimes, exact picking and independent translucent ordering. The compile-only runner now includes both suites (31 prepared groups). All 129 production sources plus both harnesses compile; no tests ran and unsupported previews stay disabled.

## Prepared display math contracts

[Context-free camera/frustum contracts](../renderer-gl/MATH-VALIDATION.md) add 13 analytic and reference-oracle groups for projection, rays, exact anchors, navigation, scalar fitting and conservative culling. The default runner explicitly includes only four dependency-free backend helpers and three harnesses (44 prepared groups). All 129 production sources plus three harnesses compile; no tests ran and unsupported previews stay disabled.

## Readback replacement guards

[GPU readback preflight/recovery](../renderer-gl/READBACK-RECOVERY.md) now preserves earlier work and untaken pixels when a replacement frame is closed, foreign or over configured limits. Current failed requests can reopen after cleanup at the same token; independent raw/owned CPU outputs retain their existing lifetimes. All 129 production sources plus three harnesses compile; no tests ran and unsupported previews stay disabled.

## Owned atlas plans and prepared animation checks

[Independent atlas plan inputs](ANIMATION-OWNERS.md) now preserve shared admission beyond completion closure and feed consuming GPU upload/queue paths with coordinator checks. The connected animation controller uses this handoff. Eleven prepared schedule/interpolation/page-sharing/limit groups bring the total to 64. All 129 production sources plus four harnesses compile; no tests ran and unsupported previews stay disabled.

## Atlas replacement guards

[Atlas GPU queue preflight/recovery](../renderer-gl/ATLAS-RECOVERY.md) now checks exact displayed scene/raster identity before replacing same-scene work, with explicit current-failure cleanup/retry. CPU plan ownership and existing scene/frame adoption contracts remain intact. Follow-up compilation in the restored workspace now covers these changes. The 64 prepared groups remain unexecuted and unsupported previews stay disabled.

## Map and image queue preservation

[Upload replacement preflight](../renderer-gl/RASTER-PREFLIGHT.md) now preserves lower-level queue work/READY textures when a candidate source fails completeness or hardware/row-staging checks. Explicit upstream invalidation retains its existing behavior. All 129 production sources plus four harnesses compile, including the prior atlas fix; no tests ran and unsupported previews stay disabled.

## World source and material preflight

[World upload/view preflight](../renderer-gl/WORLD-PREFLIGHT.md) now preserves earlier owned output when a new CPU scene or its tint/lighting inputs are invalid. Verified tint-required counts avoid synchronous geometry scans, while fresh rejected scene wrappers are still consumed. All 129 CPU/OpenGL sources plus four harnesses compile; the 64 prepared groups remain unexecuted and unsupported previews stay disabled.

## Completed-frame queue preservation

[World image preflight](../renderer-gl/FRAME-PREFLIGHT.md) now rejects invalid borrowed scene/order/atlas/camera/target metadata before replacing earlier queue-owned output. It adds no geometry scan or source copy. All 129 CPU/OpenGL sources plus four harnesses compile; the 64 prepared groups remain unexecuted and unsupported previews stay disabled.

## Camera-order reservation visibility

[Sorting-worker accounting](WORKER-MEMORY.md) now exposes active/pending/READY lifecycle totals through `reservedBytes()` and the world-view forwarding accessor. Source-cleanup overlap and caller-owned charges remain enforced. Sorting, shutdown-recovery and picking-boundary groups bring the four-suite total to 64; all compile and remain unexecuted.

## Retrying worker shutdown

[Scene-owning worker shutdown](WORKER-MEMORY.md) now retries unfinished close attempts after executor/cleanup errors, continues owned-cache retirement and preserves caller-transferred scene charges. Public construction keeps the existing daemon scheduling. All 129 production sources and four harnesses compile; 64 groups are prepared and remain unexecuted.

## Conservative world picking boundaries

[Picking hierarchy and range bounds](PICKING-RANGES.md) now share conservative padding and outward slab rounding, so hierarchy rejection cannot discard the existing triangle edge allowance. Actual triangle/marker, geometric clipping, ray-distance and stable tie rules remain unchanged. A prepared analytic edge/corner group brings the total to 64 unexecuted groups; all sources compile.

## Map and image inspection boundaries

[Display boundary precision](MAPS.md) now keeps contained upper-edge pixels inspectable and corrects adjacent-sample selection after cancellation-prone pan/zoom. It preserves half-open bounds, exact displayed internal edges and the existing coarse-screen inverse policy with constant-time scalar work. Native map samples and original image RGBA stay unchanged; the new prepared group brings the total to 64 unexecuted groups.

## Sorting view preflight

[Translucent direction guards](TRANSLUCENT-CENTERS.md) now preserve pending/READY worker output when a supplied direction is too small, while consuming only fresh rejected source wrappers. Direct sorting reports the existing coordinate problem before scratch allocation and uses the same stable length calculation as camera math. The new prepared contract brings the total to 64 unexecuted groups; all production and harness sources compile.

## Consistent CPU worker shutdown

[All twelve CPU workers](WORKER-MEMORY.md) now retry unsuccessful shutdown attempts while keeping input gates closed, active reservations charged through unwind and caller-owned sources/completions independent. Pointer workers drop their request graph before releasing admission. Controlled checks for the remaining eight worker families bring the total to 64 prepared, unexecuted groups; all sources compile.

[Worker invalidation and visual unwind](WORKER-MEMORY.md#invalidation-metadata-and-visual-unwind) now prepare IDLE metadata before cancellation in the remaining visual/render workers and clear the visual request graph before shrinking/releasing its hold. View setup also preserves worker ownership and primary errors across constructor failure. All sources compile; all 64 prepared groups remain unexecuted.

[Scalar hit-plane coordinates](SCALAR-PICKING.md#exact-normal-plane-hit-coordinates) now preserve the known face plane through clip checks and detached sample-local hit receipts. Prepared cases cover six directions, unit/greedy modes, closed ray endpoints, distant decimal origins and original-sample shared-edge ties. All sources compile; all 64 prepared groups remain unexecuted.

[Executor startup recovery](WORKER-MEMORY.md#executor-startup-failure-recovery) now clears unscheduled jobs in all twelve CPU workers after unexpected executor errors, attempts every owned release and preserves the original error through cleanup/publication failures. Existing rejection/OOM failed-token behavior and accepted revision semantics remain intact. Controlled startup/retry cases expand the existing 64 prepared groups; all production/harness sources compile, while renderer-stage execution and native/GPU validation remain deferred.

[Active drain recovery](WORKER-MEMORY.md#recovery-after-an-active-drain-aborts) now clears stopped scheduling gates in all twelve CPU workers and redispatches an already accepted latest request after an escaping task error. Original failures remain primary through owned cleanup and failed redispatch; scene-owning unwind also visits all remaining resources. Controlled sort/index abort-and-retry cases expand the existing 64 prepared groups. All production/harness sources compile; runtime/concurrency/native/GPU checks remain deferred and unsupported previews stay disabled.

[Captured-neighbor biome tint blending](BIOME-BLENDING.md) now accepts an explicit radius 0–7 and same-generation full-height halo, validates overlapping native biome identities and complete coverage, and bounds all RGB samples. Native colormap capture applies positional grass modifiers at each neighbor before averaging, retains ordinary tint output and reserves borrowed halo bytes through scene capture. A new world contract group brings the total to 65 prepared, unexecuted groups. All 134 independent production/harness sources and the 97-file dependency-free set compile; genuine Windows native compilation and native/client/GPU comparisons remain pending.

[Captured-neighbor water colors](FLUID-COLORS.md) now extend native fluid capture with explicit radius/sample limits, actual native biome palette colors and complete same-source/full-height halo validation. Palette bytes stay inside both parent and fluid-edit ceilings; model tints, lava emission, geometry/atlas formats and original-state identity remain unchanged. Geometry provenance records explicit native color policy, while the compatibility baker identifies supplied RGB without inventing a radius. The new world material group brings the total to 66 prepared, unexecuted groups. Full 134-file and dependency-free 97-file compilation passed; native/GPU validation remains pending.

[Detached visual limitation reports](VISUAL-REPORTS.md) now count complete mesh/empty/placeholder assignments and merge original-state diagnostics without double-counting duplicate problems. Reports retain exact coordinates, native IDs and compact receipts, use a new immutable assignment identity to reject stale reuse, and carry independent owned admission after the source scene closes. Count/reference/text/payload/visit/working limits fail without partial output. The new world group brings the total to 67 prepared, unexecuted groups; all 135 production/harness sources and the 98-file dependency-free set compile. Renderer/native/GPU testing and editor report adoption remain pending.

[Background visual reporting](REPORT-WORKER.md) now runs the full source census on one active/latest-pending worker with bounded source/scratch admission, idle replacement credit and detached output. Failed source release prevents READY; shutdown/scheduling/drain failures retain cleanup and retry behavior. Completed GL frames expose compact exact-assignment receipts and a metadata-only submission helper, so reports can be adopted without a context-thread census. The new world group brings the total to 68 prepared, unexecuted groups; all 136 production/harness sources and the dependency-free 99-file set compile. Actual frame/thread/native/GPU execution and final editor adoption remain pending.

[Scalar histograms and legends](SCALAR-HISTOGRAMS.md) now detach complete native scalar distributions with explicit/observed ranges, separate missing/outlier counts and stable extreme/constant range labels. Exact source/range matching permits gradient-only reuse; raster/surface conveniences retain all borrowed display payload in their working allowance. Owned histograms retain only compact receipts and bins. The new CPU group brings the total to 69 prepared, unexecuted groups; all 137 production/harness sources and the 100-file dependency-free set compile. Actual numerical/worker/native/GPU validation and editor controls remain pending.

[Histogram worker/view handoffs](HISTOGRAM-HANDOFFS.md) now opt into paired map/surface completion under the original combined working limits. Histogram bytes remain charged with display owners, metadata preflight rejects known-invalid options, and connected views retain companions through source upload/recolor/retry. Frame receipts match exact scalar source and range without a context-thread census. Legacy constructors/defaults retain no-histogram behavior. The new world group brings the total to 70 prepared, unexecuted groups; all 137 production/harness sources and the 100-file dependency-free set compile. Real thread/GPU/view adoption and editor controls remain pending.

[Independent histogram preparation](HISTOGRAM-WORKER.md) now accepts raw map/volume or retained raster/surface owners, supports automatic range suggestions separately from explicit display ranges, and releases its source owner before READY. Completed map/scalar frames submit exact admitted CPU data without retaining GL objects. Exact source-only versus display-range matching keeps later recolor decisions explicit. The two new world groups bring the total to 72 prepared, unexecuted groups; all 138 production/harness sources and the 101-file dependency-free set compile. Real thread/frame/native/GPU validation and editor controls remain pending.

The [prepared Windows GPU harness](../renderer-gl/GPU-VALIDATION.md) now supplies ten real-context contract groups alongside the earlier 72 CPU/context-free groups. All 139 production/harness files compile; the 101-file dependency-free set remains independent of LWJGL and the new GPU harness. Execution is explicit and still deferred. Driver provenance, real pixel/shader/host-state contracts and failure-safe report publication are implemented in source; no actual GPU result or native parity is claimed.

[Scalar GPU recoloring](../renderer-gl/SCALAR-GPU-RECOLOR.md) now uses a constant-time shared CPU-layout proof to copy retained GPU geometry and upload only new colors. Separate immutable buffers preserve complete scene/frame ownership and existing memory/face ceilings. Connected views retain an exact geometry source through rapid color changes. All 139 production/harness sources and the 101-file dependency-free set compile; all 83 prepared groups remain unexecuted. GPU/native fidelity, failure/race validation, measured performance and editor integration remain pending.

The [prepared world GPU groups](../renderer-gl/WORLD-GPU-VALIDATION.md) now connect test-only detached JSON/PNG worlds through ordinary bounded model/atlas/scene preparation to incremental upload, rendering and original-state inspection. Analytical texels, alpha composition, animation clocks, material inputs, diagnostics and recovery expectations expand the GPU harness to 16 groups. All 140 renderer/test files and the 101-file dependency-free set compile; all 88 groups remain unexecuted. Genuine Windows GPU/native comparisons and final integration remain pending.

[Shared world GPU culling](../renderer-gl/TRANSLUCENT-CULLING.md) now uses the existing CPU face bounds once per group across solid and sorted translucent passes, without rebuilding geometry or reordering surviving transparent faces. Explicit temporary CPU admission covers world draw offsets, visibility and cache data. Context-free and GPU differential/budget contracts bring the total to 90 prepared, unexecuted groups. All 141 renderer/test files and the 103-file dependency-free set compile; actual native/GPU verification, measured performance and editor integration remain pending.

[Scalar drawing scratch](../renderer-gl/SCALAR-DRAW-ADMISSION.md) now receives temporary CPU admission before camera/culling arrays and target writes, with target completeness published after restoration and release. The shared grid precision helper and separate immutable differential frames preserve original volumes, colors and clipping. All 142 renderer/test files and the 104-file dependency-free set compile; all 92 groups remain unexecuted, with native/GPU/runtime validation and final editor integration pending.

[Immutable histogram reuse](HISTOGRAM-REUSE.md) now avoids a second full-source histogram pass when source, range and bins match. Requests and connected views retain existing memory admission and fallback to capture for changed metadata. All 142 sources and the 104-file dependency-free set compile; all 93 prepared groups remain unexecuted. Native/GPU validation and final integration remain pending.

[Bounded transparent-face sorting](BOUNDED-FACE-SORT.md) now halves temporary ordering payload to 16 bytes per face using two arrays, with a zero-move fast path for already ordered inputs and unchanged deterministic depth/source-key ties. Admission, cancellation and immutable output remain bounded. All 143 renderer/test sources and the 105-file dependency-free set compile; all 94 groups remain unexecuted. Renderer/native/GPU validation and final integration remain pending.

[GPU transfer replacement admission](../renderer-gl/REPLACEMENT-ADMISSION.md) now preserves accepted uploads, readback/atlas output and connected world frames when a fresh transfer cannot obtain its initial memory journal. All five transfer queues admit before retiring old work or publishing a replacement token, with conservative overlap and fresh-owner cleanup. Full compilation covers 143 files; all 96 prepared groups (76 CPU/context-free, 20 GPU) remain unexecuted. Native/GPU validation and final integration remain pending.

[World material refresh](../renderer-gl/WORLD-MATERIAL-REFRESH.md) now separates geometry and material storage, copies unchanged geometry/indices/markers/base atlas on the GPU and uploads replacement tint/final RGB only. Queue and connected-view entry points preserve exact CPU provenance, bounded overlap and immutable output, without retaining GPU ancestry chains. All 143 sources compile; all 98 prepared groups (76 CPU/context-free, 22 GPU) remain unexecuted. Native/GPU validation and final editor integration remain pending.

[Owned lighting and animation recovery](../renderer-gl/WORLD-CONTROL-RECOVERY.md) now keep supplied final-lighting admission live through queue/view material replacement and re-arm failed animation using the complete current scene. Initial-atlas compilation now passes alongside all 143 renderer/validation sources; all 100 groups remain unexecuted. Native/GPU/runtime validation and final editor integration remain pending.
