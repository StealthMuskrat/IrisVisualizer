# Terrain-map relief lighting

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and source inspection only: no map colorization, worker/concurrency tests, native comparisons, GL context, pixel readback or workflow ran. Renderer-stage execution remains deferred until implementation is complete. Unsupported previews remain disabled.

## Explicit display style

`MapRaster.TerrainStyle(colors, relief)` adds optional slope lighting to captured TERRAIN maps. `colors` is the existing explicit `ScalarStyle`; its range/ramp still maps original native height to a base RGBA color. `Relief` configures one world-space direction toward the display light, an ambient floor and blend amount. The style is rejected for BIOME and NOISE maps before pixel allocation.

`MapRaster.terrainReliefStyle(samples)` uses the full configured height grayscale range and the following display defaults. The earlier `terrainStyle(samples)` remains the flat gradient, so existing callers do not acquire lighting implicitly.

| Parameter | Default | Bound |
| --- | --- | --- |
| Light direction X/Y/Z | -1 / 2 / -1 | Finite, nonzero vector; magnitude is normalized for lighting |
| Ambient floor | 0.35 | Finite, 0 through 1 |
| Blend amount | 0.7 | Finite, 0 through 1; zero keeps the exact base pixels |
| Neighbor stencil | Left/right/north/south | All four must exist and be present |

Light settings remain renderer inputs. They do not change pack JSON, seed, sampling coordinates, isolation, engine height policy, map resolution or configured dimension height. Height maps retain their actual dry/fluid-inclusive source policy; colorization does not choose water, surface blocks or another biome. This is simple display lighting, with native lighting verification remaining separate.

## Original heights and declared spacing

For each present interior sample, the renderer reads the original opposite-neighbor height differences `dx = right - left`, `dz = south - north`. Positive image columns/rows correspond to world X/Z. The upward normal is proportional to `(-dx, 2 * blocksPerSample, -dz)`, equivalent to central-difference slopes over the declared world-space sampling scale.

Height subtraction converts to double first, avoiding signed-integer subtraction overflow. Normal components are scaled before forming quotients, avoiding a direct potentially overflowing slope at extremely small positive spacing. Light components are likewise scaled before normalization, supporting finite very large/subnormal direction values without an infinite normalization length. The immutable style retains the caller's exact direction inputs for receipts.

The diffuse term is the clamped normal/light dot product. Ambient and blend amount produce a multiplier between zero and one; straight RGB8 channels are multiplied and rounded, while the original alpha byte is preserved. There is no gamma/profile conversion. Amount zero or ambient one preserves base RGB; only the former bypasses stencil work. Transparent colors remain inspectable, including their display RGB.

Edges, grids narrower/shorter than three samples and any stencil touching an absent neighbor retain the exact base color. No out-of-grid halo query, clamped neighbor, interpolated height or substituted zero is used. Absent center samples stay transparent black. The full source object and original sample coordinates/values remain unchanged, and `MapPick` still returns native height plus the actual displayed RGBA.

## Background preparation and receipts

Use the style through the existing `MapRaster.render`, `MapRasterWorker.Request` or `GlMapView.submit(samples, style)` path. Colorization stays on the single latest-request worker; upload/draw/pan/zoom use the existing immutable GPU map pipeline. Recoloring retained native samples does not resample the engine. Previously transferred frames keep their own old pixels and style during replacement.

`Summary.reliefSamples` counts present centers whose complete four-neighbor stencil was processed with nonzero amount. It does not count changed pixels: ambient one, a fully lit normal or black RGB can retain identical output. Original present/absent counts and native extrema do not depend on lighting. The previous four-argument Summary constructor supplies a zero relief count.

New `GlMapFrame.Info` receipts capture optional color style and summary together. Successful frame rendering always includes both; the previous eight-argument constructor leaves them explicitly unknown. Paired metadata, source kind, total grid counts and relief/style consistency are validated. This distinguishes the actual gradient, categorical palette or terrain lighting used by a completed image. `GlReadback.Pixels.map()` carries the same receipt without reconstructing style from a current pending view.

Receipts contain bounded presentation metadata, including at most 64 ramp stops or 8,192 biome display colors; they retain no raster/sample array, native session or GL owner. Java object/list metadata retains its existing external accounting boundary.

## Admission and deferred checks

Lighting creates one small job-local normalized-light object and reads the existing arrays. It adds no source-size scratch, normal map, neighbor cache, GPU resource, shader or staging buffer. Existing sample/output/worker/device/shared-memory ceilings and 512-by-512 defaults remain unchanged. The worker already admits complete source plus RGBA output and keeps cancelled work charged until unwind. Cancellation remains checked through bounded colorization groups; no hard elapsed-time guarantee or measured speed claim is added.

At renderer completion, compare flat/planar/rising/falling X/Z slopes against independent central-difference and diffuse-light calculations. Include signed extreme heights, full configured ranges, fractional/tiny sampling scales, large/subnormal light vectors, zero vectors/nonfinite/range rejection, ambient/amount endpoints and round-to-byte boundaries. Check one/two-row grids, every edge, missing neighbors, absent centers and transparent RGB/alpha.

Verify amount-zero equality with the scalar style, native extrema/source identity preservation, eligible-stencil counts independent of changed pixels and rejection on noise/biome maps. Exercise rapid recoloring, cancellation, stale worker/view tokens, memory limits, immutable old frames and original-value picking. Confirm style/summary metadata and legacy constructors across completed maps/readback, source-versus-viewport dimensions and invalid receipt combinations. Real Windows pixel/display QA, native source verification and final editor integration remain pending; no renderer tests ran for this batch.
