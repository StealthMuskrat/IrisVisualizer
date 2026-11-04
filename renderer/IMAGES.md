# Original image preparation

Renderer implementation, 2026-11-04. Original pack PNGs now have a separate source-image path, with asynchronous decoding and exact source-pixel inspection. This does not create native map samples or a generation context. The editor's unsupported preview gates remain disabled; renderer tests and real-driver comparison remain deferred until renderer implementation is complete.

## Frozen source and complete pixels

Capture an already-read pack PNG with `SourceImage.Encoded.capture(new SourceImage.Identity(resourcePath, revision), bytes)`. The identity is a bounded relative logical resource path and positive host revision. Empty/dot/traversal segments, absolute paths, backslashes, colons and control characters are rejected. This validates identity syntax; it does not read a file or prove pack membership. The host remains responsible for workspace identity, safe file access and invalidating changed selections. Capture checks the 32 MiB encoded ceiling before making one private copy; callers must freeze their source array during that copy and admit caller-owned buffers separately.

`SourceImage.decode` reuses the existing bounded PNG decoder. It preserves full source dimensions and top-left RGBA8 samples, including transparent RGB. It neither resizes to 512 samples nor crops, generates a map, runs Minecraft or interprets a sprite schedule. Existing PNG policy applies: supported color/sample forms and Adam7, CRC/order/zlib checks, high-byte reduction for 16-bit channels, no gamma/ICC conversion, default PNG image for APNG and no `.mcmeta` animation. These are RGBA8 source-sample conventions, not a color-management or high-bit-depth display guarantee.

Success publishes immutable pixels and a receipt containing resource/revision, SHA-256 of the exact captured encoded bytes, encoded length and actual width/height. Digest work checks cancellation in bounded chunks. The decoded result retains no encoded PNG array. A malformed, missing-dimension, oversized or memory-failed decode publishes structured image-specific problems and no receipt or partial pixels. Cancellation remains distinct. Internal texture-decoder diagnostics are translated to image problems; no fabricated block/state/biome or native map context is returned.

`SourceImage.hit(view, screenX, screenY)` returns original integer X/Y and unchanged packed RGBA plus the exact receipt. Transparent pixels remain inspectable. Outside-image points return empty; a transform with different source dimensions is rejected. `MapView` now also accepts original image dimensions up to 8,192 and has a direct width/height fit overload. Its map-grid overload and native map capture retain their independent limits and defaults. Pan, zoom and fit affect only display coordinates.

## Background decoding and ownership

`ImageDecodeWorker.Request` freezes a captured source and lowerable limits. The worker permits one active decode and one replaceable latest pending request. Submit admission precedes replacing queued/ready ownership; rejection preserves the previous revision/work. Resource invalidation immediately advances the revision, cancels obsolete work and drops untaken output. Active cancellation stays charged until the decoder stack unwinds. Only the current revision can publish/take a completion. No worker callback performs GL work or changes pack files.

A completion owns a `RenderMemory.Hold` when a shared coordinator is supplied. It must stay live through destination admission; close it after transfer or discard. `submit(RenderInput<Request>)` consumes the input after destination admission or rejection and requires matching coordinators for tracked inputs. Borrowed/escaped encoded arrays, image references and completion payloads still need their external owner lifetimes. Closing the worker cancels without blocking the UI thread.

Each job reserves encoded source bytes plus the configured RGBA cap and bounded decoder/digest scratch, conservatively before decoding. After execution unwinds and job references are dropped, a ready hold shrinks to actual retained RGBA; failed/cancelled work releases it. Object metadata, inflater/native/JVM overhead, garbage awaiting collection and caller-owned copies remain outside these primitive payload figures. There is no global image cache.

| Budget | Default and hard ceiling |
| --- | --- |
| Source width or height | 8,192 |
| Total source pixels | 16,777,216 |
| Encoded PNG | 32 MiB |
| Retained RGBA8 | 64 MiB |
| Per-decode source/output/scratch allowance | 128 MiB |
| Active plus latest pending worker reservations | 256 MiB |

The scratch reservation covers two maximum supported 16-bit RGBA rows, filter/finish bytes, palette/transparency arrays and digest scratch. Existing decoder chunk/inflated-stream limits remain unchanged. Lower limits fail explicitly. No downsampling, truncation, substitute pixels or automatic limit increase is used.

## Deferred stage checks

At renderer completion, compare odd/rectangular/large PNG dimensions and every supported PNG form against known source vectors, including alpha-zero RGB, original row orientation, 16-bit reduction and APNG default-image behavior. Cover immutable captured arrays/views, identity syntax, digest stability/change, full source receipts, outside/edge/transparent pixel hits, fit/pan/zoom and source dimensions above 4,096 within the aggregate pixel cap. Validate malformed input, complete failure publication, cancellation during decode/hash, source/output/scratch/worker boundaries, rapid replacement, stale take, invalidation, close races, scheduling failure and shared coordinator transfer. No PNG decode or concurrency test ran in this implementation batch.

The [GPU image view](../renderer-gl/IMAGE-VIEW.md) adds bounded upload, immutable frames, readback and context-thread adoption. Final editor routing and preview readiness remain separate integration work.
