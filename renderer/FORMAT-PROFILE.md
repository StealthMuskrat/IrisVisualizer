# Supported model-planning profile

Source review: 2026-11-03. The model planner explicitly targets Minecraft **26.3**, matching the existing project pin. A captured archive for another version yields a version problem instead of reusing these semantics. Compilation is complete; renderer runtime tests remain deferred. No user-selected client archive has been exercised for this batch.

## Data retained by the planner

| Input | Representation and boundary |
| --- | --- |
| Engine state | Original nonnegative native state ID, original text, exact block ID and properties; complete native property domains are required and defensively copied |
| Dispatch | Exactly one `variants` or `multipart` root; domain-checked predicates, all matching multipart cases, ordered positive signed-int weights, X/Y/Z quarter turns, UV lock, original source indices/pointers |
| Parent graph | Bounded/cycle-checked chain, ancestor geometry with child texture overrides and inherited AO; geometry origin is retained |
| Texture bindings | String sprite IDs, `#variable` links, or material objects with `sprite` and optional `force_translucent`; no stringification of other JSON types |
| Geometry | Unbaked element coordinates, optional rotations/rescale, faces, optional UV rectangles, cull directions, face quarter turns, tint indices, emission and shade-direction overrides |
| Failures | Asset family/ID/pointer and typed reason; original engine state retained, partial geometry withheld |

The application must obtain property domains from its verified native registry/engine context. Merely satisfying this input API is not proof of native provenance. Preparing the wrong domain must not be used to repair a pack or silently change a generated state. Palette-level integration and total memory accounting remain pending; this API is not intended to duplicate native domain tables for every voxel.

The planner and baker do not supply a random source. Native model tickets and multipart random-state behavior must be verified before supplying weighted choices to the baker. Likewise, a plan/mesh with no JSON elements or quads is not a determination that an engine block or fluid is visually invisible: native render-shape and fluid handling still apply outside this layer.

## Version-specific review

- Mojang's [25w46a notes](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w46a) define Z dispatch turns and X→Y→Z element rotations, while retaining precedence for the legacy axis/angle notation. The planner preserves those declarations, unrestricted finite element angles and the original cull-direction labels. The CPU baker now applies rotations within its explicit precision limits; pinned-client comparisons remain pending.
- The [26.1 Snapshot 7 notes](https://www.minecraft.net/da-dk/article/minecraft-26-1-snapshot-7) introduce material objects and `force_translucent`. The planner retains the flag. [CPU texture preparation](TEXTURES.md) now scans mapped source pixels and produces conservative classification candidates; the exact native sampling and render-pass policy remains unverified.
- The [26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) introduce `shade_direction_override` and remove the older `shade` element field. The reader accepts the override and treats the removed field as unsupported rather than guessing a conversion.
- [Fabric's model guide](https://docs.fabricmc.net/develop/blocks/block-models) and [Forge's model-provider documentation](https://docs.minecraftforge.net/en/1.21.x/datagen/client/modelproviders/) supply the parent/element/face conventions used for the typed description. The older guides do not override the newer Mojang changes. The reader currently supports element endpoints within -16..32 model units and retains reversed endpoints without repair.

## Explicit pending/guarded behavior

Unknown visual fields, custom loaders, built-in/special models and external namespaces yield placeholders. Missing PNGs also fail planning; atlas inclusion, generated sprites and animation are not inferred from a filename. Display transforms/GUI-light metadata do not affect world geometry and are not baked. An explicit empty child `elements` now replaces inherited geometry, while an absent declaration inherits it. This describes JSON resolution only; empty meshes still do not establish native invisibility. See [model coverage](MODEL-COVERAGE.md) for review provenance.

The [CPU geometry layer](GEOMETRY.md) now produces default/explicit UVs, UV lock, supported model transforms, normals, bounds and indexed buffers. [Texture preparation](TEXTURES.md) adds bounded PNG source decoding, frame schedules and conservative UV-area alpha candidates. [CPU atlas preparation](ATLAS.md) now packs shared sprites, binds mesh UVs and produces immutable base-level pages and frame update transactions. Element rescale, horizontal/vertical strips, single-axis frame sizing and zero/one-entry playback are implemented. Reversed endpoints, out-of-sprite UVs, unknown metadata, multi-frame raster interpolation and transforms exceeding coordinate/angle limits remain guarded. Native geometry/UV/texture comparison, tint application, actual alpha rendering, native render-shape/fluids, mipmaps/interpolation/filtering, native world visibility/choice production, application scene/generation scheduling, GPU/shaders and editor integration remain pending. [CPU world-section assembly](WORLD-MESH.md) now preserves supplied native state/biome data and explicit per-cell visual/visibility assignments. The later [CPU scene layer](SCENES.md) now plans full volume coverage with bounded leased caching and latest-request assembly. No asset plan, CPU mesh, texture bank, atlas or scene result enables a preview or satisfies native generation parity/readiness by itself. The original engine data and all File menu workflows remain independent of renderer failures.
