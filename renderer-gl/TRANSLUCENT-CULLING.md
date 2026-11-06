# Shared world face-group culling and draw scratch

Renderer implementation, 2026-11-06. Compilation and source inspection only. Renderer contracts, actual GPU pixels, native comparisons and final editor integration remain deferred; unsupported previews stay disabled.

## Shared conservative decisions

The existing [128-face bounds](WORLD-RANGES.md) now serve both solid material ranges and the camera-sorted translucent pass. `GlWorldCulling` prepares exact-anchor section offsets and whole-section visibility, then lazily records one byte per actual original face group: unknown, conservatively visible or rejected. Each group is tested at most once during that draw, even when it spans several material ranges or is visited repeatedly in nonmonotonic transparent order. A flat section-prefix index uses the complete scene's verified group count; no face/vertex scan or new geometry hierarchy is built.

Bounds retain the existing frustum expansion and float-translated clip allowance. Mixed materials, alpha classes, native model offsets and overhanging geometry can widen a group and admit extra work. A group is rejected only when its complete original bounds miss the frustum or current clip. Diagnostic markers still use the existing section rejection path. Original native data, index/UV order, tint/lighting, atlas playback and geometric clipping stay unchanged.

The translucent pass filters the already prepared global order. It never resorts, changes tie ordering or rewrites indices. Surviving adjacent original indices still coalesce only within the same section/page and uninterrupted sorted sequence. A rejected face ends such a run; the next surviving face starts another. Fragment clipping remains authoritative at boundary pixels. Reduced submission is not a measured frame-time improvement and may increase draw-call splits.

## Bounded temporary admission

Before constructing world draw arrays or touching host drawing state/target pixels, `GlRenderer` reserves a CPU-only device journal covering:

| Payload | Logical bytes |
| --- | --- |
| Bounded camera/frustum math and clear-vector allowance | 1,024 |
| Three float offsets plus a section-visible flag | 13 per section |
| Face-group prefix index, when range culling is enabled | 4 × (sections + 1) |
| Shared group decisions, when enabled | 1 per prepared face group |

`drawScratchBytes(scene, rangeCulling)` reports that size from existing metadata, on the original device/context. Disabled range mode retains section culling and omits group arrays. The scene's original face/section/height limits remain in force. Object/collection headers, host-state bookkeeping, native/driver allocations and unrelated application work remain outside this logical scratch estimate; this is not total application memory confirmation.

The normal device working/shared ceilings and allocation-count ceiling admit this journal; no GPU object names or storage are added. Lower custom limits may now reject scratch that was previously outside the device's known-payload count. Rejection preserves prior target completeness/pixels and host state. The drawing helper's stack unwinds before releasing its covering journal, on success or failure. Primary errors survive cleanup errors. A target becomes complete only after drawing, host-state restoration and scratch release all succeed; failed drawing/restoration cannot publish a new complete frame.

## Diagnostics and differential mode

`FrameStats` adds `faceBatchTests`, `culledFaceBatches` and `culledTranslucentFaces`, while preserving the earlier ten- and thirteen-field constructor signatures. The first two count unique actual group decisions across both passes. `culledTranslucentFaces` counts sorted faces rejected by groups **inside otherwise visible sections**; whole-section rejection remains represented by section statistics. Existing solid-range counters retain their original material-range intersection meaning and can exceed unique group-test counts.

`GlRenderer.renderFrame(..., clip, false)` now explicitly disables group rejection in both material passes, retaining ordinary section rejection, clip shaders and sorted order. The corresponding new `GlFrame.render(..., clip, rangeCulling, obsolete)` overload creates a separate immutable target for each differential result. Existing frame/view calls default to enabled group culling. Source/camera/clip/lighting/atlas receipts retain their existing meaning; only submission diagnostics differ.

## Prepared verification

A new context-free group uses complete detached 32×32 fixtures (6,144 faces, 48 groups) to prepare analytic outside-clip rejection, a known visible top face, exact 1,093/1,037-byte enabled/disabled scratch sizes, lazy one-test-per-group counters, reverse/sorted-style access, invalid-index/aspect guards, disabled group behavior and whole-section/empty rejection. The existing test-only fixture is explicitly included in the CPU runner along with the fifth context-free helper; neither depends on LWJGL or opens a context/archive.

A new GPU group prepares all-translucent and mixed opaque/translucent pixel equality against separately rendered culling-disabled images. It checks reduced face/index submission, exact original translucent coverage, shared group counts across passes, completed-image independence, released CPU scratch and rejection under a deliberately deficient shared-memory allowance without changing the prior target, host state or device counters. No assertion has run.

All 135 CPU/OpenGL production sources, five harnesses and one fixture compile with Java 25/LWJGL (141 files); the dependency-free set also compiles (103 files). All 90 prepared groups remain unexecuted: 73 CPU/context-free and 17 GPU. Actual pixel/culling equivalence, custom budgets, cleanup faults, native overhang/material coverage, real scheduling/driver behavior, measured performance and final editor integration remain pending.
