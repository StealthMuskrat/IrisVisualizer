# Greedy scalar boundary rectangles

Renderer implementation, 2026-11-04. All 119 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (83 CPU, 36 GL). Sixteen native adapters remain syntax-parsed only; genuine Windows compilation is pending. No surface/query/worker execution, renderer tests, native sampling, GL context, shader compilation, pixels or workflows ran. Unsupported previews stay disabled.

## Scope and compatibility

`NoiseSurface.Style` now includes `Meshing.GREEDY` or `CELLS`. Its existing three-argument constructor selects greedy meshing; the explicit four-argument constructor can request the original unit-face representation. Style and frame receipts retain this mode, so a transferred image and its inspection surface keep the same representation through replacement.

Only outward, coplanar scalar display-bin faces with the same direction and exactly equal original finite double bits can merge. Different values remain separate even when the color ramp maps them to identical RGBA. Positive and negative zero remain distinct. Adjacent selected samples still suppress their shared interior face. Absent/excluded neighbors remain display boundaries under the existing scalar selection policy.

The full captured volume, presence flags, actual native samples, configured Y range, sampling coordinates and source UUID remain unchanged. This is a display optimization for compatible scalar bins, including native Y-invariant data. Minecraft textured meshes keep their existing face representation, material UVs and original cell provenance; the scalar path assigns no synthetic block or biome.

## Deterministic bounded preparation

A full source scan finds selected sample count and display bounds. An empty selection produces zero rectangles without plane scratch. Nonempty preparation processes directions in DOWN/UP/NORTH/SOUTH/WEST/EAST order, planes in increasing normal coordinate, then rows and columns in increasing tangent coordinates. It greedily extends width before complete matching rows. The resulting rectangles partition the original visible unit faces without overlaps or holes introduced by merging.

One integer plane mask is reused across all directions and both count/emission passes. Its dimensions cover the selected bounds after the full scan, so sparse selections avoid allocating full-grid planes. Every boundary decision still consults the immutable original volume, including neighbors outside the selected bounds. The count pass admits each prospective rectangle plus source/scratch payload; exact-sized output arrays are allocated only after complete counting. Emission repeats the same traversal and checks rectangle and unit-face totals. Cancellation is checked during source scanning, mask filling, rectangle growth/clearing, before arrays and before publication. No partial arrays or bounds escape failure.

| Direction pair | Tangent U | Tangent V | Size XYZ in sample bins |
| --- | --- | --- | --- |
| DOWN / UP | X | Z | `(spanU, 1, spanV)` |
| NORTH / SOUTH | X | Y | `(spanU, spanV, 1)` |
| WEST / EAST | Z | Y | `(1, spanV, spanU)` |

Each rectangle stores its original anchor sample index, direction byte and one span word. U/V lengths are encoded minus one in two 12-bit fields, covering 1..4,096 under existing volume dimensions. Unit mode omits the span array and supplies implicit lengths one. No intermediate expanded vertex/index arrays or full-volume selection mask is retained.

`cell(face)` identifies a rectangle's anchor. `spanU/spanV`, `sizeX/sizeY/sizeZ` and `sample(face,u,v)` expose its geometry and exact original sample mapping. `face(face)` describes the anchor; `face(face,u,v)` describes that original covered sample. `Usage.faces` counts emitted rectangles, `unitFaces` counts the unit boundaries represented, `scratchBytes` reports the actual plane allocation, and `knownPeakBytes` includes source, output and scratch. These are representation counts, not measured benchmarks.

## GPU upload and source-preserving inspection

The scalar uploader now uses twelve bytes per instance: original anchor/direction, RGBA8 and packed U/V extents. Whole instances fit within the existing bounded staging and transfer/action/work ceilings. Remaining bytes smaller than one instance wait for the next step. Storage accounting, byte offsets, progress and integer attribute stride all use the same 12-byte constant. The shader expands its existing outward-wound corners by the direction-specific extent, preserving original horizontal scale and one-unit Y bins. Simple lighting and opaque depth/cull behavior remain unchanged. Statistics count submitted rectangles and their triangles.

`NoisePick` intersects these complete rectangles, then locates the original sample using bounded binary searches of the actual grid edges (at most twelve comparisons per tangent). A hit returns that sample's original coordinate/value and sample-local point, rather than the rectangle anchor. Inclusive shared edges choose the lower original sample; equal-distance hits compare original sample index then direction, independent of rectangle traversal. Unit mode uses the same tie policy. The complete emitted-face query allowance and cancellation checks still reject partial nearest results.

Camera fit and bounds remain those of selected display bins. Completed frames and readback retain exact style/mode/volume receipts. Native sampling and generation are never called by meshing, navigation or picking.

## Memory and deferred validation

| Payload | Bound/accounting |
| --- | --- |
| Greedy output | Thirteen primitive bytes per emitted rectangle, including prepared RGBA |
| Unit output | Nine primitive bytes per unit face, including prepared RGBA |
| Face-range bounds | 24 bytes per 1,024-face original-order range; at most 24 KiB |
| Plane scratch | Four bytes times the largest selected-bounds plane; at most 64 MiB |
| GPU instances | Twelve bytes per emitted rectangle in either mode |
| Output count | Existing 262,144 default / 1,048,576 hard primitive limit |
| Working peak | Original source + actual plane scratch + prospective exact output, within the existing 512 MiB ceiling |

[Scalar range culling](SCALAR-CULLING.md) now adds bounded integer face-plane bounds for GPU frustum rejection and CPU ray pruning without changing original face order. Worker admission reserves the full-grid scratch upper bound plus configured output and range-bound maximum, clamped to its working ceiling. Actual preparation preflights selected-bounds scratch and checks every output growth against that ceiling. READY output shrinks to retained source/mesh bytes after stack unwind; no mask escapes. Active cancellation stays charged until unwind, and pending/completed replacement follows existing shared ownership. A fragmented selection may fail a count/byte bound; no sample, height, face or value is silently discarded. Explicit unit mode remains available when its representation better fits a caller's admitted working allowance.

At renderer completion, expand greedy rectangles back into unit-face keys and compare to `CELLS` mode for all directions, uniform boxes, nonzero selected bounds, thin/maximum dimensions, stripes, holes, absent samples, equal-color different-value fields and signed zero. Compare exact selected/represented counts, bounds, deterministic order and original mapping. Exercise zero/exact/one-over count, scratch and output budgets, source-only allowances, empty selections, cancellation before each allocation and two-pass consistency.

Compare ray hits across both modes for face interiors, internal grid boundaries, shared rectangle edges/corners, fractional scale, negative world origins, near/far intervals, parallel/back faces and equal-distance ties. Verify original point/value/local coordinates, query limits and immutable frame receipts. End-stage Windows checks must compile shaders and compare raster/depth/culling, upload slicing/stride/progress, source ownership, failed admission, cancellation, retirement and readback. Runtime/performance and native/GPU comparison remain unverified.
