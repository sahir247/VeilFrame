# ADR 0003: EFQRCode 7.0.3 Parity Architecture & Differential Verification Boundary

## Status

Status: Accepted  
Implementation baseline: 8139597  
ADR recorded in: 3a22647

## Context

VeilFrame's QR Studio implements cleanroom Kotlin reproductions of artistic QR code generator and recognizer behaviors inspired by the reference Swift library [EFQRCode 7.0.3](https://github.com/EFPrefix/EFQRCode/tree/7.0.3). Prior iterations addressed high-level UI controls and basic matrix generation, but an in-depth technical audit revealed subtle semantic pipeline divergences, error classification mismatches, default behavior differences, and storage I/O failure handling holes.

Following commit `8139597`, the architectural framework, parameter wiring, error boundary, fail-closed MediaStore streams, and format export surface were aligned with EFQRCode 7.0.3. However, claiming unconditional "complete behavioral parity" conflates internal implementation correctness with upstream differential equivalence.

This ADR formally defines VeilFrame's architectural contract, documents deliberate platform policy adaptations, and establishes the boundaries of verified parity versus future differential testing.

---

## Decision & Architectural Contract

### 1. Tri-Tier Execution Model

VeilFrame explicitly partitions QR code generation into three distinct execution modes via `com.veilframe.app.qr.GenerationMode`:

```text
                     [ Client / Studio Request ]
                                  │
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
     [ SAFE ]            [ ARTISTIC_ENGINE ]       [ PARITY_EF ]
 • Standard ISO/ZXing    • VeilQrEncoder           • VeilQrEncoder
 • AUTO EC -> M          • AUTO EC -> H            • AUTO EC -> H (strict)
 • 4-module quiet zone   • 1-module image QZ       • 1-module QZ (all styles)
 • Closed-loop AutoRepair• Deterministic art       • Exact EF generator matrix
 • Mobile camera focus   • Visual fidelity focus   • Upstream parity focus
```

- **`SAFE` (Default for BASIC in Studio)**: Optimizes for real-world Android mobile camera capture under adverse lighting. Uses standard ISO/ZXing encoding, enforces a minimum 4-module quiet zone, resolves `ErrorCorrectionChoice.AUTO` to `M` (15%) when no logo is present, and enables closed-loop auto-repair feedback.
- **`ARTISTIC_ENGINE` (Default for Artistic Styles)**: Uses `VeilQrEncoder` (cleanroom `QRCodeSwift` reproduction), defaults `AUTO` to `H` (30%), and sets 1-module margin for image/resample styles to maximize visual canvas area.
- **`PARITY_EF` (Explicit Upstream Parity Mode)**: Dedicated entry point (`QrGenerator.generateParity`, `QrGenerator.generateParityMatrix`). Matches EFQRCode 7.0.3's exact default `errorCorrectLevel: EFCorrectionLevel = .h` and default 1-module quiet zone (`EFStyleParamBackdrop.quietzone = nil` $\rightarrow$ 1 module margin across all styles, including BASIC).

### 2. Style Taxonomy: Native EF Styles vs. VeilFrame Extensions

EFQRCode 7.0.3 defines 10 native style enumerations. VeilFrame provides 10 EF-derived styles plus 2 proprietary extensions:

| Style Enum | Origin | EF Parity Mapping & Status |
|---|---|---|
| `BASIC` | EFQRCode 7.0.3 | Native (`basic`). Scale 1.0. |
| `BUBBLE` | EFQRCode 7.0.3 | Native (`bubble`). Uses EF default palette (#8ED1FC, #FFFFFF, #0693E3). |
| `D25` | EFQRCode 7.0.3 | Native (`d25`). Consumes 3D isometric depth and facet colors; 2D finder styles decoupled. |
| `DSJ` | EFQRCode 7.0.3 | Native (`dsj`). Uses EF defaults (lineSize 0.7, xSize 0.7, colors #F6B506, #E02020, #0B2D97). |
| `FUNCTION` | EFQRCode 7.0.3 | Native (`function`). Implements FADE and CIRCLE spatial math formulas. |
| `IMAGE` | EFQRCode 7.0.3 | Native (`image`). Data scale 1.0 default, alpha channel masking. |
| `IMAGE_FILL` | EFQRCode 7.0.3 | Native (`imageFill`). Mask color blending and thresholding. |
| `LINE` | EFQRCode 7.0.3 | Native (`line`). Supports all 7 EF directions (Horizontal, Vertical, Cross, Loopback, ↘, ↙, X). Wires `positionStyle`, `positionSize`, and `positionColor`. |
| `RANDOM_RECTANGLE` | EFQRCode 7.0.3 | Native (`randomRectangle`). Offset jitter 0.0 default; salted RNG stream. |
| `IMAGE_RESAMPLE` | EFQRCode 7.0.3 | Native (`resampleImage`). 3x3 kernel sampling and backdrop integration. |
| `STYLE_FUNCTION` | **VeilFrame Extension** | Proprietary parametric function generator (frequency, amplitude, seed). |
| `CONNECTED_ORGANIC` | **VeilFrame Extension** | Proprietary continuous organic curve synthesis. |

### 3. Error Boundary Architecture

EFQRCode uses a compact error enum with 15 top-level cases, spanning data/encoding, color/image generation, animation/video, and internal failures (including `dataLengthExceedsCapacityLimit`, `text`, color-space failures, mutable-data/CGImage/CGContext/SVG/image-data failures, animated-image failure, video failure, and `internalError`). 

VeilFrame establishes an **EFQRCode-compatible error boundary + VeilFrame-native richer domain taxonomy**:

```text
QrError (Sealed Domain Hierarchy)
 ├── Input (EmptyContent, InvalidPayload)
 ├── Encoding (CapacityExceeded, EngineFailure, UnsupportedMode)
 ├── Design (InvalidParameter, IncompatibleStyleOptions)
 ├── Image (AllocationFailed, DecodeFailed, ResizeFailed, InvalidFrame)
 ├── Rendering (BitmapAllocationFailed, CanvasRenderFailed, StyleRenderFailed, SvgRenderFailed)
 ├── Animation (EmptyFrames, InvalidTimeline, FrameRenderFailed, EncodingFailed)
 ├── Output (PngEncodingFailed, JpegEncodingFailed, SvgExportFailed, GifEncodingFailed, ApngEncodingFailed, PdfExportFailed, VideoEncodingFailed)
 ├── Platform (PermissionDenied, StorageFailed, ExportUriUnavailable)
 └── Internal (Unclassified with cause)
```

- **ZXing Exception Translation**: Broad `WriterException` is no longer conflated with `CapacityExceeded`. If the exception indicates byte/module overflow, it maps to `Encoding.CapacityExceeded`; otherwise, it maps to `Encoding.EngineFailure("ZXing", msg, cause)`.
- **Typed Result Flow**: The UI export layer routes through `QrOutputResult<T>`, terminating raw unhandled exception propagation.

### 4. Output Format Surface Coverage

VeilFrame covers the EFQRCode 7.0.3 documented export format families used by the parity audit via `QrOutputFormat`:
- **Static**: PNG, JPEG (with quality parameter), SVG, PDF (Android `PdfDocument` vector/raster embedding).
- **Animated**: GIF (pure Kotlin `GifEncoder`), APNG (FFmpegKit APNG multiplexer), SVG (discrete `<animate>` discrete keyTimes), MP4, MOV, M4V (`VideoContainer` abstraction).

**Platform Implementation Difference**: EFQRCode utilizes macOS/iOS `AVAssetWriter` with fixed 30 FPS timing and QuickTime timescales. VeilFrame utilizes Android FFmpegKit (libx264/yuv420p) with configurable frame rates and explicit variable-duration timeline reconciliation. This represents **feature-surface compatibility**, not bit-for-bit container byte identity.

### 5. Storage I/O: Fail-Closed MediaStore Streams

To eliminate orphaned 0-byte files in Android `MediaStore`:
All typed exporters (`saveBitmapTyped`, `saveSvgStringTyped`, `saveGifTyped`, `saveVideoTyped`, `savePdfTyped`, `saveApngTyped`) verify `contentResolver.openOutputStream(uri)`. If null, the pending MediaStore entry is immediately deleted via `contentResolver.delete(uri, null, null)`, and a structured `QrOutputResult.Failure(QrError.Platform.StorageFailed(...))` is returned.

### 6. Recognizer: API Compatibility vs. Engine Differences

`QrRecognizer.recognize(bitmap: Bitmap): List<String>` provides public API compatibility matching `EFQRCode.recognize(image: CGImage) -> [String]?`.

- **EFQRCode**: Uses Apple Core Image `CIDetector` with `CIDetectorAccuracyHigh` followed by a low-accuracy grayscale fallback.
- **VeilFrame**: Uses ZXing `QRCodeMultiReader` across four adaptive binarization passes (Hybrid $\rightarrow$ GlobalHistogram $\rightarrow$ Inverted Hybrid $\rightarrow$ Inverted GlobalHistogram) and Google ML Kit Barcode Scanning.
This achieves multi-barcode detection **API compatibility**, while leveraging battle-tested Android decoders.

---

## Parity Verification Matrix & Roadmap

| Verification Level | Target | Status | Methodology |
|---|---|---|---|
| **Tier 1: Internal Unit Tests** | 406/406 Tests | ✅ Complete | Verifies internal consistency, parameter propagation, error classification, and subpixel math. |
| **Tier 2: Mathematical Parity** | Reed-Solomon & Matrix | ✅ Complete | Verified against ISO 18004 specification and embedded upstream vectors across versions 1–40. |
| **Tier 3: Error & Surface Coverage**| Types & Formats | ✅ Complete | Full coverage of PDF, APNG, M4V, GIF, SVG, PNG, JPEG, fail-closed I/O. |
| **Tier 4: Upstream Matrix Oracle** | Bit-by-bit differential | ✅ Implemented | Swift SPM oracle (`tools/efqrcode-oracle`) compiles against `QRCodeSwift` / `EFQRCode` in CI, generates frozen test matrices, and asserts 100% bit-for-bit equivalence in `Tier4UpstreamMatrixOracleTest` (126 vectors, Versions 1–26 x L/M/Q/H, UTF-8, URLs). |
| **Tier 5: Pixel & SVG Golden Diffs** | Rendered output equivalence | 🔄 Next Phase | Plan: Produce normalized SVG DOM and deterministic raster diffs against macOS CoreGraphics reference renders. |
| **Tier 6: Cross-Device Recognition** | Decoder benchmark | 🔄 Next Phase | Benchmark ZXing multi-pass against Core Image on real-world distorted test sets. |

---

## Verdict

VeilFrame has established **EFQRCode 7.0.3 parity architecture, major behavioral alignment, and Tier 4 Upstream Matrix Differential Equivalence**: default error correction, quiet-zone geometry, parameter propagation, typed error taxonomy, fail-closed storage, full export surface coverage, and bit-for-bit matrix identity against upstream Swift `QRCodeSwift` / `EFQRCode 7.0.3` are fully implemented and verified in CI. Rendered pixel and SVG diffs will be tracked under the Tier 5 roadmap.
