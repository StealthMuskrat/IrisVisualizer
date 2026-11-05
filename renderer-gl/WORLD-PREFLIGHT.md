# World scene replacement preflight

Renderer implementation, 2026-11-05. World upload begin, the lower-level upload queue and the connected world view now share scene/material preflight. Invalid complete-source submissions are rejected before prior queue/view work is invalidated. All 129 CPU/OpenGL production sources plus four prepared harnesses compile with Java 25 and LWJGL 3.4.1 (133 total). No renderer contract, worker, native, GL context, GPU upload/readback or workflow test ran; stage execution remains deferred and unsupported previews stay disabled.

## Preserve previous work on rejected input

Previously, a newer `GlUploadQueue.submit` or `GlWorldView.submit` revision could invalidate earlier uploads/scenes/untaken frames before upload begin rejected an incomplete CPU scene or mismatched tint/lighting. Missing required tint was detected later during incremental section validation, after a CPU upload journal had been admitted.

The new internal borrowed preflight checks renderer liveness and a successful live CPU scene, resolves already-captured native tint metadata when appropriate, validates explicit captures and uses the scene's verified tint-required count. It runs after token/phase and already-device-owned-wrapper guards, before revision changes or cleanup.

| Input condition | Preflight result |
| --- | --- |
| No complete CPU scene | Existing STATE/closed-owner error before replacement |
| Failed supplied tint or lighting capture | MATERIAL error before replacement |
| Tint or lighting from another exact CPU scene | STALE error before replacement |
| Surviving tint-required faces without explicit or captured native colors | MATERIAL error before journal admission |
| Complete scene with valid material inputs | Continue existing invalidation and upload admission |

On rejection, the lower-level queue preserves its revision/status/progress, current upload and untaken scene. The connected view additionally preserves world/view tokens, current-source adoption, camera, clipping, viewport, ordering, atlas clock/controller and untaken completed image. Rejection does not accept the candidate's new revision. An existing FAILED or WAITING_CPU state likewise remains unchanged, allowing corrected complete data to be submitted according to its existing phase contract.

Direct `GlUpload.begin` shares these checks. The returned resolved `WorldTint` is passed through queue/view layers into begin, avoiding repeated native-tint metadata wrapper creation. Resolution reuses the already captured per-cell/layer input; it creates no color array and calls no native producer.

## Ownership and bounded work

Fresh CPU scene wrappers remain consuming inputs on every outcome. A preflight exception closes the submitted fresh wrapper through the existing ownership scopes; it does not close an independent original owner or a previously uploaded scene. Retain another scene owner before an attempt if corrected tint/lighting or a later retry may be needed. Wrappers already transferred to the device remain rejected intact before preflight, preserving their current owner.

The lighting-input overloads retain their existing same-coordinator checks and hold lifetime through destination admission. Raw tint/lighting inputs retain their externally scoped admission contract. Successful preflight makes no GPU or shared-memory reservation; device/shared admission, setup or cleanup can still fail after revision acceptance, following the existing FAILED/retry and retirement/quarantine rules.

Preflight reads immutable scene/capture metadata and verified material totals. It performs no section/face/vertex scan, generation, sampling, tint capture or lighting capture. The bounded upload phases still validate section/raster/material coverage and atlas hardware/row-staging limits under their original work budgets. Their defensive missing-tint and count checks remain in place. No aggregate memory ceiling, shader, vertex stride, source identity, color or geometry is changed.

Explicit `invalidate(newWorldRevision)` still takes effect immediately when upstream inputs become obsolete, even before valid CPU data arrives. The host must continue to notify that source transition and compare desired tokens. A preflight rejection cannot restore resources already invalidated explicitly; it preserves only prior work that the view/queue still owns. Accepted-source failures with retained exact CPU data continue to use the existing explicit upload recovery, while failures drawing an adopted current scene use redraw.

## Deferred verification

At renderer completion, submit failed/closed CPU results, failed tint/lighting, same-looking different scene captures and missing native tint through direct begin, queue submit, view submit and owned-lighting routes. Exercise newer tokens with prior work in WAITING/UPLOADING/READY/DELIVERED and view WAITING_CPU/SORTING/FRAME_READY/FAILED states. Verify exact prior tokens/status objects/progress/source adoption and queue/view owners after preflight rejection.

Check fresh-wrapper consumption, already-device-owned-wrapper protection, retained scene/cache tickets, matching and foreign tint/lighting identity, automatic zero-copy native tint resolution, all-hidden tint-required source faces and actual surviving tint counts. Include stale/duplicate token guards, coordinator errors, corrected resubmission at unchanged WAITING tokens and explicit upstream invalidation before candidate arrival.

Inject allocation/status/setup/cleanup failures after valid acceptance, then exercise retry without regeneration, independent old frames and final shared/device/cache accounting. Compare supplied/native tint and lighting bytes, geometry, pixel output, picking/readback receipts and hostile host-state restoration on real Windows GL. Sixteen native adapters still require genuine pinned Windows compilation/execution; native parity and final editor integration remain pending. None of these runtime checks has been executed.
