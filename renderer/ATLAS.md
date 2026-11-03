# CPU atlas layout, binding and animation

Implemented during the renderer stage on 2026-11-03. All 43 CPU renderer sources compile with Java 25. Renderer runtime tests remain deferred until implementation is complete. No real client atlas, native filtering, GPU upload or visible rendering has been validated for this batch. Unsupported previews remain disabled.

## One layout epoch

`AtlasBuilder.plan(List<TexturePreparation>, cancelled)` combines bounded successful preparations from the same captured client archive and pinned profile. It retains admitted preparations, their native state IDs and original CPU meshes. Conflicting sprite content, metadata, frame layout or source epochs fail explicitly. Failed preparations propagate their original problems. Invalid API arguments, such as an empty input list, are rejected separately.

Sprite IDs are shared across meshes only when PNG/metadata SHA receipts and decoded layout/settings/schedule agree. The builder separately charges distinct retained image and packet objects by identity: independently decoded copies still consume memory even when their asset IDs match. Retained animation schedules also have an aggregate entry limit. The sorted layout map keeps one source sprite per ID; admitted inputs remain available for provenance and memory accounting.

An `AtlasLayout` is an immutable object identity used as an epoch. It contains page dimensions, padded sprite placements, the admitted inputs, limits and a usage receipt. A matching archive hash alone is insufficient for later raster/mesh binding: two layouts may pack the same assets differently. No global layout cache or editor readiness flag is introduced.

## Packing and gutters

Packing uses deterministic descending frame height, descending frame width, then asset ID. First-fit shelves are checked in page/row order, with no tile rotation or rescaling. Each placement reserves one frame-sized content rectangle and an equal gutter on all sides. Pages shrink to the next power of two covering their used width/height. Empty geometry produces a successful empty layout without a dummy texture allocation.

The algorithm is bounded and deterministic, not an optimal packing claim. A packing, page-count or byte limit reports a problem without reducing texture resolution, omitting sprites or publishing a partial layout. The configured dimension is a CPU ceiling; a future GPU consumer must additionally check device capabilities.

Content x/y are integer texel-edge coordinates. The allocation starts at `(x - gutter, y - gutter)` and spans frame size plus twice the gutter. Rasterization repeats the content's nearest edge/corner samples into the entire gutter. Unused page pixels are transparent zero. This prevents adjacent source content from occupying the immediate border, but does not establish native mipmap, filtering or derivative behavior.

## Bound meshes

`AtlasMeshes.bind(layout, admittedPreparation, cancelled)` creates a separate immutable `AtlasMesh`. Only the exact preparation objects admitted during layout planning may be bound; arbitrary new geometry cannot consume an existing layout's reservation. The source `MeshPacket` remains unchanged.

The packed stride stays eight floats: position XYZ, normal XYZ and UV. Only UV values change:

| Component | Atlas coordinate |
| --- | --- |
| U | `(contentX + spriteU * frameWidth) / pageWidth` |
| V | `(contentY + spriteV * frameHeight) / pageHeight` |

No half-texel adjustment, UV shrink or native atlas epsilon is inferred. Source UVs must stay in 0..1. Vertices, indices, faces, selection IDs, source pointers and native state identity retain their original order/values. The bound faces retain original material/tint/light/AO/cull metadata and conservative source-alpha candidates.

`DrawRange` entries cover consecutive faces with matching page and alpha candidate. Faces are not reordered or removed. These ranges are not a native transparency sort, cutout threshold or executed blending policy. A future renderer still needs the correct native rendering policy. Buffer access is read-only with independent cursors; array copies are detached. No direct staging or GPU buffer is allocated.

`AtlasMesh.requireRaster(raster)` checks that both results succeeded and share the exact layout object. This is an epoch check only, not permission to display unsupported world data.

## Raster snapshots and animation patches

`AtlasImages.capture(layout, tick, partialTick, cancelled)` materializes immutable base-level RGBA pages using the current scheduled tile of each sprite. Clock arguments follow `TextureSprite.sample`: nonnegative integer ticks and finite partial ticks in `[0,1)`. Each raster records its layout, clock and selected source tile per sprite. No native game clock is created.

Multi-frame interpolated sprites are guarded before raster allocation because the pinned native RGB/alpha interpolation policy is unverified. Layout and UV planning can still represent them, and their texture metadata remains intact. Single referenced tiles, repeated references to that tile, and animations without interpolation can be rasterized. Blur/clamp/mipmap settings remain retained source metadata; this batch creates neither filtered samples nor mip levels.

`AtlasImages.updates(expectedRaster, tick, partialTick, cancelled)` compares scheduled tile indices and creates tightly packed RGBA patches only for changed sprites. Each patch includes the full content/gutter allocation, page and top-left offset, target source tile and row byte count. Whole-frame/cycle skips, custom order, repeats and external clock seeking use the existing schedule; unchanged tiles create no pixel payload. All changed-slot byte costs are checked before creating any patch image.

`AtlasImages.apply(currentRaster, update, cancelled)` is pure CPU copy-on-write. The transaction must target the exact current raster object and layout; an obsolete baseline returns `STALE_ATLAS`. Each changed page is cloned once, patches are copied row by row, and untouched immutable pages are shared. Old snapshots and patches are never modified. A no-change transaction returns a new clock snapshot sharing every page, so previously prepared transactions still become stale after adoption.

Failure publishes no partial layout, bound mesh, raster pages or update payload. The original inputs/current raster remain available to the caller. Cancellation propagates separately. Memory failure unwinds worker references before returning an explicit problem; recovery from process-wide exhaustion is not guaranteed. These transactions do not submit GPU work, alter UI content, admit a generation result or implement asynchronous latest-request scheduling.

## Resource accounting

| Budget | Default | Hard ceiling |
| --- | --- | --- |
| Admitted preparations | 1,024 | 1,024 |
| Unique sprites | 4,096 | 4,096 |
| Aggregate faces / retained schedule entries | 262,144 / 65,536 | 262,144 / 131,072 |
| Page dimension / page count | 4,096 / 16 | 8,192 / 32 |
| Gutter width | 1 | 8 |
| Retained source RGBA | 128 MiB | 256 MiB |
| Retained source packed arrays | 64 MiB | 64 MiB |
| Atlas base-level RGBA | 128 MiB | 128 MiB |
| Prospective bound mesh arrays | 64 MiB | 64 MiB |
| One animation patch transaction | 32 MiB | 32 MiB |
| Known primitive payload working budget | 512 MiB | 512 MiB |
| Shelf comparisons | 16,777,216 | 16,777,216 |

Limits can be lowered; the hard ceilings are not evidence that the host has that much free memory. Layout planning reserves source image bytes, source packet arrays, prospective bound arrays for the admitted inputs and final page bytes together. Animation preparation adds patch bytes; application additionally reserves one new copy of each changed page while the baseline remains live. These checks occur before pixel/buffer allocation.

Usage excludes object/string/map overhead, captured ZIP bytes, native/world data, direct staging, GPU allocations and arbitrary caller-held old epochs/results or repeated bindings. Count limits bound retained metadata separately, but the working byte budget is not a complete heap measurement. The eventual renderer must manage one active bundle, reserve generation/staging/GPU memory together, release obsolete results and enforce hardware limits before upload. No existing preview size/default or generation budget has changed.

## Deferred stage verification

At renderer completion, exercise deterministic packing under input permutations, multiple pages, padded bounds/nonoverlap, power-of-two dimensions, rectangular sprites, oversized sprites, empty meshes, shelf-search limits, and every count/byte threshold. Verify exact archive/profile/content conflicts, repeated source-object versus duplicate decoded-copy accounting, schedule-entry limits and admitted-input-only binding.

Check exact source vertex/index/face preservation, atlas UV edges/mirrors/rotations, page identity, range coverage without reordering, native-state retention, immutable buffers/copies and mismatched-layout rejection. Compare native UV sampling before adding shrink/epsilon/filter policy.

Check static and custom animated tiles, frame/cycle boundaries, large integer clocks, skipped/repeated frames, clock seeking, gutter/corner extrusion and unused transparent pixels. Include multi-frame interpolation guards and retained filter metadata. Patches must contain only changed slots, with correct offsets/row layout and unchanged base pages. Applying a transaction must equal a full CPU recapture at the same supported clock. Reapplying to a newer or foreign snapshot must fail without changing either raster.

Exercise cancellation in every phase, simulated allocation failure, initial/patch/copy-on-write peak reservations, unchanged-page sharing, and absence of partial output. Actual GPU upload/subimage alignment, sampler/mipmap behavior, native RGB/alpha interpolation, render-pass ordering, native block/biome agreement, native world integration and visible Windows checks remain separate unfinished renderer requirements. The later [world-section assembler](WORLD-MESH.md) now consumes atlas-bound prototypes without enabling previews. No renderer tests were executed for this batch.
