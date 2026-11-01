# Closed resource-operation coverage

Rename, same-family move and Delete use a worker-produced review of the frozen in-memory pack. Only a complete, current, permitted report can authorize Apply. Apply updates memory; Save independently preflights writes and baseline-bound old-path removals. Save/Export remain available when a resource operation is blocked.

The current operation schema has ten JSON root owners: dimensions, regions, biomes, generators, expressions, image-maps, blocks, structures, jigsaw-pools and jigsaw-pieces. Objects and images can also be mutation targets. The source inventory includes 135 class models, 73 enums and the string-adapted MantleFlag interface: 1,365 direct serialized field declarations bound to 221 pinned Iris/VolmLib source files. The inventory includes inherited members and package/protected/public/private instance fields, excludes static/transient fields, and fails closed on unreviewed declaration changes. It does not establish complete Iris coverage or make every inventoried field editable.

| Reference domain | Planner behavior |
| --- | --- |
| Dimension regions/focus, biome pools/children/carving, generator links | Follow the source-audited loader and complete resource key; maintain incoming and self links |
| Dimension stack and legacy upper dimension | Maintain typed links; preserve inactive `none` and block replacements that would become reserved/inactive sentinels |
| Nested object pools, assembly placements, palettes, replacements and noise styles | Traverse the inventoried object/list graph; maintain typed references without replacing ordinary names, expression code or native registry strings |
| Image-map resource keys | Maintain resource links; named mask binding keys remain ordinary binding identifiers |
| Image-map colors/fallback with one unambiguous BIOME or REGION role | Resolve bare or exact `iris:` keys in that family; preserve surrounding whitespace and the namespace when rewriting |
| Unbound/mixed categorical roles, including categorical plus scalar use | Block affected biome/region operations; never infer the family from the label spelling |
| Surface-block and pure scalar image-map roles | Preserve categorical labels; native surface-block lookup does not use pack block aliases |
| Floating carving entry-or-biome strings, deposit biome-or-derivative filters, cave prefix allowlists, subterrain region filters and object collision comparisons | Nonempty values conservatively block their affected target family until the complete comparison semantics are certified |
| Omitted nested generator/layer/palette/hydrology defaults | Record implicit references; block changes requiring optional settings to be inserted |
| Null/blank biome generator links | Record the runtime `default` generator fallback; never rewrite null/blank values into authored settings |
| Non-null external terrain transformer | Block mutation because provider-defined dependencies cannot be certified from settings |
| State/tile/custom-NBT maps and native-ID maps | Preserve contents; validate the certified container/value shape |

Every frozen eligible owner is parsed before image-map applications are collected. This makes categorical classification independent of file ordering. Biome-role checks also block a rename that would collapse unequal region-pool aliases into equal strings and change native role inference; unrelated region contexts may conservatively trigger that guard. Exact loader resolution is checked again against the planned path set, including missing references and dotted-prefix aliases, so a new name cannot silently capture an unrelated reference. Rewrites splice only reviewed string-value spans and retain all other source bytes, including large numbers, formatting, unknown opaque map members and untouched assets.

Unknown JSON owners (including currently uncertified loot/spawner/entity/mod/snippet files), unknown model fields, snippet strings in object/list positions, malformed or limited JSON, unsupported reference spellings and exhausted scan budgets block mutations. A pack that uses guarded cases can still be edited, saved and exported. These guards do not certify native generation, content validity or preview support.

The model catalog and declaration fingerprints live in `editor/policy/resource-operation-models.json`; exact source hashes and dependency revisions live in `editor/policy/resource-operation-sources.json`. `resource_operation_schema.py` generates the runtime properties from those pinned declarations and explicitly audited reference/default rules. Restricted initializer interpretation handles reviewed constructor/list/palette/style forms and emits family blockers for unfamiliar resource-bearing defaults. It never runs Java or materializes defaults in pack JSON.

The expanded regression cases are prepared but deferred. All 357 editor/test Java sources compile with Java 25. No regression suites, source-drift tests, GitHub workflows or interactive GUI checks have run for this expansion. UI implementation is still in progress; unsupported previews remain disabled and renderer work has not begun.
