# Source precision checks before frame replacement

Renderer implementation, 2026-11-07. Unsupported world/scalar camera anchors and scalar sample spacing now reject before completed-frame replacement or new draw storage. Previously, the same checks could fail during drawing after a valid-looking navigation request had discarded earlier output. Compilation and source inspection are complete; renderer execution remains deferred.

## One complete world-origin proof

`WorldScene.sectionOriginsWithin(anchor)` uses the verified regular section partition to check every origin in constant metadata work. For each axis it checks the source minimum and the last origin `minimum + floor((size - 1) / sectionEdge) * sectionEdge`. Exact long subtraction must succeed and both differences must lie inclusively within `[-1048576, 1048576]`. Arithmetic overflow rejects. Source extents and the complete partition were already verified during scene planning/building.

The proof covers all sections, including empty and diagnostic-only sections. It reads no mesh, vertex, face, marker or atlas payload and allocates no source-sized array. Local geometry retains its existing separate coordinate limits. Complete-scene proof does not imply that every section is visible.

Transparent-order compatibility now delegates to this proof. Direct sorting applies it before key/depth allocation or section traversal, retaining defensive per-section checks, cancellation and existing structured coordinate failure. Canonical depth keys, exact normalized-direction reuse, original preparation receipts and sort budgets remain unchanged.

`GlCamera` shares the origin bound and exact-subtraction helper with scalar/world drawing. Offsets validate all coordinates and the complete destination span before writing any component. Failed coordinate or destination checks leave the destination unchanged.

## Scalar spacing and early rejection

`GlNoiseCulling.validateView` repeats the existing complete-source, display-scale and relative-origin policy using metadata alone. All three relative axes must fit. Horizontal spacing must satisfy the existing four-ULP condition:

`scale >= 4 * ulp(float(max(abs(offsetX) + width * scale, abs(offsetZ) + depth * scale)))`

Scale and offsets use the same float conversions as drawing, so exact equality remains accepted. No bin, row, sample spacing or requested extent is reduced to pass. Vertical origin bounds remain independently checked. Culling constructors use the same validation before their arrays, retaining the original actual draw decisions.

| Entry point | Rejection boundary |
| --- | --- |
| World frame queue | Before revision acceptance, invalidation and obsolete callbacks |
| Direct world frame | After its original initial obsolete callback, before source retention or target creation |
| Direct scalar frame | After its original initial obsolete callback, before source retention or target creation |
| Raw world/scalar draw | Before scratch admission; world range rejection also precedes automatic retirement collection |
| Connected world/scalar navigation | Before camera/dimension/revision mutation, ordering reset or release of untaken output |

Connected navigation checks only a complete current source. Pending, failed or explicitly invalidated sources retain their existing configuration flow and readiness guards; a cleanup-retained stale scene cannot establish readiness. Future source adoption can still fail if its range differs from the configured camera. Explicit upstream invalidation continues to abandon obsolete output, and unrelated admission or driver failure after acceptance keeps its existing failure protocol.

Invalid navigation leaves existing images, receipts, revisions, worker state and known memory admission unchanged. A corrected unchanged camera remains a no-op; the unspent queue token remains available for retry. Independently retained old frames keep their original immutable camera/source/style/atlas receipts. Metadata math and fixed camera arrays remain within the existing bounded bookkeeping policy; no primitive storage allowance is increased.

## Prepared verification and current status

Four new groups prepare irregular XYZ partitions; mesh, marker and empty scenes; independent exact-integer origin scans; boundary and overflowing anchors; metadata checks with inaccessible section payload; transparent-order agreement; atomic offset failure; scalar four-ULP exponent/equality boundaries; and all culling modes.

GPU cases prepare IDLE/WAITING/READY queue preservation, original obsolete callback ordering, raw-draw rejection without target mutation, connected worker/revision ownership, unchanged bindings/admission, earlier exact pixel/receipt delivery, corrected-request recovery and pending-source configuration. These are source-prepared cases, not execution evidence.

Both compilation sets pass: 144 full Java 25/LWJGL renderer/validation files and 106 dependency-free files. All 152 prepared groups remain unexecuted: 107 CPU/context-free and 45 GPU. No renderer harness, shader, GL context, native comparison or workflow ran. Renderer completion, genuine Windows/native/GPU validation, fault injection, total-process memory/performance measurement and final editor integration remain pending. Unsupported previews remain disabled.
