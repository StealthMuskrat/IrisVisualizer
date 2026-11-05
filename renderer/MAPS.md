# Native 2D samples, colorization and GPU maps

Renderer implementation, 2026-11-03. The combined renderer has 94 main Java sources: 68 CPU and 26 OpenGL. Java 25 compilation against pinned LWJGL 3.4.1 passes. No map/native comparison, worker test, GL context, upload, pixel readback, timing or interactive Windows validation has run. Renderer tests remain deferred until implementation is complete; unsupported previews remain disabled.

## Engine output stays separate from display

`MapSamples` captures native terrain heights, biome palette indices or noise values. It never scans a replacement world, chooses a surface block, calculates noise, rounds fractional sampling coordinates into block positions, substitutes a biome or changes the pack. The engine adapter must supply completed native samples using the applicable dimension, selected resource and isolation context. A failed native query must fail that engine request; it must not be disguised as an empty sample.

The immutable context retains the existing native world receipt (generation UUID, input hash, backend build, Minecraft version, dimension and seed), exact resource type/key and an explicit sampling-policy label. The label documents such things as the native height-query policy or noise slice; it is not an independently verified native receipt. The Minecraft profile must match the pinned renderer profile. Capture does not establish generation parity or preview readiness.

`MapSamples.Grid(x, z, scale)` defaults to **512 × 512** samples. Origin X/Z are the first sample, matching the existing editor framing. A column uses `originX + column * blocksPerSample`; a row uses `originZ + row * blocksPerSample`. Coordinates remain doubles within signed 32-bit world limits. Positive fractional scales are preserved, including repeated underlying native block queries where the engine's own query policy requires them. The renderer does not round, deduplicate or interpolate world queries. An excessive extent fails rather than cropping or changing scale. Width and height can be supplied explicitly within the capture budgets.

Samples are X-fastest, then positive Z, matching the top-left RGBA image row order. The caller freezes all source arrays/lists throughout capture; results own detached primitive copies and immutable palette lists. All arrays must cover every requested sample. Presence bytes must be exactly zero or one. Zero means a successful native query explicitly had no value, such as an empty height column; absent payload entries are inaccessible and carry no numeric/biome meaning. Present zero heights and zero noise remain real data.

| Kind | Captured native payload | Additional checks |
| --- | --- | --- |
| Terrain | Exact signed 32-bit heights plus presence | Full configured minimum Y/height retained; every present height lies within it |
| Biome | Exact native biome palette and integer indices plus presence | Unique native IDs, bounded palette and valid present indices; original names/IDs retained |
| Noise | Finite double values plus presence | No renderer noise calculation, native-value normalization or nonfinite replacement |

`terrain`, `biomes` and `noise` are convenience capture methods. The full `capture` method accepts explicit limits and rejects arrays/context that do not belong to the selected kind. Version, count, palette, presence, height, nonfinite and budget errors publish structured problems with no partial sample arrays. Cancellation propagates separately. Capturing a map does not require client texture assets, because these maps display supplied scalar/categorical data rather than claiming textured vanilla geometry.

## Display colors and inspection

`MapRaster.render(samples, style, cancelled)` creates one immutable RGBA8 image while retaining the exact native sample object for inspection. Failure publishes no partial image or summary. Cancellation is checked through capture and pixel colorization. Source arrays, biome IDs, scalar values, seed and coordinates remain unchanged.

Use `ScalarStyle(minimum, maximum, ramp)` for terrain/noise. Its range is explicit and stable across updates: values outside it clamp only for display. Native values remain accessible unchanged. A constant display range uses the ramp midpoint. Overflow-safe normalization supports finite ranges spanning both double extremes. `terrainStyle(samples)` uses the full configured height range with a grayscale ramp, rather than stretching each frame to its observed heights. Noise range selection belongs to the caller and does not modify Iris evaluation.

`MapRamp` accepts two through 64 strictly ordered stops from zero to one, with packed RRGGBBAA colors. It interpolates straight source-byte channels without color-profile conversion or premultiplication. `BiomeStyle` requires one explicit display color for each exact native palette entry. There is no hash-color fallback or replacement biome registry. Palette colors are a renderer presentation choice, not a native biome tint implementation.

Absent samples produce transparent black; an all-empty successful map is valid. Present samples with transparent display colors remain inspectable. The summary reports present/absent counts and observed scalar extrema, independent of display clamping; biome extrema are absent. `image()` exposes read-only/copying `RgbaImage` APIs. Summary and receipt fields do not claim complete native-world correctness.

`MapView` supplies fit-to-viewport, pointer-delta panning, anchor-preserving zoom and half-open pixel hit testing. Left/top are the displayed image origin; positive pan deltas move the image with the pointer. Display zoom does not change `blocksPerSample` or regenerate anything. Hits outside the image, including its right/bottom borders, return empty. Nonfinite or precision-collapsed transforms are refused. After a hit, use its pixel index on the matching raster's samples, check presence, and inspect the original terrain height, noise double or native biome. `grid.worldX/worldZ` provide the corresponding sample coordinates. The application must use a view whose dimensions belong to that displayed raster.

## Identity and small zoom navigation

A zero map pan or factor-one zoom returns the existing `MapView`, preserving fractional origins and signed zeros exactly. Zoom still rejects nonfinite anchors before taking that identity path. This prevents cancellation in an identity zoom from manufacturing a different transform and a needless connected-view revision.

For zoom factors from 0.5 through 2, the new origin is computed as the old origin plus the small anchor-relative displacement, using fused multiply-add. Other factors use the anchor-relative form with fused multiply-add. Near unity this avoids subtracting nearly equal viewport-sized values to recover a small origin. Existing scale/origin limits and precision-collapse rejection still apply; nonidentity zoom is ordinary rounded double arithmetic, not an exact geometric invariant.

The existing navigation contract prepares identity checks at fractional/signed-zero origins, finite anchors through the double range, invalid inputs and scale limits. Near-unity and broader factors are compared with an independent exact affine `BigDecimal` oracle over the binary inputs. These assertions remain unexecuted.

## Display boundary precision

`MapView.pixelAt` now clamps a contained point to valid source indices and checks the adjacent displayed sample edges when screen precision distinguishes them. This corrects subtraction/division rounding after pan or zoom: a pointer immediately below an internal edge stays in the preceding sample, and a pointer immediately inside right/bottom remains inspectable. Exact internal boundaries select the following sample; left/top stay included and right/bottom stay excluded.

For example, with an image origin of -1 and unit sample spacing, the largest double below screen X=1 remains inside the image even when inverse subtraction rounds up to 2. At spacing 2, that same point belongs to the first sample just below the internal edge at X=1. A fractional zoom uses its displayed `origin + index * pixelsPerSample` boundaries, preserving the following-sample rule at those exact screen positions.

The correction uses a fixed number of scalar operations with no sample scan or new per-query scratch. It runs when spacing is at least twice the largest endpoint ULP. For coarser screen precision, where adjacent edges may collapse, the existing inverse-index policy remains, with valid-index clamping after the unchanged outer containment check. The source grid, presence flags, native coordinates/values, colors and zoom limits are unchanged. `MapPick.at` and original-image inspection share this lookup.

A prepared CPU contract covers tiny offsets below/above internal edges, upper interior corners, min/max zoom, fractional displayed edges, distant coarse screen coordinates, original present zero versus absence, native map coordinates and transparent source RGBA. All 64 prepared groups compile but remain unexecuted; real GPU/DPI/pointer comparison stays deferred until renderer-stage validation.

## Latest-request worker

`MapRasterWorker` runs colorization on one daemon worker with one active and one replaceable pending request. Native generation/sampling remains a separate engine operation. `submit` returns a monotonic renderer revision. Replacing work cancels obsolete active colorization cooperatively, drops the old pending request and untaken output, and never adopts a stale result. `invalidate()` advances the token immediately when upstream native inputs become obsolete, even before replacement samples exist.

`take(latestRevision)` transfers a complete immutable raster once, only for the current token. Status is published under the worker lock without retaining large pixel/sample data. Failed captures/colorization become FAILED with structured problems; explicit later submission can reuse the worker. Closing cancels, drops owned pending/complete results and returns without joining the worker from the UI thread. Expensive payload locals unwind before the executor waits for another task.

Before accepting a request, the worker conservatively reserves its native primitive payload plus prospective image, together with the active request's corresponding reservation. Replaced pending work and untaken output are dropped as the new request is committed. A budget rejection leaves existing work/status intact. Externally retained or previously transferred samples/rasters, engine output buffers, generation jobs, palettes/Java objects and GPU data require separate application admission. The worker does not provide an unbounded output history.

## Immutable GPU texture upload

`GlMapUpload.begin(device, raster)` accepts a complete raster and charges its retained native sample arrays and image in a CPU-only device journal. It checks hardware texture and bounded row-staging dimensions. The first storage step expands that journal with one RGBA8 texture and the full GPU image charge, then uploads whole rows through the existing device staging arena. `step(GlUploadBudget, cancelled)` respects transfer/action/work limits and a cooperative time target. One chunk is at most 64 KiB. Driver calls and state capture/cleanup cannot be preempted.

Each step rebinds its owned texture, normalizes unpack/PBO state and restores host state before yielding or throwing. Textures use nearest/base-level sampling and clamp-to-edge. No shader, framebuffer, window or full-image native staging copy is added. Source image row zero is texture V=0; a later image widget must use that top-left UV convention. Display scaling/color blending is the presenting application's responsibility.

Only complete coverage, exact byte totals, successful host-state restoration and a final cancellation check permit READY. `take()` transfers a `GlMap` once. `GlMap.upload` is the synchronous convenience over the same state machine. A partially filled texture is never available to consumers. Failed/cancelled uploads retire every submitted object; pre-storage CPU journals release immediately, while submitted allocations retain their CPU/image/GPU charge through fence completion or quarantine under the existing `GlDevice` contract.

`GlMap.textureId()` and `raster()` are borrowed while live on the original context. Closing retires the map; source samples and image remain charged until its allocation actually releases. Java references retained independently by callers fall outside that owner lifetime contract. Immutable source rasters are borrowed/retained rather than consuming a generation lease; uploading the same raster twice conservatively charges both allocations.

`GlMapQueue` owns at most one active upload or one untaken GPU result. Call `invalidate(revision)` as soon as the latest native/resource/style request changes. `submit(revision, raster)` rejects old/duplicate work. `advance(budget, latestRevision)` cannot progress obsolete uploads, and `take(latestRevision)` checks the token again before transferring a completion. Newer tokens dispose queue-owned older work; a displayed caller-owned map is never closed by the queue. Only adopt the completion if the application's current context/revision still matches, then retire the replaced map after its last submitted use. Neither renderer revision equality nor matching receipt text enables a previously unsupported preview.

## Numerical budgets

| Resource | Default / hard ceiling |
| --- | --- |
| Map sample grid | Default 512 × 512; each axis at most 4,096 |
| Total captured samples | 4,194,304 |
| Native biome palette | 8,192 entries |
| Native primitive sample payload | 64 MiB ceiling; 5 bytes/sample for terrain/biome, 9 for noise |
| Raster RGBA payload | 16 MiB |
| One raster's native samples + output | 64 MiB |
| Worker active + prospective pending payload/output | 128 MiB |
| Map gradient stops | 2–64 |
| Display zoom | 0.000001–1,000,000 screen pixels/sample |

At 512 × 512, terrain/biome primitives plus RGBA cost 2.25 MiB; noise plus RGBA cost 3.25 MiB, before palette/object overhead. GPU output adds 1 MiB, plus the shared device staging reservation and other live/retired allocations. These ceilings do not confirm complete application heap/native/VRAM requirements and do not reduce the specified default resolution. Original-image previews retain their own dimensions and existing loading path.

## Deferred stage verification

At renderer completion, compare map positions/values/biome IDs against actual native output for identical pack, revision, isolation context, seed, coordinate, scale and height. Cover fractional/negative origins, signed coordinate endpoints, nondefault full heights, empty columns, real zero/negative noise and all palette identities. Assert detached capture, strict source-kind checks, version mismatch, every primitive/count boundary, cancellation, no partial output and native failure propagation upstream.

Check scalar endpoints, constant/extreme double ranges, transparent/intermediate-alpha ramps, stable explicit ranges, exact biome colors, all-empty maps and unclamped inspection data. Verify fit/pan/zoom anchors, outside/border hits, matching raster dimensions, finite bounds and repeated tiny scales. Exercise rapid submit/invalidate/take races, budget rejection without state mutation, worker failure/retry/close and no stale/pending accumulation.

On real Windows GL contexts, compare every uploaded pixel/row and orientation, odd sizes, exact/final short row groups, minimum budgets, byte/action/work limits, cancellation/restoration failures and inability to take partial textures. Seed hostile PBO/unpack/texture/sampler state at each yield. Check source charge retention, CPU-only close, GPU retirement/quarantine, context/device/thread rejection and cleanup. Exercise queue updates before sampling completes, during upload, ready-before-take and after caller transfer. Native sampling adapters, renderer/UI adoption and visible interaction remain integration work; no map preview gate is enabled by this batch.

## Connected map presentation

The [map-view endpoint](../renderer-gl/MAP-VIEW.md) now combines this worker/uploader with a private offscreen canvas, display-only fit/pan/zoom, immutable completed images and original-sample inspection. Retained map/frame wrappers share exact source data without duplicating native payload. The source texture V=0 convention above still applies; rendered framebuffer images use a flipped V convention in a top-left widget. Native API/runtime validation, real-driver/pixel comparison, combined application admission and final editor routing remain pending.

## Optional terrain relief and captured color styles

[Terrain-map relief](TERRAIN-RELIEF.md) adds an explicit TERRAIN-only style over the existing scalar ramp. Central differences use original opposite-neighbor heights and declared sampling distance, with overflow-safe normal/light normalization. RGB is shaded while alpha, native samples, extrema and inspection values stay intact. Edges/missing stencils keep base colors; the old terrain helper stays flat. Summary adds an eligible-stencil count with a compatible prior constructor. Successful completed map images/readback now retain their actual style and summary; old receipt constructors explicitly leave them unknown. No source-sized array, GPU storage or default limit changes. All 129 CPU/OpenGL sources compile; runtime/worker/native/GPU tests remain deferred and unsupported previews disabled.
