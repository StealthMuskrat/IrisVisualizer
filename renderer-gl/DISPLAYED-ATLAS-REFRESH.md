# Displayed atlas preservation during material refresh

Renderer implementation, 2026-11-06. Compilation/source inspection only. Renderer contracts, real GL copying, native comparison and final editor integration remain deferred. Unsupported previews remain disabled.

## Copy the complete displayed snapshot

`GlUpload.materials(renderer, source, atlas, tint, lighting)` and the corresponding `materialsLit` overload now accept an optional complete `GlAtlasFrame`. The queue exposes the same optional-atlas overloads. Existing signatures remain available. The supplied frame must belong to the exact live GPU source scene; equivalent CPU geometry, matching layout or equal pixels do not authorize a foreign GPU atlas. Closed/stale frames reject before replacement.

The upload retains the GPU scene and displayed atlas through the last queued copy. An internal frame owner is rebound only to the retained wrapper of that same allocation, so callers can close their original scene/frame wrappers after successful admission. Geometry and page copying use the internally owned handles. Source pages remain read-only, and the completed destination owns full independent GPU storage without scene/page ancestry chains.

`GlWorldView.materials` and `materialsLit` now offer the animation coordinator's current complete displayed frame. They preserve its exact pixels and clock as the replacement's initial atlas. When it already satisfies the latest requested clock, the new coordinator needs no CPU atlas plan or second animation GPU upload. If the requested clock has advanced during material copying, the existing initial-atlas guard waits for its needed update. Normal later clock updates retain their existing asynchronous behavior and budgets.

## Exact GPU-scene base receipts

`GlScene.raster()` now borrows the exact immutable atlas metadata represented by that scene's own textures. For an ordinary full upload this is the original CPU raster. For a displayed-atlas refresh it is the copied displayed raster, including canonical samples, tick and partial tick. The original `cpuScene().raster()` remains unchanged, preserving original generated/model/asset provenance.

`GlAtlasFrame.capture` and frames rendered without an explicit atlas now use the GPU scene's actual base raster. This keeps subsequent animation preparation, readback receipts and CPU pixel/alpha inspection consistent with the copied textures. A later material refresh with no explicit atlas inherits that scene's already copied base rather than returning to the original CPU raster. Repeated refreshes preserve the snapshot while keeping independent GPU storage.

## CPU payload and resource ownership

The destination journal reserves the original complete CPU scene, replacement tint/final lighting and each retained atlas page whose image object differs from the original CPU raster. This accounts for changed CPU pixels even after the old animated-page owner retires. Unchanged pages add no second image payload within the destination. Changed-page accounting uses immutable identity/size metadata, with no pixel scan, decoding, native sampling or CPU page reconstruction.

The actual raster remains in the destination journal through GPU retirement. Scene wrappers clear their borrowed raster reference on close, including when an internal source wrapper remains live. Displayed copying also needs one temporary zero-payload atlas-owner journal; device allocation limits still apply. Source/destination/retired storage overlaps conservatively, including changed CPU pages. No byte/name/allocation ceiling is raised or credited as free merely because inputs share Java objects.

Cleanup visits the temporary framebuffer, destination, retained atlas and retained scene, preserving primary/suppressed errors. Final source/page owners retire through their existing fences after queued reads. No source owner is held by the completed replacement. Existing owned-lighting consumption and queue/view initial-admission preservation remain intact.

## Prepared evidence

Two new GPU groups prepare scheduled/interpolated snapshot copying, exact changed-page CPU admission, closure of caller source/frame during upload, captured/default-frame clock receipts, unchanged original CPU raster, repeated refresh inheritance, no GPU ancestry, wrong GPU atlas despite equal CPU identity, one-byte-short overlapping admission, owned input consumption, cancellation and subsequent queue adoption. The connected initial-clock group now covers raw/owned refresh and requires an idle animation upload queue afterward, demonstrating that the displayed atlas was carried forward rather than rebuilt. All assertions remain unexecuted.

All 137 production sources, five harnesses and one fixture compile with Java 25/LWJGL (143 total). The unchanged dependency-free set retains its 105-file compile evidence. All 102 prepared groups remain unexecuted: 76 CPU/context-free and 26 GPU. Real pixel/alpha equivalence, failure/retirement injection, rapid clock changes during copying, driver behavior, performance/total application memory and final editor adoption remain pending.
