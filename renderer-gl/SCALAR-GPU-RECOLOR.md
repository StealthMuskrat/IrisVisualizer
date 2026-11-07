# Scalar geometry copies and color-only CPU transfers

Renderer implementation, 2026-11-06. Compilation/source inspection only; all renderer contracts, actual GPU copies/shaders, connected-worker races, Windows/native comparisons and final editor integration remain deferred. Unsupported previews stay disabled.

## Exact geometry reuse

`NoiseSurface.sharesGeometry(other)` is a constant-time proof that two successful surfaces retain the same immutable volume and face-cell, side, span and range arrays under the same inclusion/meshing policy. `NoiseSurface.recolor` preserves those arrays. An independently prepared surface does not attest shared layout even when its values and geometry compare equal. Failed surfaces never match. This introduces no native sampling, face scan, array copy or geometry hash on the context thread.

The GPU layout now separates geometry and colors:

| Buffer/attribute | Bytes per face | Encoding |
| --- | --- | --- |
| Geometry / integer attribute 0 | 8 | Packed original sample/direction, then packed U/V spans |
| Color / integer attribute 1 | 4 | Original prepared display `RRGGBBAA` |

Each attribute uses divisor one. Drawing resets both offsets for the first face in each visible coalesced batch run, preserving original face order, culling, clipping and picking. The shader reconstructs the same positions/spans and uses the same simple opaque lighting. Total GPU storage remains twelve bytes per face. Nonempty scenes use two buffers and one VAO rather than one buffer and one VAO; the existing object/name ceilings remain unchanged.

Ordinary `GlNoiseUpload.begin` uploads all eight geometry bytes and four color bytes through bounded staging. There is no second full CPU packing array. `GlNoiseUpload.recolor(sourceScene, recoloredSurface)` instead copies geometry from the exact source scene's GPU buffer into new destination storage and uploads only four color bytes per face from the CPU. Source arrays and buffers are never overwritten. Each completed scene owns its own complete buffers; repeated recolors do not form a dependency chain of old scenes.

```java
var colors = NoiseSurface.recolor(originalSurface, chosenColors, meshLimits, cancelled);
// On the original GL context thread, with sourceScene still live:
try (var job = GlNoiseUpload.recolor(sourceScene, colors)) {
    // Later steps use the ordinary host cadence and upload budget.
    var step = job.step(uploadBudget, obsolete);
    // Only READY permits job.take().
}
```

The consuming `recolor(sourceScene, RenderInput<NoiseSurface>)` overload requires the same coordinator for tracked input and consumes every fresh supplied wrapper on success or rejection. Completeness, precision and exact shared-layout checks precede destination admission. Different volume/layout, an independently rebuilt equal mesh or a closed source cannot borrow GPU geometry. The low-level API reports a structured failure rather than selecting another source or doing a context-thread geometry comparison.

## Progress, limits and ownership

`Progress.uploadedBytes/totalBytes` count CPU transfer bytes: twelve per face for full upload, four for GPU recoloring. `copiedBytes/totalCopyBytes` independently count the eight geometry bytes per face copied on the GPU. `completedFaces` advances only when both geometry and colors cover those faces. `Step.transferBytes` and `Step.copiedBytes` are separate; their **sum** stays within the caller's transfer budget. Earlier Progress/Step constructor signatures remain available and default copy counts to zero.

The new GEOMETRY phase uploads or copies complete eight-byte records in chunks no larger than 64 KiB, the existing staging limit or the remaining transfer budget. FACES writes complete four-byte color records under the same combined allowance. Each chunk consumes an action/work unit. Storage setup is a bounded two-buffer operation and reports the full twelve-byte allocation separately. Existing action/work ceilings and cooperative elapsed targets remain in force; driver calls have no hard elapsed-time guarantee. Copy read/write bindings are captured/restored with the other transfer bindings, including on failure.

Admission includes the complete destination CPU surface, new GPU buffers and still-live/retiring source allocations. Old plus new GPU storage must fit the existing device/shared ceilings; nothing receives implicit release credit or a larger budget. Full uploads now have separate geometry/color phases and one additional buffer name, so very low action/name limits can require more steps or reject the new layout. This changes no source/face/height limits and never truncates a volume to fit.

An upload independently retains its source GPU scene until all copy commands have been queued and transfer-state restoration has succeeded. It releases that owner before READY publication. If this was the final source owner, normal retirement places a fence after the copy commands; source buffers and CPU payload remain charged until the fence is observed, or through device quarantine/teardown after failure. GL command ordering makes the copied destination available to subsequent draws. There is no blocking copy fence wait on ordinary upload steps. Caller-retained source scenes/frames preserve their original immutable colors and pixels.

Cancellation, storage/copy/color/layout/restoration errors and failed source release prevent READY. Cleanup attempts both destination retirement and source-owner release, preserves the original error and keeps any incomplete cleanup resource owned for retry. Zero-face uploads allocate no buffer/VAO, transfer/copy no bytes and still produce a successful complete scene.

## Connected view behavior

`GlNoiseView` retains a matching current scene across accepted CPU recoloring, then uses that GPU geometry when adopting the exact prepared result. Rapid color-only replacements can retain the same original geometry while the latest CPU request changes. Admission/validation rejection preserves the prior view tokens and work. An accepted unrelated source/invalidation releases the retained recolor source through the normal complete cleanup path. The upload owns its own source wrapper before the view releases its temporary one. A failed CPU request may keep the admitted reuse source until replacement, invalidation or close; that owner never makes obsolete data source-ready.

Requests whose geometry has no matching live GPU owner use full upload. Existing explicit failed-upload retry still accepts exact complete CPU data through its ordinary full-upload recovery path; it does not invent a missing GPU source. Source-ready guards, exact revision adoption, histogram pairing/matching, camera/clip settings and independently retained completed frames remain intact. No new executor, automatic polling or editor control is installed.

## Prepared verification and remaining scope

The existing CPU recoloring group now prepares shared/symmetric layout identity, unchanged colors, independently rebuilt equal layouts, null peers and failed-layout rejection. A new GPU group prepares both cell and greedy surfaces, exact four-byte CPU/eight-byte GPU totals, per-step combined limits, copy-binding restoration, closing the original wrapper during copying, pixel equality against complete upload, immutable old frames, skipped leading batches with both attribute offsets, stale preflight without admission, cancellation, tracked coordinator rejection/consumption, owned submission and empty surfaces. It also prepares connected rapid latest-only recoloring with histogram/frame ownership through view shutdown. No assertion has run.

All 134 CPU/OpenGL production sources and five harness sources compile with Java 25/LWJGL (139 files); the dependency-free 101-file set also compiles. There are 83 prepared, unexecuted groups: 72 CPU/context-free and 11 GPU. Actual shader/pixel/copy behavior, active cancellation/unwind races, low memory/name/action limits, source-release/restoration/fence faults and quarantine cleanup, device shutdown during transfers, native comparisons and measured performance remain pending. Reduced CPU transfer volume is an implementation property, not a measured frame-time claim or native readiness result.

[Complete transfer storage admission](FULL-STORAGE-ADMISSION.md) now reserves the full scalar CPU surface, twelve GPU bytes per rectangle, both buffers and the VAO before native storage. Full upload and recolor share the same destination footprint; unused transfers refund immediately and release only their independently retained source wrapper. Empty scenes retain a CPU-only journal with no object slots. All 148 prepared groups remain unexecuted.
