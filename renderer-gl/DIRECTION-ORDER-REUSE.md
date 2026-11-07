# Translation-stable transparent ordering

Renderer implementation with compilation only. Both compilation sets pass with Java 25 and LWJGL 3.4.1: 144 full renderer/validation files and 106 dependency-free files. Five additional prepared groups bring the total to 134: 99 CPU/context-free and 35 GPU. All remain unexecuted until renderer implementation is complete. No shaders, GL contexts, renderer harnesses, native adapters or Windows validation ran. Native/GPU correctness, timings, total application memory and final editor integration remain pending; unsupported previews stay disabled.

## Canonical depth keys

World transparent ordering now projects each prepared original face centroid from the complete source extent's exact integer origin onto the normalized camera-forward direction. The projection excludes the camera eye translation and camera anchor:

`depth = (sectionOffsetFromSource + originalLocalCentroid) dot normalizedForward`

Larger depth still sorts first, followed by the smaller original section/face key for numeric ties. Section-origin subtraction happens as exact integers before conversion to double. No absolute large-world floating coordinates are introduced. Per-section canonical offsets are calculated once, and preparation continues reading compact translucent centroids without rescanning opaque faces or vertex buffers.

Mathematically, this removes one constant shared by every camera-relative depth. It makes the actual floating-point key expression identical for all views with the same normalized forward components, regardless of eye/target translation or anchor rebasing. Dolly movements can also qualify when they produce exactly the same normalized components. There is no angular tolerance: a differing normalized component requires a fresh sort. Normalized signed zeros compare equal, consistently with the sort's existing numeric-zero tie policy.

This intentionally changes the old floating expression. Very close or formerly rounded-equal depths can have a different deterministic order from the previous eye-relative calculation. The renderer still uses approximate global centroid ordering; it does not solve intersecting/cyclic transparency or establish native OIT/pixel parity. Fresh and reused orders now share the same canonical expression.

## Constant-size compatibility proof

`WorldDrawOrder.compatibleViews(scene, original, candidate)` requires:

- Finite direction lengths at least 1e-8 under the existing hypot calculation.
- Exact numeric equality of all three normalized forward components.
- Valid relative section-origin ranges for both views, including exact integer anchor subtraction without overflow.

The range proof reads the complete scene extent and its admitted section edge. For each axis, the first origin is the extent minimum and the final origin is `minimum + floor((size - 1) / sectionEdge) * sectionEdge`. Every emitted section origin lies between those extrema. Checking both endpoints therefore preserves the existing ±1,048,576 origin-offset bound without scanning sections on the context/UI thread. Empty sections participate in the same proof.

Ordinary preparation still independently checks every section against the admitted preparation view, including opaque-only sections, and retains its existing structured failure, source/count/byte checks and cancellation points. Camera matrix/spacing/offset checks during drawing remain active. The compatibility helper cannot make an invalid camera or source drawable.

`WorldDrawOrder.matches(candidate)` applies that proof only to complete orders. The original `view()` remains the immutable receipt of the pose actually submitted for preparation. Its keys, source scene, statistics and byte receipt are unchanged by reuse. Failed orders cannot provide a usable proof.

## GPU and connected view behavior

`GlDrawPlan` now accepts a direction/range-compatible camera while retaining its exact device, GPU scene and CPU scene identity requirements. Frame validation still checks this before target admission; direct drawing checks it before scratch admission or target modification. Complete images retain their actual current camera, clip, raster and source receipts rather than substituting the order's earlier preparation view.

`GlWorldView.view` preserves a complete plan or an already admitted pending sort when its proof remains valid. A pending completion is adopted only after checking exact CPU scene identity and compatibility with the latest camera. View/frame revisions still advance, and a resulting frame uses the latest camera/viewport. Repeated compatible camera gestures do not cancel and resubmit the same canonical sort.

The retained material-refresh slot carries its own CPU order proof while the public scene wrapper is absent. Compatible translations can therefore survive material upload/rebinding as well as ordinary redraws. Destination-plan admission still happens before the old journal is released. Direction/range changes, complete world replacement, invalidation and shutdown retain normal sort/owner cleanup. Failed worker requests do not become reusable complete orders; a subsequent changed view or explicit redraw can reset/retry preparation.

| Display change | Transparent order behavior |
| --- | --- |
| Eye/target translation with identical normalized forward and valid ranges | Reuse complete or pending order |
| Exact-direction dolly or anchor rebase | Reuse when the same proof holds |
| Viewport, projection or clipping changes | Preserve a compatible order; redraw with actual settings |
| Any normalized forward-component difference or relative-range failure | Invalidate the retained order/pending request |
| Full source/world replacement | Use ordinary new-source ordering |
| Exact-geometry material replacement | Rebind compatible complete order under independent admission |

## Admission and ownership

Sort preparation retains its two-array 16-byte-per-face working payload and eight-byte-per-face final key payload. Existing source, face, scratch, combined working and shared/worker ceilings remain unchanged. Compatibility reads constant-size metadata and allocates no face-sized arrays, GPU names or new source/admission owners. Existing fixed object/math bookkeeping exclusions remain in force.

Pending jobs retain their originally admitted source/arrays until normal completion or cancellation unwind. Complete plans keep their existing source/device dependencies through redraw and material refresh. Reuse does not release source charges or create an uncharged handoff. Caller-held completed frames remain independently valid through later camera changes and view shutdown. No wall-clock or total-process-memory improvement is claimed.

## Prepared deferred coverage

Three new CPU groups prepare independent emitted-vertex centroid oracles, multiple sections with holes and shifted placement, six directions, repeated exact-direction pans/dollies/rebases, identical fresh key/work receipts, large finite eye translations and coplanar depths, one-ULP normalized-direction changes, reversals, tiny directions, exact section-origin range boundaries and overflowing anchors. The metadata range proof is compared with a complete section scan. Exact/one-byte-short sort admission, empty orders, worker completion reuse and retained source/hold cleanup are also prepared.

Two new GPU groups prepare separate fresh/reused immutable frames with differential pixels and identical submission receipts; wrong direction/range preflight before admission/target writes; a deterministically gated pending sort across several translations; immediate complete-order redraws; translated material refresh while the public scene is absent; fresh sorting after direction change; actual latest-camera receipts and old-frame survival through shutdown. The scheduling gate reads the existing private executor only and does not mutate worker state.

All 134 prepared groups remain unexecuted. Production inventory remains 138 files: 94 CPU and 44 GL, plus five harnesses and one fixture. No PowerShell/Gradle task or renderer/native/GPU execution ran for this batch.

[Shared section-origin precision](PRECISION-PREFLIGHT.md) now exposes the complete partition proof on `WorldScene` for order reuse, direct sorting and GPU preflight. Direct sorting rejects unsupported anchors before key/depth arrays or section traversal, retaining its defensive per-section checks. Existing canonical depth keys, exact normalized-direction equality, receipts and budgets are unchanged. All 152 prepared renderer groups remain unexecuted.
