# Admitted scalar draw preparation and differential culling

Renderer implementation, 2026-11-06. Compilation/source inspection only; no renderer contract, native sampling, GL context or GPU operation has run. Renderer-stage execution and final integration remain deferred. Unsupported previews stay disabled.

## Context-free original-source preparation

`GlNoiseCulling` now prepares the scalar camera matrix, exact-origin relative offset, shader display scale, clip bounds and original-group visibility without GL calls. Its grid-only float scale/span check is shared with `GlNoiseUpload.precision`, including view preflight. Existing limits and camera-dependent spacing rejection remain unchanged: unsupported offsets, underflowed/oversized float scales or insufficient spacing fail explicitly rather than moving the anchor, reducing samples or changing the full configured height.

The helper tests the existing actual scalar bounds with the same submitted matrix, float scaling/addition and conservative frustum/clip allowance. One byte per original group records its result. It neither scans original sample arrays nor regenerates/evaluates native noise. Rendering still coalesces consecutive surviving groups, resetting both geometry/color instance offsets for each run. Original face order, greedy spans, shading, source UUID, value inspection and clipping semantics remain intact.

## Temporary CPU admission and publication

`GlNoiseRenderer.drawScratchBytes(scene, rangeCulling)` reports a metadata-only allowance: 1,024 bytes for bounded camera/frustum/clear-vector math, plus one byte per actual scalar group when group rejection is enabled. A zero-face source reserves only the fixed math allowance and invents no group or GPU instance. The hard scalar limit of 1,048,576 faces means at most 1,024 group bytes.

The renderer reserves that CPU-only device journal before constructing draw arrays, modifying host drawing state or writing target pixels. Normal device working/shared limits and allocation-count limits apply; no new GPU names/storage or larger memory ceilings are introduced. JVM object/collection headers, host-state bookkeeping, native/driver scratch and unrelated application work remain outside the logical payload estimate. Lower custom budgets can reject this newly accounted temporary work.

Array owners unwind from the drawing helper before its covering journal releases, on success or failure. Scratch/admission or camera/precision failure before drawing preserves earlier target completeness/pixels and host state. A newly drawn target becomes complete only after host restoration and scratch release succeed. Primary errors remain primary through failed cleanup; device quarantine/teardown retains its existing responsibility for incomplete cleanup. This is bounded known-payload accounting, not total application memory/readiness confirmation.

## Separate immutable comparison images

Existing renderer, frame and connected-view calls default to group culling. `GlNoiseRenderer.render(..., clip, false)` disables CPU group rejection while retaining the clip shader, original complete surface, instance layout and camera. Its statistics count every original group as submitted/visible and zero as CPU-culled. This mode can expose a differential pixel oracle without altering native data or the display style.

`GlNoiseFrame.render(..., clip, rangeCulling, obsolete)` creates a new independently owned target for each mode. It does not redraw a previously transferred image. Native volume/context/grid, original style/camera/clip and readback receipts retain their existing meaning; submission statistics reflect the chosen optimization. Histogram/frame matching continues to describe the full original source and range, independent of CPU culling.

## Prepared checks and current evidence

One new context-free group prepares a complete 32×32×1 slab: 2,176 outer faces in three original groups, exact 1,027/1,024-byte enabled/disabled allowance, analytic outside-clip rejection, nonmonotonic group lookup, complete submitted/culled coverage, disabled/disjoint/empty cases, index/aspect guards and unsupported offset/float scale/spacing failures. It never requires a GL context.

One new GPU group prepares exact enabled/disabled pixel equality for separately rendered clipped scalar frames, reduced submission, original source/style/clip receipts, released temporary CPU charge, deliberately deficient shared admission, and precision failure after successful admission. Rejected work must preserve device counters, host state, previous target completeness and pixels. All of these assertions remain unexecuted.

All 136 CPU/OpenGL production sources, five harnesses and one fixture compile with Java 25/LWJGL (142 files). The dependency-free set contains 104 files, explicitly adding the sixth context-free helper. There are 92 prepared, unexecuted groups: 74 CPU/context-free and 18 GPU. The PowerShell runner remains compile-only by default and source-inspected only here.

Actual shader/culling/pixel equivalence, low custom limits, failure/restoration/retirement fault injection, worker/transfer races, native comparisons, performance/total-memory measurement and final editor adoption still require their planned renderer/integration validation. No readiness flag or unsupported preview is enabled by this preparation.

## Hierarchical and independent linear strategies

[Scalar parent classification](HIERARCHICAL-SCALAR-CULLING.md) now reuses source hierarchy arrays to reject or strictly accept entire original group intervals. Typed `GlCullingMode` overloads select HIERARCHY, LINEAR or NONE, with earlier true/default/false calls preserved. Both enabled strategies keep the same scratch footprint; actual bound/leaf tests are separate from logical visible/culled totals. Separate immutable frames prepare three-mode pixels, coverage and admission/state comparison. Full/default compilation passes (144/106 files); all 119 prepared groups remain unexecuted.
