# Prepared world material counts

Renderer implementation, 2026-11-04. World mesh and scene preparation now retain verified translucent and tint-required face totals. GPU upload validation consumes section metadata, and camera-sort admission uses the complete translucent total. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Code inspection and compilation only: worker/renderer execution, native comparisons, failure injection and real-driver profiling remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Native visibility to verified scene totals

`WorldMesh.Usage.tintedFaces()` counts surviving emitted faces whose original prototype quad declares a nonnegative tint index. `translucentFaces()` retains its existing alpha-class meaning. A face can belong to both categories; these are independent counts, not an exclusive partition. Hidden faces, explicit empty visuals and diagnostic markers do not contribute. A native tint index still requires native color input even when its eventual RGB happens to be white.

The count-only section pass records these totals with its exact source/visibility/extent and output admission. Emission counts them independently while traversing original surviving faces and verifies both before publication. Scene planning sums both across every section into `WorldScenePlan.Usage.translucentFaces()` and `tintedFaces()`. Complete scene construction verifies its actual emitted/cached section totals against those aggregate receipts, alongside full coverage and existing payload/count checks.

| Receipt | Producer verification | Consumer |
| --- | --- | --- |
| Section tint-required count | Count pass versus emitted surviving faces | GPU upload missing-tint guard |
| Section translucent count | Count pass versus emitted translucent entries | GPU section validation |
| Complete scene material totals | Complete plan versus emitted/cached sections | GPU aggregate validation and camera sorting |

Usage constructors reject negative counts or a count above the total faces. Previous nine/ten-argument mesh-usage and seven-argument scene-usage constructors remain available, supplying zero for added fields. Successful scene factories use the complete new receipts; callers cannot inject a manually constructed usage record into a published scene. Native IDs/states, atlas/material selection, face/index/UV order, placement and picking provenance remain intact.

The new counters are bounded object metadata. They create no face-sized primitive arrays and do not change packed geometry, prepared centers/bounds, primitive payload totals, preview defaults or existing memory ceilings. Cache matching continues to compare complete section usage against its exact admitted plan.

## GPU validation by section

`GlUpload` validates one successful section receipt or atlas page per validation work unit. A section must still share the exact successful raster epoch. Any section with tinted faces requires a successful exact-scene tint input; original `WorldTint` capture/input validation and native lighting successful-capture/identity checks remain in force. This replaces the former face-by-face validation scan that only counted transparency and looked for required tint inputs.

Before GPU scene storage admission, upload verifies summed face, translucent, tint-required and marker-byte totals against the complete CPU scene. Missing colors or mismatched receipts fail before texture/buffer storage is created. Geometry/marker object counts still come from actual section contents, and hardware/row-staging checks still inspect every atlas page.

`Progress.validatedFaces` now advances by the number of faces covered by a validated section. It describes metadata coverage rather than individual validation iterations. `Step.workUnits` counts each bounded section/page check, and existing action/transfer/time ceilings remain unchanged. Actual vertex/index/material/lighting transfers still stream all required source data under those ceilings; this change does not remove the upload itself or establish a measured frame-time gain.

## Camera-sort reservation

`WorldDrawOrder.WORKING_BYTES_PER_FACE` is now 16: one long key array and one double depth array, using [bounded in-place ordering](BOUNDED-FACE-SORT.md). A complete scene now supplies its translucent count directly, removing the extra section-count pass before allocation. Depth preparation still checks every section's exact anchor/precision, visits the prepared translucent entries only, verifies their aggregate coverage, and uses the same stable global depth/key ordering.

For a valid request, `WorldOrderWorker` reserves complete source payload plus `translucentFaces * 16` primitive bytes. The configured sort-byte and combined working ceilings still cap the reservation; requests that cannot actually fit report their existing structured limit before arrays are allocated. Source-only overflow still rejects submission before changing worker state. No synchronous face scan is introduced during admission.

At the default face limit of 262,144, actual scratch requires 8 MiB, so a valid scene at that count no longer reserves the whole configured 32 MiB scratch ceiling. A zero-translucent direct request reserves its source only. The 1,048,576-face hard maximum still needs the existing full 32 MiB. This reduces conservative reservation slack without increasing any ceiling or reducing array coverage.

Active plus pending local/shared admission uses the same computed demand. Rejection preserves prior revisions/work, accepted supersession keeps cancelled active stacks charged, and untaken scene owners retain conservative replacement overlap. Successful unwind still shrinks to source plus the final eight-byte key per face; taken completions and GPU handoff keep their established independent ownership. Java array/object overhead remains outside primitive payload accounting.

## Deferred verification

At renderer completion, independently scan emitted faces and compare section/scene counts for opaque, cutout, translucent, tinted/untinted and overlapping tinted-translucent geometry. Include hidden tint-required faces, zero/nonzero tint indices, explicit empty/placeholder sections, native offsets/fluid layers, cached reuse and maximum full-height coverage. Exercise invalid legacy/new receipts, count/emission disagreement and complete-scene aggregate mismatch without partial publication.

Verify missing/wrong-scene/incomplete tint and lighting rejection before GPU storage, prepared native palette colors, section/page validation progress with one work unit, exact geometry/storage/transfer totals and unchanged original pixels/picking. Compare sort results and array coverage against exhaustive counts, including zero/default/hard count boundaries, ties, distant anchors and cancellation.

Check exact-source-plus-scratch shared/local admission, cases that fit actual demand but not the former slack, lowered face/sort/working ceilings, rejected replacement preserving state, cancellation/READY-before-unwind ownership and final retained source/order charges. Inject setup/publication/cleanup failures and profile render-thread validation separately from actual transfers on Windows before claiming a performance gain. Native/GPU fidelity and final editor integration remain pending; no renderer-stage tests ran for this batch.
