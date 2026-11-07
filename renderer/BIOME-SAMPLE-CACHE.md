# Capture-local biome sample memoization

Renderer implementation with compilation and source inspection only. Both renderer compilation sets pass with Java 25/LWJGL: 144 full renderer/validation files and 106 dependency-free files. Three added CPU groups bring the prepared total to 137: 102 CPU/context-free and 35 GPU. All remain unexecuted. All sixteen native adapter files parse and match their updated manifest, but genuine Windows native typechecking, native color comparisons, GPU validation and final integration remain pending. Unsupported previews stay disabled.

## Stable coordinate colors

`WorldBiomeBlend.memoize(stableSampler, entries, allowanceBytes)` prepares an opt-in, capture-local `MemoizedSampler`. The bound color function must produce stable values for the exact source biome and neighbor coordinate throughout the helper's lifetime. The cache is thread-confined like its owning blend helper; it introduces no executor, global lookup table or cross-capture reuse.

Each entry holds an exact captured source-cell key and the callback's original color integer. A direct memo sampler passed to another blend helper is rejected before footprint admission. Arbitrary wrapper callbacks retain the existing responsibility to use their own capture-bound inputs. The cell key includes X, Z and the full Y layer; absolute world coordinates are checked before exact local indexing. The helper also verifies that the supplied source-palette index matches that captured cell before reading any cached value. Reordered target/halo palettes therefore cannot accidentally reuse the wrong biome.

A direct-mapped slot compares the complete key. A hash collision replaces only that slot and can increase callback work, never substitute another cell's color or grow the cache. A zero key means unused; source indices plus one are nonzero under the existing sixteen-million-cell snapshot ceiling. Black and alpha-bearing integers remain exact in the cache. The outer blend keeps its existing channel masking, independent integer-floor averages and alpha-discard policy.

Only a successful callback followed by a cancellation check commits a new key/color. An exception or observed cancellation leaves an existing colliding entry intact. A failed key can be recomputed on a later call if the caller's operation remains valid. Invalid source coordinates/palette identity reject before lookup or callback work. Cache hits still check cancellation on entry.

## Exact bounded payload and counters

| Property | Bound/accounting |
| --- | --- |
| Entry count | Power of two from 1 through 65,536 |
| Primitive arrays | One integer key and one integer color per entry |
| `cacheBytes(entries)` | Exactly 8 × entries bytes |
| Maximum one-cache payload | 524,288 bytes |
| Native blended-grass cache | 1,024 entries, 8,192 bytes |

The factory checks the cache allowance before allocating either array. A one-byte deficit raises the existing structured LIMIT failure, without invoking a sampler. Source ownership and fixed JVM/object bookkeeping remain external to this primitive payload, as with the blend helper's other borrowed inputs. Physical heap allocation can still fail inside an admitted logical allowance.

`CacheStats` snapshots count lookups, hits and misses. A miss counts a stable-color callback attempt, including a callback that fails or is cancelled. Their relation is `hits + misses = lookups`; counters do not measure elapsed time. The cache exposes no mutable array alias.

Logical blend work is independent of cache work. `WorldBiomeBlend.rgb` still admits and charges the entire `(2r + 1)^2` footprint before its first callback, including all-hit, failed and cancelled footprints. It still invokes its supplied sampler once for every original neighbor and preserves the same sample ceiling. The cache does not permit a previously over-budget query, drop neighbors, clamp missing halo cells or reduce configured height.

## Native colormap integration

`NativeColormapTints` uses this cache only for grass-color modifier computations when the selected blend radius is positive. Foliage, dry foliage and water-cauldron paths already read constant palette-level colors and keep their direct path. Radius zero keeps the old uncached behavior and resource cost.

The capture constructs one stable grass sampler from its already checked native biome handles and selected-client palette colors. Mandatory `context.checkSession()` remains outside the memoized function and runs on every logical neighbor callback, including cache hits. Cache reuse therefore cannot suppress an obsolete-session/error gate. Whole-capture checks, cancellation, exact asset/native source identity, signed native coordinates and full-height halo checks remain in place.

Tall-plant upper halves continue sampling one block below. That actual neighbor height participates in the source-cell key. Ordinary grass, second grass layers and double-grass rules share only the same underlying stable coordinate color; their original tint-layer schemas and source-state identities remain unchanged.

`resourceBytes(maps, biomes, radius)` includes the additional 8,192 primitive bytes for a positive radius. The previous two-argument method remains the unblended resource calculation. Capture preflight includes the cache before allocating its arrays and subtracts its charge from the remaining tint working allowance. Existing memory/sample ceilings are unchanged; a former exact-boundary blended request can report LIMIT rather than exceed its allowance.

The cache and its native callback stay inside synchronous capture. Successful output remains ordinary detached `WorldTintInput` data; no memo table, native biome/engine handle, callback, halo or blend helper enters the returned worker request. The bridge manifest was updated for the edited adapter while retaining the same sixteen-file coverage and pinned upstream revisions.

## Prepared deferred coverage

Three new CPU groups prepare:

- Cached/uncached complete-footprint equality at radii 0/1/3/7 and four capacities, reordered palettes, distant/signed-endpoint coordinates, alpha-bearing values, full-height separation, and repeated/overlapping neighborhoods.
- Exact and one-byte-short cache admission, invalid capacities/allowances, forced single-slot collisions, black values, independent sampler identity, callback exceptions, cancelled callback publication and cancelled hits/factory setup.
- Native-style external session gates on every logical callback, rejection before cache access even for a warm footprint, complete logical sample charging on gate failure, and exhausted-sample rejection before any gate or cache work.

The native integration was source-reviewed and parsed with Java 25 without resolving native types, emitting adapter classes or executing native methods. Both renderer compilation sets passed; no renderer assertion or native/GPU comparison ran. The manual Windows compiler workflow remains available for genuine adapter typechecking and has not been dispatched. Production inventory remains 138 files (94 CPU, 44 GL), plus five harnesses and one fixture.

Fewer modifier callbacks are expected only when the bounded cache retains overlapping coordinates. Tiny caches, collisions and different source layers can reduce that benefit. No measured speedup, total-process-memory result or native color parity is claimed.
