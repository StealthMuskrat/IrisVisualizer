# Admitted immutable histogram reuse

Renderer implementation, 2026-11-06. Compilation and source inspection only. Renderer contracts, Windows native/GPU validation and final editor integration remain deferred. Unsupported previews remain disabled.

## Exact metadata before reuse

`MapRasterWorker.Request` and `NoiseSurfaceWorker.Request` accept `.withHistogram(limits, candidate)`. The immutable candidate is advisory: a different source receipt, source kind, display range or bin count removes it from `reusableHistogram()` and selects ordinary full-source capture. Failed candidates cannot match. Exact receipts include UUID, context, horizontal grid and configured vertical range; equal sample values do not substitute for source identity. Existing constructors and `.withHistogram(limits)` select capture without a candidate.

A matching candidate survives gradient changes. Full-volume counts also survive scalar inclusion and meshing policy changes because those policies do not change the original sampled distribution. Camera, clipping and visibility never determine histogram coverage. Range edits and bin changes capture a new full-source distribution on the same existing worker. This does not skip raster colorization or mesh preparation, recolor GPU storage, regenerate native values or provide automatic range selection.

`GlMapView.submit` and `GlNoiseView.submit` offer their current owned companion when a histogram-enabled request has no accepted candidate. Matching is metadata-only on the context thread; no source scan or count copy occurs there. Explicitly supplied compatible candidates take precedence. The old companion remains owned through destination admission. The views release prior display ownership only after acceptance, preserving their existing rejection, upload completion, histogram retention and retry gates. Rapid replacements without a current companion fall back to capture.

## Independent admission for the new request

Reuse reserves exactly the same histogram output bytes as capture, within the existing map/mesh/histogram ceilings. Known sample, visit and payload preflights still apply even though the histogram pass is skipped. No candidate earns admission credit simply because it is the same Java object. Caller-retained external owners remain charged independently. Pending/READY replacement credit follows the existing exclusive bundle rules.

A candidate is borrowed while constructing the request and must remain owned through `submit` admission, just like raw raster/surface inputs. The accepted destination reservation then covers the candidate reference until publication or cancellation. Reuse retains the immutable count array itself and no former owner, admission hold or source graph. Closing the old owner after successful admission is safe; rejection leaves ownership with the caller. Workers publish the reused histogram with the freshly completed display atomically. Their producer peak remains charged through unwind and shrinks to the full retained display-plus-histogram bundle. Independently retained completion companions keep that new bundle charge until final close.

The six-component request constructors normalize incompatible candidates to empty. Supplying a candidate without histogram limits rejects rather than enabling a histogram implicitly. Null candidates/settings reject. Legacy five-component request constructors remain available.

## Prepared evidence

One new world CPU group prepares immutable identity reuse for maps and volumes, unchanged reservation, source/range/bin/kind fallback, visit preflight, externally charged owners, deficient shared admission preserving pending revisions, obsolete/cancelled delivery, old-owner release after admission, split completion lifetime, actual range fallback capture and full-source counts across inclusion/meshing changes. Existing worker/view failure and GPU handoff coverage remains applicable; real view transitions, worker races and performance measurement still require later execution.

All 136 production sources, five harnesses and one fixture compile with Java 25/LWJGL (142 files); the dependency-free set contains 104 files. All 93 prepared groups remain unexecuted: 75 CPU/context-free and 18 GPU. Compilation does not establish runtime correctness, native parity, GPU readiness or stage completion.
