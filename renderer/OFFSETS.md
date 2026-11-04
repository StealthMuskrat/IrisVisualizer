# Native model offsets and shared placement

`NativeOffsets` detaches vanilla `BlockState.getOffset(BlockPos)` translations at exact original native positions. `WorldOffsets` preserves these inputs independently of shared model prototypes. This is renderer source implementation; native API compilation, native comparison and renderer-stage testing remain pending. Unsupported previews stay disabled.

## Capture and ownership

Capture requires the borrowed healthy frozen native context, the exact generation receipt, complete configured height and signed 32-bit native X/Z coordinates. Every original state ID, serialization, block ID, property and domain must match the actual native registry. Positions follow the existing X-fastest, Z, Y cell order with the original world minimum Y. Neither native generation data nor its state/biome indices are rewritten.

The native adapter calls the original state's offset function. It does not recreate a seed mixer, random generator, plant placement rule or client renderer. The call was inspected in unchanged vanilla context in the pinned Minecraft 26.3 [ModelBlockRenderer patch](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/client/renderer/block/ModelBlockRenderer.java.patch). This adds no NeoForge dependency or client-class execution and does not establish compatibility with the genuine pinned native classpath.

If no palette state has an offset function, capture returns explicit zero-storage `NONE` mode. Otherwise a streaming reader fills a private `char` index per cell and a private double XYZ palette. Entry zero is the zero vector. Equal numeric translations share one entry; signed zero is canonicalized. Double components are retained until final mesh vertex conversion to float. Nonfinite or out-of-bound components fail instead of being clamped.

Capture checks cancellation and the session gate during traversal and before publication. Failed captures retain diagnostics only; no partial arrays, registry/state handles, mutable position/vector, reader or native context escape. The palette lookup is per capture and bounded; there is no global cache.

## Placement and identity

`NativeSceneInputs` captures offsets before model decisions and native visibility, then supplies one detached scene-worker request. `WorldModelInputs.Request`, `WorldVisuals.Request` and `WorldModels` share the same immutable offset object and require exact snapshot object identity. Legacy overloads use explicit no-translation inputs; `NativeModelInputs` continues to guard offset functions unless native-mode offsets were supplied.

World meshing adds the captured translation to each `MODEL` face's cell-local position during section emission. Explicit native `CELL` faces use only the cell origin, allowing [fluid layers](FLUIDS.md) to remain fixed beside an offset-dependent JSON base. Normals, UVs, triangle indices, face ordering, declared cull directions and native face provenance stay unchanged. Model prototypes are shared across translated cells. Emitted bounds include overhangs, so section culling, transparent-face centroids and actual-triangle picking follow the shifted geometry. Picking still reports the original cell, state and biome; local hit coordinates may lie outside the unit cell. Empty cells remain empty; unsupported visuals keep original unit-cell diagnostic markers.

Tint, weighted selection, fluids, special render shapes and unsupported geometry/texture guards still apply. Capturing offsets does not make every offset-dependent model supported; for example, unsupported element rescaling still produces a placeholder. Callers supplying native quads explicitly choose `MODEL` or `CELL` placement and must avoid applying an already baked model translation twice. Placement is retained through composition, packed metadata and atlas binding; cell-origin placement requires native provenance. Legacy constructors remain `MODEL`. No inverse-offset rounding or new primitive payload is introduced.

## Admission

| Resource | Default / hard bound |
| --- | --- |
| Distinct palette entries, including zero | 65,536 |
| Cell index | Two bytes per cell, full native volume |
| Palette allocation | 24 bytes per configured entry, retained full capacity |
| Offset primitive payload | 40 MiB |
| Snapshot indices plus offset capture primitives | 256 MiB default, 512 MiB hard |
| Absolute component | At most one block; lowerable |
| Combined native scene input working ceiling | 512 MiB |

The full configured palette allocation and full-volume index are admitted before allocation. The palette is not trimmed through a second array copy. `bytes()` charges actual allocated primitive capacity, including unused palette slots. Exhausting distinct-entry or byte limits fails the complete capture without reducing height, area or precision.

Combined capture keeps the asset catalog charged during offset allocation and charges retained offsets during later model/visibility admission. Pending worker requests include offsets in retained input bytes. Model assignment/copy preflight and visual preparation include them once; scene source/cache accounting shares them with the exact model epoch. The worker subtracts only external catalog/native packet payload outside the scene source, avoiding a second offset charge. Prior pinned scenes remain charged under existing phase rules. Bounded lookup/identity objects, JVM headers, native registry caches and borrowed native memory are outside primitive totals and require final application admission.

## Deferred validation

The 118 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1. Sixteen native adapter sources are syntax-parsed and manifest checked only; genuine Windows native API compilation and execution remain pending. No renderer tests, native offset calls, GL context or workflow ran for this batch.

At renderer completion, compare captured offsets to actual pinned native output at positive/negative coordinates and Y extrema, repeated positions, every offset-bearing state and zero-offset states. Cover all-zero native mode, no-offset mode, palette deduplication, signed zero, exact double preservation, finite/component limits, palette saturation and every payload/working boundary. Check source mismatch, full-height enforcement, registry changes, cancellation/session faults through completion and absence of retained native references.

Compare shared-prototype translated vertices, unchanged normals/UVs/provenance, overhang section bounds, frustum culling, transparent ordering and triangle/alpha picking against actual native pixels. Verify original state/biome/cell identity, unchanged diagnostic markers, mixed empty/placeholder/native/JSON choices, wrong-snapshot rejection, pinned old scenes and phase/cache source accounting. Native weighted/tint/fluid/special producers, real GPU comparison, combined application admission and final integration remain pending.

At renderer completion, also compare combined offset-dependent model/fluid scenes: every model-offset component, unchanged cell-origin fluid vertices, shared recipes across different offsets, mixed-layer bounds, clipping, transparent centroids and original-state picking. Verify the default native offset requirement and all legacy constructor placement defaults. Current source compilation covers 118 CPU/OpenGL main sources and syntax parsing covers 16 native adapters; genuine native compilation and all runtime checks remain deferred.
