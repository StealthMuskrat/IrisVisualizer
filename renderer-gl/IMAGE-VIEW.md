# Original image GPU view

Renderer implementation, 2026-11-04. All 126 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1: 85 CPU and 41 OpenGL. Sixteen native adapters pass syntax parsing only; genuine pinned Windows dependency compilation remains pending. No image decode, renderer runtime test, GL context, shader execution, pixel comparison or workflow ran. Unsupported previews remain disabled.

## Complete source texture and canvas

`GlImageUpload.begin(device, sourceImage)` admits a successful [decoded original image](../renderer/IMAGES.md). Its journal reserves the retained CPU source, full-size RGBA8 texture payload and texture object slot before native storage starts. Hardware texture limits and staging-row limits are checked explicitly; unsupported sizes fail without resizing or dropping rows. Existing device GPU/CPU/journal/staging admission remains authoritative.

Upload resumes under `GlUploadBudget`, transferring complete rows in actions of at most 64 KiB. Source row zero uses V=0. Nearest, clamped, base-level sampling preserves the chosen RGBA8 policy; no mipmaps, profile conversion or animation is introduced. Host unpack/texture/sampler bindings are restored at every yield, including failure. `take` exposes only a complete restored texture. Cancellation or closure before storage refunds its unused reservation without a fence. Started allocation/upload failure retires its complete journal; failed cleanup retains charges under existing device quarantine rules.

`GlImage` is an independently retainable immutable texture/source owner. Caller and frame owners can coexist without copying source pixels or GPU storage. The device journal retains CPU pixels and any outstanding resource dependency until actual retirement. Retain before closing another owner; a borrowed texture ID or source object does not extend ownership.

`GlMapRenderer.render(GlImage, target, view)` reuses the existing top-left 2D canvas draw, with exact source dimensions. Its original `GlMap` overload remains available. Both use the same private implementation, preserving existing map draw behavior. The canvas clears outside-image pixels transparently, draws source RGBA without lighting/tint or alpha blending, and uses display-only fit/pan/zoom. A target is marked complete only after successful draw and host-state restoration. Source textures and previously transferred targets remain unchanged.

## Latest source and view revisions

`GlImageView` borrows the existing canvas renderer/device and owns one `ImageDecodeWorker` plus a latest-revision upload queue. Submit captured bytes through an image-decode request or `SourceImage.Encoded`; a tracked `RenderInput<Request>` is consumed with coordinator checks. Worker admission happens before changing the current source revision or releasing old display ownership. Invalidating a changed resource immediately drops untaken stale frames and cancels obsolete preparation/upload.

Pump `advance(budget, imageRevision)` on the original context thread. Decode runs off-thread; CPU adoption retains its completion hold through destination GPU-journal admission. A complete upload enters `WAITING_VIEW`. Call `fit(viewportWidth, viewportHeight)` or supply a matching `MapView` after dimensions are known, then pump again. Panning, zooming, fitting and explicit redraw advance only the view revision and reuse the complete source texture. They never decode/resample the source or regenerate data. Each frame draw is synchronous and has the existing bounded target size; texture upload remains incremental.

Take a frame only with the exact image/view revisions. `GlImageFrame.Info` contains a unique frame ID, view revision, original encoded-source receipt, display transform and target dimensions. Its independent owner pins the source and a private offscreen target. Replacement or closing the view leaves caller-transferred frames unchanged. Pixel inspection returns original source coordinates/RGBA, including transparent pixels. The source texture uses V=0 at its top row; an offscreen frame uses ordinary OpenGL bottom-left texture orientation and needs the same vertical flip as other completed frames in a top-left widget.

## Readback and closure

`GlReadback.begin(GlImageFrame)` and the corresponding queue overload now carry a distinct optional `Pixels.sourceImage` receipt. Exactly one of world/map/noise/source-image must be present; output dimensions must match that frame's viewport. Earlier `Pixels` constructors remain available. Readback returns rendered viewport pixels and preserves the original source receipt/transform; it does not label viewport dimensions as original source dimensions. Source receipts now distinguish PNG from supported JPEG; their decoded-pixel and [format-specific policies](../renderer/JPEG-IMAGES.md) follow the same upload/frame/readback route. Original source pixels remain separately accessible through the live frame.

The existing PBO/fence/row-copy path, pixel/working ceilings, shared journal charging and top-left CPU output apply unchanged. Image frames remain pinned until the readback's actual work retires. Readback/source/frame owners close independently; release failures retain ownership for retry and do not hide under an early byte release. Periodic device collection is still required. The view's close is nonblocking for decoder work and leaves borrowed renderer/device and transferred frames caller-owned.

## Deferred stage verification

After renderer implementation is complete, test source/viewport dimensions independently, non-square sources, native-sized textures above 4,096 where supported, small upload budgets and exact row/progress totals. Compare direct full source pixels with texture samples, offscreen canvas/readback orientation, transparent RGB, letterboxing, source edges, pan/zoom anchors and original-pixel hits. Verify the shared map renderer still draws terrain/biome/noise maps as before.

Exercise current/stale image and view revisions, decode/upload/draw failures, hardware/staging/target/device limits, foreign coordinators/devices, old-frame retention, duplicate owners, cancellation, queue invalidation, resource replacement, close/retry/fence/quarantine behavior and host state across every yield. Confirm the four readback receipt kinds are exclusive and old constructors retain their semantics. Compare small-budget and full-budget GPU output on the pinned Windows driver before enabling editor presentation. Final integration remains pending.

## Current source and failure recovery

Original-image redraw, drawing and frame transfer now require complete current upload adoption. A previous image retained after cleanup failure cannot serve a replacement revision; source diagnostics survive permitted display changes. Original dimensions/pixels, input admission and caller-held image owners retain their existing contracts. See [current-source adoption guards](SOURCE-FAILURES.md) for lifecycle and deferred verification. All 129 CPU/OpenGL sources compile; runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

The lower-level `GlImageQueue` now supports [explicit current-failure retry](UPLOAD-RETRY.md) using a retained decoded source image at the same queue token after cleanup. Connected-view resubmission keeps its existing revision flow. Source values/pixels and caller-held completed images retain their existing ownership.

## Retained upload recovery

[Image upload recovery](RASTER-RECOVERY.md) now accepts complete retained decoded pixels at the same source token after a source upload failure. The view freezes the successful CPU decode receipt before GPU handoff and requires the exact resource/revision, encoded digest/length, dimensions and format on retry. A failed decode still requires encoded-input resubmission. Image/complete-frame `retainInput`/`retainRasterInput` handoffs independently admit CPU pixels without copying; navigation and old frames retain their existing contracts. Runtime/GPU checks remain deferred.
