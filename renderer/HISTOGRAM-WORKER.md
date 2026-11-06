# Independent background histogram preparation

Renderer implementation, 2026-11-06. Compilation and source inspection only; renderer-stage execution, real thread races, Windows native/GPU validation and editor adoption remain deferred. Unsupported previews stay disabled.

## Full-source inspection and automatic ranges

`ScalarHistogramWorker` prepares detached histograms independently of rasterization, surface meshing or GPU upload. It borrows no engine and invokes no generator. A single daemon executor counts one active request and keeps at most one latest pending request. Construct it only when this independent path is needed; existing opt-in [paired worker/view companions](HISTOGRAM-HANDOFFS.md) keep their original executor and behavior.

The consuming entry points accept fresh `RenderInput` wrappers:

| Entry point | Source | Range |
| --- | --- | --- |
| `submitMap(input, range, limits)` | Original terrain/noise `MapSamples` | Explicit or automatic |
| `submitVolume(input, range, limits)` | Original full-height `NoiseVolume` | Explicit or automatic |
| `submitRaster(input, limits)` | Complete scalar `MapRaster` | Exact current display colors |
| `submitSurface(input, limits)` | Complete `NoiseSurface` | Exact current display colors |
| `submitRaster(input, range, limits)` / `submitSurface(input, range, limits)` | Same retained display data | Explicit or automatic |

`Optional.empty()` requests the complete original source's observed extrema. Automatic capture requires two full source passes when values are present, and one when all samples are absent. A second pass that exceeds the visit allowance fails with the existing structured limit diagnostic and no partial output. Metadata preflight does not scan values to discover whether that second pass is needed. All-absent automatic output leaves its range/style empty. No fallback values or automatic recoloring are installed. Explicit ranges retain below/above/missing coverage as before. Categorical biome palettes are rejected.

```java
var histogramRevision = worker.submitMap(capturedSamples.retain(),
        Optional.empty(), ScalarHistogram.Limits.DEFAULT);
// On a later host poll, after checking current upstream selection/source:
var ready = worker.take(histogramRevision, expectedSourceReceipt);
if (ready.isPresent()) {
    try (var completion = ready.orElseThrow()) {
        var suggested = completion.histogram().displayStyle(chosenRamp);
        // An explicit host choice may feed suggested into existing recoloring.
    }
}
```

The host remains responsible for its polling cadence and current selection/data revision. This worker starts no UI timer. `Status` retains only compact source metadata and structured failures. `Completion` records its exact request revision, source receipt and requested range mode. `take` transfers once, requires the exact current revision and optionally checks source identity without consuming a mismatched READY result.

## Admission and ownership

Default/local maximum working allowance is 512 MiB, with existing histogram sample/visit/bin/output limits unchanged. Known sample, one-pass visit and retained-output violations reject before earlier queued/READY work is changed. Full raw-source plus histogram output must fit both request and worker limits. Raster/surface requests additionally include their entire retained image/geometry payload while counting; clipping, inclusion or exposed faces do not reduce the source census. Active plus pending reservation must fit the worker ceiling. Active cancellation keeps its full charge until execution unwinds.

Tracked input must belong to the same optional `RenderMemory` coordinator. A tracked input's source account remains independently charged alongside the conservative destination peak until its wrapper is released; there is no implicit source-transfer credit. Idle pending/READY destination holds provide atomic replacement credit. Externally taken histogram owners provide no replacement credit. Default untracked compatibility input is still accepted.

Fresh wrappers are consumed on every submission outcome, including invalid options, closed/exhausted workers, coordinator mismatch and admission failure. Reusing the exact active/pending/untaken-completion source wrapper is rejected without closing it. Callers needing another independent request must retain a fresh wrapper before the first transfer. Submission validation and shared admission precede cancellation/revision changes. `validate()` is metadata-only host preflight for closed/exhausted workers; submission repeats it.

Successful counting releases its supplied source owner before READY publication; a failed source release prevents publication. Its active execution peak still covers local source references until unwind, then shrinks to detached histogram bytes. Completed output retains no source arrays, raster, geometry, frame, device or native object. Independently retained completion owners keep that compact admission through invalidation, worker close and coordinator retirement. Close unused owners. Pending/READY cleanup detaches output and counters before fallible release, attempts every owned cleanup and preserves primary/suppressed error identity. Dispatch/drain/shutdown failures retain existing retry and terminal-close guards.

## Completed-frame handoff

`GlMapFrame.submitHistogram(worker, limits)` and `GlNoiseFrame.submitHistogram(worker, limits)` capture fresh admitted CPU owners on the context thread and request the frame's current display range. Their `(worker, range, limits)` overloads also permit automatic range suggestions. They check worker availability and matching frame/device/worker coordinator before retaining input; categorical map frames reject without capture. The CPU source can outlive frame/device closure. Counting never touches GL or keeps a frame reference.

`Info.matches(completion)` checks live completion ownership plus exact original source and display range. `Info.matchesSource(completion)` checks only the original capture identity and complete source metadata, permitting an automatic-range suggestion that differs from current colors. Source-only matching does not attest the displayed range. Neither method authorizes stale upstream adoption or compares the requested bin granularity: check the latest worker/upstream tokens and intended configuration before adoption. Camera, map pan/zoom, clip and scalar inclusion changes can reuse a full-source distribution. A changed source UUID cannot.

## Prepared verification and remaining scope

Two new world contract groups prepare all four input families, automatic/explicit/display ranges, absent coverage, exact receipts, pending/READY replacement, tracked source release, coordinator mismatch, independent detached owners, retirement, invalid/null/categorical/closed/exhausted requests, startup/shutdown faults, active invalidation/replacement, overlap rejection, drain redispatch failure, source/READY cleanup faults and original structured failures. These fixtures compile and have not run.

All 134 CPU/OpenGL production sources plus four harnesses compile with Java 25/LWJGL (138 files); the dependency-free 101-file set also compiles. All 72 prepared groups remain unexecuted. Actual multithread scheduling, completion take/close during unwind, real GL frame handoff/matching, performance/heap measurements, genuine native/GPU comparisons and editor histogram/range controls remain pending. This path grants no native readiness or world-preview permission.
