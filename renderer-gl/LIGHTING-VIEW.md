# Lighting samples and view submission

Renderer implementation, 2026-11-03. All 78 main Java sources compile with Java 25 and pinned LWJGL 3.4.1: 57 CPU and 21 OpenGL sources. No renderer tests, GL context, shader compilation, framebuffer comparison or visible Windows QA have run. Tests remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Simple lighting and optional native samples

The initial specification requires simple lighting. Scenes without lighting samples retain the existing normal-based directional light and ambient floor; no native light/AO calculation is required to use this backend mode. The optional sample path accepts externally resolved native modulation, without introducing a Minecraft runtime, client-class execution, light simulation, advanced shadows or a new preview-readiness gate.

`WorldLighting.capture(scene, nativePolicy, sectionVertexRgb, cancelled)` detaches one float array per section. Each emitted face has four RGB triples in original vertex order, so the length is `faces * 12`. The caller freezes the source list and arrays throughout capture; the result owns private copies. The producer label must be nonblank and at most 256 characters. It identifies the supplied policy, not proof of native fidelity or an independently verified environment receipt.

Values are final sample-domain RGB multipliers in `[0,1]`, excluding the texture sample and biome tint. The producer resolves any desired native AO, directional shade, block/sky lighting, model emission and environment state before capture. The backend does not infer these from emission/tint metadata or multiply native results by its fallback light. White means full modulation, black means no RGB contribution. Nonfinite, negative and HDR values are rejected, never clamped or tone-mapped into this RGBA8 path. This is a multiplication contract, not a claim of native color-space or lightmap parity.

All section lengths and primitive budgets are checked before output sample allocation. Wrong counts, invalid samples or exceeded limits produce structured problems with no partial lighting. Invalid values identify their original world face position. Cancellation propagates separately, including between sections and through large face arrays. Captures retain the exact CPU scene; public section copies are independent.

Samples cost 48 bytes per face, under a configurable hard ceiling of 96 MiB. Scene primitive payload plus captured output must fit the configured CPU preparation ceiling, at most 512 MiB. This preparation ceiling excludes caller-owned source arrays, tint captures, other jobs, caches and object overhead. Application scheduling still needs combined admission.

Pass the optional capture through `GlUpload.begin(renderer, cpuOwner, tint, lighting)`, `queue.submit(revision, cpuOwner, tint, lighting)` or `GlScene.upload(renderer, cpuOwner, tint, lighting, cancelled)`. Tint can be null for entirely untinted scenes. Failed lighting produces a material failure; a different exact CPU scene produces a stale-input failure. Fresh CPU lease consumption and already-owned duplicate protection follow the existing upload contract. Sample arrays are retained and charged with tint and the CPU lease through scene retirement; paused uploads cannot release their charge early. A failed preflight has no scene GL storage to retire.

| GPU geometry mode | Vertex stride | Quad vertices + indices | Additional GPU bytes over the CPU quad |
| --- | --- | --- | --- |
| Simple lighting, optional tint | 36 bytes | 168 bytes | 16 bytes |
| Supplied RGB lighting, optional tint | 48 bytes | 216 bytes | 64 bytes |

The unchanged CPU layout has eight floats per vertex and six indices per face. GPU attribute four stores RGBA8 tint; attribute five stores RGB32F lighting only in the supplied-sample layout. The same bounded staging buffer streams whole four-byte words, so a yield may split an RGB triple or vertex. Storage admission and transfer totals include the selected stride before scene GL names are created. No extra full-mesh packed array is allocated.

`scene.hasNativeLighting()` reports whether an adopted GPU scene contains the optional sample stream, while that scene remains live. The renderer sets its owned program's mode for every draw, including when alternating simple/sample scenes with the same renderer. Native multipliers are interpolated per vertex and combined with texture and tint RGB in all material passes; texture alpha and translucent ordering remain independent. Animation frames reuse the stream. Changed environment lighting requires a new scene upload; no in-place lighting mutation or automatic clock is introduced.

## Conservative section frustum culling

GPU section descriptors now retain the CPU mesh's local AABB. Those bounds already include emitted geometry and unit-cell diagnostic markers, including model overhangs outside the nominal section extent. Empty sections have no drawable bounds. Culling never substitutes nominal voxel extents for actual geometry.

Before invalidating the target, drawing prepares the same float view-projection matrix submitted to the GPU and exact-anchor section offsets. Six clip planes are extracted from that matrix. A section is rejected only when the AABB support point is outside one plane beyond an outward floating-point tolerance. Plane evaluation uses double precision, with a magnitude-scaled margin to allow float coordinate addition and dot-product error. Degenerate planes do not force division by a near-zero normal. This is a conservative implementation awaiting boundary/native validation; it can keep extra sections visible.

All solid/cutout, translucent and marker passes use the same visibility table. Skipping an entire offscreen section preserves the relative order of the remaining translucent faces. Every scene still needs its complete successful camera-bound translucent order; culling does not permit missing or stale plans. Every section offset must still pass the existing exact-coordinate and float-precision limits. Culling does not hide unsupported coordinate ranges, truncate generation height, change state/biome data, evict CPU meshes or release GPU charges. It reduces submissions for the current view only.

The camera now supports exact top/bottom directions. Near the vertical poles it uses Z as the reference up vector to produce a finite orthonormal basis; elsewhere it uses Y. This is a deterministic pole convention, not a complete interactive orbit/roll controller. Coincident eye/target directions and existing clipping/precision errors remain explicit failures before target invalidation.

## Order-preserving batches and submission counts

Translucent faces can share one indexed draw only when they are already consecutive in the global sorted list, belong to the same section/page, and have consecutive original face indices in increasing order. No grouping by page or section is allowed to reorder transparent geometry. Section uniforms/VAOs and texture pages are rebound only when the corresponding value changes. The original source indices and picking provenance remain unchanged. Worst-case alternating geometry still needs one draw per visible face; centroid sorting retains its documented intersecting-surface limitations.

The existing `render` overloads use the same implementation. `renderFrame(scene, target, camera)` also returns immutable `FrameStats`; the full `renderFrame(scene, atlas, plan, target, camera)` overload accepts null atlas/plan for the original raster/no order, with translucent guards unchanged. Statistics contain total/empty/visible/culled section counts, solid and translucent draw calls, submitted translucent faces, marker draw/instance counts and triangle index count. They describe actual submissions after culling/batching, not pixel visibility, GPU completion time or native fidelity. Marker line indices are excluded from the triangle count.

Counts are returned only after successful draw and host-state restoration. Missing/stale inputs, coordinate/matrix failures and culling preparation failures occur before clearing the prior complete target. Failures after drawing starts keep the target incomplete under the existing contract. No last-frame CPU scene is retained just to expose statistics.

## Deferred end-of-stage verification

Verify detached sample mutation/copies, exact scene identity, all section/face/vertex/channel boundaries, zero/full RGB, nonfinite/HDR failures, cancellation, placeholder atomicity and every sample/working budget. Compare supplied values with pinned-native captures before claiming native AO, emission or color parity. Exercise both strides and every color/lighting word split, exact upload totals, late failures, fence-held CPU charges, duplicate owners and queue replacement.

At renderer completion, compare culling enabled by this implementation against unculled reference submissions/pixels for all six clip planes, plane-touching boxes, rotated/overhanging geometry, marker-only and empty sections, extreme FOV/aspect, near/far ratios, distant integer anchors and exact/near vertical views. Confirm no visible geometry or markers disappear. Test camera changes before draw-plan adoption and previous-target preservation on rejected input.

Compare batched and individual transparent submissions across section/page changes, reversed source order, gaps, depth ties and alternating pages. Check alpha/depth output, primitive order and all reported counts, including zero visible draws. Alternate simple/native-sample scenes and static/animated rasters under hostile host state. Profile sorting, culling, batching and driver calls on actual Windows hardware. Java compilation establishes neither GLSL validity nor runtime performance/correctness.
