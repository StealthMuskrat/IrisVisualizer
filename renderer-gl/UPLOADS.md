# Incremental GPU upload and revision adoption

Renderer implementation, 2026-11-03. The 57 CPU and 21 OpenGL Java sources compile with Java 25 against pinned LWJGL 3.4.1. No context, upload, draw, state-restoration, fence, timing or concurrency runtime test was executed for this batch. Renderer tests remain deferred until stage implementation is complete. Unsupported previews remain disabled.

## Resumable ownership

`GlUpload.begin(renderer, cpuResult)` consumes a fresh `WorldScene.Result` lease and creates a CPU-only device journal immediately. The full known CPU payload stays charged while the upload validates or waits between frames. The renderer's shared resources are dependency-pinned at this point. There is no uncharged pending CPU-result queue.

Fresh leases are consumed on every outcome after nonnull argument checks. Beginning with a lease already owned by the same device is rejected without closing its existing owner. This protects an active/retired scene against a duplicate handoff. Callers must still never reuse a transferred lease across devices or close it independently.

The optional `WorldTint` overload retains/charges detached native color arrays and expands GPU vertices from eight CPU floats to eight floats plus an RGB24/shading-byte word. The `WorldLighting` overload adds three final RGB modulation floats, retains/charges those arrays as well, and selects a 48-byte stride. Both layouts stream through the same bounded staging arena; see [LIGHTING-VIEW.md](LIGHTING-VIEW.md). See [MATERIALS.md](MATERIALS.md) for the 16-byte-per-face GPU addition, color-word chunking and separate translucent draw-plan contract.

The uploader validates one section header, face or atlas page at a time. Native tint completeness/scene-identity guards, raster epoch checks, metadata counts and hardware/staging dimensions must pass before scene GL objects/storage are created. It then upgrades the same CPU journal with the complete GPU bytes and exact name-array reservation. Admission includes active and retired allocations; no extra temporary journal slot or replacement of the CPU lease is needed.

`step(budget, cancelled)` advances explicit texture-storage/data, vertex-storage/data, index-storage/data, vertex-layout, marker-storage/data/layout and section-completion phases. Each step uses the original context/thread. A GPU work step lazily captures host state, normalizes upload bindings/unpack parameters, and restores state before returning or throwing. Validation-only steps do not capture or mutate upload bindings. The transfer-only guard now snapshots texture/sampler, VAO/array/PBO, framebuffer/renderbuffer bindings and all eight core unpack parameters; it does not query or rewrite programs, viewport arrays, scissor, blending or raster state. See [HOST-STATE.md](HOST-STATE.md).

Every resumed data phase explicitly rebinds its own texture or buffer. Index data can stream through the array-buffer target while its element binding stays recorded in the owned VAO, avoiding changes to a host VAO between steps. Geometry and marker layout setup is deferred until their complete data stores have been transferred. Paused or partially configured objects are never returned as a drawable `GlScene`.

Completion verifies total transferred bytes, section coverage and consumed buffer/VAO slots against admission. A ready scene is created only after successful host-state restoration. `take()` transfers it exactly once; closing the upload afterward does not retire the transferred scene. Closing/cancelling before transfer retires every submitted object and releases local input references. A CPU-only journal can release immediately because it has submitted no scene GPU objects; submitted work retains the existing fenced/quarantined cleanup contract.

`GlScene.upload` remains a synchronous convenience. It drives this same state machine with the maximum step settings, rather than maintaining a second transfer implementation. It can still block its caller for the entire scene; use `GlUpload` or the queue for frame-by-frame scheduling.

## Step limits and progress

| Setting | Default | Hard bound |
| --- | --- | --- |
| Client data transferred per step | 4 MiB | 64 KiB to 16 MiB, divisible by four |
| GPU action groups | 32 | 1 to 1,024 |
| Total state/validation work units | 65,536 | 1 to 262,144 |
| Cooperative elapsed target | 4 ms | Positive through 50 ms |
| One data-transfer chunk | At most 64 KiB | Also limited by native staging and remaining step bytes |

`GlUploadBudget` applies these independent ceilings. An action group is one bounded storage, layout or data operation, including its constant-size GL binding/setup calls; it is not a count of individual driver calls. Every action also consumes a work unit. Texture chunks contain whole source rows, float/index chunks contain whole values, and marker chunks contain whole XYZ instances. An insufficient remainder yields without partial rows/values/instances; the minimum legal step budget accommodates every admitted source row.

The elapsed target is cooperative, with at least one bounded work unit attempted to avoid starvation under a very short target. It is checked between units. A driver allocation, GL call, host-state capture/restoration, admission cleanup or JVM pause cannot be preempted. Storage reservation bytes are reported separately and are not charged as transferred client data. These APIs do not guarantee a hard frame duration. Large data-store allocation and retirement responsiveness still require end-of-stage profiling.

`Step` reports actual transferred bytes, requested storage bytes, action/work counts, total elapsed time including state restoration, and cumulative progress. `Progress` includes validation counts, completed sections, transferred/total bytes and phase. Direct upload mutation/progress access belongs to the context thread. The queue publishes immutable status snapshots for polling from other threads.

## Latest-revision queue

`GlUploadQueue` uses the existing render context and creates no worker thread. It owns at most one active upload or one untaken completed scene. CPU scene assembly remains in `WorldSceneWorker`.

`invalidate(revision)` immediately advances to a newer edit/request revision and retires queue-owned old work, even before the new CPU scene arrives. `submit(revision, cpuResult)` starts that revision's incremental upload. Older/duplicate fresh results are discarded and closed without replacing newer work. A result already owned by the same device is ignored without closing it again. Other fresh nonnull results, including invalid/closed-queue failure cases, follow the queue's consuming ownership contract.

`advance(budget, latestRevision)` receives the application's current CPU/edit token each frame. A newer token invalidates old work; an older token cannot advance the queue's newer upload. A matching token advances one step and only exposes READY after complete upload. Native generation receipts remain available in status, but the revision is an adoption token, not proof of engine correctness or visual readiness.

`take(latestRevision)` checks the token again and transfers a `Completion` exactly once. A newer token first invalidates an untaken old scene instead of returning it. The completion is an `AutoCloseable` owner of the transferred `GlScene`. The application must recheck its revision when actually adopting/presenting it and close it on replacement; the queue cannot revoke caller-transferred owners. Changes concurrent with a transfer are governed by the application's adoption ordering, not inferred from matching generation hashes.

Closing the queue retires only its owned active/untaken work. Device journals retain any failed-fence resources and CPU charges. If disposal cannot complete under the current context, ownership is retained for a later close attempt. Close the queue on the owning context before device teardown. No framebuffer, editor content, preview readiness or generated state is changed by this queue.

## Deferred stage verification

At renderer completion, compare incremental and synchronous upload contents for empty, geometry-only, marker-only and mixed scenes. Pause after every phase and change host texture/sampler/PBO/VAO/array/unpack state before resuming. Cover odd row widths, final short chunks, exact transfer limits, marker XYZ alignment, action/work limits and minimum elapsed targets. No partially configured scene may be drawable or transferable.

Check preflight failure after multiple frames, CPU-only charge/release, reservation upgrade thresholds, object count limits, duplicate-device owner protection, cancellation in each phase, failed restoration, partial-storage retirement and quarantine. Repeated close/take must preserve exactly one owner; transferred scenes must stay live until their own close/fence cleanup. Verify all final byte/count/coverage assertions and CPU native identity.

Submit/invalidate during validation, row upload, geometry/marker phases, ready-before-take and after caller transfer. Older fresh results must be closed; a duplicate reference to an already transferred lease must not close its live device owner. Verify current-revision-only advance/take, immutable status publication, queue shutdown/retry and absence of pending-result accumulation. Profile actual driver allocation, state capture and cleanup alongside upload budgets before editor integration. No renderer tests were executed for this batch.

## Owned lighting and unchanged vertex strides

`GlUpload.beginLit` consumes admitted streamed lighting alongside a fresh scene owner, requiring the same tracked coordinator and retaining source admission through destination journal admission. The fourth byte of the existing material word now carries shade-direction/emission flags; attributes four/six read RGB24/raw flags, with the original 36/48-byte strides and transfer totals unchanged. See [MATERIAL-LIGHTING.md](MATERIAL-LIGHTING.md).
