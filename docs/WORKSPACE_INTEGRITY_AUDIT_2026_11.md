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
| A19 | P1 | OPEN | Video PII capped sampling, rotated geometry and incomplete coverage | `cv/motion/VideoPiiRedactionEngine.kt:71-76,140-222` | Preserve incomplete result warning; test geometry; exhaustive mode and review. |
| A20 | P1 | PATCHED | Provenance embedded-key validity does not prove signer trust | `media/ProvenanceController.kt:165-166` | Label signature validity vs trusted identity. |
| A21 | P1 | OPEN | Workspace resource lifecycle dispersed; retained inflated views | `workspace/WorkspaceHost.kt` | Trace navigation/controller ownership; cancel resources without breaking sessions. |
| A22 | P2 | OPEN | PRNU external attribution resistance not established | `cv/prnu/PrnuSanitizerEngine.kt` | Retain honest diagnostic wording; benchmark independently. |
| A23 | P2 | OPEN | Stabilization real-world tracking quality and limitations | `cv/motion/VideoStabilizerEngine.kt` | Benchmark difficult footage and confidence behavior. |
| A24 | P2 | PARTIAL | Native matting activation/model contract uncertain | `cpp/matting_jni.cpp:292-316` | Inspect actual model graph before changing sigmoid; CPU/GPU parity. |
| A25 | P2 | OPEN | Native mutex cancellation latency, model and GPU lifecycle | `cpp/{matting,pii_ncnn,rife,realsr,veilnet}_jni.cpp; vulkan_gpu_lifecycle.h` | Stress device concurrency and measure native cancellation. |
| A26 | P2 | OPEN | Upscaler tile seams, model fidelity and generated detail | `upscale/inference/` | Measure dimensions, boundaries, memory and preview/export consistency. |
| A27 | P2 | OPEN | QR exported-style real-device scanability | `qr/` | Retain frozen matrix parity; roundtrip device scans and exports. |
| A28 | P2 | OPEN | Office/PDF broader semantic fidelity and workspace coverage | `pdf/operations/; media/; tools/` | Trace all reachable callers; fixtures for charts/formulas/fonts/fields/page geometry. |
| A29 | P2 | OPEN | Oversized multi-purpose controllers and ownership complexity | `media/; pdf/; navigation/` | Refactor by clear ownership boundaries after regression coverage. |
| A30 | P2 | PATCHED | Visual signing vs cryptographic signing distinction | `pdf/operations/PdfSigner.kt:103-104` | Verify UI differentiates visual overlay from digital signature validation. |
| A31 | P1 | PATCHED | ML Kit OCR failure can be treated as empty recognition; cancelled recognizer may outlive bitmap | `pdf/operations/OcrEngine.kt:24-62` | Propagate detector failure; protect asynchronous input bitmap lifetime and distinguish genuinely empty pages. |
| A32 | P1 | PATCHED | PDF raster compression swallowed individual page render errors, permitting partial or blank-page output | `pdf/operations/PdfCompressor.kt:809-823` (baseline) | Reject the entire faulty candidate; verify original page count and content fixtures. |

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
