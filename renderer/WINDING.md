# Element winding and corner order

Renderer implementation, 2026-11-03. Compilation and source inspection only. Renderer-stage runtime tests and actual selected-client/GPU comparisons remain deferred; unsupported previews remain disabled.

## Supported geometry

`GeometryBaker` now accepts reversed element endpoints inside the existing model-coordinate and bake limits. It never sorts, swaps or rewrites the model's `from`/`to` declarations. Default UV rectangles still derive from those original endpoints, including mirrors. Explicit UVs, face rotations and UV lock keep their current semantics; out-of-sprite UVs still fail explicitly.

Normal orientation follows the sign of the original face's two tangent-axis extents:

| Source face | Tangent-area product |
| --- | --- |
| DOWN / UP | `(to.x-from.x) * (to.z-from.z)` |
| NORTH / SOUTH | `(to.x-from.x) * (to.y-from.y)` |
| WEST / EAST | `(to.y-from.y) * (to.z-from.z)` |

A negative product reverses that face's input normal. The existing inverse-transpose element transform and dispatch rotation then apply, followed by normalization. Reversing only the normal-axis extent does not reverse triangle winding. Zero-area input retains its source normal fallback and degenerate geometry; it does not establish native invisibility.

For example, reversing X alone reverses NORTH/SOUTH and UP/DOWN triangle orientation but leaves EAST/WEST tangent orientation unchanged. Original `modelFace`, source pointer and declared cull label are preserved. Culling still rotates only the declared cull direction; it is not inferred from the corrected shading normal.

## Complete vertex tuples

Degeneracy is checked against the final float positions, including coordinate-rounding collapse. When no element rotation object is present, each nondegenerate face is reordered to the canonical face corners for its final winding direction after dispatch. The helper finds exact position matches within the four already-produced float vertices and swaps complete position/UV records. It does not regenerate UVs or move coordinates. The original face list and packet index pattern remain unchanged.

Any explicit element rotation object suppresses this reordering, including identity rotation. Arbitrarily rotated and rescaled faces keep their transformed source corner order. This avoids forcing tilted geometry onto axis-aligned bounds. If exact tuple matching unexpectedly fails, the whole bake reports a source-specific geometry problem; no partial mesh escapes.

The change flows through existing mesh packets, texture preparation, atlas binding, JSON/native composition, GPU culling, transparent ordering and picking. Supplied native quads keep their independent winding validation and are not reordered by this helper. No engine block/state/biome data, face metadata, asset identity or preview gate changes.

## Source review and limits

The pinned [NeoForge FaceBakery patch](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/resources/model/cuboid/FaceBakery.java.patch) exposes the nullable element-rotation condition and paired position/UV swaps. The complete winding and exact-match method bodies were reviewed in the [unofficial 26.3 source mirror](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/resources/model/cuboid/facebakery/). That mirror is not authentication of the user's selected client; no upstream source bodies are vendored. Signed-area orientation here is derived from this renderer's existing corner definitions. Double transform arithmetic and finite-normal fallback remain implemented CPU conventions, not bitwise native parity.

At most four vertices are searched four times per face. Reordering uses existing immutable vertex records without new vertex arrays. Existing face, packed-byte, origin, angle, coordinate, cancellation and whole-result failure limits remain unchanged. There is no new cache or source rewrite.

## Deferred stage validation

At renderer completion, cover all eight endpoint-reversal patterns, six faces, 64 dispatch rotations and four face UV rotations, including UV lock, mirrored/default/explicit rectangles and source labels. Distinguish missing rotation from explicit zero rotation; include nonzero single-axis/Euler rotations and rescale, flat/tiny elements, signed zero and coordinate limits. Compare ordered position/UV tuples, triangle orientation, normals, bounds, packed buffers and JSON/native composition against the actual pinned client. Confirm GPU front/back culling, displayed alpha picking, transparent sorting and immutable source data. Renderer tests, native API compilation and real-driver verification have not run for this batch.
