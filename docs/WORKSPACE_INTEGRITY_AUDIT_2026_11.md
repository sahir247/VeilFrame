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
| A01 | P1 | OPEN | Searchable PDF false success on null output stream | `pdf/operations/PdfOcrProcessor.kt:341-353` | Require non-null destination; test provider failure. |
| A02 | P1 | OPEN | Searchable OCR strips Unicode | `pdf/operations/PdfOcrProcessor.kt:617-648` | Embed Unicode font and validate text extraction; no silent character loss. |
| A03 | P1 | OPEN | OCR layer ignores page rotation and actual render dimensions | `pdf/operations/PdfOcrProcessor.kt:609-653` | Map pixel rectangles into crop/rotation-aware PDF coordinates. |
| A04 | P1 | OPEN | OCR confidence fabricated (0.90 word / 0.85 page) | `pdf/operations/OcrEngine.kt:52; PdfOcrProcessor.kt:210` | Preserve unknown confidence and derive aggregates. |
| A05 | P1 | OPEN | PDF metadata removal only clears Info dictionary | `pdf/operations/PdfMetadataManager.kt:162-196` | Clear catalog/page/resource metadata; verify saved output and scope. |
| A06 | P1 | OPEN | Flattening can silently omit forms/annotations or remove undrawn annotation | `pdf/operations/PdfFlattener.kt:191-286` | Report incomplete work; preserve annotation until drawn successfully. |
| A07 | P1 | OPEN | JavaScript/attachment removal limited to catalog entries | `pdf/operations/PdfFlattener.kt:292-331` | Inspect relevant COS graph; verify requested removal. |
| A08 | P1 | OPEN | Background removal full-resolution claim contradicts 4096 cap | `cv/segmentation/rembg/CelBackgroundRemover.kt:35-38,81-112; RembgImageUtils.kt:18` | Disclose actual output size immediately; implement bounded original-resolution compositing. |
| A09 | P1 | OPEN | Background removal source import can publish stale result | `media/BackgroundRemoverController.kt:326-368` | Cancel prior import; generation-gate results and cleanup. |
| A10 | P1 | OPEN | Background removal export may race bitmap recycling | `media/BackgroundRemoverController.kt:1258-1430` | Protect captured bitmap lifetime across export/share/clipboard. |
| A11 | P1 | OPEN | PDF compression may choose destructive rasterization by size alone | `pdf/operations/PdfCompressor.kt:375-423` | Explicitly authorize lossy semantic change; preserve structural mode. |
| A12 | P1 | OPEN | Compression cleanup deletes shared prefix-matching temp files | `pdf/operations/PdfCompressor.kt:299-307` | Own operation-specific temp files/directories. |
| A13 | P1 | OPEN | DOCX conversion falls back silently to text/binary ZIP | `pdf/operations/OfficeConverter.kt:135-158` | Propagate cancellation and errors, disclose text-only fallback. |
| A14 | P1 | OPEN | Scanner silently ignores perspective/adjustment failures | `document/DocumentSession.kt:157-166,236-254` | Fail selected transformations explicitly; distinguish preview vs export. |
| A15 | P1 | OPEN | Scanner manifest direct overwrite and ignored persistence errors | `document/DocumentSession.kt:572-576` | Atomic persistence, serialization and recovery/error status. |
| A16 | P1 | OPEN | AI bundle heuristic secrets detection incomplete | `tools/AiBundleEngine.kt:49-54` | Expand patterns and clearly disclose limitations; pre-export review. |
| A17 | P1 | OPEN | AI bundle JSON key escaping, omissions, budget and memory | `tools/AiBundleEngine.kt:89-159,185-211` | Use serialization, deterministic selection, bounded streaming and omissions report. |
| A18 | P2 | OPEN | Motion Lab failed gallery write leaves MediaStore row | `media/MotionLabController.kt:766-803` | Delete pending entry on failed copy/publish. |
| A19 | P1 | OPEN | Video PII capped sampling, rotated geometry and incomplete coverage | `cv/motion/VideoPiiRedactionEngine.kt:71-76,140-222` | Preserve incomplete result warning; test geometry; exhaustive mode and review. |
| A20 | P1 | OPEN | Provenance embedded-key validity does not prove signer trust | `media/ProvenanceController.kt:165-166` | Label signature validity vs trusted identity. |
| A21 | P1 | OPEN | Workspace resource lifecycle dispersed; retained inflated views | `workspace/WorkspaceHost.kt` | Trace navigation/controller ownership; cancel resources without breaking sessions. |
| A22 | P2 | OPEN | PRNU external attribution resistance not established | `cv/prnu/PrnuSanitizerEngine.kt` | Retain honest diagnostic wording; benchmark independently. |
| A23 | P2 | OPEN | Stabilization real-world tracking quality and limitations | `cv/motion/VideoStabilizerEngine.kt` | Benchmark difficult footage and confidence behavior. |
| A24 | P2 | OPEN | Native matting activation/model contract uncertain | `cpp/matting_jni.cpp:292-316` | Inspect actual model graph before changing sigmoid; CPU/GPU parity. |
| A25 | P2 | OPEN | Native mutex cancellation latency, model and GPU lifecycle | `cpp/{matting,pii_ncnn,rife,realsr,veilnet}_jni.cpp; vulkan_gpu_lifecycle.h` | Stress device concurrency and measure native cancellation. |
| A26 | P2 | OPEN | Upscaler tile seams, model fidelity and generated detail | `upscale/inference/` | Measure dimensions, boundaries, memory and preview/export consistency. |
| A27 | P2 | OPEN | QR exported-style real-device scanability | `qr/` | Retain frozen matrix parity; roundtrip device scans and exports. |
| A28 | P2 | OPEN | Office/PDF broader semantic fidelity and workspace coverage | `pdf/operations/; media/; tools/` | Trace all reachable callers; fixtures for charts/formulas/fonts/fields/page geometry. |
| A29 | P2 | OPEN | Oversized multi-purpose controllers and ownership complexity | `media/; pdf/; navigation/` | Refactor by clear ownership boundaries after regression coverage. |
| A30 | P2 | OPEN | Visual signing vs cryptographic signing distinction | `pdf/operations/PdfSigner.kt:103-104` | Verify UI differentiates visual overlay from digital signature validation. |

## Implementation log

- Ledger initialized before fixes; no tests executed yet.
