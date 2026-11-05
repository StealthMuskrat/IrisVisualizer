# Background scalar inspection

Renderer implementation, 2026-11-04. All 127 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (86 CPU, 41 GL). This batch used compilation and code inspection only. Renderer tests, worker/query execution, native/GPU comparisons and Windows visual validation remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Latest pointer scheduling

`NoisePickWorker` runs the existing bounded `NoisePick.query` on one daemon CPU thread. It admits one active query and one latest pending request. Accepted pointer replacements cancel active work cooperatively, immediately release superseded pending ownership and drop untaken old completions. Cancellation is checked through the existing range/face loops; it is not a hard time limit. No native sampling, meshing, GL call or shader work runs on this executor.

A request retains the exact immutable surface, ray, face allowance and geometric clip. An optional `Frame` records a displayed image UUID and display revision. Source-volume identity alone cannot distinguish two images of the same data with different cameras, clips or display styles. Request construction and submission perform only bounded metadata/admission work; they never scan faces on the context thread.

Each accepted submission advances the worker revision. `take(expectedRevision)` transfers only the latest complete result once. `take(expectedRevision, expectedFrame)` additionally requires the exact image UUID/revision; a mismatch leaves the completion available for its rightful consumer. A miss is a successful READY result with no hit. Query-limit/source/coordinate problems produce FAILED status with their original diagnostics and no partial nearest hit. Unexpected runtime and memory failures publish bounded WORKER/MEMORY diagnostics; a subsequent explicit submission can reuse the same worker.

`invalidate()` immediately rejects old adoption, cancels work and enters WAITING. Call it when the pointer leaves or the displayed image changes before another query can be submitted. Unchanged status polling does not submit/restart queries. The host controls pointer event frequency and any UI polling policy; no generation polling or automatic preview is enabled.

## Completed-image handoff

On the original GL context, construct a worker with the same optional `RenderMemory` coordinator as the device and call:

```java
long pointerRevision = displayed.submitPick(picker, x + 0.5, y + 0.5,
        new NoisePick.Options(1_048_576));
GlNoiseFrame.Info receipt = displayed.info();
```

The helper captures the frame's actual surface, camera/viewport ray, closed clip planes and image token before admission. Coordinates are continuous top-left viewport coordinates; the half-pixel offsets above apply when `x`/`y` are integer pixel indices. Existing viewport, face-count and closed-frame guards apply. A caller-supplied clip is replaced with the displayed frame's clip. Foreign/missing coordinators fail before submission and preserve earlier worker work/revisions.

Later, without performing a query on the GL thread:

```java
var result = picker.take(pointerRevision, receipt.pickFrame());
if (result.isPresent() && receipt.matches(result.orElseThrow())) {
    NoisePick.Result originalSamples = result.orElseThrow().result();
    // Present a hit or miss only if this receipt is still the displayed image.
}
```

`Info.matches` checks image UUID/revision, volume UUID and clip. It is detached metadata and remains usable after frame closure. The host must still compare against its currently displayed image and latest pointer revision; an old receipt does not become current because its query succeeded. The worker does not own the view or observe camera/selection changes automatically.

After successful admission, the immutable CPU request has independent source ownership/accounting. The frame can close on its original context without waiting for the query, and GPU retirement/collection can proceed normally. The worker never retains or closes the frame, scene, device, texture or target. Raw `frame.surface()` plus manual queries retains its earlier borrowed-lifetime contract; the new helper is the independently admitted route.

## Source admission and release

Every active/pending request conservatively reserves the full `NoiseSurface.payloadBytes()`: all scalar samples/presence arrays, original face/extent arrays and range bounds. Queries reuse that index and allocate no volume-sized scratch or index copy. Active plus prospective pending payload must fit the worker's configurable ceiling, default/hard maximum 1 GiB. The ceiling does not preallocate that memory or raise any source, GPU or application allowance.

With a coordinator, the worker also reserves each request through `RenderMemory.holdReplacing`. Only its idle pending hold can supply replacement credit. A cancelled active query remains charged until its execution frame returns. The same surface in active/pending requests and live GPU journals is conservatively charged more than once; no shared-object deduplication or newly available memory is assumed. Rejection of local/shared byte or account admission preserves earlier work, result, revision and holds.

The consuming `submit(RenderInput<Request>)` overload retains incoming ownership through destination admission, rejects foreign tracked coordinators and closes the supplied input on both success and rejection. Retain another input first if it is needed for retry. Raw immutable requests remain caller-owned outside the worker's accepted lifetime and require host accounting if retained independently.

Completions and statuses contain only detached metadata, scalar hit values and bounded diagnostics. They retain no request, surface, volume arrays or GL allocation. Source holds close after actual query unwind, including failures and discarded obsolete results. Pending invalidation releases immediately. `close()` is idempotent and nonblocking: pending work/completions drop immediately, while active cancellation keeps its admission until unwind. It never closes the shared coordinator or caller inputs other than the explicitly consuming overload. JVM/executor/receipt overhead and unrelated native/application owners remain outside primitive byte estimates.

## Deferred stage verification

At renderer completion, compare synchronous and worker results for all six directions, unit/greedy surfaces, empty/missing/thresholded samples, fractional spacing, distant anchors, full Y ranges, merged-edge ties, clipped nearest faces, misses and exact face allowances. Confirm camera near/far and original values/coordinates/color/work counters survive the frame helper unchanged.

Exercise rapid pointer/source/style/camera/clip/viewport changes; same volume and revision with different image UUIDs; stale/mismatched/duplicate takes; WAITING/QUEUED/PICKING/READY invalidation and close; cancelled scans that finish late; explicit same-worker recovery from query, executor and allocation failures; and no unsolicited retry from polling. Check exact and one-over local/shared bounds, account pressure, atomic pending replacement, active cancellation charges, source/destination overlap, foreign/closed coordinators and consuming input rejection.

Verify completed metadata remains useful after source/frame closure, no completed result retains arrays, active sources remain owned until unwind, and GPU cleanup is independent of the CPU query. On Windows, compare displayed image rays/clips and original hits with actual scalar pixels, including float edges and camera-inside cases. Final UI routing, native fidelity, real-driver checks and total application memory confirmation remain pending.

## Exact normal-plane hit coordinates

`NoisePick` now takes the intersected face's normal coordinate directly from its known axis-aligned plane. Reconstructing that coordinate as `origin + distance * direction` can round across a closed clip plane, or lose a decimal plane after cancellation with a distant ray origin. The picker still computes the same ray distance, front-face test and two tangent coordinates. It uses the known plane for clip containment and stores the accepted three-coordinate point when choosing the nearest hit. The detached hit subtracts the original sample's origin from that stored point, so it does not reconstruct the normal coordinate again after selection.

This applies to unit faces and greedy rectangles. Native volume/sample/value/direction identities, lower-original-sample shared-edge ties and rectangle-to-sample lookup retain their existing rules. The change adds three scalar locals and no per-face arrays, admission, tolerance expansion or source scan. Near/far comparisons remain closed at the exact computed distance; strict outside limits still reject. Geometric clips retain their original exact integer bounds, and GPU face positions/scale, source data and preview readiness are unchanged.

The existing scalar-picking contract group now prepares all six face directions in both meshing modes, touching closed clip planes, near/far endpoints and one-step outside bounds. It uses oblique rays across fractional and near-limit relative origins, analytic face distances, exact normal-plane receipts, original samples and unchanged scalar values. Separate 0.1-scale cases cover far-origin cancellation, merged rectangle sample-local coordinates and lower shared-edge sample ties; the complete face allowance still rejects partial results. The suite remains 19 CPU groups and 64 total.

Full and dependency-free compilation passed; these cases are compiled but unexecuted. At renderer completion, run the CPU contracts and compare corresponding displayed-frame picks/focus/readback receipts on real Windows GL under clipping, navigation and greedy/unit modes. Native/GPU parity and runtime validation remain pending.

## Detached grid receipts for precise focus

Produced scalar hits now include `grid(): Optional<NoiseVolume.Grid>`, validated against their original sample index/point. This immutable sampling metadata contains no volume arrays and can survive worker/source/frame disposal alongside the detached hit. [Camera focus](../renderer-gl/CAMERA-FIT.md#fractional-scalar-focus-at-distant-origins) uses it to preserve fractional sample positions before absolute-coordinate rounding at distant origins. Original volume/sample/value/point/direction, nearest distance and local hit coordinates retain their existing meaning.

The six-argument `Hit` constructor remains available for legacy/manual metadata and supplies an empty grid; focus then uses its existing absolute-point route. The new optional field participates in record equality/metadata. Current frame/volume validation remains the host's responsibility. No per-face array, mesh, sample copy or source retention is added. Expanded CPU/navigation contracts compile within the existing 64 groups; execution and native/GPU comparison remain deferred.
