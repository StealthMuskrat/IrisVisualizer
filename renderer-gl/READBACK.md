# Bounded completed-image pixel readback

`GlReadback` now transfers actual stored GPU pixels from complete `GlFrame` or `GlMapFrame` images into immutable top-left RGBA data. `GlReadbackQueue` adds latest-revision adoption. This implements the readback path needed for renderer-stage pixel comparison; no GL context, readback, comparison or renderer test has been executed. Unsupported previews remain disabled.

## Inputs and exact output

`GlReadback.begin(frame, limits)` borrows a live completed world or map image and retains its own independent source owner. Only those immutable completed-image types are accepted; a partially rendered/uploaded target cannot be supplied. The source must remain on its original device/context for GPU operations, but the caller may close its original wrapper after capture begins. The retained owner and allocation dependencies keep the source image/data alive. No source texture, draw target, attachment, native state or generated sample is modified.

`step(GlUploadBudget, cancelled)` queues whole-row reads into an owned pixel-pack buffer, inserts and flushes a completion fence, polls it with a zero timeout, and copies bounded groups of completed rows through the device's existing staging arena. A timeout yields after one poll, without a wait loop. The cancellation supplier must be side-effect free. Driver calls and state capture/cleanup cannot be preempted; this is a bounded cooperative pump, not a hard frame-time guarantee.

GPU row reads and CPU row copies share the caller's transfer-byte allowance. Each group is at most 64 KiB, with action/work limits and the existing elapsed-time target. Storage allocation is reported separately. The pump reads the entire admitted image at its original size; it never downscales, crops or omits rows to meet a limit.

After complete GPU/CPU row coverage, successful pack-state restoration, immutable image construction, temporary-resource retirement and a final cancellation check, the job becomes READY. `take()` transfers `Pixels` exactly once. It contains an immutable `RgbaImage` and exactly one original world/map image receipt, including original image UUID, revision, dimensions and source/view information. No native session, framebuffer, source image owner, callback or device is retained by that packet. Its CPU memory becomes the caller's responsibility after transfer. [Owned handoff](READBACK-OWNERS.md) is now available through `takeInput()` and revision-checked queue `takeInput(revision)`, retaining managed CPU admission until the final input owner closes without copying pixels.

Pixels preserve stored RGBA bytes without gamma, tint, alpha, channel or premultiplication conversion. OpenGL framebuffer rows are read bottom-up; CPU row groups reverse into top-left order. A map output therefore has the same top-left orientation as its `hit(x,y)`/display transform, without requiring a second V flip when inspecting the CPU image. World image receipts retain their original camera/atlas clock; map receipts retain their original grid context/kind/display transform. This identifies the actual rendered image, not a new generation result or parity claim.

## Single-copy CPU construction

`RgbaImage.Builder` allocates one bounded private output array and accepts complete tightly packed row groups. Input byte-buffer cursors are preserved; the caller must freeze source bytes throughout each append. Optional bottom-to-top ordering reverses source rows while copying directly into the next output rows. Source buffers/arrays are never retained.

`finish()` requires every row and transfers the backing array once into an immutable image. A sealed builder rejects further appends/finishes; no mutable array escapes and no second full-image copy is needed. Read-only `pixels()` and copying `copyPixels()` retain their existing contracts. The builder is single-owner, not a shared concurrent writer; count/dimension/byte validation precedes pixel writes.

## Host state and ownership

`GlPackState` captures the read framebuffer/buffer, pixel-pack buffer and pack alignment, row length, skipped rows/pixels/images, image height, byte swapping and bit order. Each binding/pixel-transfer step normalizes its own pack operation and restores those exact host values before yielding or throwing. It never changes host buffer storage or framebuffer attachments. Draw framebuffer, shader, texture, unpack state and viewport are untouched by the readback path.

One work journal owns the pixel-pack buffer and transfer fence, depends on the retained source-frame journal and initially charges the private CPU output array. A second CPU-only output journal is admitted before pixel allocation. At completion, the CPU charge moves to that output journal without an aggregate release/re-add. Temporary work/source owners then retire; READY pixels remain charged even after their GPU work has been collected. Taking or dropping the result releases the CPU-only journal. The transfer fence is recorded in its allocation journal and deleted by normal collection/teardown, including failure paths.

Closing/cancelling a job before readiness discards partial CPU output and retires submitted GPU resources. Dependencies retain source pixels and native provenance through outstanding commands, failed fences and quarantine. Cleanup errors remain explicit and retryable. Closing a job/queue never closes the caller's original source wrapper or independently transferred pixel packets. Close GPU readback owners before destroying the original device/context; detached taken pixels need no GL context.

## Latest-revision queue

`GlReadbackQueue(device, limits)` owns at most one job, including its untaken ready output. `submit(revision, frame)` supports world, map and scalar frames, rejects stale/duplicate submissions and retains only accepted source images. `invalidate(revision)` immediately cancels queue-owned older work and drops untaken output. A submit/setup failure publishes a bounded diagnostic and no incomplete pixels. No background GL thread or unbounded completed-image history is introduced.

`advance(budget, latestRevision)` cannot progress an older request; a newer revision invalidates it. `take(latestRevision)` checks the exact token and READY state again, constructs a completion before transfer and hands over the packet once. Source image UUID/receipt remains available for the host's final adoption checks. Queue status is an immutable volatile snapshot without full pixel/source graphs. Mutators and direct job progress belong to the original context thread.

These readback revisions are separate from generation, world/view, map/view and atlas revisions. The host must invalidate whenever its desired image changes and adopt only the matching completion. Revision equality is not preview readiness, asset authenticity or native-world parity.

## Admission

| Resource | Default / hard ceiling |
| --- | --- |
| Pixel payload | 64 MiB, lowerable |
| Readback GPU buffer plus CPU output | 128 MiB, lowerable |
| Image dimensions | Existing completed-target bounds, up to 4,096 per axis |
| Transfer group | Whole rows, at most 64 KiB |
| GPU read + CPU copy per step | Shared existing caller transfer/action/work/time budgets |
| New allocation journals per job | Two: work/source dependency and CPU output |
| Transfer fences | At most one per work allocation, bounded by the existing journal limit |
| Queue-owned results | One active or untaken ready result |

The existing device limits still include source targets/scenes/maps, live/retired/quarantined readback buffers, ready CPU pixels and shared staging. No device ceiling is raised. A transferred CPU image is external memory and requires combined application admission with native generation, caches, workers and other retained results. Object/metadata and driver overhead remain outside primitive totals. Resource pressure fails explicitly and preserves the original image.

OpenGL API review uses Khronos primary references for [pixel-pack buffer offsets and row order](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glReadPixels.xml), [fence status/timeout behavior](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glClientWaitSync.xml) and [buffer read ranges](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glGetBufferSubData.xml). Source/API review and Java compilation do not establish driver execution, timing, pixel parity or successful runtime cleanup.

## Deferred stage validation

All 97 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1. The eight native adapters are unchanged and still await genuine Windows native API compilation and execution. No renderer tests, GPU readback, native queries, GL context or workflow ran for this batch.

At renderer completion, exercise CPU builder count/shape/byte bounds, zero/partial/overflow appends, forward/reversed rows, unchanged input cursors, direct/read-only/sliced inputs, source changes after copying, sealing and immutable output views. Cover every readback phase, zero-timeout polls, lower limits, setup failure, cancellation, stale submit/advance/take, ready-before-invalidate and close/retry behavior.

On Windows GL, compare every captured RGBA byte against independently known completed world/map target pixels, including alpha, channels, odd dimensions, one-row images, partial/final groups, tiny budgets and row orientation. Verify no CPU copy before fence completion and exact read/copy totals. Seed hostile host read-framebuffer/read-buffer/PBO/pack state at each yield and validate restoration and unchanged source pixels. Retain/close original images during readback, render replacements, then verify original UUID/source/view/camera/atlas receipts and pixel identity. Exercise shader/draw failures upstream, GL read/copy/sync errors, failed restoration and failed cleanup; no partial or stale pixels may escape. Confirm READY output stays charged after GPU collection, transfer moves CPU ownership once, dependencies preserve source lifetime, all transfer/retirement fences remain journaled, and final device teardown releases or reports quarantined resources.

## Pixel-store group

The readback guard now shares the [complete core pixel-store capture](HOST-STATE.md) used by upload isolation. Pack alignment, row length, row/pixel skips, image height/image skips, byte swapping and bit order are normalized for tight output and restored. The pack PBO and original read framebuffer/read buffer remain separately preserved. Readback does not bind a program or normalize drawing state; uploaded RGBA8 and completed-frame ownership contracts are unchanged.

## Scalar completed images

Readback now also accepts `GlNoiseFrame` through both direct and queue overloads. `Pixels.noise()` retains its exact detached volume UUID, native resource/grid, display interval/colors, camera and frame statistics; it contains no scalar arrays or GL owner. Exactly one world/map/noise receipt is required. The original world/map constructor and transfer/pack/fence/journal contracts remain unchanged. See [NOISE-VIEW.md](NOISE-VIEW.md) for scalar lifetimes and deferred pixel checks.

Original pack images now enter through `GlReadback.begin(GlImageFrame)` and the queue overload. `Pixels.sourceImage` carries its exact original-source receipt plus viewport transform/dimensions, exclusively with the world/map/noise receipts. Earlier constructors remain available. Readback pixels describe the rendered viewport; source PNG dimensions and original pixels remain distinct. See [IMAGE-VIEW.md](IMAGE-VIEW.md).

## Owned CPU outputs

`GlReadbackQueue.OwnedCompletion` closes its `RenderInput<Pixels>`; `pixels()` borrows the immutable packet and `input().retain()` creates an independent owner. For a managed device, destination image-byte admission precedes consuming the CPU output journal, with conservative overlap. Admission/setup rejection preserves READY pixels/status for another attempt. Taken inputs keep their shared charge through queue/device close and need no GL context for CPU use or release. The raw take APIs retain their external-accounting contract; see [owned readback handoffs](READBACK-OWNERS.md) for failure and deferred-verification details.

## Replacement preflight and retry

[Queue preflight/recovery](READBACK-RECOVERY.md) now checks the replacement target and exact device before invalidating earlier work. Closed/incomplete/foreign/over-limit source rejection preserves the previous token/status/progress and untaken output; explicit upstream invalidation remains available. `retry(expectedRevision)` reopens only the exact FAILED token after cleanup, then requires a caller-retained complete frame for ordinary same-token submission. No runtime/GPU checks have been executed.

## Restoration failure diagnostics

[Transfer failure aggregation](HOST-STATE.md#transfer-failures-remain-primary) now keeps the original readback or cancellation error primary if pack-state restoration also fails, then attempts ordinary disposal. A restoration-only error still fails the step before READY output publication. Pack bindings, row orientation, budgets, fence polling and independent taken pixels keep their existing contracts. Full and dependency-free compilation passed; all 64 prepared groups and Windows GL/failure-injection checks remain unexecuted.

## Output transfer failures

[Raw and owned handoffs](READBACK-OWNERS.md#rejected-handoff-and-cleanup) now distinguish setup from journal consumption. Raw queued completion metadata is allocated before transfer, so setup failure preserves READY output. A direct retirement failure instead marks the job FAILED and attempts disposal: the journal may already be released/quarantined and cannot support another READY take. Owned direct/queued input cleanup preserves the original transfer error, and queued cleanup/publication continues if CPU-input release also fails. Successful pixels/receipts, source-frame ownership, conservative admission overlap and device quarantine accounting are unchanged. All production/harness sources compile; no context, fault-injection, readback, native/GPU test or workflow was executed.
