# Consuming GPU input handoffs

Renderer implementation, 2026-11-05. Owned-input upload factories now keep a newly created job locally owned until CPU input cleanup finishes. World lighting/scene submission also preserves original rejection errors through fresh-owner cleanup. All 129 production sources and four harnesses compile with Java 25/LWJGL 3.4.1, and the dependency-free set compiles. All 64 prepared groups and runtime/GPU/failure-injection validation remain unexecuted. Unsupported previews remain disabled.

## Return only after input cleanup

| Factory | Supplied CPU ownership | Newly created resource held until cleanup succeeds |
| --- | --- | --- |
| `GlAtlasUpload.begin(displayed, input)` | Independently admitted `AtlasFramePlan` | Atlas updater, retained displayed frame and work journal |
| `GlNoiseUpload.begin(device, input)` | Independently admitted `NoiseSurface` | Scalar uploader and retained-source journal |
| `GlUpload.beginLit(renderer, owner, tint, input)` | Fresh scene lease plus independently admitted `WorldLighting` | World uploader and its scene/material journal |

The factory scopes the supplied input and its independent temporary retain through destination admission. It stores the created job locally, closes temporary/supplied CPU wrappers, then returns the job. If input cleanup throws after creation, the factory catches the error and attempts job close before propagating it. A distinct job-close error is suppressed onto the input-release error. Before this change, a return expression could create a job and then lose its returned reference when input close failed.

Failure before job creation keeps the original validation/admission error primary through scoped CPU-wrapper cleanup. Failure after job creation returns no upload. If its close also fails, device journals retain unfinished or retired/quarantined resources under the existing accounting/teardown contract. The path attempts resource disposal rather than claiming that a failing native/device cleanup released its charge.

Successful destination admission still overlaps the independent CPU hold conservatively. Coordinator compatibility, exact atlas baseline and world lighting/scene identity checks keep their existing ordering. Input consumption begins after the existing nonnull checks, including when validation or temporary retain fails. Other independently retained wrappers survive consumption. No additional source/pixel copy, GPU storage, task, generation setting or memory ceiling is introduced.

## Paired scene and lighting cleanup

`GlUpload.beginLit` and `GlWorldView.submitLit` now scope the supplied lighting input so its close cannot skip fresh-scene cleanup on rejection. If the scene was not delegated or recognized as device-owned, scene close is attempted after the input scopes unwind. Its error is suppressed onto the earlier rejection or input-release error. Once the lower consuming route owns the scene, that route retains responsibility for it; the outer wrapper does not independently release the same transferred owner. Existing recognized device-owned-wrapper rejection remains intact.

A direct factory retains the just-created upload until all input scopes close, so a post-admission input-release error triggers the unreturned-job cleanup above. A view/queue already owns accepted work in its fields. That work stays reachable under the existing recovery/invalidation/close rules if a later input-release error propagates; the error does not promise atomic rollback of already accepted submission or retroactively confer prior-source adoption. Preflight rejection preserves prior view/queue tokens and ownership under the existing contracts.

## Fresh-scene rejection errors

Raw scene submission through `GlUploadQueue` and `GlWorldView` now remembers a validation/context/admission error before attempting fresh-owner close. Distinct close errors are suppressed onto that original error. If submission simply returns false for a stale/duplicate token and fresh-owner close fails, that cleanup error is reported as primary because there was no earlier exception. Fresh owners are still consumed, and wrappers already recognized as device-owned retain the existing protection. No new revision, upload acceptance or source mutation is added by the error aggregator.

## Deferred verification

On real Windows GL at renderer completion, cover all three factories with successful admission followed by temporary/supplied input-close failure, then combine it with job-close RuntimeException or Error. Verify the job is not returned, close is attempted, the original input error stays primary and all remaining ownership stays charged in reachable device journals until release. Include ordinary successful return, failure before creation, closed/foreign input, matching tracked and untracked admission, exact atlas baseline and lighting/source mismatch, pressure rejection and independently retained CPU inputs.

For both lighting/scene routes, combine validation/coordinator/context errors, input close failure and fresh-scene release failure. Check every required owner is visited, original/suppressed errors retain identity, recognized already-device-owned wrappers stay intact and delegated ownership is not released twice. For raw queue/view submission, combine preflight rejection with scene cleanup failure and cover false stale return plus cleanup-only error. Verify prior status/tokens/current images survive preflight rejection and already accepted work remains reachable if a later input release fails. Check final leases, device dependency pins, retired/quarantined charges and independent input lifetime through device teardown.

Compilation and source inspection are the current evidence. No harness, mock/real GL context, worker/native comparison or workflow ran for this batch.
