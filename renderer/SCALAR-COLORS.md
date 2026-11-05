# Prepared scalar colors and geometry reuse

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and source inspection only: no meshing/recoloring/query/worker execution, runtime tests, native comparison, GL context, pixel readback or workflow ran. Renderer-stage validation remains deferred until implementation is complete. Unsupported previews remain disabled.

## CPU preparation

`NoiseSurface` now stores one immutable RGBA word per emitted unit face or greedy rectangle. Its normal preparation computes colors on the CPU after face emission, using the same original scalar value, explicit range and opaque ramp. This color pass shares the existing range-bound traversal and cancellation checks. Greedy faces remain uniform because only exactly equal native scalar bits merge.

`rgba(face)` is now a checked array lookup. Scalar upload no longer evaluates ramps/normalization on the context thread, and original-value picking reuses the prepared word. The GPU format remains twelve bytes per instance: source sample/direction, RGBA and extents. Shader lighting, face winding, source identity, range culling and clipping stay unchanged. Stored RGBA is the base display color before the existing simple shader lighting.

CPU output now costs nine bytes per unit face or thirteen per greedy rectangle, plus the existing 24-byte range bounds per 1,024 faces. The additional four bytes per face are charged by count preflight, actual usage, prospective worker reservations, picking inputs and live/retired GPU source journals. This adds at most 4 MiB at the hard face bound, or 1 MiB at the default face bound. Existing working/device/shared ceilings do not grow; tighter caller limits may now reject before allocation rather than silently omit output.

## Recolor without remeshing

`NoiseSurface.recolor(source, colors, limits, cancelled)` retains the exact volume, inclusion interval, meshing mode, face cells/directions/extents, bounds and range arrays. It evaluates only the existing emitted faces into replacement colors. It never scans the full scalar volume, recomputes selection/neighbors, allocates a greedy plane, evaluates native noise or copies geometry. Different color ranges/ramps cannot change which samples contribute faces.

Opaque stops remain required. Complete source, face and source-plus-replacement working bounds are checked before output allocation. Invalid budgets publish a structured failure without partial geometry/colors; cancellation propagates separately. Equal `ScalarStyle` settings share the original color array. Equality follows existing record/ramp identity semantics, rather than adding a global style cache or comparing arbitrary graphs.

The replacement is immutable. Different colors use a fresh array and leave every caller-held old surface/frame intact. New wrappers share geometry directly, not an old surface wrapper, so discarded old color arrays need no permanent parent reference. `Usage` preserves selected/unit-face counts and reports old-color coexistence as temporary scratch; its retained source/mesh bytes match the resulting arrays. Equal-color reuse needs no color coexistence scratch. Original UUID, samples, coordinates, scalar bits and picking tie order remain unchanged.

## Worker ownership

`NoiseSurfaceWorker.Request.recolor(source, colors, limits)` schedules this path on the existing single latest-request executor. The prior three-argument Request constructor still requests full meshing. Explicit geometry requests require the exact volume object and bit-exact inclusion endpoints/meshing policy, and reject invalid source/face/working limits before replacing work. RECOLORING status distinguishes the operation from MESHING.

Recolor admission covers the full old source/geometry/color payload plus prospective replacement colors. Equal-color requests reserve only the retained source payload. Active plus pending work remains under the existing 1 GiB worker ceiling and optional shared coordinator. Cancelled requests keep their charge until unwind. Publication creates an owned `RenderInput<NoiseSurface>`; payload-bearing job references are cleared before the shared hold shrinks to actual output. Shared geometry in independently held old/new owners is conservatively charged by each owner, without an uncharged transfer gap or deduplication claim.

The consuming `worker.recolor(RenderInput<NoiseSurface>, colors, limits)` overload retains source admission through destination admission, requires the same coordinator for tracked inputs and consumes its wrapper on success/rejection. Keep another retained owner for retry or subsequent changes. Raw surfaces require their caller's owner through submission; accepted jobs independently own/admit their immutable source references.

## Connected view and rapid changes

`GlNoiseView.recolor(colors, limits)` uses its current complete scene. For rapid replacements while that scene is being replaced, use `recolor(surface, colors, limits)` with a surface captured from an independently held displayed frame. This overload checks the latest native volume UUID and exact inclusion/meshing policy before submission. A frame from an older volume or changed threshold/meshing policy cannot silently restore obsolete geometry. Invalidation clears this reuse context immediately.

Accepted recolors advance the existing data/view revisions and follow normal complete CPU preparation, bounded upload and fresh-frame drawing. Camera, viewport and geometric clip are retained. These renderer revisions do not imply a new native generation. The complete instance buffer is still uploaded incrementally, including its unchanged cell/extents words; no in-place GPU color mutation is introduced. Caller-transferred old images remain independent during recoloring and failures.

Source/limit/shared admission rejection preserves earlier jobs, tokens and output. Explicit resubmission supports retry. Completed frame/readback receipts retain the new scalar style, and frame-bound pointer requests receive the corresponding prepared colors. Native source identity and readiness remain separate. These APIs add no editor controls, automatic polling, pack settings or preview enablement.

## Deferred verification

At renderer completion, compare prepared words with an independent evaluation of the prior color formula across constant/extreme finite ranges, opaque gradients, signed zero, all directions, CELLS/GREEDY geometry and large merged faces. Compare original preparation versus recoloring from the same volume/style, including original-value hits and GPU base-color instance words before shader lighting.

Verify immutable old/new colors, geometry/sample/range reuse, equal-style reuse, empty/all-absent sources, unchanged unit/selected counts and failure without partial output. Check exact/one-over color/output/coexistence/worker/shared bounds, lowered limits, cancellation through color preparation and job-reference release before shrinking admission. Exercise consuming tracked inputs, foreign/closed coordinators, latest-only replacement, take/close before unwind and independently retained old GPU frames.

For connected views, cover recoloring during pending work/upload, the current-scene convenience guard, explicit retained-frame reuse, source/threshold/meshing mismatch, invalidation, retry, camera/clip retention and stale frame/query/readback tokens. Compare final Windows GPU colors, upload bytes and retirement lifetimes before claiming runtime or performance improvements. Native validation and final editor routing remain pending.

## Gradient value equality

`MapRamp` now compares immutable ordered stops by value, with matching hash codes. Separately constructed equal scalar color styles therefore use the existing scalar recolor source-only reservation and CPU no-op. Interpolation, thresholds and geometry identity remain unchanged. See [map recoloring](MAP-RECOLOR.md) for the corresponding captured-map path and deferred checks.

## Independent displayed CPU sources

[Scalar source handoff](../renderer-gl/SCALAR-RECOVERY.md) now admits an independent CPU input from a displayed scene/frame without copying geometry, volume or prepared colors. The input can outlive GL closure and feed consuming connected-view recolor requests. Keep a retained retry owner before consuming submission. Full-style upload recovery is separate from geometry-sharing recoloring, preserving the latest requested display colors. Runtime/GPU checks remain deferred.
