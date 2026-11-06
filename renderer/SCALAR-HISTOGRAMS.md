# Detached scalar histograms and display legends

Renderer implementation, 2026-11-06. Compilation and source inspection only. Renderer-stage contracts, native/GPU comparisons and final editor integration remain deferred; unsupported previews stay disabled.

## Original values and display range

`ScalarHistogram.capture` accepts successful immutable terrain/noise `MapSamples` or a full `NoiseVolume`. It synchronously borrows the source on a worker and returns detached counts plus compact context/grid/source-UUID metadata. It never calls an engine, re-evaluates noise, changes the sampling scale or height, normalizes the stored values, or replaces absent samples with zero. Biome palettes are categorical and are rejected rather than treated as numeric registry values.

An explicit `Range(minimum, maximum)` selects the histogram's displayed interval. Present values below/above it are counted separately. Included values use the existing `MapRaster.ScalarStyle` normalization, with uniform normalized bins: an internal boundary belongs to the higher bin and the inclusive maximum belongs to the last bin. Constant ranges place their actual value in the midpoint bin; other values remain below/above that constant. Observed minimum/maximum cover all present values, including outliers. Bin counts plus below/above counts equal present coverage; present plus absent equals the complete source count.

An empty requested range selects the actual observed minimum/maximum with a first source pass, followed by a complete binning pass. An all-absent source needs only its first pass and leaves the automatic range, observed extrema and legend empty. An explicit range remains available with an all-absent source, but its bins stay zero. No arbitrary fallback range is installed.

```java
var automatic = ScalarHistogram.capture(samples, Optional.empty(),
        ScalarHistogram.Limits.DEFAULT, cancelled);
var suggestedColors = automatic.displayStyle(MapRamp.GRAYSCALE);
// A successful optional style can feed the existing recoloring path; no native resampling is needed.
```

`Summary` reports present/absent/in-range/below/above counts, original observed extrema and reserved source visits. `count(i)` reads an immutable bin; `copyCounts()` returns an independent caller-owned copy. Copy allocations are outside the histogram owner's retained charge. Failed capture exposes no bins/range/summary and zero retained bytes; cancellation escapes separately. Source diagnostics remain intact.

## Legends and exact matching

`boundary(edge)` returns an optional native-value label for edges 0 through the bin count. `displayStyle(ramp)` creates an optional color style for the selected range. The new `MapRaster.ScalarStyle.valueAt(position)` interpolates legend values at finite positions in [0,1], preserving endpoints and handling ranges whose subtraction overflows. Signed-zero/constant ranges and subnormal spans stay finite. Legend labels are floating-point approximations: extremely narrow ranges can have repeated representable boundaries. Counts continue to use the colorizer's normalization, and original values remain available through existing source inspection/picking.

Receipts distinguish terrain maps, noise maps and noise volumes and retain exact source UUID/context/grid/full-height metadata without source arrays. Source matching rejects another capture even when all values and context compare equal. Matching a `MapRaster` or `NoiseSurface` additionally requires its exact current display range. Gradient-only recoloring can reuse the same distribution. Surface inclusion/meshing changes can also reuse it because the histogram describes the entire original volume, including absent or nonselected samples, rather than exposed faces, clipped geometry or visible pixels. A changed range requires another histogram.

Complete rasters and scalar surfaces expose explicit synchronous `histogram(limits, cancelled)` conveniences using their current color range. These must run off the context thread. They keep the borrowed raster RGBA or surface geometry/color payload inside the existing working ceiling while counting its original samples. They reject failed inputs; categorical raster styles remain unsupported. The later [worker/view/frame handoff](HISTOGRAM-HANDOFFS.md) can explicitly prepare these histograms off-thread through the existing workers. No automatic editor control or polling is installed.

## Bounds and ownership

Defaults allow 256 bins, 16,777,216 samples, 33,554,432 source visits, 64 KiB retained logical payload and 512 MiB working payload. Bin count is configurable from one to 4,096; other limits can be lowered within those hard maxima. Explicit ranges reserve one visit per source index, including absent indices. Automatic ranges reserve one complete census plus a second complete pass if any values are present. A second pass that cannot fit fails without partial counts, rather than truncating the source.

Retained logical payload is 256 fixed bytes, four bytes per bin and two bytes per compact context text character, charged conservatively even when immutable strings are shared. Raw-source preflight includes its complete primitive payload plus histogram output. Raster/surface conveniences additionally deduct their retained image/geometry bytes. Object/collection headers and unrelated jobs remain outside these estimates and require combined host admission.

`captureOwned(source, requestedRange, limits, memory, cancelled)` optionally reserves the complete raw-source/output peak through `RenderMemory`, then shrinks to detached histogram bytes after capture. Source ownership remains borrowed and must stay alive through the call. Already tracked input can be charged conservatively twice; it provides no implicit replacement credit. Independently retained `RenderInput` owners keep histogram charge until final release. No map, volume, raster, mesh, native object, reader or engine graph is retained by the completed histogram.

## Prepared verification

The new CPU group prepares explicit/automatic bin boundaries and outliers; missing nonfinite storage; all-absent and constant sources; signed zeros, subnormal and extreme finite ranges; original terrain/full-volume coverage; source/range identity and recoloring reuse; immutable bins; exact/deficient sample/visit/payload/working limits; borrowed raster/geometry admission; independent ownership, shared rejection and cancellation cleanup. It also prepares failure without partial output and rejection of categorical data.

All 133 CPU/OpenGL production sources plus four harnesses compile with Java 25/LWJGL (137 files); the dependency-free 100-file set also compiles. All 70 prepared groups remain unexecuted. Actual thread/view adoption for histogram controls, numerical/runtime cases, Windows native/GPU comparison, performance/heap measurements and editor legend/histogram presentation remain pending. Histogram data grants no native-generation or preview readiness.
