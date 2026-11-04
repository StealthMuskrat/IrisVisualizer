# CPU block geometry

Implemented during the renderer stage; compilation only. No renderer runtime or GPU tests have been executed, and no user-selected client model has been compared to the pinned client baker yet. Unsupported previews remain disabled.

## Inputs and output

`GeometryBaker.bake(ModelPlan, int[] nativeTickets, BooleanSupplier cancelled)` accepts a resolved model plan and one ticket per selected dispatch part. Tickets are checked against each part's weight range and copied before baking. The caller is responsible for a verified native client choice/seeding policy; this module does not invent one.

Successful `BlockMesh` results retain the exact `RenderBlockState` and captured client receipt, selected tickets/models/rotations, original dispatch case indices/pointers, quad source pointers, local bounds and an immutable `MeshPacket`. A failed bake has explicit structured problems and no selections, quads, bounds or packet. Cancellation propagates separately. Allocation failure unwinds the worker bake frame and returns a memory problem instead of exposing partial buffers.

Model plans now carry the archive receipt through to geometry and packets. `MeshPacket.requireAssets` compares SHA-256 and declared/expected version with a later client snapshot. This prevents accidentally binding geometry from one archive capture to textures from another; it does not establish generation readiness or asset authenticity.

## Coordinate and UV profile

| Operation | CPU representation |
| --- | --- |
| Model coordinates | Element endpoints/origins start in model units; final positions divide by 16 into local block units |
| Element rotations | Right-handed X, then Y, then Z around the declared origin, using double intermediate arithmetic |
| Dispatch rotations | Clockwise X/Y/Z quarter turns about model center `(8,8,8)`, using exact signed coordinate swaps |
| Face order | DOWN, UP, NORTH, SOUTH, WEST, EAST; original source labels survive endpoint reversal |
| Corner order | With no element rotation object, reorder complete position/UV tuples using transformed face bounds and winding direction |
| Default UVs | Derived from the unrotated element endpoints and original face direction |
| Explicit UVs | Kept in source order, including mirrored rectangles; face quarter turns permute the four UV corners |
| UV lock | Projects the rotated face's U/V basis onto the destination face basis, applying the counter-turn about sprite center `(8,8)` |
| Element rescale | Positive per-axis scaling before element rotation; each scale is the reciprocal largest absolute component of the rotated unit axis |
| Normals | Source tangent-axis area sign, inverse-transpose element transform, then dispatch rotation; normalized separately from translation |
| Culling | Optional declared cull direction receives dispatch rotation only; neighbor visibility is not guessed or applied |

These are implemented CPU conventions awaiting end-of-stage native comparison, not a claim of bit-exact client vertex parity. Element rescale now follows the reviewed 26.3 transform structure; see [model coverage](MODEL-COVERAGE.md) for source provenance and remaining comparison requirements. [Reversed endpoints](WINDING.md) now bake without sorting or repairing source endpoints; negative tangent-axis area reverses the shading normal. Zero-area faces may remain as degenerate quads; their presence does not imply visible pixels or a render-shape classification.

The current baker requires UVs to stay inside their source sprite, 0..16 before normalization. It does not clamp or wrap out-of-sprite coordinates. The separate [CPU atlas layer](ATLAS.md) now packs direct PNG sprites and maps normalized UVs into page rectangles without modifying source packets. Native interpolation/shrink, generated sprites and sampling beyond a sprite's rectangle remain pending. Materials retain `force_translucent`; the [texture preparation layer](TEXTURES.md) decodes source sprites, retains conservative mapped diagnostics and applies the [pre-transform model alpha policy](MODEL-MATERIALS.md). Each baked JSON face now carries its original explicit/default UV rectangle as material metadata, before corner rotation or UV lock. Existing whole-source groups retain their declared scope. The separate GPU backend now supplies material passes and explicit tint/final lighting. Declared shade overrides and emission also drive its [simple display lighting](../renderer-gl/MATERIAL-LIGHTING.md), without changing geometry normals. Native AO/lightmap fidelity remains unverified.

## Packet layout and limits

Each vertex contains eight float32 values: position XYZ, normal XYZ and normalized sprite UV. Each quad has four vertices and indices `0,1,2,0,2,3` relative to its vertex base. The packed array cost is 152 bytes per quad. Face metadata separately retains source, selected part, original model-face label, rotated cull direction, material, tint, emission, AO and shade override.

Read-only heap buffer views have independent cursors; copied arrays are detached. No API hands out the packet's mutable backing arrays. The buffers are not direct GPU staging allocations, and their byte count excludes object/metadata overhead and the model/client snapshots.

Defaults: 1,024 selected parts, 4,096 selected elements, 24,576 quads, 16 MiB of packed arrays, local coordinates within ±8 blocks and absolute element angles at most 360 degrees. Rotation origins must fit within the corresponding local model-coordinate budget. Configurable ceilings remain bounded to ±64 blocks and 36,000 degrees; broader limits are not native validation. Defaults deliberately do not certify total generation, heap, image, direct-buffer, atlas or GPU memory.

## Remaining stage validation

At renderer-stage completion, compare all 64 X/Y/Z dispatch combinations, six faces and four UV rotations against pinned native fixtures. Include off-center/mirrored UV rectangles and UV lock, non-cube and zero-area elements, arbitrary supported element rotations, inherited geometry provenance and cull labels, and selected multipart/weighted cases using verified native tickets.

Exercise byte/count/coordinate/angle limits, invalid tickets, plan problems, identity/single-axis/Euler rescale, inverse-transpose normals, all endpoint-reversal combinations, canonical corner/UV tuple order, guarded out-of-sprite cases, cancellation during every phase, allocation failure without actual process heap exhaustion, immutable array/buffer views, wrong-archive texture binding and fresh-session packet ordering. World placement/occlusion, native render shapes and fluids, tint/lighting, texture/atlas/GPU validation and visible Windows rendering remain separate requirements before enabling previews.

Native quad intake and ordered JSON/native composition now share the packed geometry path. Native-only faces have detached provenance and `selection=-1`; JSON selection indices remain nonnegative. See [NATIVE-GEOMETRY.md](NATIVE-GEOMETRY.md) for source, binding, budget and deferred validation contracts. Native fluid/special producers remain unfinished.
