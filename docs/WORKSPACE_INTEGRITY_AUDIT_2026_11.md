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
