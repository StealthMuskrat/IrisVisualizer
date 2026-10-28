# Audited non-generation field policies

Iris revision: `c3359e0a16438b1bb8468901c6171dcefdf63f7f`.

This audit resolves the last seven unclassified fields in the existing inventory
of 1,218 direct fields across 111 classes. It does not expand that inventory to
all inherited fields, nested classes, resources or schemas. There are now 1,188
implemented generation fields and 30 read-only fields in this bounded inventory.
Classifying these fields does not enable previews or establish native parity.

The editing boundary is initial voxel generation. Sapling growth events,
inventory contents, entity spawning and metadata attached to already placed
blocks remain outside that boundary. Generation tree geometry, procedural trees,
object selection and the existing generation placement controls remain editable.

| Field | Pinned consumer trace | Policy |
| --- | --- | --- |
| `dimension.treeSettings` | `TreeSVC.growTree` checks enabled during a Bukkit `StructureGrowEvent`; `getObjectPlacement` reads mode to choose growth replacements. `IrisTreeSettings` describes tree growth overrides. | Read-only gameplay tree growth settings. |
| `objectPlacement.isDolphinTarget` | The three object-placement paths in `MantleObjectComponent` write `MatterStructurePOI.BURIED_TREASURE` for underwater storage chests through `setData`. | Read-only POI metadata; retains the placed block state. |
| `objectPlacement.loot` | `IrisObjectPlacement.getCache` loads custom loot tables; `EngineBukkitOps.getLootTables` selects them for a placed container and `addItems` populates its inventory. | Read-only inventory loot. |
| `objectPlacement.vanillaLoot` | The same cache and inventory path resolves Bukkit vanilla loot table keys. | Read-only inventory loot. |
| `objectPlacement.overrideGlobalLoot` | `EngineBukkitOps.getLootTables` returns the object table before environment tables when this flag is set. | Read-only inventory precedence. |
| `objectPlacement.markers` | `IrisObjectPlacementRunner` inspects matching blocks and records `MatterMarker` with `setData` after placement. Selection uses `nextParallelRNG`, explicitly avoiding changes to the placement RNG stream. `IrisMarker`, `MarkerSpawnScanner` and `WorldBlockDropRouter` handle spawner markers and their runtime lifecycle. | Read-only spawning/lifecycle metadata. Matching blocks and their generated states are retained. |
| `objectPlacement.trees` | `IrisObjectPlacement.matches` and `TreeSVC` match sapling species/size replacements. `MantleObjectComponent.isTreePlacement` also checks nonempty trees and writes `TreeBlockMaterial` through `setData`. `TreeDefinitionIndex` records explicit tree object keys. | Read-only growth matching and tree-material tagging; separate from `proceduralObjects.trees` generation geometry. |

The audit preserves metadata because it can matter to Iris runtime behavior;
read-only does not mean unused. The editor does not execute these services,
strip their fields, insert defaults, or validate their runtime content. Unknown,
malformed, snippet and null values remain preserved when other supported fields
are edited. Existing lossless writer allowlists enforce the authoring boundary;
coverage metadata never authorizes an edit or blocks Save/Export.

`editor/policy/field-policy.json` stores only classifications and reasons. The
separate `editor/policy/read-only-consumers.json` binds this review to thirteen
complete declaration/consumer file hashes and the Iris lock revision.
`editor/tools/audit_read_only_consumers.py` verifies that evidence and the seven
read-only policy entries. Hash verification records the exact reviewed source;
it is not an automatic proof of generation semantics. An Iris/source update
requires reviewing consumers and updating the audit deliberately.

The Windows workflow fetches the exact pinned sources, runs drift-rejection
checks and regenerates the existing inventory. Java regressions verify blocked
authoring of these fields, independent edits to generation fields, preservation
of unsupported/absent values, and exact Save/Copy/As/ZIP round trips. Interactive
GUI behavior and complete native generation remain unverified; unsupported
previews remain disabled.
