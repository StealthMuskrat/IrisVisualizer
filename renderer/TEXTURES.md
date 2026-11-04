# CPU texture preparation

Renderer-stage implementation, 2026-11-04. The CPU renderer and separate OpenGL backend compile with Java 25, including the later [CPU atlas layer](ATLAS.md). Runtime tests remain deferred until renderer implementation is complete. No user-selected client archive, native decoder, animation or GPU result has been exercised for this batch. Unsupported previews remain disabled.

## Snapshot-bound contract

`TextureMaterials.prepare(ClientAssetSnapshot, BlockMesh, BooleanSupplier)` accepts a complete CPU geometry result and the same captured asset content. Archive SHA-256, declared version and expected version must match the mesh receipt and pinned 26.3 profile. Paths or installation filenames are not used as epoch identity. Input placeholder problems propagate without attempting texture work.

Preparation decodes each unique referenced sprite once, retains PNG/metadata SHA-256 receipts, and publishes an immutable sorted sprite bank and one material result per quad in original order. `TexturePreparation.mesh()` retains the original input mesh and exact native state object. It does not replace block IDs or change geometry/material declarations. Face indices correspond directly to `BlockMesh.quads()` and `MeshPacket.faces()`.

Failure returns structured source-kind/resource/pointer problems and an empty texture bank/face list with no usage receipt. The input mesh remains available for diagnostics. Cancellation throws separately. Allocation failure unwinds worker references before reporting a memory placeholder; recovery from process-wide exhaustion is not guaranteed. There is no cross-snapshot global cache, editor readiness flag, native memory allocation, GL call or source write.

## Source images

The dependency-free decoder implements the relevant static PNG datastream from the [W3C PNG specification](https://www.w3.org/TR/2025/REC-png-3-20250624/): legal grayscale/RGB/palette/alpha sample forms, palette and keyed transparency, all scanline filters, noninterlaced and Adam7 reconstruction. Header and chunk structure, CRCs, palette indices, required ordering, exact inflated scanline size and zlib termination are checked. Unknown critical chunks, truncated/excess compressed data and trailing file content fail.

`RgbaImage` stores top-left-origin RGBA8, unpremultiplied, with host-independent packed `RRGGBBAA` access, read-only byte-buffer views and detached copies. Source color samples are preserved without gamma/ICC conversion; 16-bit channels use their high byte. Sub-byte grayscale expands to 0..255; transparency keys compare original sample precision before conversion. This is an implemented source-sample policy awaiting native decoder comparison, not a color-management guarantee.

Ancillary chunks are CRC-checked and ignored. APNG control/frame data does not drive playback: only the default PNG image is decoded, while Minecraft playback comes from `.png.mcmeta`. Ignored ancillary payloads are never decompressed. Limits and cancellation apply to chunk scanning, CRC work, inflation, row reconstruction and pixel conversion. IDAT spans reference one private encoded copy rather than a second concatenated buffer. Inflater native resources close on success, failure and cancellation.

## Metadata and schedules

The supported metadata fields follow the [NeoForge texture documentation](https://docs.neoforged.net/docs/resources/client/textures/), with conservative guards for unresolved pinned-client details:

| Input | CPU behavior |
| --- | --- |
| No animation section | Full image is one static tile |
| Empty animation object | Square tiles using the smaller image dimension; horizontal/vertical strips must divide exactly |
| Only width | Declared frame width and full image height |
| Only height | Full image width and declared frame height |
| Explicit width and height | Whole-tile row-major grid, including rectangular tiles |
| Omitted frames | Every grid tile in row-major order |
| Zero/one schedule entries | Static top-left tile, with interpolation disabled; a lone nonzero index does not select that tile |
| Frame number | Tile index with default positive `frametime` |
| Frame object | Required `index`, optional positive `time`; repetitions/order preserved |
| `interpolate` | Continuous schedule metadata retained; atlas pixels use integer-tick, thousandth-quantized RGBA blending |
| Texture settings | Blur, clamp, mean/cutout/strict_cutout/dark_cutout strategy and finite cutoff bias retained; no filtering/mipmaps applied |

Sizing and effective playback now follow the reviewed 26.3 source structure; see [model coverage](MODEL-COVERAGE.md). Unknown fields fail explicitly. Invalid frame indices and durations are rejected rather than reproducing the client's warning-and-removal recovery. Root sections outside `texture`/`animation`, including GUI-specific metadata on a referenced sprite, are currently unsupported. Strict bounded UTF-8 JSON rejects duplicate keys, malformed strings and nonfinite values; tile indices and exact positive durations are checked. Metadata absence is distinct from malformed metadata.

`TextureSprite.sample(long tick, double partialTick)` takes a nonnegative external integer tick and finite partial tick in `[0,1)`. It uses integer modulo and binary search over long duration totals, preserving large-clock and duration boundaries without converting the clock to a floating value. It returns the selected effective sequence slot, current/next tile and blend fraction. Zero/one-entry metadata becomes a single `(tile 0, duration 1)` entry; PNG/metadata digests retain source identity, and `animated()` reports effective playback rather than section presence. `pixelSample(tick, partialTick)` separately returns canonical current/next tiles and integer blend steps for [atlas interpolation](INTERPOLATION.md). It validates partial ticks but uses integer ticks for pixel progression. No game clock, PRNG, frame upload or mipmap regeneration is supplied by this class. Referenced tile indices are separately deduplicated for alpha work.

## Conservative alpha candidates

For ordinary materials without an explicit alpha group, each face's normalized UV extrema form a frame-local pixel rectangle. The scan includes `floor(min * size)` through `floor(max * size)`, with both clamped to the last valid pixel; upper bounds in the result are exclusive. Thus an exact interior maximum boundary also includes the adjoining pixel. Degenerate UV extents scan at least one pixel. This deliberately conservative policy has not been matched to the pinned native UV epsilon or sampling convention.

Only distinct referenced animation frames are scanned, including later frames and custom schedules; unused sheet tiles do not affect classification. Each unique sprite/quantized rectangle shares one cached scan within the preparation call. Results retain the presence of zero, partial and full alpha. Partial samples select a translucent candidate; zero-only/binary samples select cutout; fully opaque samples select opaque. `force_translucent` overrides every source result. Interpolated animations with multiple referenced frames and mixed zero/full alpha conservatively retain a possible blended-alpha flag and translucent candidate, even if the particular spatial masks or transitions would not require it.

These candidates describe source pixels and possible interpolation, not a certified native render pass. Mipmap cutoff bias, texture blur, UV shrinking, derivatives, transparent-color bleed and actual fragment alpha tests/blending are not applied during classification. Atlas rasterization separately applies the reviewed base-level RGBA interpolation profile. The separate [CPU atlas layer](ATLAS.md) now packs these sprites and extrudes frame-edge gutters; it does not alter the alpha analysis or certify filtered sampling. Fully transparent or degenerate faces are not removed. No neighbor culling, tint, AO or lighting is inferred.

Native fluid materials additionally declare bounded [whole-source alpha groups](FLUID-MATERIALS.md). Their dependencies are decoded/admitted together, and complete PNG sheets determine a shared class, including unused animation tiles. The mapped-region summary remains available separately from the optional material-wide summary. Whole-sheet and mapped scans share the region/pixel budgets below; group-summary metadata has its own equally sized count bound. Ordinary JSON material behavior is unchanged.

## Resource limits

| Budget | Default and hard ceiling |
| --- | --- |
| Unique sprites, including alpha dependencies / mesh faces | 256 / 24,576 |
| Image dimension / pixels per image | 8,192 / 16,777,216 |
| Aggregate retained RGBA / encoded PNG plus metadata | 128 MiB / 32 MiB |
| Aggregate metadata bytes / parsed nodes | 1 MiB / 65,536 |
| Aggregate schedule entries, including static tiles | 16,384 |
| Combined mapped/sheet alpha scans / pixel visits | 8,192 / 64 Mi |
| PNG chunks per image / inflated scanline bytes | 4,096 / 256 MiB |

Callers can lower preparation limits. Image allocation checks the remaining aggregate RGBA budget before allocation. Metadata and encoded asset totals are checked before copying; schedule entries and scan cost across all referenced frames are checked before expansion/scanning. Cached regions do not incur duplicate pixel charges. There is no success receipt after a failed reservation.

Counts exclude object overhead, JSON text, temporary encoded copies, scanline buffers, inflater overhead, existing captured assets and mesh/native/world data. The per-image pixel ceiling implies at most 64 MiB for one RGBA image. These bounds do not approve combined heap/direct/atlas/GPU memory; later integration must reserve those budgets together before enabling a renderer.

## Deferred stage validation

At renderer completion, run known PNG vectors for all legal color/depth forms, five filters, small/odd Adam7 passes, palettes and transparency keys at original precision, ignored profiles, 16-bit reduction and exact immutable pixel/buffer copies. Include bad signatures/header fields, CRCs/order, duplicate/unknown critical chunks, palette misuse, zlib checksum/dictionary/truncation/excess/trailing streams, chunk limits and cancellation in every phase. Compare source samples with the pinned native decoder, including transparent RGB and color metadata.

Exercise static/animated distinction, implicit horizontal/vertical strips, each single-axis dimension, explicit rectangular grids, divisibility, frame objects/order/repetition, empty and single-entry schedules (including nonzero indices), omitted defaults, tile and duration boundaries, large clocks, fractional rollover, malformed/duplicate-key metadata and guarded fields. Compare effective tile selection, native frame sizing, interpolation channel policy and all mipmap strategies against the selected pinned client.

Validate subrectangles/mirrors/rotated UV corners, exact boundaries, degenerate UVs, opaque/zero/partial samples, forced translucency, later and unused frames, interpolation uncertainty, duplicate-region caching, face ordering, wrong archive/version, aggregate budgets, cancellation and whole-result failure. Native model/atlas/pass comparison, full world integration and visible Windows/GPU checks remain required before previews can be enabled. No renderer tests were executed for this implementation batch.

`ClientColormaps` separately decodes the selected archive's three vanilla 256×256 colormaps with this PNG decoder. They are complete color tables rather than model sprites or animation schedules. [VANILLA-TINTS.md](VANILLA-TINTS.md) describes their receipts, native use and working admission.
