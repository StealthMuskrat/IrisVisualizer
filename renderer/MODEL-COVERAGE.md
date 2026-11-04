# Model and animation coverage

Renderer implementation batch, 2026-11-03. Compilation and source inspection only; runtime, native-client and GPU comparison remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Geometry

`ModelTransforms.Element` applies positive per-axis rescaling before the existing X/Y/Z rotation around the declared origin. For each rotation-matrix column, the factor is the reciprocal of its largest absolute component. Identity rotation stays exact. Positions use `input + (R*S-I)*(input-origin)`; normals use `R*inverse(S)` before the baker normalizes them. Existing origin, angle, final-coordinate, face and byte limits still apply. UVs and declared cull labels retain their existing treatment.

`ModelResolver` now distinguishes absent geometry from explicit empty geometry. The latter replaces parent geometry; inherited textures and ambient occlusion remain independently resolved. Native world preparation still requires an explicit native empty/render-shape decision and does not infer invisible blocks from an empty JSON mesh.

## Sprite preparation

| Dimensions declared | Effective frame size |
| --- | --- |
| Neither | Square using the smaller image dimension |
| Width only | Declared width × full image height |
| Height only | Full image width × declared height |
| Both | Declared width × declared height |

Whole-tile divisibility, positive exact dimensions, frame-index ranges and schedule budgets remain mandatory. Omitted schedules enumerate the row-major grid. Zero/one-entry schedules become static tile zero with no interpolation; source metadata digests remain unchanged. The existing sprite object carries this effective schedule through alpha classification, atlas packing, CPU raster snapshots, animation updates, GPU upload and frame-bound picking without separate layout rules.

Invalid frame entries remain explicit failures. A later [interpolation batch](INTERPOLATION.md) adds tick-quantized base-level RGBA blending; native pixel comparison, mipmaps, filtering and out-of-sprite UVs remain pending or guarded. Static PNGs without an animation section still use the full image.

## Review provenance

The version-specific method bodies reviewed are published as Minecraft 26.3 source by an unofficial decompilation mirror:

- [CuboidRotation](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/resources/model/cuboid/cuboidrotation/): axis rescale and transform composition.
- [CuboidModel](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/resources/model/cuboid/cuboidmodel/): absent geometry versus an explicit geometry object.
- [AnimationMetadataSection](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/resources/metadata/animation/animationmetadatasection/): dimension defaults.
- [SpriteContents](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/renderer/texture/spritecontents/): animation-state eligibility and static upload origin.

The [official NeoForge model patch](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/resources/model/cuboid/CuboidModel.java.patch) corroborates the modern nullable geometry shape; [texture documentation](https://docs.neoforged.net/docs/resources/client/textures/) describes the public metadata format. Neither authenticates the mirror or replaces comparison with the user's selected client. No Minecraft source bodies are vendored. Renderer arithmetic uses double intermediates rather than claiming bitwise parity with native float/JOML operations.

## Deferred stage checks

Compare identity, positive/negative single-axis and compound rotations with rescale on/off, off-center origins, normalized normals, geometry bounds, source UVs and budget failures. Cover absent/empty/nonempty declarations across multi-level parents, texture overrides and native empty-shape guards. Compare rectangular sheets, both strip orientations, each dimension default, empty schedules, lone nonzero indices, repeated frames, exact duration boundaries, alpha scans, atlas gutters and immutable completed images. Include cancellation and resource failures. Actual Windows native dependency compilation and visible GPU checks remain required; compilation alone establishes neither native API compatibility nor preview readiness.
