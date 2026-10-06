# VeilFrame CV Engine (OpenCV)

Status: implemented incrementally — **QR stack is the primary capability**; other
CV features land per the phase plan at the bottom of this document.

## Where it lives

The CV engine lives **inside the existing Android app**, not in a separate app or
module:

```
app/src/main/java/com/veilframe/app/cv/
├── core/          CvEngine, CvDispatcher, CvJob, MatPool, CvMemoryManager, BitmapBridge
├── preprocess/    Preprocessor (gray/resize/normalize/threshold/morphology/…)
├── edges/         EdgeDetector (Canny/Sobel/Scharr/Laplacian — internal primitives)
├── geometry/      Geometry, PerspectiveCorrector, QuadDetector
├── analysis/      BlurAnalyzer, ImageQualityAnalyzer, QualityScore
├── denoise/       NoiseReducer (fast tier + non-local means)
└── qr/            WeChatQrEngine, QrRecoveryEngine, QrStressMatrix,
                   QrReliabilityGate, QrCodeDetectorDiagnostic
```

Dependencies added to `:app`: `org.opencv:opencv:4.14.0` (pinned).
WeChat detector + super-resolution Caffe models ship in
`app/src/main/assets/cv/wechat_qr/` (offline-first; extracted to app storage at
runtime because the Caffe loader needs real file paths).

## QR architecture (authoritative)

```
                 VEILFRAME QR
                      │
          ┌───────────┴───────────┐
          │                       │
       QR Maker                QR Scanner
          │                       │
   VeilFrame QR Engine       OpenCV WeChatQRCode   ← PRIMARY
          │                       │
          ▼                       ▼
       Render                  Detection + Super Resolution
          │                       │
     QR Validator                 │
          │                       │
          └───────────┬───────────┘
                      ▼
                PASS / FAIL
                      │
              if WeChat fails
                      ▼
                   ML Kit           ← SECONDARY FALLBACK
```

Rules:

1. **Primary decoder: `cv::wechat_qrcode::WeChatQRCode`** (detector + super
   resolution). `WeChatQrEngine` owns the native instance and serialises access.
2. **Not primary: `cv::QRCodeDetector`.** Kept only as
   `QrCodeDetectorDiagnostic` — an extremely lightweight diagnostic probe. It is
   never evidence for a PASS verdict.
3. **Secondary fallback: Google ML Kit Barcode Scanning** (bundled, offline).
   ZXing remains generator-side/legacy coverage only.
4. **The validator tests the same engine users scan with.**
   `ScanabilityValidator.resolveDecoder()` prefers `WeChatQrDecoder`, so
   `validateFast` / `validateStrict` (export gate) exercise WeChatQRCode. When
   the engine is unavailable (JVM tests, stripped builds) the chain falls back to
   ML Kit exactly as before — behaviour is preserved.
5. **Generator ↔ validator feedback loop:** a WeChat decode failure flows into
   `ScanabilityReport.repairSuggestions` (restore quiet zone, reduce deformation,
   strengthen modules, elevate EC) and drives `AutoRepairEngine` retries.
   SAFE / ARTISTIC_ENGINE / PARITY_EF mode distinctions are never silently
   changed by auto-repair.

### QR reliability gate (graded, not binary)

`QrReliabilityGate` runs the deterministic `QrStressMatrix` —
Original / 75% / 50% / 25% / Downscaled / JPEG q70 / Blur / Contrast /
Brightness / Rotation / Perspective / Noise — through the WeChat-first probe and
grades the result:

| Level | Meaning |
|---|---|
| `PASS` | every stress condition decodes on WeChatQRCode |
| `PASS_WITH_MARGIN` | ≥90% WeChat, at most one ML Kit fallback |
| `WEAK` | ≥60% overall coverage, unreliable on WeChat |
| `FAIL` | coverage < 60%, or the pristine render itself fails |

ML Kit results are recorded as *fallback* coverage and can never upgrade `WEAK`
to `PASS`. The pristine render failing is a hard `FAIL`. Matrix oracle (mathematical
QR validity) stays separate from rendered-raster validation, as before.

### Scanner pipeline (CameraX)

```
YUV frame → luma Mat (≤1024px) → WeChatQRCode (PRIMARY)
             ↓ fail
           ML Kit (SECONDARY, localisation + zoom hints)
             ↓ fail
           ZXing last resort (existing 3-pass binarization)
```

Degraded frames escalate through `QrRecoveryEngine` stages:
original → gray+upscale → contrast/adaptive threshold → perspective correction →
alternative preprocessing → ML Kit. Escalation is staged and bounded — variants
are never all run on every frame.

## CV core contracts

- **Every operation runs through `CvEngine.submit()`**: memory check →
  resolution selection → operation → cancellation checks → release Mats.
- **`MatPool` provides reusable native buffer leases** (size classes,
  automatic release). A 6000×4000 RGBA buffer is ~96 MB. Large pixel buffers in
  hot paths (`FrameSynthesizer` warp grid/maps, `FlowConsistency` warp flows)
  are pooled through `MatPool.default` / `CvContext.acquireMat()`, preventing
  continuous native allocation churn during intermediate frame synthesis.
  Small/transient matrices (< 256 KB) remain directly managed for architectural
  clarity.
- **`CvMemoryManager` with reservational admission** models the 6–16 GB *total*
  RAM target: conservative / normal / high tiers from total+available memory,
  resolution tiers (720p preview / 1280 / 1080p / source / tiled).
  Admission control is concurrency-safe: `CvEngine.submit` atomically reserves
  estimated memory against the safety budget before enqueuing, releasing the
  reservation upon job completion, error, or cancellation.
- **`CvDispatcher`** lanes: INTERACTIVE / BACKGROUND / VIDEO with dedicated
  permits, so video work can never freeze the UI. Jobs support `cancel()`,
  progress and memory estimates.
- Quality/blur scores are **component-wise and configurable**. High-resolution
  Gaussian noise estimation uses streaming row-buffered histograms (4 KB heap
  footprint) rather than full-resolution byte arrays.
- `SmartAutoCrop` handles both color (BGR/BGRA) and single-channel grayscale
  (`CV_8UC1`) inputs natively.

## Testing

- JVM (`app/src/test/.../cv/`): gate grading policy, stress-suite contract,
  memory/reservation policy, concurrent admission gating, quality/blur score policy.
  No native OpenCV needed.
- Instrumented (`app/src/androidTest/.../cv/qr/WeChatQrRoundTripTest.kt`):
  WeChatQRCode round-trip decode + reliability gate end-to-end on device.

## Hardening Progress

1. ✅ CV foundation (core, preprocess, geometry, analysis) + QR stack
2. ✅ Reservational memory admission (`CvMemoryManager.reserve` + atomic semaphore)
3. ✅ Large-Mat pooling in hot motion & frame synthesis paths (`FrameSynthesizer`, `FlowConsistency`)
4. ✅ Streamed noise estimation histogram (zero large JVM heap allocations)
5. ✅ Grayscale `CV_8UC1` smart auto-crop compatibility
6. ✅ WeChatQrEngine diagnostic health reporting contract
7. ⬜ Full ONNX segmentation mask model integration (post-processing is implemented)
8. ⬜ Device-level 6 GB concurrent stress execution (physical device test)

Video boundary stays as planned: FFmpeg owns demux/decode/encode; the CV engine
only analyses/interpolates decoded frames.
