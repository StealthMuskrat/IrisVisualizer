# Animated image admission before atlas adoption

`GlWorldView` now admits the next image before adopting a READY animated atlas. Previously, adoption advanced the view and invalidated an untaken completed image before its replacement target and draw scratch were admitted. A known image-capacity deficit could therefore discard that earlier output even though the new atlas was complete.

## Independent atlas and image ownership

An internal `GlWorldAnimation.advance` host hook runs only after the atlas queue reaches READY and before its complete frame transfers to the animation controller. The public advance signature and upload budgets are unchanged. Standalone animation uses an empty hook.

When a complete world source and camera exist, the connected hook reserves the same unbound `GlFrame.Prepared` used by [world navigation](WORLD-NAVIGATION-ADMISSION.md): a full scene-layout RGBA upper bound, exact hierarchy draw scratch, a complete color/depth target, three journals and three object slots. Admission creates no target handles or storage. At drawing, the preparation binds the actual immutable atlas/order and refunds the unused atlas upper bound.

A rejection during this independent image admission propagates to the caller while preserving the displayed atlas, world/view revisions, world status and earlier queued image. The new atlas stays queue-owned READY. The animation remains UPLOADING rather than FAILED because its prepared sample has not yet been adopted. Its existing progress and desired/displayed clock receipts describe those distinct owners.

The next `advance` retries image admission automatically. It does not require `retryAnimation`, `redraw`, CPU recapture, geometry reupload, page copies or patch upload. Releasing other ownership or increasing host capacity can make that same completed sample admissible. All retained atlas pages, old images and retired allocations remain charged; no hypothetical replacement release is credited.

Successful admission permits atlas adoption, advances the view revision, invalidates the previous queue-owned image and transfers the held preparation into the existing rendering path. Caller-transferred older images retain their original pixels, source identity and clock receipts. Driver allocation, drawing, state restoration and metadata allocation can still fail after admission and retain the established failure/retirement rules.

## Existing scheduling and lifecycle

If navigation already holds a frame preparation, its complete atlas upper bound covers adoption. The controller reuses and retokens that preparation without another image reservation. With no camera, atlas updates need no image storage; configuring a camera later follows normal navigation admission.

Live playback keeps its latest desired clock behind the already admitted sample, including while image admission is blocked. An explicit seek can cancel the READY update, including a seek back to currently displayed pixels. Ordinary navigation may still render the last complete atlas during later seeks. Initial sample priming and the existing presentable/settled rules are unchanged.

CPU preparation, atlas GPU work and atlas adoption/cleanup errors continue to use FAILED and explicit recovery. The host hook is outside that atlas failure boundary; it does not reclassify actual upload failures. Source invalidation, material replacement and closure keep their existing ownership cleanup. No native-data, full-height, selected-asset or preview-readiness gate changes.

## Prepared validation

Three additional GPU groups prepare:

- Standalone READY rejection, repeated retry without recapture/upload, exact original errors, explicit cancellation and live-clock coalescing.
- Connected atlas/scratch/target shared-admission deficits, preserved untaken images/revisions, exact admitted peaks, immutable old pixels, cancellation, existing-navigation reuse and camera-less updates.
- Independent GPU-byte, journal and object-slot rejection, partial-reservation refunds, preserved host bindings and recovery with the already completed atlas after old output releases.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). All 178 prepared groups remain unexecuted: 109 CPU/context-free and 69 GPU. No test main, GL context, shader, native operation or validation workflow ran. Renderer completion, genuine Windows/native/GPU validation, total-process memory/performance measurement and final editor integration remain pending. Unsupported previews stay disabled.
