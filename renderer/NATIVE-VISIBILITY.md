# Native block-face visibility and scene input capture

`NativeVisibility` supplies actual native block-face decisions for the existing per-cell direction mask. `NativeSceneInputs` combines those decisions with detached native model decisions and exact native offsets and returns a complete `WorldRenderWorker.ModelRequest`. These are source implementation paths; genuine native API compilation, native comparisons and renderer-stage tests remain pending. Unsupported previews remain disabled.

## Native predicate and exact input

The adapter calls the vanilla Minecraft 26.3 method `Block.shouldRenderFace(state, neighborState, direction)`. It does not reproduce an opacity test, compare block names, assume that equal states hide each other, approximate voxel shapes or load client classes. Every palette entry first round-trips through the actual native registry with the same original state ID, serialization, block ID, values and complete domains.

The three-argument vanilla signature was inspected in the unchanged base context of the pinned NeoForge [Block patch](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/patches/net/minecraft/world/level/block/Block.java.patch), with that source's Minecraft 26.3 target checked in [gradle.properties](https://github.com/neoforged/NeoForge/blob/b45f8c9d069cfb0099fae5bd1262b27ac4c1e7b4/gradle.properties). The adapter uses the vanilla method only. No NeoForge dependency, patched level-context overload, modded block or external shape provider is introduced. This source review does not replace compilation against the project's genuine pinned Paper/native runtime or actual predicate comparison.

The borrowed `NativeRenderContext` must remain current, healthy and frozen throughout capture. The snapshot must preserve the same complete generation receipt and engine height. Native registry metadata is checked before reading cells, and the session/error gate is checked during palette scans, native queries, long cache probes, cell traversal and completion. Obsolete/failed native sessions publish no mask or partial request.

Directions are mapped explicitly to native constants. Renderer mask bits remain DOWN, UP, NORTH, SOUTH, WEST and EAST in bits 0 through 5; no native enum ordinal ordering is assumed. Traversal uses the immutable snapshot's X-fastest, Z, Y order, independent of absolute coordinate sign. Neighbor indices never wrap across a row, layer or extent boundary.

All six in-volume neighbor directions receive the native predicate's answer. A false native answer sets that direction's hidden bit. Unknown neighbors outside any requested boundary retain the face: no outside chunk is generated, sampled, treated as air or inferred opaque. Native state/biome indices and the full configured height remain unchanged.

This is a **block-model** culling policy. World meshing suppresses only faces carrying a matching declared cull direction. Unculled faces and visual diagnostic markers are unaffected. The mask is not a native fluid-surface policy. Already-culled native fluid/special quads must omit block cull labels or use another appropriate visibility input. The automatic guarded model path still keeps fluid/waterlogged, special and tint visuals as placeholders. Offset-dependent models require the matching successful [native offset capture](OFFSETS.md).

## Bounded native pair cache

The vanilla predicate takes only self state, adjacent state and direction, so the adapter memoizes that exact tuple within one operation. Keys use original snapshot palette indices and an explicit renderer direction. A fixed primitive open-addressed table stores the tuple and returned Boolean; it does not retain another native world or grow a boxed-object cache per cell. At most half of its slots are populated, so a missing lookup always finds an empty slot.

Once the configured entry count is reached, uncached pairs still call the native method and receive its actual answer. They are not silently kept/hidden or approximated. Native-query and probe-visit limits apply independently; exhausting either fails the complete capture. Cancellation and the native healthy gate are checked during long probe runs. The cache is not global, cannot mix native epochs and is released before the result is returned.

`Usage` reports full cell coverage, in-volume/boundary directions, hidden directions, cached tuples, native calls, cache probes, retained mask bytes, temporary pair-array bytes and the known primitive peak. Boundary plus in-volume direction counts cover all six directions for every cell; hidden directions are a subset of in-volume directions. These counters do not claim measured frame time or complete heap/native memory.

## Single-copy streaming capture

`WorldVisibility.Input.read(snapshot, policy, reader, limits, cancelled)` allocates one privately owned mask array and reads each cell's native bits directly into it. It validates bits before writing, checks source-index/output payload together and invokes the producer's completion guard before publication. No reader, native context or mutable source array escapes. Errors discard the partial mask; cancellation propagates separately.

The existing array-based `Input.capture` remains available and retains its defensive-copy contract. Streaming native capture avoids allocating a separate full-volume source mask just to copy it. Later `Input.bind` shares the private immutable mask with the exact atlas; it does not create another volume-sized copy.

| Resource | Default / hard bound |
| --- | --- |
| Captured mask | One byte/cell, at most 16 MiB |
| Standalone snapshot indices + mask + pair arrays | 256 MiB default, 512 MiB hard |
| Memoized native state pairs/directions | 65,536 |
| Pair-table capacity | Up to 131,072 slots, 9 bytes/slot |
| Actual native predicate calls | 1,048,576 |
| Pair-cache probe visits | 268,435,456 |
| Native palette identities | Existing 65,536 state and 16 Mi-character bounds |

All limits can be lowered. Pair storage is preflighted before allocation and deducted from the core streaming reader allowance. A zero-entry cache performs direct bounded native queries. Native registry/shape caches, block-state reference arrays, Java metadata and borrowed engine memory remain outside primitive byte totals, with palette/text bounds still applied. Combined application admission remains necessary.

## Combined model and visibility handoff

`NativeSceneInputs.capture(context, assets, snapshot, tick, partialTick)` performs native offset capture, native model decision capture and native block visibility capture under the same borrowed session, then returns a detached `WorldRenderWorker.ModelRequest`. Its limits include the model-inspection, offset, visibility, scene and section limits and a default/hard 512 MiB known CPU job ceiling.

Before native traversal it admits the selected catalog, complete native index arrays, retained mask and prospective full-volume assignment/copy arrays together. Offset capture is admitted against the catalog and full native indices, then its retained palette/indices are included in the mask/choice-copy preflight. During visibility capture, the catalog and offsets remain charged outside the snapshot/mask/pair allowance. During later model inspection, the mask remains charged outside the lowered visual allowance. Existing scene-worker admission also includes pinned prior scenes. Area, height, states, samples and faces are never silently dropped to fit.

The resulting request retains only the immutable asset catalog, detached snapshot/model decisions, immutable offsets and visibility mask, clock and limits. The native session, actual state handles, pair-cache arrays, reader callbacks and temporary identity caches do not escape. The host can release its native ownership guard after capture and submit `result.request()` to the existing scene worker. Native model JSON inspection, baking, textures, atlas and complete scene construction then run on that same single CPU thread, with one active and one replaceable pending request.

Native capture creates no executor, starts no generation and does not close the caller's native session. The host must serialize this read with generation/hotload, track reported native errors and reject stale tokens before GPU/image adoption. A successful request is not a readiness certificate or independent native-world parity result. The usual [model guards](MODEL-INPUTS.md), [scene ownership](PIPELINE.md) and [native source/build contract](../native-renderer/README.md) still apply.

## Deferred validation

All 88 independent CPU/OpenGL sources compile with Java 25 and pinned LWJGL 3.4.1. Eight native adapter sources are syntax-parsed and source-manifest checked only; genuine native API type checking and execution remain pending. No renderer tests, native visibility query, GL context or workflow ran for this implementation batch.

At renderer completion, compare every emitted direction bit to the actual pinned native predicate for the identical state pairs, including full cubes, transparent/cutout states, stairs/slabs, fences/panes and native shape edge cases. Verify exact original registry domains, explicit direction mapping, negative origins, all six boundaries, single-cell/row/layer extents, all-empty/placeholder worlds, no neighbor-index wrap and full configured height.

Exercise cache-disabled, empty, collision-heavy and saturated tables, cached/uncached equality, query/probe limits and all primitive/text/count boundaries. Inject native/session faults and cancellation during registry scans, predicate calls, mask writes, probes and completion; no partial mask or retained native graph may escape. Compare shared-source/copy peak accounting with the combined catalog/model/mask path and pinned prior scenes. Verify actual hidden-face reduction, native state/biome picking and rendered pixels on Windows before enabling a supported preview. Fluid/special geometry, weighted choices, offset comparison, native tint, native API/runtime/GPU validation and final application integration remain pending.
