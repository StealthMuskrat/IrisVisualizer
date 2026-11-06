# Original JPEG images

Renderer implementation, 2026-11-04. All 128 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (87 CPU, 41 GL). This batch used source inspection and compilation only. No JPEG/PNG decode, concurrency test, GL context, pixel comparison, Windows GUI check or workflow ran. Renderer-stage execution remains deferred until implementation is complete; unsupported previews remain disabled.

## Source format and pixels

`SourceImage.Encoded` recognizes PNG and JPEG by constant-size byte signatures. Resource suffixes do not choose the decoder. Capture still makes one bounded private copy, while all JPEG inspection/decoding runs inside `SourceImage.decode`, normally on the existing single `ImageDecodeWorker`. Model textures, colormaps and atlases keep their separate custom PNG-only path.

Successful receipts carry `SourceImage.Format.PNG` or `JPEG` alongside resource/revision, encoded SHA-256, byte length and stored source dimensions. The previous five-argument `Receipt` constructor retains PNG semantics. GPU images, completed frames, original-pixel inspection and readback already retain that exact receipt without an alternate GPU format or fabricated native map samples.

JPEG produces opaque RGBA8 from the standard JDK's RGB output, with alpha 255. YCbCr conversion, upsampling and IDCT rounding belong to that runtime; compressed pixels do not imply lossless reconstruction of an image before compression. Grayscale expands through the codec's explicit RGB destination, avoiding a later Java grayscale color-space transfer. Inspection returns the exact decoded RGB used for upload. PNG's transparent-RGB/high-byte/no-profile convention stays unchanged.

Stored width, height and top-left scanline order remain exact. EXIF orientation, thumbnails and descriptive metadata are not applied. There is no automatic rotation, crop, resampling, profile approximation or image editing. Original encoded pack bytes are never rewritten by this path.

## Complete input preflight

Supported input is eight-bit Huffman-coded baseline, extended sequential or progressive grayscale/three-component JPEG. Three-component identities must be standard 1/2/3 or R/G/B. RGB/YCbCr conversion is permitted. Other coding processes, CMYK/YCCK, unknown component layouts and ICC color-management profiles report FORMAT limitations without changing source bytes or Save/Export authority.

Before constructing a codec or allocating a raster, a marker walk checks frame/segment lengths, dimensions, component identities, sampling bounds and table selectors. Quantization/Huffman directories require complete entries, positive quantization values, bounded symbol/tree counts and the required tables for each scan; no missing-table defaults are supplied. Scan components, entropy stuffing/restarts, complete component coverage and a real final EOI marker are required. Missing-end repair, trailing/concatenated image data, more than 4,096 non-restart markers or more than 256 scans fail explicitly.

The walk checks cancellation through table/entropy ranges without building a metadata tree or segment list. It does not implement entropy decoding, inverse transforms, JPEG color formulas or pixel repair. The standard codec validates compressed samples and remaining scan semantics. Any codec warning, abort or unexpected destination rejects the complete image; partially decoded/repaired pixels never publish a receipt.

## Codec ownership

Only the standard `java.desktop` JPEG reader is instantiated, checked by provider class and module. Installed third-party reader ordering does not choose a codec. A private `ImageInputStreamImpl` reads the already-owned bytes directly in bounded chunks, with no file cache or whole-source stream copy. No ImageIO global setting is changed. The reviewed OpenJDK 25 native memory backend also has no backing-store implementation; this is source evidence, not executed verification of arbitrary vendors.

The reader receives `ignoreMetadata=true`, one explicit full-size three-byte BGR destination and no subsampling/crop. Reader dimensions must match preflight. Progress callbacks cooperatively request abort, input reads check cancellation and row conversion checks again before publication. Codec calls are not preemptible time budgets. The reader is disposed and stream closed before a decode result escapes, including failures/cancellation.

## Admission

JPEG requests reserve the configured decode working allowance before queueing, with actual retained encoded bytes as a minimum even for invalid or undersized requests. Dimension discovery stays off the context thread. Before raster allocation, the actual prospective peak must fit that allowance:

| Payload/reserve | Bytes |
| --- | --- |
| Owned encoded input | Actual encoded length |
| BGR destination plus complete RGBA result | 7 × source pixels |
| RGB conversion row | 4 × source width |
| Conservative coefficient reserve | 12 × MCU-padded width × MCU-padded height |
| Additional codec/row reserve | 1 MiB |

The coefficient reserve allows two complete three-component 16-bit coefficient sets over padded bounds, conservatively including sequential/grayscale images. JDK/native allocator overhead, registry initialization, JVM metadata, garbage awaiting collection and unrelated owners are outside these payload estimates. Total process memory still needs profiling; this is not a heap/codec allocation guarantee.

Limits stay at 8,192 per dimension, 16,777,216 pixels, 32 MiB encoded, 64 MiB retained RGBA, 128 MiB per decode and 256 MiB active-plus-pending worker admission. The JPEG working estimate can be stricter than the pixel cap. Excessive input fails without resizing or changing defaults. Shared admission keeps cancelled active work charged until unwind and preserves existing atomic idle replacement. Ready ownership then shrinks to actual immutable RGBA only; no encoded array, codec, raster or native object escapes in output.

## Review and deferred checks

[Source receipts](jpeg-source-review.json) identify exact official OpenJDK sources and release tag. Destination/color conversion, scanline buffering, warnings, disposal, callback-safe abort, native coefficients and backing-store behavior were inspected. Hashes record that review; they do not authenticate user runtimes, guarantee vendor-specific pixels or replace execution. No upstream source bodies or image assets are vendored.

At renderer completion, compare baseline/extended/progressive RGB, YCbCr and grayscale, odd/subsampled dimensions, multiple scans/restarts, EXIF-marked raw orientation and opaque output against the actual supported Windows Java 25 codec. Verify source SHA/format/dimensions, identical inspected/uploaded RGB and unchanged PNG forms/alpha/APNG/legacy constructors.

Cover truncated signatures/segments/tables/scans, missing tables/end markers, invalid symbols/restarts, trailing/concatenated data, warnings/partial output, unsupported profiles/components/processes, every byte/count/dimension/working boundary and unusual sampling padding. Include undersized limits retaining encoded charges, cancellation in preflight/input/codec/conversion/hash, rapid replacement, READY/input handoff, foreign/closed coordinators, failure/retry, reader cleanup and absence of disk caches. Confirm readback retains JPEG source dimensions/format independently of viewport pixels. Renderer-stage tests, final editor routing and real GPU display remain pending.

## Reader disposal errors

JPEG decoding now records its active I/O, runtime or other error before disposing the standard reader. A distinct disposal error is suppressed onto that original error; the identical throwable is not self-suppressed. Disposal-only failure still prevents image publication, and the input stream's existing resource scope still closes afterward. Existing codec I/O-to-FORMAT diagnostics and separate cancellation handling are unchanged. No alternate reader/provider, disk cache, pixel conversion or byte limit is added. At renderer completion, combine cancellation/codec/conversion errors with reader-disposal errors and verify primary identity, input closure and absence of partial output. Full and dependency-free compilation passed; all 64 prepared groups and genuine-codec/native/GPU validation remain deferred.
