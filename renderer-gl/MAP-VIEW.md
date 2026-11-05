# Connected native map images

`GlMapView` now connects detached native map samples to background colorization, bounded texture upload, an offscreen pan/zoom canvas and independently owned completed images. `MapPick` reads the exact original sample behind that image. These sources compile; renderer/native/GPU execution and stage tests remain deferred. Unsupported previews remain disabled.

## Input and revision flow

Create a `GlMapRenderer` on the existing `GlDevice` and a `GlMapView` borrowing that renderer. Submit a `MapRasterWorker.Request` containing complete detached `MapSamples`, an explicit display style and optional lower limits. `submit` returns the map revision; each source/resource/style submission advances it. `invalidate()` advances it immediately when upstream native inputs change, even before replacement samples arrive. Neither operation samples or generates native data.

CPU worker admission happens before view mutation. A reservation rejection preserves the prior map/view tokens, display owners, pending work and completion. Accepted submissions cancel obsolete colorization and invalidate earlier uploads/untaken frames. Previously transferred images remain caller-owned. A native failed capture produces diagnostics through the existing worker, rather than an inferred empty or substitute map.

`advance(uploadBudget, expectedMapRevision)` only progresses the exact current map revision. It takes the current CPU completion once, streams a complete GPU texture under the existing transfer/action/work/cooperative-time budget, and draws a new canvas when a map and viewport are ready. A stale token cannot progress or adopt a replacement. Drawing one admitted canvas is a synchronous context operation, separate from the bounded upload step.

`fit(width, height)` frames the latest submitted grid. `view(MapView, width, height)` supplies an explicit transform; `pan` and `zoomAt` update it without re-colorization, resampling or upload. New grid dimensions clear an incompatible transform and require fitting/configuration. Matching dimensions preserve the transform across source/style replacement. Framing can be configured while CPU/upload work is pending. A no-op transform/size does not advance the view revision or redraw.

`take(mapRevision, viewRevision)` transfers one complete `GlMapFrame` only for the exact current tokens. Status exposes both revisions; take against the status after advancing. Panning/resizing/source replacement invalidates untaken older frames. After a drawing failure, `redraw()` retries the complete uploaded map. CPU or upload failure requires resubmitting a request. Worker and upload status remain inspectable, with source diagnostics or bounded GPU failure reasons.

## Canvas and texture orientation

`GlMapRenderer` uses a small OpenGL 3.3 program and private VAO. It clips the displayed image rectangle to the target in double precision before supplying bounded clip/UV rectangles to the GPU. The shader uses the uploaded map's nearest/base-level sampling. Source rows remain X-fastest and positive Z downward in the top-left display transform; sampling scale and original values are unchanged.

The canvas clears to transparent black, draws source RGBA directly with blending/depth/culling disabled, and leaves letterbox/outside-image pixels transparent. Alpha is retained, including display colors with intermediate or zero alpha. It performs no lighting, color normalization, biome tint, premultiplication or source filtering. Host state follows the existing `GlState` capture/restore contract. A target is complete only after the draw and restoration succeed.

The source `GlMap` texture stores source row zero at V=0. The rendered `GlMapFrame` is an OpenGL framebuffer image: a top-left image widget must display its output with V flipped, `(0,1)` at the top-left and `(1,0)` at the bottom-right. Pointer positions passed to `hit` are top-left coordinates inside that displayed canvas, independent of this texture convention.

CPU transforms/inspection use doubles; clip/UV shader inputs use floats. Sampling precision at extreme zoom, tiny visible rectangles and texel boundaries has not been validated against GPU pixels. These paths remain behind the existing disabled-preview gates until native/real-driver verification.

## Independent images and inspection

Each successful `GlMapFrame.render` owns a fresh private offscreen target, a retained source-map owner and a dependency journal pinning both allocations. Later renders never draw into an older completed target. The frame's immutable receipt retains its exact map context/kind, display transform, target size, UUID and view revision. `retain()` creates another wrapper over the same image/data without copying payload; the final close retires its target/journal and releases its source owner.

`GlMap.retain()` likewise shares one immutable texture/native raster. Closing one wrapper makes that wrapper unusable, while other owners continue to work. The texture/source payload retires only after its last owner closes. Frame journals preserve dependency/fence charges during failed cleanup. Closing the view, map wrapper or borrowed renderer does not invalidate caller-owned completed images; the original device/context must remain alive through their use and retirement.

`frame.hit(x, y)` rejects nonfinite and outside-viewport coordinates and then uses the frame's exact transform/raster. Right/bottom image and viewport edges are half-open. Letterbox and clipped-away regions return empty. Hits contain detached context/kind, original source pixel/index, original fractional world X/Z, explicit presence and the raster's display RGBA. Present samples return their unchanged integer height, finite noise double or original native biome identity. An absent sample returns no invented value. A present sample with transparent display color remains inspectable.

`MapPick.at(raster, view, x, y)` is the dependency-free form, with exact dimension checks. The frame form also applies viewport clipping. A hit retains no full sample arrays, image, native session or callback. Native full height, biome IDs, noise values, source coordinates, seed and pack remain unchanged.

## Admission and lifecycle

| Resource | Bound / ownership |
| --- | --- |
| Map resolution | Existing 512 × 512 default; full supplied grid retained |
| Native primitives / RGBA | Existing 64 MiB sample / 16 MiB image maxima |
| One raster's source plus image | Existing 64 MiB maximum |
| Active plus latest pending CPU work | 128 MiB default/hard, lowerable in view constructor |
| Canvas dimension/pixels | Existing device target limits: up to 4,096 per axis and 16,777,216 pixels |
| Canvas allocation | Existing RGBA8/depth24 target charge, eight bytes/pixel |
| Untaken completed canvas | At most one per view |
| Map/frame wrapper count | At most 65,536 owners per shared object |
| Device active/retired/quarantined payload and journals | Existing 512 MiB GPU / 1 GiB working / 64-journal bounds |

The worker charges native source plus prospective RGBA for active/pending work and actual payload for untaken output. An output still on its active job's stack shares that reservation. Cancelled active jobs remain charged until unwound; `reservedCpuBytes()` exposes that amount even after view shutdown. Transferred CPU rasters are admitted by the device before upload. Retained map/frame wrappers share one source allocation; each distinct completed canvas adds its own target and journal, not another full native sample copy.

The CPU worker, GPU device, native generation and externally held frames require final combined application admission. Their concurrent reservations may overlap conservatively during transfer. Java metadata, driver overhead and unrelated resources remain outside primitive totals. No requested area, configured height, scale, feature or sample is silently reduced to fit memory.

`close()` cancels the one fixed CPU worker without joining it on the context thread, closes queue-owned upload/result work and retires owned current/untaken display resources. Transferred frames remain caller-owned. Cleanup failures keep unfinished owners retryable and retired/quarantined charges visible; retry close before final device teardown. These APIs create no window, native engine, editor widget or preview-readiness capability.

## Deferred stage verification

All 94 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1. Eight native adapter sources are unchanged and still require genuine Windows native compilation and runtime validation. No renderer tests, map rendering, shader execution, native queries, GL context or workflow ran for this batch.

At renderer completion, cover native terrain/biome/noise samples, fractional/negative world grids, nondefault full height, absent versus real zero values, transparent display colors and exact original identities. Verify map hit bounds, viewport clipping, fit/pan/zoom anchors, wrong dimensions and detached results. Exercise default and lowered limits, active-cancelled reservations, admission rejection without mutation, rapid style/resource churn, stale tokens, setup failures, invalidate/take/close races and no retained native graph.

On Windows GL, compare source RGBA, uploaded maps and output canvas pixels at native size, down/up scale, partial/offscreen/fully hidden rectangles, large/tiny zoom, odd viewports and texel boundaries. Verify source/output V conventions and CPU hit/GPU sample agreement, including transparency. Check no-op view reuse, redraw without native/colorization work, one bounded upload step, old transferred-frame pixels/data after source/style/view replacement and view/renderer close, and multiple map/frame owners. Inject shader/upload/draw/restoration/cancellation/fence/cleanup failures; no incomplete target may escape. Validate hostile host state, original-context rejection, journal/source charges, dependency retirement and final device teardown before enabling any supported preview.

## Captured colorization and terrain relief

`GlMapFrame.Info` now records optional `MapRaster.Style` and Summary as a pair. New rendered frames capture the exact successful raster metadata, including [terrain relief](../renderer/TERRAIN-RELIEF.md), native extrema and eligible-stencil counts. The prior eight-argument receipt constructor leaves this metadata unknown. Kind/grid/style consistency is validated, and readback retains the same frozen receipt rather than current pending options. CPU map lighting changes no shader/texture layout or native sampling; original sample inspection and independent old-frame ownership remain intact. Runtime/GPU/worker validation stays deferred.

## Current source and failure recovery

Map navigation now preserves upstream failure problems/codes/reasons. Redraw, draw and frame transfer require a complete current upload, even if a previous map remains held after cleanup failure. Grid framing during pending work, admission rejection and caller-held frames keep their existing contracts. See [current-source adoption guards](SOURCE-FAILURES.md) for lifecycle and deferred verification. All 129 CPU/OpenGL sources compile; runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

The lower-level `GlMapQueue` now supports [explicit current-failure retry](UPLOAD-RETRY.md) using a retained map raster at the same queue token after cleanup. Connected-view resubmission keeps its existing revision flow. Source values/pixels and caller-held completed images retain their existing ownership.

## Explicit recoloring of current samples

[Map recoloring](../renderer/MAP-RECOLOR.md) now provides `recolor(style[, limits])` for a complete current upload and explicit retained-raster/owned-input overloads for rapid style replacement. These reject captures other than the latest submitted `MapSamples.id()`; invalidation clears that identity. Admission precedes view mutation, matching-grid transforms survive, and status reports RECOLORING. Equal value styles reuse CPU output while the ordinary revision/upload path continues. Changed styles charge native samples, old RGBA and new RGBA until worker unwind; existing working/image ceilings remain in force. Runtime/native/GPU checks stay deferred.

## Retained upload recovery

[Map upload recovery](RASTER-RECOVERY.md) now accepts a complete retained raster at the same current map token after a source failure, checking exact sample UUID, entire latest style and lowered output limits. It cancels obsolete CPU adoption, advances the display token and restarts GPU work after cleanup while preserving navigation. Map/complete-frame `retainInput`/`retainRasterInput` handoffs provide independent CPU ownership for retry or recoloring. Display failures with an adopted map still use redraw. Runtime/GPU checks remain deferred.
