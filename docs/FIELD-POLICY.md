# Source inventory and editor field policy

Editor coverage and typed reference planning share `editor/tools/iris_source_inventory.py`. It reads the same pinned Iris/VolmLib declarations, source hashes and class fingerprints, including class annotations. Source inventory includes inherited members and all instance-field access levels; static/transient state, enum constants, methods and actual nested declarations are excluded. Field indentation is irrelevant.

The current catalog includes 150 class models, 75 enums and the string-adapted MantleFlag interface across 244 pinned sources. The 149 nonempty class groups contain 1,462 serialized fields. These are the known JSON owner/nested models in the pinned catalog, not a schema for arbitrary runtime extensions or opaque binary assets.

| Policy state | Fields | Meaning |
| --- | ---: | --- |
| Generation with implemented controls | 1,237 | An existing control covers the field in a supported editor context; stored values, ancestors and context limits still apply |
| Read-only | 196 | Metadata or gameplay behavior outside initial voxel-generation editing |
| Unclassified | 29 | Modification-recipe consumers and live generation scope require an explicit audit; these fields remain read-only |
| Missing policy entries | 0 | Every inventoried field has a policy entry; an explicit unclassified entry is still unfinished classification work |

`editor/policy/field-policy.json` stores only classifications and reasons. Types, defaults and constraints are not copied into it. Field names and nested type structure come from the shared source inventory; implemented-control coverage comes from the existing UI allowlists. Invalid policy shapes, undeclared fields, unknown groups, source drift and editable allowlist entries without generation classification stop metadata generation. A generation classification by itself cannot mark an unimplemented control as implemented.

The additional reviewed read-only models describe entity spawning/AI/equipment, gameplay effects and commands, item/loot data, block-break drops, marker metadata and post-generation sapling/tree growth. These classifications preserve the existing restrictions on their containing fields. Procedural generation trees use a separate editable model. Modification recipes are not silently treated as generation settings or dismissed as gameplay: their 29 unresolved fields remain visible in the coverage report. The human-readable modification name is read-only metadata.

The existing coverage section now derives its class list from the nested source graph, including recursive classes, read-only JSON owners and typed snippet kinds. Cached metadata replaces the former manually maintained per-resource class lists. Coverage remains advisory and does not validate pack contents or authorize edits to read-only resources.

The JSON source browser shows policy information for its selected indexed pointer. It resolves known object fields and canonical array indices, retains restrictions inherited from read-only/unclassified ancestors, distinguishes opaque map members from independently declared Iris fields, and clips generation-model information to read-only resource contexts. Unknown fields, internal metadata-like names, malformed pointers and unsupported structures remain read-only. Snippet contents are not expanded by this browser lookup. Model controls can exist elsewhere without making the selected value editable. Source inspection never reads a pack through the policy API, inserts defaults, changes drafts or triggers generation.

The Windows workflow is prepared to download the complete shared inventory before rebuilding coverage. Deferred contracts cover inherited/shadowed/recursive type graphs, policy shape/source boundaries, honest pending controls, read-only resource and ancestor restrictions, array/map/unknown-pointer handling, and exact source preservation. All 371 Java sources compile; no regression scripts, suites, GitHub workflows or interactive GUI tests ran for this work batch. UI implementation and end-of-UI validation remain unfinished, and unsupported previews remain disabled.
