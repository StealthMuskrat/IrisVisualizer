# Model shading and streamed final lighting

Renderer implementation, 2026-11-03. All 116 CPU/OpenGL Java sources compile with Java 25 and LWJGL 3.4.1. Fourteen native adapter sources are syntax-parsed/source-manifest checked only; genuine Windows native API compilation remains pending. No renderer tests, producer/native sampling, GL context, GLSL compilation, pixel checks or workflows ran. Unsupported previews remain disabled.

## Simple-lighting material flags

The renderer previously retained `shade_direction_override` and `light_emission` through model, atlas and world faces but ignored both when drawing simple lighting. They now reach the existing geometry shader without changing CPU geometry, GPU vertex stride or upload-byte reservations.

The four-byte GPU material word at byte offset 32 is now three normalized RGB tint bytes and one raw shading byte. Attribute four reads RGB24, and integer attribute six reads the final byte. The 36-byte simple and 48-byte supplied-light layouts remain the same size. Attribute five continues to hold supplied RGB32F lighting at byte offset 36 when present. No tint alpha is accepted or inferred; texture alpha still controls material passes independently.

| Shading byte | Meaning |
| --- | --- |
| Low three bits, zero | Use the original geometric shading normal |
| Low three bits, 1..6 | Use the declared DOWN/UP/NORTH/SOUTH/WEST/EAST override for simple directional shading |
| Bits 3..6 | Original model emission level, 0..15 |
| High bit | Unused, zero |

The shader applies the existing simple directional/ambient formula to either the original normal or the explicitly captured cardinal direction. In that display mode, `emission / 15` supplies a minimum RGB multiplier. This is a simple visualization floor: emission zero preserves existing unmodified shading, and emission fifteen gives full modulation. It does not propagate light, evaluate a native lightmap, create AO, apply native gamma or claim native emission brightness parity. Geometry normals, winding, visibility/cull directions, tint indices, block IDs and picking remain unchanged.

Explicit final `WorldLighting` RGB remains authoritative. When supplied, the shader uses it directly and does not multiply by simple direction/ambient or apply the display emission floor. A black supplied sample stays black even on emission fifteen; the producer resolves all desired native emission/shading/light/AO before capture. This prevents double application. Animation and all material passes reuse the same immutable stream. The shader mode is set for each scene draw.

The source-format boundary follows Mojang's [26.3 notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3): cardinal `shade_direction_override` and removal of the old `shade` field. The existing reader still rejects removed/unknown fields and preserves captured override metadata. Native comparisons of dispatch/element rotations, direction conventions, emission/lightmap behavior and final pixels remain deferred. This simple mode is not a replacement native lighting algorithm.

## Streaming final samples

`WorldLighting.read(scene, policy, reader, limits, cancelled)` now captures final RGB directly from a scoped `Reader`, avoiding an intermediate caller-owned full array set. The existing `capture(scene, policy, List<float[]>, ...)` methods delegate to that same implementation and retain their immutable-copy behavior.

The reader supplies `sections`, a component count per section, and `component(section, face, vertex, channel)`. Its optional `begin` is called after sample/working-budget preflight. Every section must have exactly `faces * 12` values in original emitted face/four-vertex/RGB order. All shapes are checked before any output sample array is allocated. Counts must remain frozen throughout capture; completion and final shape checks reject changed inputs.

Every sample must be finite and in `[0,1]`, excluding texture and tint. Invalid samples identify the original emitted face's world position. Cancellation is checked between individual samples as well as sections/faces and completion gates. Structured read failures retain their original problem; other producer exceptions become bounded WORKER diagnostics. Allocation failure produces MEMORY. Failure exposes no partial sample arrays or bytes. Two `complete` gates bracket result sealing, and no reader/callback is stored in `WorldLighting`.

Default/hard sample output remains 96 MiB at 48 bytes per face, and scene plus output must fit the lowerable 512 MiB preparation bound. Source array copies from the old API still require external input admission; streaming avoids that extra array set. Provider-owned scratch, native/JVM metadata and unrelated jobs remain separately admitted responsibilities.

## Shared owned output and native producer boundary

`WorldLighting.readOwned` optionally admits the complete scene-plus-sample peak through `RenderMemory` before `Reader.begin`, shape callbacks, sample arrays or producer sampling. It returns `RenderInput<WorldLighting>` after the reader stack unwinds. Failed capture throws a detached `WorldCapture.ReadFailure` and releases its hold. A successful owner keeps both the exact CPU scene graph and sample-array payload charged until transferred or closed. The caller must separately keep the scene result lease live while using/uploading that scene.

The new native bridge `NativeLightingInputs.capture(context, scene, provider, limits, memory, cancelled)` uses this path. Its explicit `Provider` identifies the selected archive and supplies final sample components; no light/AO factory, biome blending, environment policy, renderer-selection or native color algorithm is guessed. Run it synchronously on the serialized native worker under the host's live scene lease/session/resource ownership, not from the GL pump.

After shared admission, native `begin` validates generation receipt, asset SHA/version, full configured height, signed native coordinates, original state registry serialization/properties/domains and original biome IDs/keys. A face's original source cell supplies state, biome and position; the provider also sees the exact emitted mesh, face, vertex and channel, including offsets, native/JSON source metadata and declared lighting hints. This permits the explicit resolver to evaluate the actual geometry without the bridge fabricating normals or native sample rules. Borrowed native objects and the mutable position must not escape callbacks.

Every component checks session health/cancellation. Completion gates and the outer final native check reject logged faults, obsolete sessions and failures before publication. The output owns detached final arrays, the CPU scene and admission only: no engine, native handles, reader or provider is retained. Failed results contain detached source/snapshot/problem metadata and no input. An allocation or final-context failure closes any partially sealed owner. Native reference arrays/cache metadata and provider scratch remain outside primitive byte accounting and require host admission.

## GPU handoff and image receipts

`GlUpload.beginLit(renderer, sceneOwner, tint, lightingInput)` and `GlWorldView.submitLit(revision, sceneOwner, tint, lightingInput)` consume fresh scene/input wrappers after nonnull checks. They retain a temporary lighting owner, reject a tracked input from a foreign/unmanaged coordinator and keep its hold through the normal destination scene journal's CPU admission. Scene identity must be exact. The connected view checks that identity before changing its revision. Fresh consuming failures close inputs, so retain separate retry owners first. Existing same-device duplicate scene-owner protection remains; raw lighting APIs keep their external lifetime responsibility.

The temporary lighting source and device journal can conservatively charge the same scene/sample payload during transfer. No uncharged gap or cross-account deduplication is introduced. The device keeps final arrays and its scene lease charged through upload, drawing and actual retirement; failed cleanup/quarantine does not release them early. Native sampling never runs on the upload/context thread.

`GlScene.lightingInfo()` now reports a detached supplied/simple flag and bounded policy label. Actual `GlFrame.Info.lighting()` captures it with the successful image, and existing world readback preserves it automatically. A transferred image keeps its original mode/policy when newer scenes use another lighting source. Earlier Info constructors remain available with the simple-mode default. A supplied label describes provenance; it is not independent proof that native lighting was correct. No lighting arrays, scene lease or provider graph are retained by the receipt.

## Deferred verification

At renderer completion, compare old/default simple lighting with zero emission/no override, each cardinal override, every emission level and arbitrary native/JSON normals. Cover all combinations of tint, opaque/cutout/translucent passes, animation, geometry rotations and supplied RGB. Verify original geometry/cull/picking identity and byte-exact 36/48-byte packing, RGB/shading boundaries, integer attribute six and every four-byte-word upload yield. Explicit native samples must bypass both fallback direction and emission floor in every scene alternation.

Exercise streaming/array equivalence, all shape/value/count limits, empty sections, changing reader shapes at completion, native resource/state/biome/position identity, individual sample cancellation, callback/native/logged errors and result-sealing allocation failure. Check source/scene/output admission before native callbacks, closed/foreign coordinators, retained retry owners, duplicate transfer protection, stale lighting, unchanged view tokens on preflight rejection, and charges after transfer/failure/shutdown/retirement. Native callbacks must not escape into detached results or GL pumping.

On genuine Windows GL, compile/link shaders and compare supplied-native and simple-mode images independently. Verify host state and actual frame/readback lighting receipts, old caller-held image preservation, transparent alpha/order, exact native sampler outputs and material-policy provenance. Native factory/lightmap fidelity, complete renderer testing and final editor integration remain pending. No tests ran for this implementation batch.
