# Bounded transparent-face sorting

Renderer implementation, 2026-11-06. Compilation and source inspection only. Renderer execution, native/GPU comparison and final integration remain deferred; unsupported previews stay disabled.

## Two arrays and deterministic original keys

`WorldDrawOrder` now allocates one long key array and one double depth array: 16 primitive bytes per translucent face, replacing the former four-array, 32-byte merge-sort peak. The final order still retains only its original eight-byte section/face key. Source payload, face count, sort-byte and combined working ceilings remain unchanged. `reservation` and `WorldOrderWorker` use the reduced actual peak, including active/pending overlap and shared admission. Existing requests can fit a lower explicitly supplied budget without dropping a face or raising any ceiling.

At the default 262,144-face cap, temporary key/depth payload is 4 MiB instead of 8 MiB. At the hard 1,048,576-face bound it is 16 MiB instead of 32 MiB. These figures exclude the retained source, JVM object/array headers, executor and unrelated application work. They describe logical primitive payload, not total application memory or a measured speedup.

The package-private `WorldFaceSort` first checks adjacent pairs. Already ordered inputs, including empty/singleton arrays, return without moving pairs. Other inputs use an iterative in-place heap sort with constant-size bookkeeping and no recursion, collections or face-sized scratch. A heap places the pair that belongs latest at its root and fills the final array from its end. Worst-case work remains O(n log n); the already ordered path is O(n). General-case timing and cache behavior require later measurement against the former merge sort.

The total order is unchanged: larger original camera-forward depth first, then smaller original section/face key. Finite positive and negative zero depths compare equal before the key tie rule, as in the former sort. Keys identify unique original faces, so algorithmic stability is unnecessary to reproduce the old deterministic tie result. Depth bits stay paired with their key through every move. No native state, geometry, index, atlas, lighting, tint, visibility, clip or GPU layout changes.

## Preparation, cancellation and publication

Depth preparation still checks every section anchor and the exact relative precision bounds, including opaque-only sections. It visits the existing compact translucent centroids and uses the identical floating-point expression. All source/limit/direction checks precede array allocation with the existing diagnostic precedence. Finite-depth validation and cancellation checks cover the adjacent scan, heap construction and extraction, with a check at least every 1,024 comparisons and additional entry/loop/exit checks. A cancelled or failed public request never publishes a partial order.

`WorldDrawOrder.sortStats()` provides optional detached `SortStats`: original face coverage, comparisons, pair writes and whether the adjacent scan found the input already ordered. Successful empty orders have statistics; failed orders expose none. Pair writes count assignments into the working arrays, including extraction swaps. Statistics do not estimate elapsed time, native sorting fidelity or intersection correctness. Worker publication, latest-revision transfer, source leases, unwind and retained-byte shrink remain unchanged.

## Prepared evidence and remaining scope

One new world CPU group prepares independent reference comparisons for eleven sizes and seven depth/key patterns: empty/singleton, odd/even, non-power-of-two, already ordered, reversed, repeated depths, signed zeros, extreme finite values and deterministic random inputs. It checks exact key/depth-bit pairing, zero-move fast paths, invalid pair/depth rejection, cancellation across phases, exact/one-byte-short scratch and combined admission, complete statistics, worker transfer and retained cleanup. Existing world-order admission expectations now use 16-byte peaks. All assertions remain unexecuted.

All 137 CPU/OpenGL production files, five harnesses and one fixture compile with Java 25/LWJGL (143 total). The dependency-free set contains 105 files. All 94 prepared groups remain unexecuted: 76 CPU/context-free and 18 GPU. Real differential orders/pixels, driver behavior, cancellation races, performance, total application memory and final editor adoption still require renderer/integration validation. Global centroid sorting continues to have its existing intersecting/cyclic transparency limitations; this adds no OIT or readiness claim.
