# Sparse atlas clocks and reusable sample intervals

Renderer implementation with compilation only. All 143 renderer/validation sources compile with Java 25/LWJGL; the dependency-free set contains 105 files. Three additional animation groups and one GPU group bring the prepared total to 112: 80 CPU/context-free and 32 GPU. All remain unexecuted. Native/GPU fidelity, performance measurements, total application memory and final integration are pending; unsupported previews stay disabled.

## Time-varying sprite index

Atlas clock preflight previously walked every sprite and rebuilt a complete sample map on each observed clock, including static textures. `AtlasLayout.timeVaryingSprites()` now supplies a reusable immutable ordered index of slots that can select more than one canonical source tile. Its values are the original exact placement objects; it copies no image, schedule, vertex or page arrays.

`TextureSprite.timeVarying()` uses the distinct referenced tile indices. Ordinary static sprites and multi-entry schedules that repeatedly select a single tile are fixed. A repeated nonzero tile remains that tile; the original animation flags, durations and continuous `sample()` metadata are preserved. Different tile indices remain time-varying even when their decoded colors happen to match. No image-content equality or native sampling is inferred.

`AtlasFrameWorker.Request.capture` visits only this index. Its private canonical map contains time-varying entries, while the expected raster still retains its complete immutable receipt. Changed-slot counts, padded patch bytes and one complete page-copy charge per affected page follow the same existing budgets. The page marker array is allocated only after an actual change is found. Static atlases need neither a per-sprite sample-map build nor a page marker array during preflight.

Worker-side `AtlasImages.updates` starts from the complete expected sample map and resamples only indexed slots. Public update/raster `samples()` and `frames()` remain complete, including static and fixed nonzero tiles. Their complete map copies still occur on the worker; this change does not make every atlas operation proportional only to animated sprites. Initial rasterization still visits/fills the entire atlas.

## Conservative canonical intervals

`TextureSprite.pixelWindow(tick, partialTick)` returns a canonical `PixelSample` and an inclusive `[firstTick, lastTick]` interval that selects the same inputs. Partial ticks retain their existing validation and tick-quantized pixel behavior.

| Schedule | Conservative interval |
| --- | --- |
| One distinct referenced tile | From the observed tick through `Long.MAX_VALUE` |
| Stepped multi-tile animation | Through the current schedule entry's last tick |
| Interpolation between equal tile indices | Through the current schedule entry's last tick |
| Interpolation between different indices | Through the current float/thousandth quantization plateau, bounded by the current entry |

Interpolation uses the exact existing positive float division, thousandth quantization and canonical endpoint rules. A monotone binary search locates the first changed quantized input within the current entry, using at most 31 comparisons for an integer-duration frame. It scans no schedule history or pixels. Entry boundaries may conservatively require another observation even when adjacent entries select the same canonical inputs. The ordinary schedule lookup remains logarithmic.

Long integer modulo preserves cycle selection. Interval addition saturates at `Long.MAX_VALUE` without wrapping, including at the last representable tick. The interval is a sampling proof rather than a wall-clock timing promise or a claim that two different samples produce different RGBA8 output.

Each atlas request records the earliest interval endpoint among all varying slots. `matchesClock` accepts only clocks proven to preserve those samples, validates every supplied clock, and rejects backward dynamic seeks before the observation's start. A wholly fixed atlas can accept backward clocks too. Exact raster identity remains a separate mandatory part of `samePixels` and the GPU coordinator's reuse check.

## Context-owned observation reuse

`GlWorldAnimation` keeps one bounded metadata observation for its exact current raster. Forward clocks inside its proven interval can reuse it without another palette walk, schedule search or sample-map comparison. Both explicit seeks and live playback use this path; the live path still completes an admitted update before sampling newer desired clocks.

Crossing an endpoint or seeking backward outside the proof captures a new sparse observation. A matching pending request keeps its original preparation clock. Unchanged displayed pixels retain their original raster receipt rather than being relabelled with the latest host time.

Atlas adoption clears the old observation before using the new exact raster. CPU/GPU/admission failure and shutdown also drop the cached proof; explicit retry must observe again. The cache stores at most one request and does not form a prior-raster chain. Existing active-worker cancellation, source ownership, GPU journals, retirement and memory limits remain in force.

The index, clock intervals and cached observation are bounded metadata. JVM collection/object overhead remains outside primitive payload estimates, as before. Per-clock status/receipt bookkeeping still allocates; the optimization removes palette-sized preflight work, not all allocation. No preview size, configured height, native state, asset authority, CPU/GPU allowance or readiness gate changes.

## Prepared deferred coverage

Three CPU groups prepare independent linear schedule expectations, stepped/repeated/nonzero tiles, interpolation plateaus, unequal and maximum integer durations, large modulo clocks, overflow-safe endpoints, partial-tick guards, immutable exact placement indices, mixed static/dynamic sprites, earliest-boundary selection, exact patch/page reservations and direct no-op worker handoffs with complete receipts and shared ownership.

One GPU group prepares static forward/backward clocks with a zero-byte worker, exact observation-object reuse, long stepped/interpolated entries, pending-request coalescing, adoption invalidation, backward seeks, failure/retry and shutdown. Its reflection only reads the cache owner; it does not patch jobs, rasters or admission state. Existing pixel, animation, material and first-frame groups remain compiled alongside it. No test, GL context, native operation or workflow was executed for this batch.
