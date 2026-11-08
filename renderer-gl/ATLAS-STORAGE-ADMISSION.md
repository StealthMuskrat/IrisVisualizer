# Complete atlas update admission

Animated atlas updates now admit every known destination page, the copy framebuffer and the eventual immutable output-frame journal before returning from `GlAtlasUpload.begin`. Previously, initial admission covered CPU plans while changed textures, the copy framebuffer and output frame were admitted later. A known capacity deficit could therefore arrive after the queue had discarded earlier accepted animation work.

## Initial footprint

The complete immutable `AtlasFramePlan` supplies changed-page identities, copied RGBA bytes and patch bytes. Initial preparation checks changed-page hardware/row-staging bounds and requires their patches before making any reservations. Original scene/layout/raster identity gates remain unchanged.

| Component | Initial reservation |
| --- | --- |
| Work | One journal retaining `plan.extraCpuBytes()` = changed page pixels plus patch bytes |
| Independent source frame | One journal retaining original page references and scene/page dependencies |
| Output frame | One zero-payload journal with the original scene dependency |
| Each changed page | One journal, one texture slot and its complete RGBA GPU bytes |
| Copy framebuffer | One zero-payload journal and framebuffer slot, only when pages change |

For `n > 0` changed pages, begin reserves `n + 4` journals and `n + 1` object slots. Added GPU payload equals `plan.copiedBytes()`, and added CPU payload equals `plan.extraCpuBytes()`. The independent source retains existing original page payload without duplicating it. No byte/name/journal allowance is credited for old work a successful replacement may release. All reservations must fit beside live and retired ownership under the existing device/shared limits.

A no-change plan still admits work, source and output journals, with no new GPU bytes, texture slots or framebuffer. It can complete at a new clock receipt while sharing all original page pixels. Journal limits still apply even when byte demand is zero. The plan's metadata/bookkeeping exclusions remain unchanged.

## Incremental storage and immutable publication

Native framebuffer creation and each page's texture storage remain separate budgeted steps. Every changed page begins with a never-started reservation and an internal page owner. The existing `STORAGE` step moves that page's CPU pixel charge from work to its page journal, marks storage started, and creates the native texture. Other reserved pages remain unused until their own step. Copy/patch ordering, row/chunk budgets, whole-page copy traffic, dirty rectangle traffic and host-state restoration retain their original behavior.

The internal `GlAtlasFrame.Prepared` owner reserves the eventual frame journal without publishing page ownership or textures. Finishing consumes that owner once, attaches exact raster/page references and dependencies, and creates the immutable frame without another device reservation. Invalid or incomplete output refunds its journal, and unstarted page storage cannot enter a completed frame. Existing capture/retain APIs use the same preparation path and keep their original one-journal footprint and public signatures.

READY requires complete page-cursor coverage, copy/patch totals and CPU-charge transfer. Only then can source/frame-token-checked adoption expose the output. Unchanged pages share their original page owners; changed pages remain independent from older displayed frames. Original atlas sample/clock receipts, gutters, texture pixels, material alpha and native source identity remain intact.

## Cleanup and queue preservation

Failed initial setup visits every admitted output, page, framebuffer, work and source owner, preserving the primary failure and suppressing distinct cleanup failures. Owned CPU plan inputs remain charged through destination admission and are consumed under the existing contract. Bounded automatic collection can reclaim unrelated eligible retirement during admission.

Unused page/framebuffer storage refunds without fences using its original-device, never-started lifecycle proof. Cancelling before storage refunds the complete candidate immediately. After only the copy framebuffer starts, unused page payload still refunds independently. Once a page starts, its full GPU and transferred CPU pixel charge remains until fenced retirement; later unused pages refund immediately. Work releases patches and still-unused pixel charges after abandoning staged page references. Source-frame dependencies remain pinned through copy-framebuffer retirement. Existing quarantine and synchronized teardown rules remain in force. Unused, partial and READY upload wrappers can finish closing after the device has already released their journals during synchronized teardown.

`GlAtlasQueue` already installs fresh work only after begin succeeds. It now preserves exact accepted scene/frame tokens, progress and pending/READY output when any known page, object-slot or journal admission fails. The same unspent revision can be submitted after pressure is relieved. Explicit invalidation and connected animation seek/retry policies remain unchanged; this does not revive upstream-invalidated work. Native allocation, state restoration or Java metadata allocation can still fail after capacity admission succeeds.

## Prepared validation

The shared CPU-only fixture adds eight original sprites across multiple pages, with either all pages or one animated slot changing. Three GPU groups prepare complete initial counters; exact/one-byte-short shared budgets; tracked input rejection; closure/cancellation at validation, framebuffer-only, page copy/patch and finish boundaries; partial started-page retention; complete original page pixels and traffic; old-image immutability; unchanged-page sharing; prepared-frame single consumption; and rejection of incomplete output pages.

Queue coverage prepares IDLE, WAITING, unstarted, partially copied and READY replacement rejection/recovery. Separate device limits force later page admission to fail after earlier pages fit, and force output-journal admission to fail after work/source journals fit. Metadata-only updates must finish with zero free GPU/name allowance using only their three admitted journals.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). There are 166 prepared, unexecuted groups: 109 CPU/context-free and 57 GPU. No renderer test main, context, shader, native operation, Windows validation task or workflow ran. Genuine native/API compatibility, GPU correctness/fault injection, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews stay disabled.
