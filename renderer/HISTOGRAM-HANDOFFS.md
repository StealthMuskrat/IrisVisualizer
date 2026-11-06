# Histogram worker, view and frame handoffs

Renderer implementation, 2026-11-06. Compilation/source inspection only; renderer contracts, thread races, genuine Windows native/GPU comparison and final editor controls remain deferred. Unsupported previews stay disabled.

## Opt-in worker requests

The existing map and scalar-surface workers now accept `.withHistogram(limits)` on their immutable requests. Earlier constructors and completion constructors retain their signatures and select no histogram. No new executor, automatic scan or generation call is introduced.

```java
var mapRequest = new MapRasterWorker.Request(samples, colors, mapLimits)
        .withHistogram(histogramLimits);
var volumeRequest = new NoiseSurfaceWorker.Request(volume, style, meshLimits)
        .withHistogram(histogramLimits);
```

Enabled requests capture the complete original scalar distribution at the request's explicit display color range after successful raster/mesh preparation, on that same CPU worker. They do not choose automatic ranges or limit counts to visible pixels, selected surface samples, clipping or camera bounds. Recolor requests retain exact source values and recompute the opted-in distribution without native resampling. Categorical maps and known sample/visit/payload violations reject during metadata preflight.

READY requires the requested pair to succeed: raster/surface plus matching histogram. Histogram failure publishes its structured diagnostics without a partial completion. Source preparation failure preserves the original diagnostics. Existing revision replacement, cancellation and single-delivery rules still apply. Pending/completed bundles detach before fallible release, cleanup attempts both owners, and failed old-work cleanup releases any uninstalled replacement hold. Closed completions cannot escape after a release error.

## Combined admission

Histogram output size is known from compact context metadata and the configured bin count. Map reservation adds it to the existing render/recolor peak and requires that complete peak to fit both the original map working limit and the histogram working limit. Scalar requests reserve histogram space inside the lesser of their original mesh and histogram working ceilings before planning geometry. They retain the original face/area/height policies and fail rather than truncating input to fit.

During histogram capture, recolor inputs and conservative preparation scratch still covered by the request reservation remain deducted from the histogram allowance. The raster/surface convenience then accounts for its retained image/geometry payload. Active producer admission includes the entire pair through unwind; only afterward does it shrink to retained display plus histogram bytes. Idle pending/READY replacement credit includes the whole bundle. Externally transferred owners provide no replacement credit.

Completions expose borrowed `histogram()` metadata while owned, plus `retainHistogram()` for an independent `RenderInput<ScalarHistogram>`. Existing raster/surface retention remains available. These split owners share the already-admitted bundle hold: even a histogram-only owner conservatively keeps the complete display-plus-histogram charge until final release. It retains no additional source graph itself. Use the standalone `ScalarHistogram.captureOwned` path when independently admitted, minimal histogram retention is required. Invalid owned submission/recolor options still consume their fresh supplied source wrappers, including null histogram settings.

## Connected views

`GlMapView` and `GlNoiseView` accept the opted-in requests. Their owned input overloads also accept histogram limits. CPU completion handoff retains the companion before transferring the display data to GPU upload; it is not discarded with the consumed completion. The view's `retainHistogram(expectedDataRevision)` returns an independent owner only after that current source upload completes. Stale revisions, absent options and incomplete source uploads return empty. Retention also checks the exact requested bin count as well as source/range identity. Callers must keep the returned owner live while reading its value.

View recoloring inherits the latest enabled histogram limits. Explicit submission without the option disables it for that new request. Source invalidation, replacement and shutdown release the view's companion while preserving caller-retained owners. Display-only pan/zoom/camera/clip changes reuse the full-source histogram. Failed-upload companions remain hidden until a current upload completes. Fast upload retry preserves a matching existing companion; if a required companion was never prepared, retry rejects before changing tokens and the host must resubmit the CPU preparation rather than scan samples on the context thread. Failed retry attempts preserve a retained companion for another attempt.

## Completed-frame matching

Real scalar map frames now include optional `Info.scalarSource()` metadata with the exact `MapSamples` UUID, kind, context, grid and configured vertical range. Old map Info constructors leave that identity unknown and cannot attest histogram matches. Categorical frames leave it empty. Scalar-volume frames already include their exact volume UUID/context/grid. Both frame receipt types expose `matches(histogram)`, comparing exact source and display range without reading any sample arrays. Gradient-only changes, map navigation, scalar inclusion/meshing, camera and clip do not change full-source counts.

```java
var optional = mapView.retainHistogram(currentMapRevision);
if (optional.isPresent()) {
    try (var owner = optional.orElseThrow()) {
        if (currentlyDisplayed.info().matches(owner.value())) {
            // Read counts here, or independently retain owner for longer presentation.
        }
    }
}
```

Use the currently displayed image receipt and latest request/data revision. Matching a saved old image alone does not authorize stale adoption. The receipt does not compare bin granularity or attest ownership: the host must also honor its requested histogram configuration and keep its owner live. Rejected or unused owners must be closed. There is no editor histogram/legend UI or automatic polling in this batch.

## Prepared verification and remaining scope

The new world group prepares disabled/enabled behavior, combined peak/retained bytes, idle replacement credit, paired identity/count delivery, obsolete token rejection, independent split owners, raw/owned recoloring, invalid settings consuming fresh wrappers, original source failures, categorical rejection, pending/READY release errors, detached counters, exact error identity and same-worker retry. Tests remain unexecuted. Real GL view retention, legacy/current frame metadata, failed upload/retry/cleanup, active replacement, take/close during unwind, source/range changes and UI adoption remain in the deferred runtime/GPU matrix.

All 134 independent CPU/OpenGL production sources and four harnesses compile with Java 25/LWJGL (138 files); the dependency-free 101-file set also compiles. All 72 prepared contract groups remain unexecuted. No native readiness flag, generation algorithm, asset-selection rule or memory ceiling is raised.

The separate [independent worker](HISTOGRAM-WORKER.md) now supports post-render current-range inspection and automatic-range suggestions from completed frames. It releases source ownership and admits detached histogram bytes independently; opted-in paired requests continue using the original worker and conservative bundle charge.
