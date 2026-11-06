# Owned world lighting and animation recovery

Renderer implementation, 2026-11-06. Java 25/LWJGL compilation succeeds for all 143 renderer/validation files, including the previously source-only initial-atlas change. No renderer contract, GL operation, native comparison or workflow has run. Renderer-stage execution and final integration remain deferred; unsupported previews stay disabled.

## Owned lighting through queue/view admission

`GlUploadQueue.materialsLit(nextRevision, source, tint, lightingInput)` and `GlWorldView.materialsLit(nextWorldRevision, tint, lightingInput)` now accept a fresh `RenderInput<WorldLighting>` directly. The value is the same detached, exact-scene final RGB input supported by `GlUpload.materialsLit`. Tracking must use the GPU device's shared coordinator. The existing raw `materials` overloads remain available and share the same view replacement path.

Lighting admission stays live until the new device journal covers its full CPU source/tint/lighting payload. Input cleanup completes before accepted queue work is retired or a replacement token is published. This preserves conservative overlap: freeing the supplied lighting hold before admission would incorrectly make a near-limit request appear to fit. No array copies, new native calculations or increased limits are introduced.

Fresh lighting wrappers are consumed on success, stale/duplicate tokens, invalid revisions, wrong scenes/coordinators, incomplete sources, initial admission rejection and closed queue/view errors. A reused closed wrapper rejects when its value would be needed. Raw GPU source scenes remain borrowed; already accepted work and caller-owned frames keep their original ownership. Rejected admission preserves queue/view status and tokens. Failed temporary cleanup keeps its primary/suppressed errors and existing device-journal responsibility.

The queue prepares the copy using the lower-level consuming upload method, then installs it only after input cleanup. The connected view routes owned/raw calls through one replacement helper. Once accepted, both paths retain the existing geometry/base-atlas GPU copy, exact CPU provenance, source replacement and immutable completed-frame behavior.

## Explicit current-animation retry

`GlWorldView.retryAnimation(expectedWorldRevision)` re-arms a failed animation coordinator while keeping its complete GPU scene. It accepts only the current world token, a complete source, and FAILED world/animation status. Stale tokens and pending, healthy, unrelated-failure or incomplete-source cases return false with the existing state intact. Nonpositive tokens reject.

A successful retry retains the latest requested tick/partial tick, camera, viewport, clip, source and original native identities. It advances the view token once, invalidates the old queue-owned image and resumes the normal animation worker/upload path on later `advance` calls. It prepares no native generation, CPU mesh or new scene upload. Existing caller-owned saved images remain independent. Initial atlas readiness remains required if that source has never been presented.

Retry creates no additional allowance and does not silently change the clock. A request that still exceeds its configured budget fails again. The host may explicitly select another clock before retrying; if that clock's canonical pixels already exist, recovery needs no atlas worker payload. Complete-source animation failures use this operation; source-upload failures continue using `retryUpload`, and unrelated display failures use `redraw`. Existing manual `clock` plus `redraw` calls remain possible.

## Prepared coverage and evidence

One new GPU group prepares tracked lighting transfer through queue/view success, exact initial memory charge, duplicate and reused wrappers, a one-byte-short overlapping admission allowance, wrong-scene and foreign-coordinator rejection, closed/incomplete endpoints, exact rendered pixels and saved frames after view shutdown. The initial-atlas group now also prepares stale/invalid/duplicate/healthy retry gates, unchanged-budget rejection, changed-clock recovery, unchanged world/upload receipts and zero worker payload for existing pixels.

All 100 prepared groups remain unexecuted: 76 CPU/context-free and 24 GPU. Full compilation covers 137 production files, five harnesses and one fixture (143 total). The unchanged dependency-free source set retains its 105-file compile evidence. The previously pending initial-atlas compilation gap is resolved. Genuine Windows GPU/native verification, cleanup/retirement fault injection, concurrency, performance/total application memory and editor control adoption remain pending.

[Displayed atlas refresh](DISPLAYED-ATLAS-REFRESH.md) now extends raw/owned handoffs with an optional exact GPU atlas frame. The view supplies its complete displayed frame, preserving animation pixels without a second animation upload when the clock matches. Changed CPU pages and temporary source ownership remain inside existing ceilings. All 143 sources compile; all 102 groups remain unexecuted.
