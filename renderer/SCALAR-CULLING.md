# Scalar surface batches and conservative culling

Renderer implementation, 2026-11-04. All 126 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (85 CPU, 41 OpenGL). Sixteen native adapters pass syntax parsing only; genuine pinned Windows native dependency compilation remains pending. No mesher/query runtime tests, GL contexts, shader execution, pixel comparisons or workflows ran. Unsupported previews remain disabled.

## Original-order batch bounds

`NoiseSurface` now indexes consecutive ranges of at most 1,024 emitted faces in both CELLS and GREEDY modes. No face is reordered, subdivided or removed. These original ranges retain their spatial bounds; a group spanning distant faces can have loose bounds and remain visible. [Balanced internal unions](SCALAR-RANGE-HIERARCHY.md) now accelerate CPU inspection while GPU drawing keeps its original group culling. Existing greedy rectangle coverage, original scalar bits, selection thresholds, colors and source sample/direction tie policy remain intact.

Each range stores six integer grid-space extrema in a private primitive array. Bounds enclose the actual emitted face planes and all tangent extents, including full greedy U/V spans. Positive-side faces use their upper grid plane. A single planar batch can therefore have zero thickness on its normal axis. First face and count derive from range index and complete mesh size; no separate index/permutation array is created.

Preparation builds the bounds after face emission and before publication, with cancellation checks per range and every 256 faces. Existing complete count/emission checks remain in force. Empty and failed surfaces have no bounds entries. `batchCount`, `batchFirst`, `batchSize`, `batchMinimum`/`batchMaximum` and `batchBounds` expose checked immutable range metadata. Source volume, original face indices and picking identity remain unchanged.

## Admission

Bounds cost 24 primitive bytes per nonempty range: at most 24 KiB for 1,048,576 faces. This payload is included in `Usage.meshBytes`, `NoiseSurface.payloadBytes` and the worker's prospective reservation. Unit and greedy count passes admit each prospective face count together with its required ranges before output allocation. Greedy peak admission still includes its live plane mask while bounds are built. Retained output drops plane scratch after preparation and includes the range array.

The existing CPU working, face, GPU storage and shared memory ceilings are unchanged. Upload/source journals automatically charge the larger CPU surface through `payloadBytes`. GPU face instances remain twelve bytes each, with identical buffer size, format, transfer progress and primitive geometry. Range metadata adds no GPU buffer, staging expansion, source sample array or native evaluation. No data is dropped or reduced when admission fails.

## GPU frustum rejection

`GlNoiseRenderer` now checks every range against the exact float camera matrix submitted to the shader. It converts integer bounds using the shader's float horizontal scale and float anchor addition, then uses the shared frustum plane test with its outward roundoff margin. Only a complete range outside a plane is rejected. Near-plane intersections, planar ranges and uncertain edge cases remain conservatively included. [Geometric scalar clipping](SCALAR-CLIPPING.md) now adds a second conservative whole-range rejection before the shader applies exact clip planes. The existing world-section frustum overload delegates to the same plane test without changing its bounds arithmetic.

Consecutive visible ranges coalesce into one instanced draw. An all-visible surface still needs one draw; separated visible runs use bounded additional calls. Each run points the owned VAO's integer instance attribute at `firstFace * 12` in the original buffer, then uses ordinary OpenGL 3.3 instanced drawing. No base-instance extension or instance-data copy is required. The owned VAO and array-buffer bindings are selected before layout changes; existing state guards restore host bindings and state on success/failure.

The shader, lighting, winding, face colors, complete source height and GPU instance data are unchanged. Fully culled/empty output still publishes a complete background image after successful clearing/restoration. Camera/precision/frustum validation precedes target invalidation. Culling does not free retained source/GPU storage or alter caller-owned old frames.

`GlNoiseRenderer.Stats` now reports total/visible/culled ranges and culled faces alongside actual draw calls, submitted faces and triangles. The earlier three-argument constructor remains available. Submitted plus culled face counts cover the complete surface; statistics describe submission, not surviving fragments. Completed scalar frame/readback receipts preserve these statistics with their original camera and volume identity.

## Original-value picking

`NoisePick` uses the same range bounds before testing individual face planes. Its slab test expands spatial bounds using bound/origin precision and interval rounding outward, handles parallel directions and limits rejection to the current near/nearest interval. An optional frame-bound clip intersects those range slabs and filters candidate hit points. Face intersection, inclusive sample-edge lookup and original sample/direction tie ordering remain unchanged; no sorted traversal or replacement scalar is introduced.

The query still checks the complete surface against `maximumFaces` before searching, even for an eventual miss. Bounds are an optimization, never permission to return a partial nearest result under a smaller allowance. Hard limits remain 1,048,576 faces and at most 1,024 range tests. Cancellation is checked per range and within visited face runs. `Result.visitedFaces` now counts actual face tests, with new tested/culled range counters. The previous result constructor remains available. Hits remain detached original UUID/sample/coordinate/value records and retain no range/source arrays.

## Deferred stage verification

At renderer completion, expand every range and verify its exact face coverage and integer extrema for all six directions, CELLS/GREEDY modes, large spans, mixed directions, empty meshes and 1,023/1,024/1,025 boundary counts. Compare payload/reservation/usage and count/emission results at every byte limit, including live greedy scratch, cancellation and no partial publication.

Compare batched and unculled reference drawing on the real Windows GL path: all-visible, all-hidden, alternating visible runs, partial last ranges, near/far/side planes, camera-inside scenes, fractional scales, negative/distant anchors and every precision boundary. Verify original instance offsets, exact face/triangle totals, run coalescing, unchanged winding/depth/colors, hostile host VAO/array-buffer state and draw/restoration failures. Loose ranges are an expected performance limitation, not an omitted feature or correctness shortcut.

Compare optimized queries with exhaustive original-order face traversal for random rays, parallel axes, negative zero directions, planar bounds, tangent/shared edges and corners, exact near/far ties, different source scales and greedy sample ties. Test work counters, full-face budget rejection, cancellation, retained old frames and readback receipts. Runtime correctness/performance, native sample comparisons and final editor integration remain pending.

## Shared internal bounds

[Scalar inspection hierarchies](SCALAR-RANGE-HIERARCHY.md) add six integer extrema per internal group node, with no face permutation or leaf copy. Empty/one-group surfaces need no internal payload. Count/emission peaks, mesh receipts and source owners now include these arrays; recoloring shares them with exact geometry identity. CPU picking can traverse trees or choose independent linear/exhaustive modes, and its explicit visited-face policy retains no partial hit after exhaustion. GPU draw culling and buffer packing keep their existing contracts. Full/default compilation passes (143/105 files); all 118 groups remain unexecuted.
