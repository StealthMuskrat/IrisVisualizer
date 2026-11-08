# Admitted scalar navigation

`GlNoiseView` now reserves its complete replacement frame before accepting camera, viewport, clipping, fit or redraw changes on a complete uploaded source with a configured camera. A known scratch/target capacity failure previously arrived during `advance`, after the view token changed and queued output was released. It now leaves the original READY or undrawn view intact.

## One-shot scalar frame preparation

The internal `GlNoiseFrame.Prepared` owner validates the exact original source/device, camera precision, viewport and culling strategy, independently retains the GPU scene wrapper, and admits all frame resources before native storage starts.

| Reservation | Added footprint |
| --- | --- |
| Frame journal | One journal pinning the original source allocation and target |
| Scratch journal | One journal covering `GlNoiseCulling.workingBytes(surface, mode)` |
| Target journal | One journal and `width * height * 8` GPU bytes |
| Object slots | One RGBA texture, one framebuffer and one depth renderbuffer |
| Source wrapper | Independent ownership of the original uploaded scalar samples and geometry |

Scratch remains the existing fixed 1,024-byte math allowance plus one byte per source batch for enabled culling, or only the math allowance for disabled group culling. Empty geometry still retains the fixed allowance. Admission creates no native target handles or culling arrays. All payload/journal/object limits apply alongside current and retired ownership, including previous queued output or an undrawn preparation. No credit is taken for old resources that successful replacement might release. Bounded automatic collection and existing JVM/driver accounting exclusions remain unchanged.

Rendering consumes the preparation once. It revalidates source/renderer liveness and precision, initializes the admitted target, and passes the exact admitted scratch journal into the existing renderer. The renderer consumes that scratch through draw cleanup without another reservation. Only a successful draw, state restoration and cancellation check publishes the immutable frame and its original volume/style/camera/clip receipt. Frame/target/source ownership then transfers to the output.

Unused preparation closure refunds the target and scratch immediately without fences. Started targets follow normal fenced retirement; scratch and independent source wrappers follow their existing ownership rules. Cleanup visits every owner and preserves the primary failure. Preparations can close after synchronized device teardown and cannot be rendered twice. Public direct-render signatures, differential modes, initial precision preflight and obsolete callback ordering remain intact.

## Connected view behavior

A changed camera/viewport, changed clip, fit or explicit redraw first admits its replacement preparation. Failure leaves the exact prior camera, clip, viewport, view/data revisions, status object, source/worker/upload state and queued output unchanged. Equal camera/viewport or clip requests remain allocation-free no-ops; redraw explicitly asks for another image.

After admission succeeds, the controller accepts the next view revision and retains its new preparation until `advance`. Drawing reuses its held target and scratch, so intervening shared-capacity pressure does not cause another known-frame admission failure. A newer request supersedes an undrawn preparation only after its own admission succeeds. Unused old target/scratch reservations refund immediately; failed requests preserve the earlier undrawn request.

Clip configuration before a camera is available, and camera configuration while source preparation is pending, retain their metadata-only behavior. A frame is admitted when both the complete source and camera are available. Explicit source invalidation, accepted source replacement and closure abandon pending preparations. Supplied native values, configured volume height, inclusion/meshing policy, histogram ownership and source identity are unchanged. Navigation schedules no generation, meshing, recoloring or source upload.

Caller-held older images and full-source histograms remain independent of controller navigation and invalidation. Capacity admission does not guarantee successful driver allocation, host-state restoration or Java metadata allocation; those errors retain the display-failure/redraw policy. These changes cover the scalar controller; world-view navigation retains its existing policy.

## Prepared validation

Three GPU groups prepare cell/greedy and empty/populated sources, all culling strategies, exact initial counters, cancellation at each prepared draw gate, single-use/source-wrapper ownership and post-device-teardown closure. Frame pixels and original source/style/clip receipts are compared with the direct-render path.

Connected cases prepare scratch-only and complete-peak rejection for camera/resize, clip, fit and redraw; unchanged-setting no-ops; exact-peak acceptance; drawing with zero additional shared allowance; complete clipped/moved pixel comparisons; unchanged old frames; full-source histogram reuse; undrawn supersession; source invalidation; and closure before drawing. Independent GPU-byte, journal and object-slot limits prepare READY and undrawn rejection, complete rollback and reuse of the unspent view revision after earlier output releases.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). There are 172 prepared, unexecuted groups: 109 CPU/context-free and 63 GPU. No renderer test main, context, shader, native operation, Windows validation task or workflow ran. Genuine native/API compatibility, GPU correctness/fault injection, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews stay disabled.
