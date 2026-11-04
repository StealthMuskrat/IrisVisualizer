# Native model selection and JSON-only entity visuals

Renderer implementation, 2026-11-04. All 119 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (83 CPU, 36 GL). Sixteen native adapters pass syntax parsing only; genuine pinned Windows native API compilation remains pending. No renderer tests, native random draws, entity construction, GL contexts or workflows ran. Unsupported previews stay disabled.

## Dedicated native model random source

The no-RNG overloads of `NativeWeightedInputs.capture`, `NativeTintInputs.weighted`, `NativeCapture.weighted` and `NativeCapture.fluidScene` now use the reviewed Minecraft 26.3 factory `RandomSource.createThreadLocalInstance(0L)`. The native common API returns a fresh `SingleThreadedRandomSource`; capture rejects an unexpected actual class or an unreviewed profile. The factory is pinned separately to 26.3, requiring a new review when the renderer profile changes. Despite the factory name, this overload creates a dedicated instance rather than borrowing a shared thread-local source. Allocation happens inside checked capture and, for `NativeCapture`, after phase admission. The instance never escapes the capture.

The existing reader reseeds it from the actual original native block state and world position before every model selection. Multipart parts retain the existing single-seed/reset contract. Initialization with zero is the renderer's factory input, not a replacement world seed. No unique-seed factory, engine RNG, native generator, client bootstrap or user-selected archive class is used. Original states, biomes, seed and configured height remain unchanged.

Explicit-RNG overloads are preserved for separately verified producers; they require exclusive caller ownership and renderer-matching policy. The default source records its factory policy and actual class in existing usage receipts. Neither class names nor source review authenticate the loaded native binary. Genuine factory/output comparisons remain pending.

The default and explicit paths share model/tint inspection, offset/visibility capture, palette/part/weight/query/byte bounds, cancellation, healthy-session checks and detached ownership. Consuming catalog overloads retain source admission through output admission and release it on admitted failure. Combined fluid capture still publishes complete base/layer decisions only, including optional borrowed neighbor admission and independent model/cell placement.

## JSON-only block-entity policy

`VanillaModelPolicy` permits the following 23 exact vanilla IDs in the 26.3 profile. Their reviewed entity types have no registered vanilla block-entity visual producer; the native world JSON model describes their displayed block state. This removes the blanket entity guard only after native registry/state verification and only for actual `MODEL` render shape. It does not infer geometry from a block name or fabricate entity data.

| Family | Exact block paths in `minecraft` |
| --- | --- |
| Furnaces | `furnace`, `blast_furnace`, `smoker` |
| Containers/mechanisms | `dispenser`, `dropper`, `hopper`, `brewing_stand`, `jukebox`, `barrel`, `crafter` |
| State-driven details | `comparator`, `daylight_detector`, `chiseled_bookshelf` |
| Command/jigsaw | `command_block`, `repeating_command_block`, `chain_command_block`, `jigsaw` |
| Bees | `bee_nest`, `beehive` |
| Sculk | `sculk_sensor`, `calibrated_sculk_sensor`, `sculk_catalyst`, `sculk_shrieker` |

All selected asset/model, face, state orientation, weighted choice, native tint and native offset guards still apply. Lit furnace faces, bookshelf occupancy and brewing bottle state come from the original generated properties; no default inventory, ticking state or replacement block is invented. The policy is immutable and pinned separately to 26.3, so changing another module's version constant cannot silently carry this exception to a new profile. Unknown and external identifiers remain excluded.

The deterministic and weighted model paths both use this policy. A known-fluid state still requires the combined fluid producer; eligible JSON-only entity bases can then retain their actual model and complete fluid layer. A missing base/tint/offset/neighbor remains a complete visual problem, preserving original identity. The renderer never constructs a block entity, reads invented NBT, ticks it or executes client renderers.

Chests (including copper/trapped/ender variants), signs, banners, skulls, moving pistons, beds, spawners, beacons, portals, campfires, lecterns, shelves and other entity-rendered visuals remain guarded. This policy adds no incomplete model-only approximation for them. Other entity types absent from the reviewed allowlist remain guarded even if they might be supportable later. Runtime particles, sounds and gameplay/inventory behavior are outside this static generated-world visual profile.

## Source review and deferred comparison

[Review receipts](model-policy-source-review.json) identify the official pinned NeoForge patch context and the separately reviewed complete vanilla method bodies from an unofficial 26.3 source mirror. The relevant contracts are the renderer's local RNG creation and reseeding, actual native factory behavior, built-in block-to-entity mappings, registered client visual producers, and dispatch behavior when no entity renderer is registered. No source bodies, Minecraft assets or client executables are bundled. Mirror provenance does not authenticate client assets or establish native API/runtime compatibility.

At renderer completion, compile genuine pinned Windows sources and compare default capture to the actual renderer and to an explicit native instance of the reviewed factory. Cover negative/extreme positions, all weighted intervals, multipart combinations and seed resets, repeated fresh captures, native query bounds, factory/type mismatch, cancellation/session faults and absence of shared generation RNG changes. Compare selection/class/policy receipts and ensure no random/native graph survives in queued output.

Check every allowed entity block against genuine native shape/type/JSON output, all relevant facing/lit/occupancy/power/bottle/waterlogged states, native offsets and tint coverage. Verify unknown profiles/IDs, malformed or absent assets, non-MODEL shapes and all excluded special families retain whole visual problems. Compare actual native pixels and original state/biome picking after deterministic, weighted and fluid-scene capture. Renderer-stage tests and final integration remain deferred until their prescribed stages.
