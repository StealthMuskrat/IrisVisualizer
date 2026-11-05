# Retained native-map and original-image recovery

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and code inspection only; renderer-stage execution remains deferred until implementation is complete. No worker, retry, native, GL, readback, failure-injection or workflow tests ran. Unsupported previews remain disabled.

## Current-token GPU retry

`GlMapView.retryUpload(expectedMapRevision, source)` and `GlImageView.retryUpload(expectedImageRevision, source)` restart failed source uploads from complete retained CPU data. They preserve the source revision and current navigation/viewport, advance the view revision and use normal bounded upload/draw/take afterward. Neither invokes native sampling, colorization, image decoding or source-size scans.

Both require the exact positive current source token, FAILED view status and no adopted complete current source. Other tokens/phases return false without changing work or revisions; nonpositive tokens and closed/wrong-context calls retain explicit errors. Display failures with an adopted complete source continue to use `redraw()`. Callers supply already prepared data; there is no automatic retry loop or timer.

| View | Required retained source |
| --- | --- |
| Native map | Complete `MapRaster` with the latest sample-capture UUID and entire latest scalar/biome/terrain style |
| Original image | Complete `SourceImage` matching the latest successfully adopted CPU decode receipt |

Map retry also enforces the latest accepted request's image-byte and retained source/output working limits. Old colors from the same native capture cannot satisfy a newer style request. A complete matching raster may recover a CPU failure as well as an upload failure. Recolor scratch/old-image overlap is unnecessary during this direct upload; final retained output must still fit the latest allowance.

The image view records the bounded decoded receipt before submitting that CPU result to the GPU queue. It keeps no extra encoded bytes or RGBA array. Equality includes original resource/revision, SHA-256 of encoded bytes, encoded length, stored width/height and PNG/JPEG format. A matching path/revision alone is insufficient. A separately decoded identical receipt may be reused; this is content verification rather than a claim that two decode objects are the same capture. Its pixels already passed the current decode request's limits. A failed decode has no successful receipt and cannot use upload retry; submit replacement encoded input instead. New submission, invalidation and close clear the previous receipt.

## Cleanup and adoption

Source completeness/identity/retained-limit checks, shared upload capability validation and new status allocation happen before revision mutation or cleanup. Map and image retries now reject hardware texture-size and whole-row staging incompatibility before invalidating CPU work, advancing the view token, restarting the GPU queue or releasing retained display owners. Rejection preserves the exact FAILED status, source/display tokens, worker/queue state, navigation and unfinished cleanup resources; it creates no upload journal. Normal upload begin repeats the same borrowed checks before admission. Accepted recovery invalidates worker-owned pending/ready data, cancels active work and clears the CPU adoption token. Cancelled active stacks keep their existing charge until unwinding. The new view revision prevents an older untaken frame from transferring.

Retry releases view-owned frames/sources and brings its queue to the current source token if an earlier failure interrupted that handoff. A package-scoped queue restart disposes partial/untaken work even when the failure occurred outside that queue; the public queue `retry` remains restricted to FAILED. Successful cleanup publishes WAITING before new complete input admission. Queue mismatch, cleanup or admission failure leaves the connected view FAILED, with unfinished owners reachable by retry/close and retirement/quarantine charges preserved.

Only complete upload adoption sets `sourceReady`. Cleanup-retained old data cannot become current through pan/zoom or redraw. Matching navigation remains configured through retry, including a null transform when initial image dimensions were not yet adopted. Previously transferred frames preserve original pixels, source inspection and readback receipts. No requested resolution, native value, alpha, color or source format is changed to fit memory.

## Independent CPU raster owners

| Operation | Result |
| --- | --- |
| `GlMap.retainInput()` / `GlImage.retainInput()` | Independently admitted CPU source on the original context |
| `GlMapFrame.retainRasterInput()` / `GlImageFrame.retainRasterInput()` | Same ownership from the exact completed frame source |
| `ImageDecodeWorker.Completion.retainInput()` | Independent handle over the completion's existing CPU admission |
| `view.retryUpload(revision, input)` | Consumes input through destination admission or rejection |
| Existing map `recolor(input, style, limits)` | Consumes retained map source through CPU recolor admission |

GPU-source handoff first admits the full map sample-plus-RGBA or decoded image RGBA payload in a separate shared account, conservatively overlapping the source's device charge. It copies no pixels and retains no GL wrapper, framebuffer, callback or device. Hold/input allocation failure releases temporary admission and leaves the GL source/frame live. With no device coordinator the compatibility input remains untracked.

The CPU input may outlive frame/source/device shutdown and be read, retained or closed on CPU threads. Independent `input.retain()` wrappers share one account; its last close releases the charge. Completion retention shares its existing account instead of creating a duplicate one. Raw escaped values do not independently extend admission.

Consuming view overloads require matching coordinators for tracked inputs and retain source ownership until the destination worker/device is admitted or rejects the attempt. They close the supplied wrapper on every outcome, including stale tokens, identity mismatch and context/coordinator errors. Retain a separate retry owner before consuming an attempt. Raw overloads remain available with externally scoped ownership through admission. All existing sample/image, worker, device and shared ceilings remain unchanged.

## Map preparation capability preflight

`GlMapView.submit` also checks original sample-grid width/height against texture and row-staging bounds before worker admission or any view mutation. Colorization and recolor preserve those exact dimensions, so they share `GlMapUpload.dimensions` with direct upload and retry. An unsupported map therefore leaves the previous worker request, complete output and source/view tokens intact instead of spending CPU work and failing only at upload. Standalone CPU raster preparation remains independent of GL capabilities. Original encoded-image dimensions remain checked after decoding, when the exact image is available. No source is resized or reduced.

## Deferred verification

At renderer completion, cover current/stale/future/nonpositive tokens, every view/queue phase, wrong capture/style, equal value ramps, lowered map limits and original-image receipt mismatches in each field. Include distinct encoded bytes at the same path/revision, independently decoded identical content, PNG/JPEG, failed decode, source invalidation and retry without a configured viewport. At texture and row-staging limits, compare map submission, direct map/image upload and retry; reject one dimension or row beyond the allowance before worker admission, token mutation, queue restart or owner release. Confirm exact FAILED status/worker/queue identity and retained cleanup owners survive retry preflight rejection; eligible retries with valid bounds must still reach admission and normal cleanup. Exercise raw and consuming-input routes, with supplied wrappers closed and independently retained owners live on every rejection.

Inject failures before CPU adoption, during GPU admission/storage/rows/finishing, queue/view status publication and source/untaken-frame cleanup. Verify exact source-token preservation, display-token advancement, cancellation without obsolete adoption, public retry restrictions, partial queue restart and old transferred frames. Exercise owned-input accounting through admission, frame/device/coordinator close, foreign coordinators, retained handles and all failure/consumption paths; confirm no source copies, leaked accounts or dropped retired/quarantined charges.

Compare original/retried native values, RGBA, map relief metadata, image format/dimensions, inspection and readback pixels on real Windows GL. Validate navigation preservation and final zero charges after actual retirement. Native compilation and final editor integration remain pending.
