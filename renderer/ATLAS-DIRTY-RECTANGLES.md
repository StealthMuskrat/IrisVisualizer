# Bounded dirty atlas rectangles

Renderer implementation, 2026-11-07. Animated atlas updates can now carry the smallest enclosing rectangle of changed RGBA8 pixels in each affected sprite. The connected `GlWorldAnimation` path selects this mode. The compatibility overloads retain whole-slot updates as an explicit comparison path. Compilation and source inspection are complete; renderer runtime, native and GPU validation remain deferred. Unsupported previews remain disabled.

## Pixel preparation and exact receipts

`AtlasImages.updates(expected, tick, partialTick, PatchMode.DIRTY_RECTANGLES, cancelled)` resamples only the layout's existing time-varying sprite index. When canonical inputs differ, it compares the new encoded, unpremultiplied RGBA8 output against the exact expected page. It uses the existing thousandth-progress interpolation and channel rounding; it introduces no filtering, mipmaps or native sampling policy.

The comparison includes the complete edge-extruded padded slot. A changed corner therefore includes its duplicated corner/edge gutter pixels. Each dirty sprite produces one tightly packed bounding rectangle at its actual atlas offset. Disconnected changes can include unchanged pixels inside their enclosing rectangle; this implementation does not split rectangles or reorder sprites. Untouched page pixels stay unchanged.

Different tiles or blend inputs can produce identical output bytes. In that case there is no patch, page copy or new GPU page, but the new raster still carries the complete new canonical sample map and its actual preparation clock. The metadata-only GPU frame shares the old immutable pages. Existing canonical-window coalescing remains metadata-only: it does not discover additional rounded-pixel plateaus on the context thread or relabel existing frames with an unprepared clock.

`FULL_SLOTS` retains prior behavior: every changed canonical sample creates a complete padded-slot patch. Both the old update and worker-request overloads default to this mode. `AtlasImages.apply` accepts rectangles only inside the exact sprite's padded allocation, with matching page and frame, and still rejects a different or stale raster identity. Old CPU/GPU snapshots and completed images retain their independent ownership.

## Admission and ownership

Scanning allocates no pixel arrays. Region/sampling records and complete sample maps are bounded object metadata outside the existing primitive-payload estimate. Cancellation is checked per row and every 1,024 columns. Total actual patch bytes are checked against both update and working ceilings before patch arrays are created. Application separately checks complete changed-page copies plus patches and retained source before allocating those page arrays.

A dirty worker request performs only sparse metadata preflight. Let `S` be the existing source/layout payload, `C` the sum of full pages containing candidate sprites, `P` the candidate whole-slot patch bytes, `U` the update ceiling and `W` the atlas working ceiling. Its reservation is:

`min(W, S + C + min(P, U))`

The layout must already fit `S` within `W`. This reservation bounds any successful dirty result; exact pixel preparation can still return a structured `LIMIT` when actual patches or changed-page copies do not fit. Failure publishes no partial plan and leaves the displayed atlas intact. Full-slot mode preserves exact metadata preflight and its early rejection behavior.

Active work retains its upper reservation until it unwinds. A successful completion then retains only `S + actual patches + actual changed-page copies`. Independent completion/input holds preserve that charge through worker closure and GPU destination admission. Cancellation, replacement and error paths retain the existing latest-request and cleanup rules.

Changed pages still receive full CPU page copies and full GPU page copies before their rectangle patches are uploaded. A sparse change reduces patch payload and patch upload traffic; it does not reduce the size of a changed page's copy. Visually identical output avoids both copies. The scan adds CPU work, and performance gains depend on animation content; no measured speedup is claimed. Worker, atlas, device and generation limits are unchanged.

## Prepared coverage and remaining work

Three additional `AnimationCpuContracts` groups prepare known interior/asymmetric pixels, extruded corners, sparse enclosing rectangles, full-slot/full-recapture equivalence, interpolation, identical tiles, RGBA rounding plateaus, static-page sharing, exact clocks, stale/out-of-slot rejection, exact and one-byte-short limits, cancellation, conservative worker admission, retained-charge shrinkage and independent input lifetime.

One additional `GlGpuContracts` group prepares byte-for-byte base-level texture comparison against full CPU recapture and whole-slot upload for stepped and interpolated interior/corner/identical patterns. It also prepares exact copy/upload counters, bounded stepping, host-state restoration, original-frame immutability, metadata-only page sharing and connected worker/controller adoption. The report's old eighteen-group completion guard now matches all 36 registered GPU groups.

All 138 CPU/OpenGL production sources, five harnesses and one fixture compile with Java 25/LWJGL (144 files). The 106-file dependency-free set also compiles. There are 141 prepared groups: 23 renderer CPU, 47 world CPU, 17 animation CPU, 18 context-free display math and 36 GPU. All remain unexecuted. Genuine Windows/native typechecking, driver/native pixel comparisons, total-process memory and performance measurements, renderer completion and final editor integration remain pending.
