# VeilFrame — workspace integrity and production audit

Baseline: `1645a94269f6ec648836035db75d544519171a42` (main). Branch: `fix/workspace-integrity-audit-2026-11`. Opened 2026-11-20.

This is the living record of the two fresh static audits: engineering integrity and intention-versus-delivery. Source locations refer to the original baseline (Android Java package unless noted). Static findings are not equivalent to device reproduction. Some findings may be dormant; trace callers before claiming impact. No device or benchmark result is assumed.

## Status policy

- OPEN: uncorrected or requires investigation.
- PARTIAL: a subset is implemented; remaining work explicit.
- PATCHED: source fix committed, CI/device acceptance pending.
- VERIFIED: record actual test evidence and scope before claiming verification.

Code and ledger updates must be committed together. Never mark an issue verified without evidence. Preserve main and release gates; no changes to signing secrets. Prioritize false success, privacy, fidelity, ownership, then benchmarks.

## Findings

| ID | Priority | Status | Finding | Baseline evidence | Acceptance / next action |
|---|---|---|---|---|---|
| A01 | P1 | PATCHED | Searchable PDF false success on null output stream | `pdf/operations/PdfOcrProcessor.kt:341-353` | Require non-null destination; test provider failure. |
| A02 | P1 | PARTIAL | Searchable OCR strips Unicode | `pdf/operations/PdfOcrProcessor.kt:617-648` | Embed Unicode font and validate text extraction; no silent character loss. |
| A03 | P1 | PATCHED | OCR layer ignores page rotation and actual render dimensions | `pdf/operations/PdfOcrProcessor.kt:609-653` | Map pixel rectangles into crop/rotation-aware PDF coordinates. |
| A04 | P1 | PATCHED | OCR confidence fabricated (0.90 word / 0.85 page) | `pdf/operations/OcrEngine.kt:52; PdfOcrProcessor.kt:210` | Preserve unknown confidence and derive aggregates. |
| A05 | P1 | PARTIAL | PDF metadata removal only clears Info dictionary | `pdf/operations/PdfMetadataManager.kt:162-196` | Clear catalog/page/resource metadata; verify saved output and scope. |
| A06 | P1 | PARTIAL | Flattening can silently omit forms/annotations or remove undrawn annotation | `pdf/operations/PdfFlattener.kt:191-286` | Report incomplete work; preserve annotation until drawn successfully. |
| A07 | P1 | PARTIAL | JavaScript/attachment removal limited to catalog entries | `pdf/operations/PdfFlattener.kt:292-331` | Inspect relevant COS graph; verify requested removal. |
| A08 | P1 | PARTIAL | Background removal full-resolution claim contradicts 4096 cap | `cv/segmentation/rembg/CelBackgroundRemover.kt:35-38,81-112; RembgImageUtils.kt:18` | Disclose actual output size immediately; implement bounded original-resolution compositing. |
| A09 | P1 | PATCHED | Background removal source import can publish stale result | `media/BackgroundRemoverController.kt:326-368` | Cancel prior import; generation-gate results and cleanup. |
| A10 | P1 | PATCHED | Background removal export may race bitmap recycling | `media/BackgroundRemoverController.kt:1258-1430` | Protect captured bitmap lifetime across export/share/clipboard. |
| A11 | P1 | PARTIAL | PDF compression may choose destructive rasterization by size alone | `pdf/operations/PdfCompressor.kt:375-423` | Explicitly authorize lossy semantic change; preserve structural mode. |
| A12 | P1 | PATCHED | Compression cleanup deletes shared prefix-matching temp files | `pdf/operations/PdfCompressor.kt:299-307` | Own operation-specific temp files/directories. |
| A13 | P1 | PATCHED | DOCX conversion falls back silently to text/binary ZIP | `pdf/operations/OfficeConverter.kt:135-158` | Propagate cancellation and errors, disclose text-only fallback. |
| A14 | P1 | PARTIAL | Scanner silently ignores perspective/adjustment failures | `document/DocumentSession.kt:157-166,236-254` | Fail selected transformations explicitly; distinguish preview vs export. |
| A15 | P1 | PARTIAL | Scanner manifest direct overwrite and ignored persistence errors | `document/DocumentSession.kt:572-576` | Atomic persistence, serialization and recovery/error status. |
| A16 | P1 | PARTIAL | AI bundle heuristic secrets detection incomplete | `tools/AiBundleEngine.kt:49-54` | Expand patterns and clearly disclose limitations; pre-export review. |
| A17 | P1 | PARTIAL | AI bundle JSON key escaping, omissions, budget and memory | `tools/AiBundleEngine.kt:89-159,185-211` | Use serialization, deterministic selection, bounded streaming and omissions report. |
| A18 | P2 | PATCHED | Motion Lab failed gallery write leaves MediaStore row | `media/MotionLabController.kt:766-803` | Delete pending entry on failed copy/publish. |
| A19 | P1 | PARTIAL | Video PII capped sampling, rotated geometry and incomplete coverage | `cv/motion/VideoPiiRedactionEngine.kt:71-76,140-222` | Preserve incomplete result warning; test geometry; exhaustive mode and review. |
| A20 | P1 | PATCHED | Provenance embedded-key validity does not prove signer trust | `media/ProvenanceController.kt:165-166` | Label signature validity vs trusted identity. |
| A21 | P1 | PARTIAL | Workspace resource lifecycle dispersed; retained inflated views | `workspace/WorkspaceHost.kt` | Trace navigation/controller ownership; cancel resources without breaking sessions. |
| A22 | P2 | PARTIAL | PRNU external attribution resistance not established | `cv/prnu/PrnuSanitizerEngine.kt` | Retain honest diagnostic wording; benchmark independently. |
| A23 | P2 | PARTIAL | Stabilization real-world tracking quality and limitations | `cv/motion/VideoStabilizerEngine.kt` | Benchmark difficult footage and confidence behavior. |
| A24 | P2 | PARTIAL | Native matting activation/model contract uncertain | `cpp/matting_jni.cpp:292-316` | Inspect actual model graph before changing sigmoid; CPU/GPU parity. |
| A25 | P2 | PARTIAL | Native mutex cancellation latency, model and GPU lifecycle | `cpp/{matting,pii_ncnn,rife,realsr,veilnet}_jni.cpp; vulkan_gpu_lifecycle.h` | Stress device concurrency and measure native cancellation. |
| A26 | P2 | PARTIAL | Upscaler tile seams, model fidelity and generated detail | `upscale/inference/` | Measure dimensions, boundaries, memory and preview/export consistency. |
| A27 | P2 | PARTIAL | QR exported-style real-device scanability | `qr/` | Retain frozen matrix parity; roundtrip device scans and exports. |
| A28 | P2 | PARTIAL | Office/PDF broader semantic fidelity and workspace coverage | `pdf/operations/; media/; tools/` | Trace all reachable callers; fixtures for charts/formulas/fonts/fields/page geometry. |
| A29 | P2 | PARTIAL | Oversized multi-purpose controllers and ownership complexity | `media/; pdf/; navigation/` | Refactor by clear ownership boundaries after regression coverage. |
| A30 | P2 | PATCHED | Visual signing vs cryptographic signing distinction | `pdf/operations/PdfSigner.kt:103-104` | Verify UI differentiates visual overlay from digital signature validation. |
| A31 | P1 | PATCHED | ML Kit OCR failure can be treated as empty recognition; cancelled recognizer may outlive bitmap | `pdf/operations/OcrEngine.kt:24-62` | Propagate detector failure; protect asynchronous input bitmap lifetime and distinguish genuinely empty pages. |
| A32 | P1 | PATCHED | PDF raster compression swallowed individual page render errors, permitting partial or blank-page output | `pdf/operations/PdfCompressor.kt:809-823` (baseline) | Reject the entire faulty candidate; verify original page count and content fixtures. |

## Remaining-work classification (after 2026-11-20 follow-up)

Status and verification are separate axes. The original 32 finding statuses above are intentionally retained until their acceptance criteria are met. A missing benchmark is not the same as missing code. Do not turn a partial finding into PATCHED or VERIFIED merely by moving the goalposts.

| Finding | Outstanding source scope | Outstanding validation |
|---|---|---|
| A02 | Additional scripts/fonts only if broader recognition is intended; current Latin contract remains explicit. | Actual device OCR, text extraction and multilingual corpus. |
| A05 | Explicit PDF metadata scope is implemented; arbitrary hidden/opaque content is outside that scope unless a separate privacy contract is designed. | Adversarial PDFs, raw bytes and provider failures. |
| A06 | Broader support for view-dependent/unsupported form and annotation appearances; currently fails closed. | Rendered before/after comparisons and form fixtures. |
| A07 | Broader nonstandard payload sanitization if claimed; reachable standard references already handled. | Malicious corpus, independent parser, serialized bytes. |
| A08 | PNG/JPEG original-resolution strip export, trim, backdrop, indexed brush replay, straight-alpha decode and stream validation implemented. Input codec parity and measured optimization remain. | Desktop 128 MP codec tests passed; on-device straight alpha, EXIF, actual region decoder memory and mask quality remain unverified. |
| A11 | Structural default and explicitly consented image-only mode implemented; additional color/interactive fidelity coverage may reveal defects. | Rendered comparisons, signed and encrypted input handling, device raster export. |
| A14 | No specific remaining implementation defect established by the latest evidence; investigate failures rather than assuming closure. | Preview/export equivalence and native cancellation. |
| A15 | Versioned original digests, canonical page identity, orphan avoidance, legacy migration checks and corrupt-cache rejection implemented. Legacy sampled decoding is not full pixel integrity. | Process death, simultaneous saves and storage failure injection. |
| A16 | Visual exact-path selection, metadata inventory binding, alias exclusions and explicit unread-content consent implemented. Heuristic detector coverage remains. | Adversarial credential fixtures and provider UI. |
| A17 | o200k_base and cl100k_base exact artifact budgets implemented; packing efficiency, other model encodings and independent native-format parser interoperability remain. | Large SAF trees, provider errors, tokenizer parity and Android resource/runtime acceptance. |
| A19 | Bounded FIFO every-frame transport with checksummed RGB, exact PTS, cancellation and 216,000-frame ceiling implemented. Retained region/filter budgets may still require splitting. | Desktop VFR/FIFO tests passed; Android FIFO, detection recall, rotation, device codecs and rendering parity remain. |
| A21 | Generic target generation gates and owned bundle selector/review teardown implemented. Broader controller/binding lifecycle decomposition remains. | Navigation, activity recreation and service cancellation stress. |
| A22 | No independent attribution-resistance claim can be established from source fixes alone. | Held-out consented multi-camera original/export corpus and independent detectors. |
| A23 | Investigate quality failures from evidence; production EIS path already corrected. | Real footage, crop and tracking quality, multistream scenarios. |
| A24 | Pinned U2-Net-P/MODNet graph activations inspected; downloaded bytes now revalidated. No additional activation change justified. | Actual CPU/Vulkan inference and photographic mask quality for both models. |
| A25 | NCNN transaction gate and asynchronous retirement, verified model snapshots and LiteRT delegate lifetime implemented. Other native runtimes need evidence-driven investigation. | Native concurrency, cancellation latency and memory stress. |
| A26 | Portable kernels, bounded brush sweep and cheaper cancellable upscale PNG verification implemented. Neural tile/model changes require measured seam/fidelity evidence. | Native/device benchmarks and whole-image versus tiled quality. |
| A27 | No specific outstanding implementation defect established by the latest encoded-artifact checks. | Real encoded video/GIF/APNG fixtures, camera scans and physical print tests. |
| A28 | XLSX geometry, number/date formats, cached formula disclosure, dimensions, supported styles/colors and shared-edge borders implemented. Broader Office semantics, charts, conditional formats, arbitrary fonts, and comprehensive streaming limits remain. | Office corpus, reopen, rendered fidelity and device memory comparisons. |
| A29 | Architectural decomposition along ownership boundaries; avoid blanket refactors without coverage. | Lifecycle/regression suites across affected workspaces. |

Evidence checkpoint: CI run [44733311963](https://github.com/sahir247/VeilFrame/actions/runs/44733311963) passed on `5aeb53e`, including Android ARM64 debug build, complete Android unit suite and APK verification. Signed release and physical-device acceptance did not execute. Subsequent remediation commits are not covered by that result; their own CI and device checks remain pending.

## Implementation log

- Ledger initialized before fixes; no tests executed yet.

### A01 — Searchable PDF destination

- Required destination stream before writing; null stream now enters existing IO failure path instead of returning successful page count.
- Source fix only; provider failure regression and Android CI pending. No claim of device validation.

### A18 — rollback failed Motion Lab gallery exports

- Track newly inserted row; require successful pending publication; remove incomplete row in finally including cancellation.
- Source inspection only; Android CI and targeted regression acceptance pending.

### A20 — distinguish manifest signature validity from signer trust

- Explicitly state embedded-key signature validity does not establish trusted publisher identity or media authenticity. No trust bypass or verification change.
- Source inspection only; Android CI and targeted regression acceptance pending.

### A13 — Remove silent text-only DOCX fallback

- Engine retains formatting-aware OpenXML and POI attempts but propagates cancellation; rejects conversion failure instead of emitting text-only PDF or interpreting binary DOCX as text. Failed primary output is removed before retry. Removed duplicate controller fallback as well; existing UI reports conversion failure and removes unhanded output.
- Fidelity differences between the two formatting-aware renderers still need fixtures (A28). Android CI and malformed-DOCX regression pending.

### A08 — report actual bounded matting resolution

- Replace false full-resolution claim with actual decoded dimensions when resolution is reduced. Original-resolution compositing and other model paths remain open.
- Source inspection only; Android CI and targeted regression acceptance pending.

### A09 — Source import ownership

- Cancel superseded image imports, generation-check accepted results, atomically replace URI/bytes/preview on Main, recycle abandoned decoded bitmaps and invalidate pending imports on cleanup/discard. Refuse source switching during active removal and reject outdated dialog callbacks. File-backed import and export bitmap leases remain separate (A08/A10).
- Rapid selection/back-navigation device regression and Android CI pending; full native-job cancellation audit remains A25.

### A05 — Remove explicit PDF metadata recursively

- Clear Info plus `/Metadata` and `/PieceInfo` throughout the reachable PDF catalog graph using identity-based traversal (cycles/shared dictionaries safe). Added save/reload regression for catalog, page, nested resource and Info entries. This intentionally does not claim anonymization of visible content, opaque image bytes, attachments or orphaned objects; scope wording and full physical-byte privacy validation remain open. CI has not executed the new fixture yet.

### A16 — Heuristic secret masking and honest output

- Expanded recognition of private keys, fine-grained tokens, JWTs, credential URLs, bearer tokens and quoted assignments; centralized the masking implementation. Replaced unconditional SECURE status and added sharing-review guidance for both input modes. Added synthetic regression tests; CI pending. This remains heuristic and cannot establish absence of secrets; pre-export per-file review and explicit omissions remain open.

### A17 — Valid bundle JSON and explicit input failures

- Both tree and single-file JSON export quote keys/project names with JSONObject.quote; folder traversal deterministic, missing/empty inputs rejected, single-file input checks estimated token budget, UI shows evaluated/included counts. Full streaming, exact tokenizer and per-file omission reasons remain open. Also corrected A16 matcher ordering so the full Authorization: Bearer value is masked before generic assignment matching; extended fixture. Tests/CI pending.

### A04 — Preserve genuine OCR confidence

- Null word/page confidence explicitly means unknown; reject non-finite and out-of-range scores. Aggregate only when all words have known scores. Removed fixed 0.9/0.85 values. GitHub source search located the result types in the two changed OCR files. Added synthetic aggregation regression tests; CI pending.
- Follow-up: OCR engine failure currently resolves empty results rather than propagating an error. Track A31; also ensure ML Kit bitmap lifetime across cancelled tasks.

### A12 — Isolate compression scratch ownership

- Every regular and target-size compression call creates a unique scratch directory; optimizer/rasterizer candidates live only in it. Cleanup deletes only that directory, including early failures. Strict-target fallback ownership is explicitly transferred to a unique cache file before returning the exception. Concurrent compressions cannot delete another operation's files using the old prefix sweep. CI and concurrent target-size tests pending.

### A32 — Reject incomplete raster compression candidates

- New source finding while implementing A12. A thrown page-render/encode error now aborts that entire raster candidate; the existing candidate-level cleanup deletes it, and the caller can retain a valid structural candidate or original. Verify output page count before saving. No intentional relaxation of any release guard. Actual failed-page fixtures and CI pending.

### A15 — Atomic scanner persistence

- Use Android AtomicFile for page images, thumbnails and JSON manifest; fail failed bitmap encoding; recycle newly generated temporary thumbnails; serialize writes per session instance and read manifests through AtomicFile recovery. Persistence exceptions now logged rather than silently discarded. This protects individual files, not an entire multi-file snapshot or concurrent instances of the same session; explicit UI persistence status and cross-file versioned recovery remain open. CI/process-death test pending.

### A31 — Propagate OCR task errors and retain input lifetime

- Shared recognition path now awaits actual ML Kit task completion before releasing the caller's bitmap. A task failure or cancellation raises an IOException rather than pretending recognition returned no text. Caller coroutine cancellation is checked before submission and after completion; cancellation can wait for one in-flight ML Kit task (the underlying API has no cancellation handle). No extra full-size bitmap allocation is introduced.
- PDF processing already propagates recognition errors via its existing error result. Image OCR's higher-level fallback/outcome still needs explicit empty-versus-failed status and UI acceptance; therefore A31 remains PARTIAL. Task cancellation/bitmap lifetime and failing recognizer regression tests and CI pending.

### A02/A03 — Searchable OCR text fidelity and geometry

- Reuse an embedded Type 0 subset font, preserve recognized Unicode rather than filtering code points, remove the arbitrary 1,000-word truncation, fail closed on missing glyphs or geometry before writing destination. Bundled font is not universal; additional script-specific fonts and extraction fixtures remain A02 PARTIAL.
- Map actual bitmap coordinates through crop origin and all four page rotations, orient baseline and horizontally fit invisible text to recognition bounds. Added pure JVM four-rotation fixture; rendered-PDF integration and CI pending.

### A06 — Flattening must fail rather than silently discard content

- Preflight annotation appearance/rectangle validity, fit transformed appearance bounds, remove annotations only after drawing succeeds, fail on residual fields or annotations, propagate cancellation and close PDF/streams on all paths. This deliberately refuses unsupported appearances instead of producing incomplete success. Raster geometry and PDFBox form appearance behavior still need fixture testing; A06 remains PARTIAL. A07 sanitization is independent.

### A11 — Structural compression is the safe default

- Removed automatic full-page raster fallback from regular and both target-size compression entry points. A smaller size cannot justify loss of searchability, vector or interactive content without consent. Kept raster engine private for a future explicitly authorized mode; current UI uses structural compression only. Also stopped incidental metadata pruning in structural compression and bounded strict target fallback by original size. Explicit raster opt-in UI and semantic save/reload regression fixtures remain pending; A11 PARTIAL. Signed PDFs still require separate signature-preservation policy.

### A24 — Confirm bundled model activation and fix production double sigmoid

- Inspected the actual packaged `assets/models/u2netp/u2netp.ncnn.param`: layer `convsigmoid_112` produces `out0` with convolution parameter `9=4`. NCNN's [convolution loader](https://github.com/Tencent/ncnn/blob/master/src/layer/convolution.cpp) reads parameter 9 as activation type; [fused_activation.h](https://github.com/Tencent/ncnn/blob/master/src/layer/fused_activation.h) maps type 4 to sigmoid. Removed the redundant sigmoid from production nativeProcess; reject nonfinite model output, bound probabilities and retain upstream saliency normalization. Legacy benchmark comparator intentionally unchanged; numerical CPU/GPU parity and saliency-vs-opacity quality are unmeasured. PARTIAL pending native/device validation.

### A10 — Lease cutout pixels across asynchronous exports

- Added identity-based resource leases with idempotent completion release. Sticker, save, clipboard and share acquire before scheduling, and retirement on source/model/refinement/discard paths defers recycling until all readers finish. Refine brush already copies pixels. Unique shared/clipboard files prevent overwrites; gallery publish failures are checked; stale share/clipboard/SAF result callbacks are gated by source generation. Added lease lifecycle unit fixtures. CI and device race acceptance pending; temporary-file retention policy remains a separate concern.

### A07 — Traverse standard PDF active-content references

- Replaced catalog-only best effort with identity-safe recursive COS graph processing and post-mutation verification. Remove standard JavaScript action references/name entries (including indirect and chained actions), associated/embedded file references and file-attachment annotations when requested, preserve unrelated navigation, fail on unsupported XFA rather than claim script removal. Added serialized/reloaded nested action and attachment fixture. This is reachable PDF-object sanitization, not a proof of removal of arbitrary opaque content or unreferenced bytes; full adversarial corpus and physical-byte checks pending. PARTIAL.

### A05/A11/A30 — Match workspace labels to actual output contracts

- Removed the false “Strip All Forensic Traces” label. Explicit metadata-removal scope and exclusions are visible before export; A05 remains PARTIAL pending raw-byte validation. Compression UI now describes the structural-only path (A11 remains PARTIAL pending explicit raster opt-in and semantic fixtures).
- Rename visual signature stamping in the card/tool header; explicitly distinguish it from cryptographic signing. The verification card describes certificate trust rather than suggesting authentic third-party checkmark stamping. A30 PATCHED; verify layouts and UI on device.

### A14 — Fail selected scanner transformations and distinguish render revisions

- Invalid or failed selected crop no longer silently exports an unwarped image. Propagate warmth/filter failures and clean intermediate bitmap allocations. Detached preview render snapshot and version-checked acceptance reject superseded results; dispose discarded results. Report render failures on the active page. Processed preview/disk cache reuse for export is restricted to matching edit revisions; manifests do not claim old processed files match current edits. Initial imports mark unprocessed previews explicitly. The export fallback re-renders or fails rather than returning an unprocessed original. Persistent generation transaction, background auto-crop ordering and device acceptance remain open; A14 PARTIAL.

### A06 — CI feedback and visible-widget preflight

- Actual Android job 135896920460 reported one failure out of 1,365 tests: `NoSuchMethodException` in `PdfOperationsFindingsRegressionTest:78`. Kotlin internal function name mangling invalidated the old reflection call, not the flatten assertion. Use the existing test's direct internal API, retaining both count and empty-form assertions. Restrict appearance refresh to attached visible fields that require regeneration; reject XFA and missing visible appearances before removing widgets. Re-run CI to validate the actual flatten behavior; no test skipped or weakened.

### A17 — Bounded streaming bundle construction

- Both SAF tree and single input use one suspendable engine, bounded 500 KiB reads independent of provider-reported size, strict UTF-8, deterministic traversal with cycle/depth guard, per-entry omission reasons and an explicit estimated section-only budget. Content and omission bodies spool to isolated scratch files, then stream into a staged output rather than retaining the whole project in RAM. Cancellation/failure cleans scratch; controller catches bundle errors; unique output names. Native format conflicts are explicitly omitted with JSON alternative; Markdown fences adapt to content. Full model-specific tokenization and native parser interoperability acceptance remain open, so A17 PARTIAL. CI and large-tree/cancellation tests pending.

### A31 — Distinguish no recognized text from failed recognition

- Image OCR outcome carries explicit TEXT_FOUND, NO_TEXT, INCOMPLETE or FAILED; failed wrapper/direct paths cannot masquerade as a successful blank recognition. Direct decode failure is an error. Existing reachable PDF Extract Text UI already distinguishes thrown recognition errors from genuinely empty output; switched that caller to temp-file PDFBox load and memory-bounded DPI. Source search found no current caller of the single-image outcome method outside its definition; it retains typed status for future callers. CI for these changes and device/task fault-injection pending.

### A15 — Versioned scanner persistence and observable recovery failure

- New originals stored losslessly; processed image and thumbnail generation paths are immutable until a single atomic manifest commits them. Snapshot page metadata/corners, update live cache paths only when edit revision still matches, and remove only obsolete generation-scoped processed caches after commit (originals retained; missing cache can re-render). Unchanged saved revisions do not re-encode. Save returns explicit success/failure; scanner alerts user about failed recovery persistence and activity restoration only records a recoverable manifest. Cross-instance locking, full UI-thread snapshot ordering and process-death fault-injection remain pending, so A15 PARTIAL. Device/CI tests pending.

### A14 follow-up: page-specific failure diagnostics

CI run 44620999275 / Android job 135902300549 executed 1,368 tests with one failure, `DocumentExportEngineTest.failed zip page removes incomplete archive instead of reporting success`. The tightened renderer threw an IOException lacking the page index. All five export routes now wrap page acquisition failures with the exact page number and cause; cancellation remains unwrapped. The existing cleanup regression assertion is unchanged. Re-run required.

### A16 follow-up: defensive file exclusions and explicit transfer review

Mask-enabled bundle generation now excludes known credential containers by case-insensitive filename, even if their contents do not match a secret regex, including single-file input. Omissions remain visible. All four workspace save/share buttons use a bounded on-device preview and explicit confirmation before handing the bundle to Android storage/share. Large previews explicitly show only the first 12,000 characters; unreadable bundles cannot be transferred through these controls. Added filename regression coverage. Full per-file review/edit workflow, broader detector validation, and device UX testing remain open; no claim of complete secret detection.

### A19 — Align standalone video mask timeline and prevent false clean state

- Traced actual reachable caller in VideoStudioController and standalone FFmpeg renderer: trim-relative scan times were baked into the full untrimmed input. Scan the exact full source duration now, with an exclusive final timestamp, overflow-safe sample count and immutable display geometry derived from encoded dimensions/rotation. Abort if decoded frame aspect disagrees rather than mixing coordinates.
- Negative scans never clear prior masks, stale dialog/apply callbacks cannot modify another item, baked masks are not advertised as clearable, cancellation dismisses its own progress dialog. All detected classes are initially selected; review guidance explicitly warns about sampling gaps, weaker blur/pixelation and unchanged audio. Added sampling and display-geometry regression tests. Still PARTIAL: exhaustive frame decoding, actual orientation parity (including 180-degree/video display matrices), temporal render fixtures and Android CI/device validation remain open.

### A08 — Restore original RGB resolution within explicit memory budget

- NCNN and ColorOS Lift & Shift now separate bounded neural-analysis resolution from the original RGB decode when the conservative memory admission check permits (24 MP maximum, 16 bytes/pixel allowance plus 24 MiB reserve). Below that admission threshold both paths report the actual reduced pre-trim size; no unconditional full-resolution promise. A bilinear scanline compositor resamples alpha without a full-resolution second mask and multiplies source alpha to avoid resurrecting transparent pixels. ColorOS no longer allocates a full-sized OpenCV mask and byte array and rejects non-finite model output. Added original-size admission and scaled alpha fixtures. PARTIAL: native memory pressure is not fully represented by the Java heap estimate, extreme-resolution streaming output requires a file-backed API, and device quality/peak memory plus CI are pending.

### A21/A20 — Workspace visibility ownership, stale navigation and provenance audit bounds

- WorkspaceHost now owns secondary-root hiding and active identity across all routes (not only QR). A preserved outgoing animation root is not active; stale transition completion callbacks cannot hide a later destination. Back prompts bind to their original screen, explicit discard/background decisions are not repeated by navigation cleanup, and QR's duplicate exit cleanup was removed. Activity destruction releases QR/provenance and clears host bindings after controller cleanup. Added host regression for active/inactive/animation visibility.
- Provenance becomes a real work-state contract: repeated selection and discard cancel the previous audit, generation-check publication, and bound ZIP inspection at 4,096 entries, 32 MiB inflated data and 1 MiB manifest. Errors say inspection incomplete, never no manifest. Correct the A20 wording: AppUpdateManager checks the app-pinned update public key, not arbitrary archive signers. Manifest validation does not verify archive payload, and standalone artifacts were not inspected for embedded signatures. Android tests and lifecycle stress pending; activity-level view retention remains intentional until controller binding ownership is redesigned. A21 PARTIAL.

### A11/A28 — Prevent image-semantic loss and invalid signature rewrites during compression

- Source inspection found that the “structural” optimizer also flattened transparent images onto white and deduplicated decoded bytes with MD5 independently of image dictionary semantics. Removed raw-byte dedup; retain only identity-based indirect-object reuse. Limit JPEG replacement to plain opaque 8-bit DeviceRGB images, preserve masked/custom/annotated image dictionaries, bound image decode size, and retain original when a candidate cannot be reopened with the original page count.
- All three public compression entry points preflight PDF page count, modification rights and signature/rights dictionaries before writing. Strict target failure returns an independently owned fallback file without also writing a failed candidate to the destination; targets already met pass through unchanged. Added COS eligibility and document preflight tests. PARTIAL: explicit raster opt-in, signed-file UI acceptance and rendered appearance/color-profile integration fixtures remain open. No claim of arbitrary PDF fidelity.

### A23 — Stabilization source ownership and baked-state truthfulness

- Standalone libvidstab analysis and rendering explicitly select the same video stream; first-pass progress uses source duration when no trim is requested. The render retains the first audio stream if present.
- EIS dialog guards callbacks by workspace generation and selected source, disables the false undo action for baked footage, and discloses tracking limitations. Stabilization waits for source copy, uses a unique output path, and dismisses progress on cancellation.
- Remaining: multi-stream, scene-cut, moving-subject and low-texture device fixtures; frame-quality and crop measurements. Android CI/device verification pending; no tracking-quality claim.

### A02/A05/A06/A07 — Verify actual serialized PDF output before delivery

- Added a common staged full-save/reopen pipeline with page-count and operation-specific checks, unique scratch ownership, cancellation-aware copy and no destination opening before verification. Signed/rights-restricted rewrites fail closed for OCR, metadata and flattening. Metadata input streams close promptly; cancellation propagates. Metadata removal checks saved Info and reachable explicit metadata; script/attachment removal verifies the saved graph. This is not a proof that all opaque payloads or arbitrary hidden information are gone.
- Searchable OCR uses lazily embedded bundled font fallbacks and rejects unencodable text, maintains Latin-recognizer scope, uses the crop-box DPI and verifies serialization. Added Unicode save/reload text-extraction fixture; non-Latin recognition is not enabled. Raster flatten uses the inverse rotated crop geometry, rejects inconsistent flatten options and does not draw hidden/invisible/no-view annotations. Added staged-rejection, crop corner and physical Info-marker fixtures. CI and rendered document corpus pending; keep partial status until evidence arrives.

### A14/A15/A21 — Scanner generation ownership and ordered snapshot persistence

- Captured autosave metadata on the controller's UI owner before asynchronous persistence, rather than sampling a later session on IO. Shared striped session locks and process-epoch monotonic manifest tickets prevent older queued writes from overwriting a newer successful save, including separate instances. Persisted manifests retain their captured session identity; discard starts a fresh ID and does not overwrite a recoverable saved session. Restore keeps active index and timestamps. Added snapshot ordering and identity tests.
- Import/restore jobs are canceled and generation-gated on discard; unaccepted decoded bitmaps are recycled. First-pipeline burst results are accepted for the matching page even when it is not selected; stale outputs are recycled. Auto-crop no longer overwrites a prior crop with a failed detection and every page render uses detached revision-gated acceptance. Duplicate pages do not label stale previews as current. PARTIAL pending process-death, delete-vs-save, camera/native-cancellation and bitmap ownership stress tests; no claim of complete multi-process snapshot safety.

### A16/A17 — Complete local review navigation and bounded serialized bundle output

- Added previous/next local review pages for the whole exact artifact, not just a first-page dialog. Transfer stays blocked on unreadable/changed files and while a page loads; users explicitly choose transfer after review (not a claim that every page was read). UTF-16 offsets avoid splitting surrogate pairs. Added reader roundtrip and additional credential pattern fixtures.
- Mask recognized secrets in exported path/project labels as well as content, exclude more credential containers, omit duplicate serialized paths, limit path size, traversal count and total output to 32 MiB, and enforce the estimated UTF-8-byte/4 budget on the complete serialized output including omission report. Fail closed if the report cannot fit rather than silently discard it. The estimate is not model-specific tokenization; native-format interoperability, provider stress and missed-secret validation remain PARTIAL.

### A25/A19 — Native GPU lease accounting and PII model ownership

- Checked the actual bundled NCNN gpu.h API: create_gpu_instance returns an integer status. Failed creation no longer mints a GPU lease; RealSR releases only its own successfully acquired GPU lease and does not query GPU state without one. Matting declares the GPU lease before its candidate network, ensuring failure unwinding destroys the network before the lease. RealSR destruction flags orphaned/in-flight handles canceled; cancellation is sticky for the per-attempt handle and is not erased on entering inference.
- Shared PII singleton networks now have transactional pair initialization and native owner counting, with idempotent per-engine release and synchronized detector/close operations. A background-removal engine closing its temporary PII detector cannot unload another scanner's models. Null/invalid native inference output is failure rather than a clean result; the multimodal orchestrator already surfaces thrown detector failures. Same-engine close waits for native calls; no claim of interrupting GPU shaders or bounded native mutex latency. PARTIAL pending ARM64 CI, native stress and cancellation timing tests.

### A19/A25 — Preserve partial observations and signal native cancellation immediately

- Extended the intervening native ownership commit without reverting it. RealSR global teardown drops retired instances outside the registry mutex, checks cancellation immediately after mutex admission and at model-load/output boundaries, and the Kotlin job signals on cancellation transition rather than eventual completion. Added ARM64 instrumentation for cancel-before-load and cancel-before-stream behavior. Multi-modal PII now retains successful NCNN observations even if the other NCNN detector fails, while marking the scan incomplete; strict list callers still reject partial results. Device timing, same-engine lock latency and native validation remain pending.

### A26/A29 — Full-resolution streamed upscaler finishing and kernel halos

- Found that every streamed neural output and large algorithmic output applied detail enhancement to preview pixels only. Exports now go through a shared bounded full-resolution PNG finishing stage, with exact disjoint core rows and real neighboring source halos for Lanczos and local contrast. Preview is decoded from the final verified artifact. Hybrid refinement uses the same stage; intermediate outputs and failure/cancellation staging files are reclaimed. Conservative band/disk admission precedes output creation.
- Extracted the duplicated band pipelines into StreamingImagePostProcessor; added a deterministic alpha-preserving scanline detail kernel, avoiding silent no-op and signed high-pass truncation in the former OpenCV fallback. Streamed and in-memory production paths use that same kernel. Added odd-size band coverage and whole-image-versus-band pixel/alpha regression tests. This does not establish neural tile seams or real-photo fidelity. PARTIAL pending ARM64 benchmarks, performance measurements and actual pixel parity across native resampling implementations.

### A27/A22 — QR artifact publication and device-test coverage

- All six QR MediaStore publication paths now require exactly one row to be updated; failure stays inside the existing pending-row cleanup boundary. Check cancellation immediately before publication and propagate cancellations through the storage result boundary. Unit-test the publication invariant and add an instrumented WeChat decode round-trip through actual PNG and JPEG encoded bytes rather than just the source bitmap. This does not certify artistic styles, printers, or camera capture; physical device execution remains pending (PARTIAL).
- Corrected a missing assertion import in the PRNU instrumentation fixture. A22 remains OPEN for independent attribution-resistance evaluation; passing the fixture does not establish forensic anonymity.

### A27 follow-up — Enforce the actual raster and printable QR publication gate

- Removed the four QR Studio export/share bypasses. Strict report, non-skipped validation and exact payload are required. PNG/JPEG are encoded once, reopened and validated before those exact bytes are saved/shared. Share uses a uniquely owned FileProvider artifact and returns typed failures. Printable QR export passes its actual matrix/design and fails closed on missing/unreadable PDF, renders the centered QR bounds rather than treating the portrait page as a QR grid, and fits the source within both page axes. Added exact-payload/skipped-validation and encoded-byte regression coverage. No QR design/matrix parity changes. PARTIAL pending host and real-device verification, animated artifact coverage and print/camera scanability.

### A28/A29 — Office conversion publication and slide-by-slide ownership

- All Office-to-PDF render routes now stage in the destination directory, reopen to verify a nonzero and consistent page count, check cancellation and atomically replace only after verification. Prior artifacts are retained on conversion failure; DOCX fallback no longer deletes them. XLSX keeps its original natural/extended/tiled layout policy. PPTX rendering propagates fatal failures rather than returning a successful partial slide list, checks dimensions and bulk bitmap budget, releases in-progress buffers on failure, and PDF conversion consumes one slide bitmap at a time. Added empty/malformed-input destination-preservation tests. This is not complete Office fidelity: shape-level fallback, Unicode coverage, unsupported chart/layout features, large PDF native allocations and full corpus comparison remain PARTIAL.

### A22 — Privacy output integrity (not external attribution validation)

- Privacy image exports now use a unique same-directory stage, reject malformed input/format mismatch, verify reopened dimensions, container MIME and the existing limited common identifiable EXIF check, then atomically publish. Failure preserves prior destinations; unreadable transformed output cannot return success. Metadata-only mode refuses pixel re-encode fallback and preserves display orientation. Removed the unsupported absolute-zero metadata claim and redundant post-encoding EXIF rewrite. Updated the regression that previously required success for a malformed dummy image; added prior-destination preservation. This does not establish PRNU suppression, independent camera unlinkability, or exhaustive metadata absence. A22 remains OPEN for independent attribution study.

### A19 — Correct temporal interpolation, outward mask bounds and video stream selection

- Interpolate at actual observation times, with separate static endpoint lead/trail regions. The previous padded interpolation shifted moving masks away from known observations. Do not extend track identity association beyond 1.35 seconds on sparsely sampled sources. Bound track intervals to source duration.
- Reject malformed selected masks; round spatial bounds outward to chroma alignment, round starts down and ends up, request exact crop, and map the first video and optional audio tracks consistently in both filter styles. Reject ambiguous multi-video sources before scan and render since MMR lacks track selection. Unique intermediate file and progress dialog cancellation cleanup. Added observation coverage, rounding and malformed-geometry regressions; corrected device test assertion import. PARTIAL pending actual device rendering, rotation/variable timestamp parity and exhaustive frame decode.

### A21/A29 — Scope FFmpeg cancellation and repair Motion Lab trim ownership

- Removed the process-wide Motion Lab FFmpegKit.cancel call and cache prefix deletion. Introduced a shared coroutine-owned FFmpeg execution adapter for local trim and the foreground service's extraction/encoding/stabilization passes. Cancellation targets the exact session ID and awaits native completion before releasing scratch inputs/outputs. Unit fixture tests isolation and drain-before-return.
- Motion Lab trim now uses an accurately decoded seek rather than keyframe stream copy, validates output duration, commits directly on the UI owner (the former setSource call rejected its own active trim job), generation-gates late results, and owns only its own unique temporary inputs and trimmed source. Trim re-encodes H.264/AAC; this trades some time and generation loss for accurate clip boundaries. Foreground Motion Lab encoding stages and publishes only on the matching RUNNING job; cancellation retains a prior destination. Reject duplicate service starts while earlier native work drains; do not relabel cancellation as failure. A21/A29 PARTIAL pending Android CI and background/service lifecycle instrumentation; no global release gates changed.

### A22 — Attribution benchmark methodology (not external validation)

- Removed self-correlation inflation from the multi-camera PRNU baseline. Both conditions now query the same enrolled fingerprint using held-out original and paired transformed probes. Reject absent/unpaired probes, incompatible geometry, nonfinite and empty/degenerate enrollment, and exact enrollment/probe overlap. Synthetic fixtures separate enrollment and original probes. Pair-only analysis remains a same-recording residual diagnostic. ROC threshold ties are grouped: a constant classifier must report AUC 0.5. Added null-effect, leakage and tie regressions.
- Report matched/nonmatched camera-level pair counts and the fixed-threshold scope. Exact duplicate rejection cannot establish independent captures. Real Android output needs consented independent capture sessions, matched original/export files, frozen calibration and independent detectors; no anonymity claim. A22 PARTIAL pending device/corpus evaluation.

### A05 — Metadata rewrite raw-byte regression

Added a loaded-document full-rewrite fixture with an uncompressed metadata stream and private author sentinel; checks the serialized bytes for retained payload and independently verifies the reopened document structure. Removed shared mutable date formatter from concurrent metadata reads. Scope remains explicit PDF metadata, not opaque attachments or image payloads. Pending CI; A05 remains PARTIAL.

### A14/A15/A21 — Explicit deletion wins over autosave and interactive crop owns user input

- Library deletion now runs on IO, reports filesystem failure, serializes manifest enumeration with writers, and retires the deleted session identity for the remainder of this process. A pending or live autosave cannot recreate it. Removing the last page also retires the previous document identity rather than silently leaving an obsolete one-page recovery document in Library. Added a recovery/deletion regression; validate process-death and physical storage failure separately. Library refresh captures UI filter before IO and rejects obsolete query results. Reject path traversal / mismatched manifest session identities.
- Crop detection results are scoped to dialog generation, current page identity, request ticket and a monotonic user interaction revision. A drag beginning during inference, reset, dismissal or workspace switch cannot be overwritten by an obsolete detection. The crop view returns detached mutable PointF values rather than leaking internal state; detection cancellation propagates. A14/A15/A21 remain PARTIAL until device lifecycle, concurrent native and recovery acceptance.

### A11/A28 — Structural compression validates serialized drawing commands, geometry and annotations

- Correct source-stream reuse: XObject resource names are local to their subdictionary; cache on the actual underlying COSStream identity, not a lookup in the outer resource dictionary. Skip full saves when no eligible image becomes smaller. Encrypted inputs now fail closed rather than promising to preserve encryption without an owner-password/permission workflow.
- Reopen original and candidate and compare every page's decoded drawing-command SHA-256, five page boxes, rotation, UserUnit and ordered annotation subtype/name/rectangle. A mismatch rejects the candidate. This guards structure, not arbitrary rendering/interactive equivalence. Add changed-command/geometry fixture. Copy source and destination with cancellation checkpoints, reject invalid targets, avoid false 100% progress before writing and call an unmet target the smallest tested size rather than a theoretical minimum. No automatic rasterization. Remaining: explicit opt-in raster workflow, image/color/interactive fixture corpus and device profiling. A11/A28 PARTIAL.

### A11 — Activate the existing image-only compression choice with explicit consent

- The Quick Tool already displayed a Full Re-Render chip, but every execution ignored it. Its quality labels were also opposite the engine's compression-intensity parameter. Wire the chip to an independent image-only route, reset to structural mode when the workspace opens, require explicit generation-bound consent for every lossy conversion, and correct the quality-to-compression mapping. Image-only filenames are distinct; the app refuses signed/encrypted/rights-enabled input and never automatically rasterizes in structural/target mode.
- Render pages into a temp-file-backed PDF, transfer file-descriptor ownership correctly, validate page count before opening the sink, propagate failures/cancellation and reject a raster candidate that is not actually smaller. On-device rendering, pixel/geometry and visual fidelity fixtures still pending; A11 remains PARTIAL until tested. Added quality-direction fixture.

### A14/A15/A21/A29 — Document export snapshot and manual perspective contract

- Capture page ordering, geometry, revisions and strong bitmap references before scheduling export. Reject concurrent exports, cancel on discard, gate publication by session generation, use unique output names and reject empty documents. Image batch publication now requires a non-null sink and exactly one updated MediaStore row, deletes failed pending rows, and reports partial external saves without losing internal exports.
- Validate ordered manual quad geometry before committing or rendering (finite, within source, convex, non-crossing), stop subpixel refitting and automatic post-warp rotation from contradicting chosen crop coordinates, and release failed perspective/deskew matrices. Added snapshot/order and invalid crop tests. Device preview/export comparison and bitmap/native cancellation stress still pending. No claim of all device acceptance tests executed.

### A02/A05/A06/A07 — Verify the actual OCR text layer and protect encrypted inputs

- For every newly written page OCR layer, retain a length-prefixed SHA-256 digest of the exact recognized Unicode words. Reopen the staged PDF, parse the appended content stream, decode character codes through the saved font's actual Unicode mapping and compare the digest before opening the destination. No pre-existing text can substitute for the appended layer. Use PDF invisible-text rendering mode in addition to alpha. Bound verification per page; fail closed on unsupported/unmapped characters. Extended the Unicode roundtrip fixture to verify actual layer bytes and distinguish an accent-stripped result. Latin recognizer scope remains unchanged.
- Shared verified PDF rewrite preflight now refuses encrypted inputs rather than implicitly changing access/encryption semantics. Physical-device OCR recognition accuracy, broader fonts and non-Latin scope are not established. Keep findings partial until new CI and acceptance evidence.

### A16/A17/A21/A29 — Reviewed AI artifact ownership survives the system picker

- Previously the generic CreateDocument callback fetched the current workspace result instead of the source reviewed before opening the picker. Freeze a uniquely owned, bounded private copy before review; both saving and sharing transfer that same immutable snapshot, never a later generated artifact. Generic exports also capture their source at picker launch. Cancel/failure removes review scratch; delivered share snapshots are retained for the recipient and age-reclaimed after 24 h. A lost pending source after process recreation fails closed. Added source-replacement regression and strict UTF-8 review decoding.
- Match escaped quoted credential assignments and YAML block credentials, reject whitespace/CRLF native delimiters and unsafe exported paths, and require atomic same-directory bundle publication. Heuristic secrets coverage remains incomplete, independent native-format parser and provider/device validation pending. No guarantee of secret-free output.

### A06/A07 — Reject view-dependent flattening and preserve name-tree structure

- Refuse form widgets whose conditional visibility, rotation or invalid bounds cannot be faithfully flattened. Refuse view-dependent visible annotations and nonfinite geometry before modifying content. Name-tree arrays now remove forbidden key/value pairs together rather than leaving malformed odd-length arrays; leaf limits are updated when present. Added a loaded-document script sentinel removal and surviving pair regression. Arbitrary nonstandard embedded payloads and device-rendered form/annotation comparisons remain outside proven coverage.
- Follow-up: correct the escaped-quote test fixture to use one actual escape and reduce regex backtracking on long quoted/block secrets. CI pending.

### A08/A24/A25 — Strict matting tensor contract, actual decode bounds and device parity fixture

- Confirmed bundled U2-Net-P out0 is a single 320x320 sigmoid plane. Reject failed input submission, wrong output dimensions, packed/non-float/multi-channel tensors, and materially out-of-range/nonfinite outputs before dereferencing. Store valid premultiplied white-mask pixels. Expose loaded GPU backend for device parity fixtures; the new ARM64 instrumentation compares CPU/Vulkan letterboxed alpha on the same odd non-square input and does not mistake CPU fallback for Vulkan. No claim that this synthetic fixture establishes photographic fidelity; MODNet/downloaded graph and device execution pending.
- Analysis decode now bounds the intermediate decoder allocation itself, not merely a post-decode scale (previously up to twice the edge). Original-resolution compositing still follows admission limits. Add per-row cancellation in compositing/edge operations, release failed edge intermediates, and feather inward to avoid resurrecting transparent-source holes or black fringe. Added decode-bound and transparent-edge regressions. Keep A08/A24/A25 PARTIAL pending device and real-image validation.

### A19 — Bounded dense video scanning and cancellable region construction

- Offer explicit standard (350 ms target/120 sample limit) or dense (100 ms target/600 sample limit) full-source scanning. Both are sampled, never exhaustive. Derive capped status from actual interval; refuse timestamp overflow and avoid unbounded fallback decoding above 16 MP. Cap retained detections at 12,000 with explicit failure rather than silently dropping observations. Cancel before or during temporal tracking, retire inactive tracks, and bound coalescing lookback. Replace quadratic repeated filter-budget merging with time-ordered conservative union buckets; every retained observation remains inside a mask, at the cost of possible extra obscuration. Added dense-count and cancellation fixtures. Device decode/timestamp/rotation, independent detection recall and exhaustive decoder mode remain PARTIAL.

### A19/A23/A25/A29 — Frame-indexed stabilization execution and native cancellation ownership

- General EIS pass 2 now explicitly maps the same video stream as pass 1. GIF temporal resampling runs after the frame-indexed transform, preserving configured speed instead of dropping setpts. Standalone EIS and redaction reject aliased input/output and reopen encoded output with MediaExtractor for stream, sample, dimensions and material duration checks before reporting success. This is container validation, not frame-level equivalence.
- The synchronous FFmpeg adapter used by VideoProcessor now drains its exact native session after interrupt/registration failure before returning ownership of scratch files. Repeated interrupts preserve the interrupted flag but do not release files while native execution remains active. Added thread interruption regression.
- Diagnostic OpenCV motion estimator now bases confidence on actual RANSAC inliers and rejects unsupported consensus, nonfinite and implausible similarity scale, using center-referenced motion. Empty diagnostic sequences cannot imply perfect confidence. Added confidence and GIF filter-order tests. Production EIS remains libvidstab, not the diagnostic OpenCV estimator; independent video quality and native device timing remain PARTIAL.

### A19 follow-up — Bound association work and preserve tiny observations

- Bound scaled decoder output dimensions, per-frame observations and active track/detection pair construction in addition to the existing whole-scan budget. Fail closed on nonfinite, degenerate or wholly out-of-frame observations; preserve positive sub-two-pixel observations with outward padding. Regression cases cover tiny detections, malformed geometry and overcrowded frames. Device verification still pending.

### A25/A26 — RIFE exception ownership and mathematically correct resampling

- Hold the RIFE initialization mutex through exception cleanup; a failed initializer must not clear a later successful model/GPU state after releasing its lock. Release any owned model before resetting its lease. Native concurrent-failure/device timing remains pending.
- Replace the Canvas bilinear implementation mislabeled Bicubic with a separable Keys cubic kernel. Portable Lanczos-4 and cubic filter premultiplied colors and unpremultiply only once, preserving transparent edges; use source halos and a fixed portable Lanczos backend across all streamed bands instead of switching native/portable kernels per band. Full opaque in-memory Lanczos may use native acceleration; bitwise native/portable parity is not claimed. The fixed portable streamed path trades throughput for consistent seams. Per-row cancellation and failed bitmap cleanup; add whole-versus-banded and alpha fixture. Neural tile quality, native throughput and physical-device testing remain PARTIAL.

### A28/A29 — Office preview and conversion use the same real format contract

- Reject legacy binary Office and RTF inputs instead of relabeling them as OOXML or plain text. Picker exposes implemented DOCX/XLSX/PPTX/plain text types; source format resolution is shared by preview and conversion. The user must convert legacy formats first. Preview must complete before enabling conversion; parser failure, missing stream and empty input are visible errors, not “Ready to convert”.
- Use unique scratch files, close POI document/presentation handles, check cancellation during copy and sampling, limit preview input to 128 MiB and bounded text samples, retain blank spreadsheet cell positions, and explicitly label schematic previews as content samples rather than PDF page layouts. Release obsolete preview bitmaps. Input-size limit is an admission decision for preview, not a guarantee of bounded decompressed OOXML memory; full fidelity and corpus/device validation remain PARTIAL.

### A28 — Reject partial workbook parsing and honor worksheet relationships

- XLSX now resolves workbook sheet relationship IDs in workbook order, including non-contiguous part filenames. Missing/duplicate/unresolvable or non-worksheet parts fail explicitly. Minimal old packages without relationships are accepted only where sequential IDs and the complete enumerated worksheet part set agree. A malformed later sheet no longer returns an apparently successful earlier prefix. Resolve shared-string relationship targets; reject unsupported prefixed worksheet structure instead of an empty result. DOCX XML parse errors now propagate instead of returning partial nodes.
- Added reordered/non-contiguous worksheet, malformed later-sheet and missing-part fixtures. Preview reports linkage failure explicitly and releases failed schematic allocations. Does not change natural/extended XLSX page sizing or establish formula/chart/layout fidelity. CI pending.

### A11/A28 — Shared PDF color resources and exact spreadsheet cell admission

- Skip structural JPEG optimization when the reachable PDF uses DefaultRGB color substitution. Track attempted original streams and already-produced replacements by identity, preventing repeated lossy recompression through shared page resource dictionaries. Reject transparency at original pixel resolution, before downsampling. Added color-resource graph regression; device rendering parity remains pending.
- XLSX now rejects out-of-range/mismatched/duplicate cell references, invalid shared-string indices, uncached formulas and oversized dense placeholder expansion instead of inventing or silently moving values. Preserve concatenated rich inline runs and whitespace. The new 2 million expanded-cell per-sheet limit is explicit memory admission, not a page width or row pagination change. Cached formulas remain cached values, not recalculated formulas. Added malformed-cell and rich-text fixtures. CI pending.

### A27 — Decode actual animated QR artifacts before publication

- The previous post-export verification extracted bounded 512px source frames and rendered new QR codes around them, so it could affirm a corrupt encoded QR. GIF/APNG/video publication now reopens the actual encoded output, decodes native-resolution frames without frame-rate resampling, validates every decoded frame's exact payload and strict report, and blocks missing, malformed or over-budget output. The cap is explicit (600 frames maximum and a 512 MiB temporary decode budget with an overflow sentinel); exceeding it is failure rather than partial success. Owned FFmpeg decode cancellation drains the correct session before deleting temporary files. The public URI verification route now validates decoded output rather than re-rendering; generated-frame verification recycles its render and checks exact payload. Added host admission fixture; actual encoded-frame/device and physical-camera validation remains pending. Frozen QR matrix/render parity unchanged.

### A21/A29 — Quick PDF operation ownership frozen at the execute event

- Capture merge-page order, split source, selected deletion/rotation/reorder pages, lock options, HTML and signature placement data on Main before dispatch. PDF-to-image and split no longer index the mutable live selection on IO. The generated signature bitmap is released when its job completes. Common export handoff requires the execution's captured generation; obsolete jobs cannot reset the current workspace UI or open its picker. Form inspection and export now check ownership before changing UI; reject duplicate applies. Device navigation/cancellation and signature fixture execution pending.

### A28/A29 — CI regression contract aligned with supported document formats

- CI run 44721611957 (head 0bb1909) completed 1,419 Android tests with one failure: `DocToPdfScopeAndPreviewTest.docMimeTypes_strictlyScopedToOfficeAndTextDocuments` still required legacy binary Office/RTF after the intentional format-admission correction above. Replaced the stale requirements with an exact, duplicate-free allowlist of DOCX/XLSX/PPTX/plain text MIME types. Existing `OfficeSourceFormatTest` independently verifies admitted extensions and rejection of DOC/XLS/PPT/RTF. No production format support, CI gate, or assertion coverage is relaxed; unsupported types must remain excluded. Validation on the new head is pending; A28/A29 remain PARTIAL.

### A15/A16/A17 — Reject broken recovery manifests and unsafe bundle entries

- Recovery now requires a nonempty page list, unique valid page identities and present, nonempty original files canonically owned by the session. Missing optional render caches no longer establish a persisted render revision. Library thumbnails cannot resolve foreign paths; retired sessions are not listed after a failed deletion. Added missing-original and foreign-session-path recovery regression. Process-death, image decode integrity and storage fault injection remain unverified.
- Bundle generation rejects non-relative, traversal, control-character and ambiguous provider entry names before serialization, bounds native language identifiers to safe ASCII and uses locale-independent IDs. Added common environment credential names, short unquoted secrets and additional credential containers to masking/exclusion, with regressions. This is still heuristic, not a guarantee; selective per-file review, exact tokenizer integration and provider fault tests remain implementation/acceptance work.

### A28 — Bound fallback OpenXML parsing before DOM allocation

- The native-JVM fallback previously trusted compressed ZIP data and placed multiple parser-security feature settings inside one ignored exception block. Bound actual inflated XML bytes to 16 MiB per part and 64 MiB for the entire fallback parse, including workbook relationships and shared strings. Strict Unicode decoding plus DTD admission rejection precedes DOM allocation; external entity resolution fails closed. Reject wrong document roots, missing Word bodies and ambiguous shared-string relationships. Added UTF-8/16 DTD rejection and incorrect ZIP metadata/cumulative-budget fixtures. This cap is a fallback memory admission limit, not a spreadsheet width/page-size restriction; no promise that DOM heap use is bounded by its input bytes. POI routes, fully streaming parsing, Office corpus fidelity and device memory tests remain separate work.

### A28 — Preserve visible content across valid OpenXML prefixes and inline containers

- Fallback Word parser now extracts direct visible nested hyperlink/inserted/content-control runs, preserves tabs and line breaks, ignores deleted revision text, and uses prefix-independent body and attribute lookup. Fallback spreadsheet parser now handles valid namespace-prefixed worksheet/shared-string elements and reads only actual cell values and visible rich text, excluding phonetic guides. Direct child traversal avoids interpreting unrelated extension nodes as rows/cells, and ambiguous multiple cell values fail explicitly. Added two fixtures. This is a genuine supported-content improvement, not full Word/Excel layout or revision semantics; rendering and corpus acceptance remain PARTIAL.

### A24/A25 — Verify downloaded matting artifacts at use and confirm MODNet graph contract

- Independently fetched upstream MODNet param blob `82e92960cfeb51e236d58f12d4343d545afe22c6` ([source](https://github.com/FeiGeChuanShu/ncnn_Android_matting/blob/main/android/app/src/main/assets/mobilenetv2.param)). The 21,166 ASCII bytes hash to `6748834c44f53dce16a19f19bfcb55c21c823a49d0640e0ce0db5acf041c98d0`, exactly the existing pinned SHA-256. Input is `input`; terminal Conv_485 produces one-channel `output` with `9=4` (sigmoid). No extra sigmoid should be applied. This establishes graph identity and output activation, not numerical device parity or photographic quality.
- Existing size-only readiness was a UI hint incorrectly used as authorization for cached download success and native model initialization. Rehash bounded downloaded binary and param against their required pinned digests, off Main, before initial native load and cached download reuse; verify installed bytes before reporting download success. LiteRT models cannot fall through the NCNN MODNet route. Tests distinguish same-length replacement from the pinned artifact. Native load and file replacement are not one atomic operation; app-private storage and immutable pinned artifacts reduce the risk, but independent device concurrency tests are still required.

### A16/A17 — Explicit per-file exclusions before bundle reads

- Added a source-scoped folder-generation dialog for exact relative file exclusions, independent of secret-masking mode and format. Exclusions apply before provider content reads and to every encountered alias of the same relative path; they appear in the omission report (names remain subject to the existing masking policy). Invalid paths are rejected and unmatched paths (including typos or paths hidden by depth/filter traversal) abort publication rather than risk unwanted transfer. Existing destination is not replaced on failed generation. Added exact/case-sensitive/duplicate match, unmatched and invalid-path fixtures. This is a manual exact-path selector, not a visual full-tree browser or arbitrary content editor; SAF/device UI validation is pending.

### A21/A29 — Bind generic workspace target metadata and AI bundle publication to selection ownership

- New mounted file/folder clears prior COMPLETE state and artifact association, cancels current execution/indexing, and increments a per-tool target revision (same-URI re-pick is a new target). Explicit target clear does the same. Folder indexing checks cancellation, bounds directory depth/entry count, avoids repeated directory identities and saturates size accumulation; only the captured current target on Main can receive index results. Partial index is labeled, not an exact inventory guarantee (provider APIs may themselves hide errors).
- AI Bundle captures execution generation, target revision, source name/type, and rejects stale error, progress and artifact publication callbacks; failed/cancelled/unowned output files are deleted even when cancellation races the IO-to-Main handoff. Added identity/re-pick fixture. Other legacy tool processing callbacks and full controller decomposition remain separate work; physical picker/navigation races need device acceptance.

### A15 — Scanner snapshot rollback and verified manifest publication

- Enforce unique valid page IDs and same-session original/cache ownership at write time, not only restore time. Invalid optional render caches cannot be serialized as current revisions. Verify new image files are nonempty, and read back the exact committed manifest after Android AtomicFile.finishWrite (which can log rename failures without throwing). A subsequent cache maintenance failure does not falsely invalidate an already verified manifest.
- Track only newly allocated render generations; when a later page fails before manifest publication, reclaim those unpublished caches and retain the prior committed manifest and originals. If manifest writing was attempted but commit cannot be verified, conservatively retain candidate caches until the next verified save. Added later-page rollback and duplicate-ID fixtures. Nonempty file checks are not full image decoding; process-death, storage fault injection and cross-process acceptance remain pending.

### A19 — Sequential every-decoded-frame scan with verified presentation timestamps

- Added every-frame mode using the same FFmpeg demux/decode/autorotation convention as standalone rendering. Passthrough timing and showinfo on a microsecond timebase before scaling verify raw frame count, ordered timestamps and display dimensions. Decode errors, geometry/timestamp mismatch, missing metadata and over-budget sources fail closed, never fall back to sampling. Private raw spool bounded to 1 GiB, available storage minus 128 MiB, and 1,800 accepted frames; an extra sentinel detects truncation. HD clips may hit the storage cap well before 1,800 frames. Process each frame via one reused bitmap and cover actual PTS intervals (including VFR holds). Cancellation cancels the owned native session, awaits termination and deletes the spool. Added three JVM regressions and a VFR Android FFmpeg fixture. Device fixture and rendered timestamp parity remain unexecuted; detection is not guaranteed, audio not scanned, larger clips need splitting. A19 remains PARTIAL pending acceptance.

### A08/A21/A29 — File-backed matting intake and actual governed-worker ownership

- The production background-removal workspace now stages the selected source in private cache using bounded 64 KiB copies (256 MiB compressed source ceiling, 128 MiB free-storage reserve), passes a file-backed image abstraction through both NCNN and LiteRT, and decodes preview/inference from disk rather than keeping an unbounded compressed ByteArray on heap. ByteArray compatibility entry points remain. The controller leases the staged file across asynchronous CV work; replace/discard retires it without deleting while in use.
- Fix detached CV worker cancellation: snapshot model and source at submission, guard progress and publication by removal generation, cancel the actual CvJob on UI cancellation, wait for worker termination before lease release, and recycle orphan bitmap results. Stale download callbacks no longer auto-process a replacement source. Original-size bitmap budget still applies to compositing; streaming full-resolution export is subsequent work. Device tests must cover cancellation and provider/decoder behavior.

### A08 — Original-resolution transparent PNG via region decoding and streaming RGBA

- Preserve the uncomposited neural alpha as an immutable, bounded byte plane when an admitted staged JPEG/PNG cannot be composited at original resolution as a bitmap. Add an explicit More options action for untrimmed original-resolution transparent PNG (does not silently ignore the regular save format/background settings). Manual brush refinement invalidates this matte so unedited source cannot accidentally be exported as refined output.
- Decode at most 512 Ki pixels per region, map strips through all eight EXIF orientations, bilinearly sample neural alpha in original coordinates, multiply source alpha exactly once and zero hidden RGB of fully transparent pixels. Stream PNG scanlines through zlib and CRC-checked IDAT chunks; no original-sized output bitmap is allocated. Output staged/atomically renamed after dimension/header validation, source leased through export, cancelled/obsolete outputs removed before transfer. JPEG/PNG up to 128 MP and 32,768 px per edge are admitted with conservative disk-space checks. Codec internal native allocations and per-format runtime cost require device testing; other codecs, manual-refined high-resolution export, trim and backdrop/JPEG streaming remain outside this initial explicit route. Added PNG roundtrip, matte and orientation unit tests and Android region-decoder fixture. A08 remains PARTIAL pending device acceptance and broader editing/export support.

### A08/A21/A29 — Owned cutout transfer and native-worker regression

- Original-size export now transfers a private cache artifact to the SAF picker only after the picker accepts ownership. A concurrent picker launch is refused rather than overwriting its pending payload. Cancel, transfer completion and activity destruction retire the owned artifact; regular gallery-export fallback does not mislabel picker launch as a completed save. Activity recreation still requires re-generating the cutout rather than serializing private pending data. Added noncooperative CV cancellation/join regression.
- Independent desktop checks: FFmpeg 7.1.5 decoded the VFR fixture at PTS 0, 100000, 300000, 600000, 900000 microseconds with exactly 1,152,000 RGB bytes; CI [44742587313](https://github.com/sahir247/VeilFrame/actions/runs/44742587313) passed on `ec442f9`. The streaming PNG encoder was separately compiled with Kotlin/JVM and round-tripped all 80,000 seeded ARGB pixels via ImageIO, including zero-alpha RGB scrubbing and multiple IDAT chunks (320,411 encoded bytes). This is independent desktop evidence, not Android instrumented verification; later commits require their own CI.

### A08 — Validate complete streamed PNG before publication

- Declare sRGB for straight Android pixel values and select bounded None/Sub/Up row predictors; retain a single zlib stream and bounded IDAT chunks. Validate the entire private PNG with an independent bounded inflater before atomic publication: exact dimensions and RGBA header, chunk order/length/CRCs, zlib termination, row filter markers and exact inflated scanline count, no trailing bytes. This replaces header-only validation. Added corruption/truncation/geometry, degenerate dimensions and cancellation regression tests; physical region-decoder and memory measurements remain pending.

### A21/A25/A29 — Fail-closed SAF transfers, batch artifact identity and LiteRT pins

- SAF copy no longer succeeds on null streams; import stages atomically, output requests explicit truncation, checks copied bytes, and propagates cancellation. Folder staging now uses explicit provider cursor reads (rather than DocumentFile.listFiles swallowing exceptions), validates each entry name, rejects duplicates/cycles and bounds traversal; any staging failure deletes its private scratch directory rather than silently accepting a partial inventory. Providers may still fail after opening a destination, so this cannot guarantee rollback of external bytes.
- Cleaners preserve source-relative output directories with uniquely indexed filenames rather than overwriting equal stems; failed image output is removed, actual file count must equal completed encodes, and all batch scratch paths are owned by a unique invocation and deleted in finally. Generic cleaner/scanner errors now produce a visible FAILED state instead of escaping an uncaught coroutine. Folder scanner also closes single-document descriptors. Existing single-file processing and late progress callbacks need further ownership review.
- LiteRT Lift & Shift initial load verifies downloaded and staged fallback bytes against the pinned SHA-256 instead of accepting any eight-megabyte file. Existing compiled session lifetime and concurrent model replacement still need device testing.

- Independent PNG verification of exact Git blob sources (encoder `debbb5d`, verifier `7e7c3ec`): Kotlin/JVM harness passed 5,435,970 exact Java ImageIO ARGB pixel comparisons over eight fixtures, including a 32,768-wide row and seeded noise. Pillow independently matched 5,355,970 RGBA pixels; Python zlib validated all five IDAT CRCs and 320,200 inflated bytes in the seeded noise fixture. Forged valid-CRC PNGs containing invalid filters, too few/too many scanline bytes, truncated zlib or trailing zlib bytes were all rejected. Added permanent malformed-zlib fixtures.
- Follow-up transfer hardening: check provider's loading/error cursor extras, cap actual imported bytes by available private disk minus reserve, reserve the full output directory hierarchy before generating collision-free names, offload final ZIP/large single-artifact copy and cleanup to IO with cancellation checkpoints. Folder listings are intentionally fail-closed rather than silently skip unreadable paths.

### A08 — 128 MP independent bounded-heap stress evidence

- Compiled exact encoder and verifier Git blob sources on desktop Kotlin/JVM with a synthetic 16,000 × 8,000 RGBA raster generated per row, using a 32 MiB JVM heap cap. All 128,000,000 pixels were encoded and the complete compressed stream verified; output 561,783 bytes (highly compressible synthetic data), encode 4,172 ms, verify 1,106 ms, peak process RSS approximately 41,984 KiB. These are one desktop run's measurements, not Android performance estimates or real-photo compression ratios. No full-image allocation in the harness. Android native decoder/inference and SAF provider tests remain outstanding. Source commit `04b1c1c` CI [44745572593](https://github.com/sahir247/VeilFrame/actions/runs/44745572593) was in progress at this checkpoint; the preceding original-size export implementation passed [44743816935](https://github.com/sahir247/VeilFrame/actions/runs/44743816935). The remaining-work table reflects actual implemented scope without upgrading original finding statuses.

### A21/A29 — Generic single-artifact ownership

- Bind cleaner and scanner publication/progress to the original execution generation, including same-target re-runs. Single-media operations allocate unique input/output files and unconditionally retire private staging and unpublished results, including cancellation at IO-to-Main handoff. Provider-derived extensions are admitted only as bounded ASCII suffixes. Folder report publication follows the same ownership rule and displays PARTIAL coverage distinctly; FAILED audits cannot publish a successful artifact. Further scanner-engine coverage hardening follows separately. Android CI and lifecycle acceptance pending.

### A21/A29 — Bounded, truthful SAF folder audit and single-file inspection

- Folder scanner now queries child cursors directly and reports incomplete provider listings, cycles, invalid names, failed streams, size mismatches, unknown sizes, actual read limits and truncated findings as partial coverage. No SHA-256 is generated for a null/incomplete read; hash and text checks share one bounded stream. Inspect all admitted text rather than a 5,000-line prefix; findings stay masked and count is capped. Single-file mode now uses the same checks. Unknown total volume stops traversal; requested size-only checks remain metadata-based where size is available. Existing regex detection remains heuristic, not a security guarantee. Added bounded stream and post-line-limit regressions; CI/device fixtures pending.

### A15 — Pin committed page bytes and reject corrupt recovery caches

- Recovery manifests now store versioned SHA-256 digests for originals and current render/thumbnail files. Bounded streaming verification rejects modified/truncated originals; corrupt derived caches are dropped so the original remains available for rendering. Autosave must not replace an existing committed digest with a changed original; corrupt cached render generations can be rebuilt or omitted. Legacy unpinned manifests require bounded sampled image decoding on recovery before migration, which is not full pixel validation of legacy images. New manifests verify byte integrity, not semantic image correctness. Added same-length mutation and corrupt-render regression fixtures; process-death and device fault injection remain outstanding.
- Folder scan stops when actual volume becomes unknown and treats an unreadable root cursor as FAILED.

### A08 — Original-pixel trim support for streaming PNG export

- Original-resolution transparent export now snapshots the trim option and computes the exact nonzero final-alpha bounding box using the original-pixel strip reader (source alpha multiplied by neural alpha). Encoder makes a second bounded row pass over cropped coordinates, including rewind of region-reader bands as necessary, and verifies the actual cropped PNG dimensions/complete stream before atomic publication. No full-source or cropped-output bitmap is created. Fully transparent images retain original dimensions, matching preview behavior. Added three pure JVM crop/matte tests and Android multi-strip trim fixture. Trim entails a second native decoding pass and actual device latency remains unmeasured. Manual refinements and additional original-resolution export formats remain separate implementation scope.

### A08/A21/A29 — Correct manual-refinement coordinates and bounded history

- Manual refinement uses the untrimmed cutout, maps the restore shader to the complete source preview, and replaces source alpha instead of compositing repeated semitransparent restore strokes. Trim is applied only after edits. Do not invalidate the original-resolution matte on a no-op/fully undone refinement; release editor bitmap/shader/history resources on exit. Commit partial strokes interrupted by multitouch and do not apply cancelled taps. Bound owned work/history allocations by heap and 64 MiB; reject oversize editor inputs rather than allocate six full-resolution snapshots. Added aligned restore/alpha and history admission tests. This is preview editing correctness, not original-resolution stroke replay: actual applied edits continue to use the regular export path. Device/native graphics acceptance pending.

### A16/A17 — Two-phase AI bundle provider inventory and alias-safe exclusion

- Inventory SAF metadata directly before opening payload streams; missing/error/loading cursors, duplicate names, invalid entries and repeated directory IDs prevent publication instead of hiding incomplete or ambiguous traversal. Bound paths, entry count and aggregate inventory metadata. Resolve every explicit exclusion before reads and exclude every enumerated alias of the same document URI; credential-container filenames also exclude enumerated aliases when masking is enabled. Zero-progress input streams fail rather than loop. Added identity exclusion fixture. Scope is enumerated provider identities, not arbitrary content-equivalent copies or aliases buried under ignored directories. Exact model-specific tokenization and visual file selection remain outstanding. CI/device provider validation pending.

### A08 — Original-resolution opaque backdrop PNGs

- Snapshot white/black/transparent background selection at original-resolution export confirmation. Composite straight sRGB pixels against an opaque selected backdrop in the bounded row encoder; trim uses final cutout alpha BEFORE flattening so backdrop pixels never expand the crop. Added two pure JVM tests over alpha endpoints and all 256 alpha values, and a device fixture for partial transparency, crop coordinates and white backdrop. This expands PNG output semantics without changing the validated writer/verifier. Streaming JPEG and manual stroke replay still remain; Android integration remains unverified.

### A08 — Replay manual brush edits in original-resolution PNG strip export

- Record brush segments in untrimmed editor coordinates, preserving complete committed stroke history independent of bitmap-history eviction. Undo, redo and branching update the semantic journal; multi-session edits append to an immutable export snapshot. A 4,096-segment limit explicitly disables original-size export if a complete journal cannot be represented; regular preview export remains available. New neural inference or source replacement resets the journal.
- Rasterize the journal in bounded original-resolution alpha strips using Android Canvas round caps and CLEAR/SRC paint operations. Restore changes the neural matte to source coverage rather than synthesizing RGB/alpha from a downscaled preview. Apply original source alpha exactly once after replay; crop and backdrop operate on edited composited rows. No full-resolution output bitmap; extra mask strip is at most 512 Ki pixels. Original-size stroke edges may differ from preview rasterization due to resolution/antialiasing. Added journal and device cross-strip replay fixtures; Android CI/native Canvas tests and worst-case stroke runtime remain pending. JPEG streaming and other formats remain separate work.
- Added editor integration coverage for replay preservation after bitmap-history eviction; undo/redo ends any pending brush gesture before changing both histories.

### A08/A29 — CV governance correction

- CI run 44750829980 failed the unchanged CV governance ratchet because the UI brush canvas imported pure stroke/history records from the CV namespace. Move those records and their unit tests to media/refine (no native dependencies), with dependencies directed from CV and UI toward the shared data model. No suppression, exemption or fake runtime gate added. CI revalidation pending.

### A16/A17 — Metadata-only visual file exclusion and inventory binding

- Extract one bounded SAF metadata enumeration used by both the visual selector and actual generation. Paginated checkboxes and path filtering preserve selection across pages; only file names are enumerated before confirmation, no payload reads. Bind selection to a SHA-256 fingerprint of the ordered path, provider identity, size and directory records. Re-enumerate before payload reads and fail closed on changed inventory; aliases and explicit exclusions still use the existing identity-based policy. Selection is bounded to 2,000 paths, and target revision/generation and depth are checked before starting. Added inventory fingerprint regression coverage. This is metadata binding, not a snapshot of file bytes; same-size edits or provider changes after preflight remain possible and final artifact review is still required. Device selector/provider testing and exact tokenization remain pending.

### A08 — Native sequential baseline JPEG backend

- Isolated libjpeg-turbo classic API library pinned to upstream commit `7b4993786d856cbccabfc0ed2f923ba000e326cc` (3.2.1 source). Separate CMake ExternalProject, static PIC, ARM SIMD required, no tools/TurboJPEG API, private symbols and 16 KiB-aligned JNI library. No private Android system codec dependency.
- Reuse original-color strips, replay, trim and backdrop; write RGB rows directly with one sequential baseline encode, quality 90, 4:4:4. Disable progressive, arithmetic and Huffman optimization (no image-sized coefficient passes). JPEG requires opaque backdrop: black when selected, otherwise white. Decode and verify all output scanlines with strict warning/error handling, dimensions, single scan, EOI and no trailing bytes before atomic publication. Kotlin finally closes native handles on cancellation/errors. No intermediate PNG or second encode.
- This software is based in part on the work of the Independent JPEG Group. Native build, Android JNI and region decoder tests, memory/performance and 16 KiB acceptance remain pending. Source dependency increases build-time download and native binary footprint; JPEG remains lossy and opaque.
- Added four Android instrumentation regressions: odd-size JPEG independent platform decode/color, exact geometry/truncation/trailing-data rejection, cancellation and invalid alpha handling followed by successful retry, and original source alpha/crop/backdrop end-to-end export. Updated user-facing format descriptions and product codec attribution. Tests are not yet run on Android.

- Independent host JNI validation: exact committed C++ blob `1dff57eb2161fe27869e64e37c800951a7cdc191` and Kotlin blob `e2cf82acbde0b6ab278d75d33d4a5e309ad56c1a` matched local Git hashes. Compiled C++17 with `-Wall -Wextra -Werror` on Linux x86-64 against host libjpeg-turbo 2.1.5 (not the pinned Android 3.2.1 source). Java ImageIO independently decoded six fixtures totaling 4,562,737 pixels, including 1×1, 1×100, odd-sized raster, 32,768×1 and 2,048×2,048; checked dimensions and solid-color interior samples. Strict verifier rejected truncated EOI, truncated entropy/header, trailing data and incorrect geometry; cancellation and subsequent retry succeeded. Synthetic 16,000×8,000 (128 MP) run with `-Xmx32m`: 3,831,319-byte output, encode 987 ms, verify 214 ms, peak process RSS 35,668 KiB. These are host measurements only, not Android-native build, region-decoder, image-quality or actual device latency evidence. Host network cannot fetch the pinned dependency; Android CI must validate the isolated build and packaging.

### A16/A17/A29 — Bounded review paging and explicit unread-content consent

- Review now seeks the exact immutable artifact using UTF-8 byte offsets instead of reopening and skipping the entire prefix on every page. Each page allocates at most pageSize+3 bytes and uses a strict UTF-8 decoder; pages complete UTF-8 characters at boundaries. Stalled snapshot reads fail. Transfer remains disabled during loading/errors; until the end page has been opened, it requires explicit unread-content consent and a distinct button label. Opening pages does not prove that a person has read every character. Added two review regressions. Provider/device dialog acceptance pending.

### A25/A26 — NCNN matting transaction ownership and immutable model-load snapshot

- Native call mutex alone did not protect load + multi-ROI inference; synchronous UI release could wait on native inference and invalidate the next ROI. Shared transaction gate serializes NCNN removal and subject-blur load/use. Release becomes a nonblocking retirement request, consumed under the transaction lock before next use (or by retirement executor); a delayed worker cannot repeat an already-consumed invalidation. Cancellation is checked while waiting for ownership, between ROI calls and during strip fusion. Recycle abandoned base/blur input buffers on failure. Downloaded NCNN weights and graph are copied to bounded private staging while hashing the copied bytes, then loaded from that immutable staging path; concurrent file replacement cannot change the verified bytes. Model snapshot removed after synchronous native load. Added gate race tests. Native calls themselves remain nonpreemptible, LiteRT compiled-model snapshot ownership remains separate, and real-device stress is pending.
- Retirement handles a missing native library without an uncaught executor exception; no artificial successful inference result is generated.

### A25 — LiteRT verified model snapshot lifetime

- Share the bounded copy-and-digest snapshot mechanism between NCNN and LiteRT. The downloaded or bundled TFLite model is copied directly into a unique private file, with SHA-256 admission over the actual copied bytes; bundled fallback no longer depends on a separately mutable staging path. LiteRT retains the private snapshot until compiled model/tensor teardown on its affinity thread, including GPU-to-CPU fallback. Failed initialization closes the unadopted snapshot, cancellation is propagated instead of starting CPU warm-up, and warm-up validates finite output and exact tensor size. This closes the validation-to-open race on the supported source paths; the platform may still reclaim app cache during extreme storage pressure, and real-delegate lifetime stress remains pending.

### A19 — Backpressured every-frame video decode transport

- Replace full-clip RGB spool (1 GiB/1,800 frame limit) with two FFmpegKit named pipes carrying raw RGB and framecrc packet metadata from the same tee muxer. Explicit 1/1,000,000 encoder timebase, passthrough timing, per-packet byte count/Adler-32 validation, strict order, original display geometry check before scaling, one-frame lookahead, and native completion/EOF checks. Nonblocking reads on RDWR descriptors prevent open-time deadlock, provide cancellation and stall checks, and close consumer handles before cancelling/joining the native session. No raw frame spool, no per-frame log retention; constant frame storage. Explicit 216,000-frame sentinel limit; crowded sources remain subject to retained-region and downstream filter budgets. Coalesce stationary observations incrementally using coverage-preserving unions. Report unknown totals during streaming. Host FFmpeg 7.1.5 verified tee/framecrc microsecond timebase, raw checksum parity and geometry rejection (regular file outputs); Android FIFO transport and exact FFmpegKit distribution acceptance remain pending. Updated existing VFR device fixture and JVM packet/region tests. No claim of improved detector recall or audio coverage.
- Host FIFO validation with the actual FFmpeg tee command and Linux RDWR/nonblocking pipe readers under intentional consumer backpressure passed: all five VFR presentation timestamps (0, 100000, 300000, 600000, 900000 µs) and all 1,152,000 raw bytes matched packet Adler-32 values. Host geometry mismatch exited nonzero. Android implementation uses poll to wake immediately when bytes are available; added device regression for 1,860 frames, cancellation and subsequent retry. Host result is transport evidence only; Android FFmpegKit/native pipe tests remain pending.

### A17 — Exact named-encoding token budgets for serialized Android bundles

- Pin JTokkit 1.1.0 (MIT, offline, bundled vocabulary) and use ordinary-text `o200k_base` BPE counting. Token mode admits whole file sections using independent section counts plus byte limits/reserve; exact tokenization of the complete staged serialized text (including all headers, syntax/escaping and omissions) is mandatory before atomic publication. Nonadditive BPE boundaries cannot cause an over-budget artifact to publish. Section metadata explicitly describes its scope; UI result shows exact complete artifact count and encoding. No partial file truncation. Exact-token mode bounded to 2 MiB serialized UTF-8 for heap admission; byte-only mode 32 MiB remains available and reports an estimate. No automatic guarantee for arbitrary model encodings, service chat wrappers, system prompts or reserved completion tokens. Packing is deterministic greedy, not optimal knapsack; if final overhead exceeds budget it fails with guidance. Three JVM regressions added. Android runtime/resource packaging and upstream tokenizer parity require CI/device testing. No API calls, no runtime network use.

### A28 — Spreadsheet merge geometry and rectangular layout admission

- Validate complete merged A1 references, ordering, XLSX bounds and merge count; preserve declared blank rows/columns instead of clamping to populated data. Reject overlaps and distinct covered values rather than silently losing unique content; allow redundant identical anchor strings for compatibility with existing producer fixtures. Admission checks actual rectangular layout cells (2M per sheet, 4M per workbook) and expanded text characters (8M/16M), including repeated shared strings, before allocating layout arrays. Restrict tile count to 2,000 and validate aggregate workbook pages before atomic publication. Natural/extended width behavior remains intact; no A4 forcing or silent scaling introduced. Four geometry tests plus corrected existing sparse merge expectation. These are explicit resource limits rather than generalized Office fidelity; real workbook and device stress, formatting, date/number conventions, formulas and charts remain to be addressed.

### A28 — Indexed merged-cell rendering and cancellable PDF page ownership

- Replace a linear scan of all merged ranges for every cell with a compact row-major merge index (at most 8 MB, omitted for unmerged sheets). Natural and tiled renderers check cancellation per row/tile and enforce page budgets before each page allocation; close content streams even on failures. Tiled anchor text is rewrapped to the actual visible slice and rejected if it cannot fit vertically rather than clipping the remainder. This does not implement flow across panels; oversized merged values may require wider tile geometry. Added index parity regression. No Android CI or rendered acceptance verified yet.

### A19 — Android SDK compilation correction

- CI run 44760845651 failed at compileDebugKotlin because Android OsConstants does not expose EWOULDBLOCK. Linux/Android aliases it to EAGAIN, so use the public SDK EAGAIN constant alone. No change to the nonblocking FIFO protocol, error handling or CI settings. New Android build validation pending.

### A28 — Deterministic XLSX display values and cached formula disclosure

- Parse the workbook's declared date system and styles part via its relationship (legacy default path supported), validate style and custom-format references/inheritance/flags, and apply a bounded formatting subset to stored numeric values. Decimal/grouped/zero-padded/percentage and literal-currency patterns use BigDecimal with US decimal symbols; supported date/time patterns use java.time with 1900/1904 epochs and no device timezone conversion. Unsupported formats and the fictitious 1900 leap day preserve the raw value with an explicit numeric-format marker. General/text/error values retain text; Boolean cached values display TRUE/FALSE; formula strings retain whitespace. Reject missing formula caches, count cached formulas and disclose in PDF sheet heading that no recalculation was performed. No external link resolution, formula execution or desktop AWT dependency. Added parser relationship/style/epoch fixture and two pure formatter tests. This intentionally is not full Excel formatting: conditional/accounting/scientific/fraction/locale-specific formats not in the supported subset remain raw, custom text formatting and charts remain unimplemented, and PDF font coverage can still limit Unicode output. Android tests pending.
- Enforce the expanded display-text limit during worksheet parsing, before retaining strings for a whole sheet. Reuse immutable date formatters and at most 64 thread-confined DecimalFormat instances; do not allocate or cache one per arbitrary custom style/cell. Added invalid style and empty numeric formula cache rejection fixture.
- Host validation: exact formatter source matched Git blob `b704c0caac0dd1defb564daaab0edef19699f369`; compiled with kotlinc on OpenJDK 21.0.12. A standalone harness passed 19 assertions each in `Asia/Kolkata` and `America/Los_Angeles`, covering date epochs/leap-day fallback, elapsed time, ordinary time, percentage, grouped decimal, rounding, zero padding, literal currency, unknown format fallback, large General integer, Boolean, cached string whitespace, error text and invalid style. This verifies the pure formatter on JVM only, not Android PDF glyph fidelity or parser/render integration.

### A28 — Saved worksheet dimensions and frozen-panel width admission

- Read explicit/default row heights in points and declared/default column widths in Excel character units. Resolve column widths using the conventional 7px maximum-digit reference at 96dpi; actual workbook font metrics may differ. Validate dimensions and range ordering/overlap, reject duplicate row declarations. Saved dimensions are lower bounds in this content-preserving renderer (not pixel-exact Excel layout); the existing content-aware expansion remains, and hidden rows/columns are not suppressed. Natural renderer now uses the planned full row height, including explicit blank padding. Oversize saved dimensions that cannot fit a requested tile fail explicitly rather than silently shrink. Correct frozen-column partitioning: repeat only when complete frozen group plus next column fit; never duplicate a frozen column within its active panel. Added parser and layout regression fixtures. No fixed A4 sizing or silent scale-down added. Font metrics, exact hidden geometry, borders/fills/alignment and original Excel print layout still incomplete; Android verification pending.

### A28 — Explicit XLSX cell appearance subset

- Parse and validate font/fill references and inheritance flags alongside number styles. Retain bounded packed per-row style indices for explicitly styled cells, including styled blank rows. Support bold/italic via matching Helvetica variants, explicit ARGB text and solid-fill RGB, and left/center/right alignment; use the same font in width measurement, wrapping and rendering. Natural and tiled renderers apply anchor styles to merged regions and avoid duplicated merged titles on repeated frozen panels. This is a supported subset: arbitrary source fonts/sizes, themes/indexed/tinted colors, borders, row/column default styles, hidden cells, vertical alignment, conditional formatting and charts still need separate support. Added sparse/inherited style parser fixture. PDF rendering/device acceptance remains pending.
- Added an end-to-end JVM/Robolectric PDF fixture checking extracted text coordinates, actual Helvetica-BoldOblique glyphs, and emitted red fill/blue text color operators. This fixture is added, not yet executed by CI.

### A28 — Split-row producer compatibility correction

- Android CI 44764874087 ran 1,484 unit tests with one failure: `testParseXlsxOutOfOrderAndDuplicateRows`. Restore the established disjoint split-row compatibility contract rather than weaken the test or discard cells. Explicit duplicate cell identities still fail; conflicting explicit row heights fail, equal or absent heights merge safely. Merge visual indices across row fragments with bounded geometric growth (max 16,384 columns per row). Existing style fixture now exercises two fragments and the dimension test checks conflicting heights. Revalidation pending.

### A28 — Theme, indexed and tinted spreadsheet colors

- Resolve the actual workbook theme relationship (legacy theme1 path when present), named SpreadsheetML theme slots, sRGB and system `lastClr` fallback, custom indexed palette or ISO/IEC 29500 default indices 0–63. Apply signed tint to HSL luminance without AWT or device theme dependencies. Opaque rendering ignores stored alpha for cell colors as before. Invalid references, ambiguous sources and nonfinite/out-of-range tints fail; unresolved system/automatic, absent palette entries and unsupported theme color types retain default renderer color/fill, not a guessed Office theme. Continuous HSL math may differ from Excel's integer rounding by small channel amounts. Three tests cover palette, tint and relationship/integration. References: https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.indexedcolors and https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.foregroundcolor . Borders and advanced color transforms remain pending. Android CI not yet run.

### A17 — Multiple exact offline token encodings

- Add cl100k_base alongside o200k_base with a shared option table preserving existing option indices. Section packing and complete serialized artifact verification use the selected encoding. Add multilingual count and budget regression. Chat wrappers remain outside the contract. CI pending.

### A21/A29 — Own bundle dialogs and teardown callbacks

- Bundle selector returns its owned dialog, dismissed on replacement, cancellation and destruction; generation invalidated on activity release without updating destroyed views. Direct sublist paging avoids allocating skipped suffixes. Activity owns one review dialog, dismissing it before replacement and on destruction; existing dismissal cleanup cancels page jobs and reclaims unhanded review snapshots. No broad controller rewrite. Android lifecycle tests remain pending.

### A26 — Streaming upscale validation cost and cancellation

- Replace per-decoded-byte modulus checks with direct scanline-filter offset traversal. All zlib bytes, exact dimensions, scanline lengths, CRCs and EOF remain checked; reject empty IDAT and enforce the expected RGB/RGBA channel count for native inference output. Add cancellation checkpoints inside bounded inflate iterations and chunk reads. Validate array row offsets before consumption, including negative and extreme values. Added two regression tests for wide rows crossing inflater buffers, channel mismatch, cancellation/retry and bad offsets. This reduces validation bookkeeping, not neural inference FLOPs; no device speed or tile-seam claim.

### A28 — Shared-edge orthogonal spreadsheet borders

- Parse border IDs, parent inheritance, four orthogonal sides and supported line types using the same theme/indexed/RGB color resolver. Honor saved showGridLines. Draw shared edges once after all fills and text, explicit edges beat gridlines, stronger explicit edges win with deterministic top/left tie-break. Suppress interior edges of merges; prefer stored perimeter cell side before anchor fallback. Tiled continuation row fragments do not invent horizontal edges. Natural/extended plan now renders its declared single page directly instead of redundant dynamic row splitting; use planned row coordinates and complete merged rectangles. Double strokes and dash patterns supported with approximate physical widths. Diagonal/RTL/inside-border variants and pixel-identical Excel conflict priority remain unsupported; no claims of chart fidelity. Two regression tests added; CI and rendered comparison pending.

### A08/A26 — Straight-alpha region decode and bounded brush sweep

- Request unpremultiplied sRGB ARGB_8888 region pixels (never Canvas-draw these source strips). Fail closed if the region decoder returns premultiplied alpha instead of silently losing low-alpha RGB precision. Android AOSP region decoder explicitly supports the unpremultiplied request: https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/jni/BitmapRegionDecoder.cpp . Added straight-RGBA source/PNG export instrumentation fixture with alpha 1, 2, 17 and 128; actual Android validation pending. Original brush replay now sweeps intersecting spans in journal order with O(strokes) index memory, resets correctly on crop-pass rewind, and avoids full journal traversal for unrelated bands. Added deterministic randomized parity test against brute-force intersection. No new claim that neural matte or preview interpolation matches original pixel detail.

### A15 — Reject original rebaselining across autosave and orphan files

- Previous versioned manifests must have a valid original digest; validate identity/version, canonicalize pinned paths and reject divergent digests for the same file. A queued snapshot with blank path may reuse its existing committed original only by stable page ID and verified digest; a substituted path fails. Legacy migration requires bounded sampled decoding before pinning. Newly written originals never adopt a preexisting deterministic orphan filename, instead use a unique file when needed. Detect observed mtime changes during digest reads. Derived caches with absent/invalid digest are rebuilt/omitted; legacy decode remains sampled, not full pixel verification. Added missing-digest/path substitution and orphan regression tests. Device process-death/fault tests still required.

### Consolidated source remediation checkpoint

- Baseline `953f3fd` passed both CI workflows (44766966134 and 44766966148). Six subsequent implementation commits address A08/A15/A17/A21/A26/A28/A29, with Android CI still pending at this checkpoint. Added an actual PDF stream regression for one shared border, gridlines off, and stroke after fill. Original finding classifications are unchanged; code-level improvements do not constitute physical-device, privacy-effectiveness or full Office fidelity verification. No release/signed-device gate bypasses.

### A21/A26/A29 — Upscale output ownership across dispatcher handoff

- Retain generated file/bitmap ownership across cancellable withContext return boundaries at native and high-level inference entry points. Dispose undelivered results once, recycle native preview and remove a published-but-unclaimed file on failure/cancellation. Propagate preview cancellation; protect successful output from optional telemetry failures and avoid falsely labelling CPU fallback as Vulkan solely from GPU availability. Postprocessor now validates expected channels and checks cancellation during inflate. Two coroutine regressions cover delivery and prompt cancellation. No inference quality or device benchmark claims; CI pending.

### A21/A28/A29 — Upscaler transactional gallery export

- Reject missing MediaStore row/stream, failed bitmap PNG compression, short or growing source copy and unsuccessful pending-row publication. Roll back allocated pending rows on exceptions and cancellation; an external provider can still reject rollback. Copy streamed PNG in bounded 64 KiB chunks with cancellation and exact source length checks; legacy file path stages before atomic move. Unique output name and captured scale. Gate completion/error UI on currently owned output and live activity. Two transaction tests added. Physical provider testing and broader share/export resource leasing remain pending.
