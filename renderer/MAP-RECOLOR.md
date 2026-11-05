# Recoloring captured native maps

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and code inspection only: renderer-stage execution remains deferred until implementation is complete. No colorization, concurrency, native, GL, GPU readback or workflow tests ran. Unsupported previews remain disabled.

## Explicit display replacement

Map recoloring reuses the exact immutable `MapSamples` capture from a complete `MapRaster`. Scalar range/ramp, biome palette and terrain relief changes run on the existing single map worker without a native query, resampling, source copy or change to native values. The usual kind/palette validation, absent pixels, alpha, original extrema and relief-stencil summary apply to changed styles.

| API | Contract |
| --- | --- |
| `MapRaster.recolor(source, style, limits, cancelled)` | Direct CPU operation; caller owns execution and source admission |
| `MapRasterWorker.Request.recolor(source, style, limits)` | Complete source required; exact sample object retained by the request |
| `MapRasterWorker.recolor(input, style, limits)` | Consumes an owned CPU raster input through admission or rejection |
| `GlMapView.recolor(style[, limits])` | Requires a complete current adopted upload |
| `GlMapView.recolor(source, style, limits)` | Accepts a caller-held raster only from the latest submitted capture |
| `GlMapView.recolor(input, style, limits)` | Same capture guard, consuming independent CPU input ownership |

`MapRamp` now compares its immutable ordered stops by value, with a matching hash code. Equal scalar, biome and terrain style records therefore permit a CPU no-op, including separately constructed equal ramps. After cancellation and lowered-limit checks, direct recoloring returns the original raster, image and summary without scanning pixels or allocating replacement RGBA. This equality also benefits the existing scalar-surface recolor path. Distinct styles still execute ordinary complete colorization, even if they happen to produce the same pixels. No GPU upload or view-revision no-op is promised.

## Capture identity and revisions

Each `MapSamples` has an opaque capture UUID. It distinguishes separately captured data even when context, grid and values match; it replaces no native resource, biome or block identity. The connected view stores only the latest UUID, rather than retaining another full raster graph. Recoloring preserves that capture identity; accepting a new sample submission changes it, and invalidation/close clears it.

The simple view overload requires `sourceReady`, so an old resource retained for failed cleanup cannot become a new recolor source. For rapid style changes before the replacement upload is adopted, keep a caller-owned prior `GlMapFrame` alive and pass its `raster()`, or use an independently owned CPU input. The capture must still match the latest submission. An obsolete capture fails before worker/view mutation, even if its dimensions and native context match. General `submit` remains the explicit source-replacement API.

Accepted recolors follow normal map/view revision advancement, cancellation, upload, drawing and exact-token take. Matching grid dimensions preserve the current pan/zoom transform. Worker/view status distinguishes recoloring from initial colorization. Old transferred frames retain their original pixels, inspection values, styles and summaries.

## Full source and replacement admission

Changed-style jobs reserve native samples plus old RGBA plus new RGBA; equal-style jobs reserve only the existing raster payload. `recolorReservation` computes this in constant time without a pixel scan. The 16 MiB image and 64 MiB per-operation working ceilings remain unchanged; the latter includes the retained old image. Active plus latest pending worker work stays within the existing 128 MiB maximum. A lower caller limit may reject recoloring even when a new image alone would fit.

Worker admission precedes cancellation/revision mutation. Rejection preserves prior work and view state. Cancelled active work remains charged until its stack unwinds. Successful output is checked against its admitted peak; the worker drops source/request references before shrinking the shared hold to native samples plus the final image. Transferred or retained external owners keep their own charges; overlapping sources may be charged conservatively in separate accounts.

`Completion.retainInput()` creates an independent CPU raster owner without copying pixels. Close the completion after handoff and close each retained input when done. Consuming worker/view overloads keep input ownership through destination admission or rejection and require tracked inputs to use the same coordinator. Raw raster APIs require callers to keep their source owner live through admission. GPU upload then follows the existing CPU/device overlap and independent frame lifetime contracts.

## Deferred verification

At renderer completion, cover equal and changed scalar/biome/relief styles, separately constructed equal ramps, hash consistency, lowered image/working limits, wrong kind/palette, absence/alpha, cancellation and allocation failures. Check original sample identity, exact summaries, source-only versus old/new reservations, READY/take-before-unwind, rapid replacements, stale capture rejection after native replacement/invalidate, foreign coordinators, consumed-input failures and final zero charges. Compare old/new frame and readback pixels on a real Windows GL context before enabling supported previews. Native adapters and final editor integration still require their separate validation.
