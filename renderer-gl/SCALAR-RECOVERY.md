# Scalar upload recovery and independent CPU surfaces

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and source inspection only; renderer-stage execution remains deferred until implementation is complete. No retry, worker, native, GL, GPU readback or workflow tests ran. Unsupported previews remain disabled.

## Retry from complete prepared data

`GlNoiseView.retryUpload(expectedDataRevision, surface)` accepts a complete retained `NoiseSurface` after a failed current-source preparation/upload/adoption path. It preserves the data revision, camera, viewport and geometric clipping, while advancing the view revision. It performs no native sampling, boundary meshing or recoloring. The ordinary bounded upload starts from its initial phase; subsequent `advance` calls retain existing byte/action/work/time budgets and complete-frame requirements.

A positive token must match the current data revision, the view must be FAILED and no complete current source may already be adopted. Other tokens/phases return false without changing work or display tokens. Nonpositive tokens, closed views and wrong-context calls retain explicit errors. A draw failure with an adopted scene continues to use `redraw()`.

Before mutation, retry validates the exact latest native volume UUID and the entire requested display style: inclusion thresholds, meshing mode and scalar color range/ramp. A matching volume with old colors is rejected, even when geometry is reusable for a separate recolor request. Failed/partial surfaces are rejected. Retained face count and source/mesh bytes must also fit the most recently accepted request's lowered limits. No scratch is allocated during upload retry, so this check concerns retained output rather than preparation scratch or old/new recolor overlap.

After validation and status allocation, retry invalidates worker-owned pending/ready output and cancels active work, clears its CPU adoption token, advances the display token and disposes view-owned upload/frame/scene resources. Cancelled active CPU stacks keep their existing reservation until unwind. Cleanup must finish before a replacement device journal is admitted. Cleanup/admission failure leaves FAILED diagnostics and any unfinished owner reachable through the existing retry/close path; later calls may try again. The source token is unchanged throughout. Successful admission publishes UPLOADING and leaves current-source adoption false until the complete scene is actually taken.

This explicit recovery may also replace a failed CPU job with an already complete surface for the exact same volume/style and retained limits. It never invents missing output. Native-input invalidation clears the latest identity/style/limits, so an old captured surface cannot satisfy a newer source. Previously transferred frames keep their original immutable pixels and receipts.

## CPU ownership across frame lifetime

| Operation | Ownership |
| --- | --- |
| `GlNoiseScene.retainInput()` | Independently admitted CPU surface, captured on the original context |
| `GlNoiseFrame.retainSurfaceInput()` | Same CPU ownership from that frame's exact displayed scene |
| `GlNoiseView.retryUpload(revision, input)` | Consumes supplied input on success, rejection or exception |
| `GlNoiseView.recolor(input, colors, limits)` | Consumes supplied input through background recolor admission or rejection |
| `input.retain()` | Independent wrapper sharing one CPU admission account |
| `input.close()` | Drops that wrapper's reference and closes its hold |

Retaining CPU input copies no samples, face arrays, colors or range metadata. A device with `RenderMemory` first admits the full `surface.payloadBytes()` in an independent account while the GL source remains charged. It can therefore reject on shared byte/account limits; failure leaves the scene/frame intact. Without a coordinator the compatibility input is untracked. Temporary handles close if wrapper creation fails.

The CPU input contains only the immutable surface and its hold, with no target, GL owner, callback or device. It survives frame/scene/device closure, and CPU-side retain/read/close require no GL context. Its shared charge lasts until final close. The old GL allocations remain separately charged through their actual retirement/quarantine. Escaped borrowed values alone do not extend admission.

The consuming view overloads retain the input through destination worker/device admission or rejection, require the same coordinator for tracked inputs and close the supplied wrapper on every outcome. Keep an independent `input.retain()` owner before an attempt when another retry may be needed. Raw surface overloads remain available; keep their externally admitted source owner alive through the call. Recolor still checks latest volume and geometry policy, whereas upload retry checks the complete latest style. No memory ceiling, preview capability or native identity changes.

## Deferred verification

At renderer completion, exercise failed initial/partial/finishing upload, CPU failure with retained exact output, cleanup failure before new admission, coordinator pressure and repeated retry. Cover stale/future/nonpositive tokens, nonfailed and adopted-source phases, exact volume with wrong thresholds/meshing/colors, equal separately constructed ramps, lowered count/byte limits, invalidation, counter exhaustion and status allocation failures. Check that rejected validation preserves tokens/work, accepted recovery cancels obsolete CPU adoption, old frames survive and no partial scene/image escapes.

Verify independent CPU handoff admission before frame release, no copied payload, multiple retained wrappers, source/frame/device/coordinator closure, foreign coordinators and every consuming-input outcome. Inject hold/input creation failures and confirm the source remains live with no leaked account. On real Windows GL compare original/retried instances, pixels, camera/clip receipts, readback and picking; include empty surfaces, greedy/unit modes and transparent background. Confirm final CPU/device/shared charges after fence/cleanup retirement. Native compilation, real-driver comparison and final editor integration remain pending.
