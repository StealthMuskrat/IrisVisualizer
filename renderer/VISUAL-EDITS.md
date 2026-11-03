# Streaming native visual producer handoff

`WorldVisualEdits` applies explicit per-cell visual decisions to an immutable `WorldVisuals.Request`. `NativeVisualInputs` runs that traversal in a borrowed native session, verifies actual state/biome handles and returns the existing complete scene-worker request. This connects supplied native geometry to previously captured model choices. It does not implement or verify a fluid/special renderer, choose a native producer or enable a preview.

## Frozen baseline and explicit decisions

Start with a complete model-choice request, such as successful `NativeWeightedInputs.capture`. The producer receives each original choice together with the exact original native state, biome and world position. It can return that choice unchanged, supply a complete captured `NativeChoice`, add a native layer to a valid base, explicitly declare native empty output or retain a bounded original-state problem. The renderer never infers any decision from a block name.

The full volume is visited in X-fastest, Z, Y order. Every returned choice must preserve that cell's original state index. Native meshes must also preserve the exact original serialized state/properties/domains, selected archive, snapshot UUID, generation source and optional position. A position-dependent mesh cannot be reused at another cell. Explicitly position-independent meshes may be reused within that same state/capture; the provider must establish that independence. Changed external-namespace visuals remain unsupported except for original-state problems.

Equal choices are interned into one output palette. Unused baseline entries disappear from the output palette, while unchanged model tickets remain unchanged. Reusing immutable mesh objects allows repeated cells to share packets/prototypes; independently capturing the same geometry for every cell does not deduplicate it by pixel/vertex similarity. Such output reaches the existing variant/packet bounds and fails explicitly. Source state and biome indices, area, configured height and generation inputs never change.

`WorldVisualEdits.layer` preserves a `ModelChoice`'s exact ticket list and appends its supplied native layer after the JSON base. Existing native layers compose in their original order. Native-only choices compose through `NativeGeometry.combine`; an explicit empty base can become a supplied native visual. A problem base stays a problem: an additional partial layer cannot establish its missing base geometry. A producer must explicitly supply the complete replacement when it can establish the entire native visual. This is especially relevant to currently guarded fluid/waterlogged/special states; no automatic producer or inferred JSON base has been added.

## Native provider contract

`NativeVisualInputs.Provider` carries a selected-client receipt, a bounded policy label and a sampler. Capture checks the native source/session, selected archive SHA/version, full configured height, signed native coordinates and exact registry state/biome identity before invoking it. Session/cancellation checks continue through cell traversal; completion checks run before and after freezing the edited assignment, then again before scene-request publication.

The host must freeze and verify the producer, its native resources, random selection, geometry, materials, color resources and neighborhood data throughout capture. A policy label and matching archive receipt do not establish fidelity. The supplied `BlockPos` is reused and borrowed only for that callback; it must not be retained or mutated. Native work, callbacks, registries and handles do not enter the CPU render queue. Exceptions, cancellation, unhealthy sessions and limit failures publish no partial replacement request; the original request is immutable and remains available to its owner.

`NativeVisualInputs.geometry` captures supplied native quads and returns either a complete replacement or an additional layer. It accepts a frozen list or the streaming reader below. These are the existing block-local coordinates **before** the captured `WorldOffsets` translation, which meshing still applies. Producers must avoid applying the same offset twice. Original face labels, normals, winding, sprite-local UVs, tint indices, emission and shading metadata remain supplied native data; this boundary does not calculate them.

The baseline visibility mask is shared unchanged. Native geometry must use cull-face metadata compatible with it, or supply already evaluated faces without block-mask suppression. This path does not make the vanilla block-face predicate into a fluid/special visibility algorithm. Baseline tint input is also shared unchanged; newly introduced tint layers still require complete coverage or later preparation remains guarded. Optional per-vertex tint/lighting still use the existing exact-scene capture contracts.

## Streaming quad intake

`NativeGeometry.read` accepts a `Reader` exposing a declared count, one immutable `Quad` at a time and a completion guard. Count/packed-byte and version limits are checked before allocating output or reading any quad; selected sprite resources are checked as each quad arrives. Output validation retains the existing local-coordinate, UV, finite unit-normal, winding, material and original-provenance rules. Completion/count checks run before packing and before publication, with cancellation checks during traversal. No full intermediate input list or reader is retained. The legacy list capture delegates to this same implementation.

Native reader exceptions propagate to the surrounding atomic capture; geometric validation/memory failures still return the existing failed mesh. Producers must not downgrade native session failures into successful geometry. The declared count must remain stable, and the host freezes the actual source until both completion checks return. The immutable output graph and packed packet still consume their existing limits; streaming removes an intermediate list, not the final mesh or native producer memory.

## Admission and ownership

| Resource | Default / hard ceiling |
| --- | --- |
| Visited cells | 16,777,216, complete configured volume |
| Changed cells | 16,777,216 |
| Additional distinct packets | 1,024 |
| Additional packed source bytes | 64 MiB |
| Output choice palette / mesh variants | Existing 65,536 / 1,024 |
| Combined known edit working payload | 512 MiB |
| Streaming geometry per capture | Existing 24,576 quads / 16 MiB packed |

All limits are lowerable. Before traversal, the original retained input plus two prospective four-byte-per-cell assignment arrays must fit the edit allowance. During capture, every new unique packet adds its packed size once; original packets are recognized by identity. The conservative peak is `original.inputBytes + 8 × cells + addedPackedBytes`. This includes baseline inputs and both new scratch/final assignment arrays, even when baseline entries are replaced. Final `WorldVisuals.Request.capture` independently validates output metadata, packet counts/bytes, native provenance and complete assignments before making its private copy.

The native scene wrapper also deducts the retained visibility mask from the available edit allowance and preserves the original scene/section/job ceilings. Its result shares only immutable inputs and the selected output choices. Baseline-only packet references, old choice palette, old assignments, scratch maps, producer and native handles do not remain solely because the new request exists. A caller retaining the baseline separately must still account for it after capture. Provider-owned temporary geometry, native meshes/resources, Java collection/object overhead and simultaneous external jobs require separate application admission; the callback is not a memory allocator sandbox.

Successful results carry detached receipts, a bounded policy and usage counters plus the complete render request. Failures carry receipts/diagnostics only. The host releases its borrowed native session before enqueuing `result.request()` into `WorldRenderWorker`. Existing revision invalidation, exact-scene tint, cache ownership, GPU upload and independently owned image contracts continue unchanged.

## Deferred validation

All 100 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (71 CPU, 29 GL). Eleven native adapters are syntax-parsed and manifest checked only; genuine pinned Windows native API type checking and execution remain pending. No renderer tests, native producer execution, GL context, pixel comparisons or workflows ran for this batch.

At renderer completion, cover all-keep/empty/problem/replacement/layered worlds; repeated choices, ticket preservation, unused palette entries and position-independent sharing; exact state/snapshot/assets/position rejection; external namespace guards; changed-cell, visit, packet, metadata and copy-peak limits; cancellation and reader/session faults at every completion boundary. Compare list and streamed quad outputs, zero/max/negative/changing counts, callback ordering, preflight before reading, winding/UV/normal validation and packed output identity. Exercise layered model/native/empty/problem bases, complete guarded-state replacement, no partial multipart/base adoption, missing tint coverage and cull-face/offset provenance. Verify old requests remain immutable, new requests retain no producer/native graph, and scenes/picking/readback preserve original native cell/state/biome identity. Actual producer selection, fluid/special geometry fidelity, genuine native API compatibility, Windows GPU pixels and final editor integration remain pending. Unsupported previews stay disabled.
