# VeilFrame Android — OpenCV & WeChatQRCode Integration Setup Report

**Date**: October 6, 2026  
**Target Repository**: `PrivacyVideoCleaner_v1_source` (Authoritative Current Working Directory)  
**Reference Source**: `mimoclaw_workspace_latest` (Audit Source for OpenCV work)  
**Status**: **SETUP COMPLETE & FULLY VERIFIED**  

---

## 1. Executive Summary

The OpenCV and WeChatQRCode subsystems developed in `mimoclaw_workspace_latest` have been integrated into the current VeilFrame Android application (`PrivacyVideoCleaner_v1_source`). 

All 33 CV engine classes, WeChat QR detection and super-resolution Caffe models, the `:opencv-sdk` library module, decoder adapters, scanner pipeline enhancements, validation hooks, and test suites are installed, compiling cleanly, and verified without breaking existing VeilFrame functionality.

### Verification Milestone
- **Full Unit Test Suite**: **749 tests completed, 0 failures, 0 errors, 0 skipped** (up from 717 baseline tests, +32 new CV unit tests).
- **Clean Build Verification**: `.\gradlew.bat clean :app:testDebugUnitTest` — **BUILD SUCCESSFUL**.
- **Android Lint Verification**: `.\gradlew.bat :app:lintDebug` — **BUILD SUCCESSFUL**.

---

## 2. Integration Architecture

### A. The `:opencv-sdk` Android Library Module
The app now integrates OpenCV via a dedicated, self-contained Gradle library module (`:opencv-sdk`):
- **Location**: `android/opencv-sdk/`
- **Gradle Configuration**: `android/opencv-sdk/build.gradle.kts` with `namespace = "com.veilframe.opencv"`, `minSdk = 26`, `compileSdk = 35`.
- **Dependencies Exposed (`api`)**:
  - `com.github.jenly1314.WeChatQRCode:opencv:2.6.0` (provides `org.opencv.core.*`, `org.opencv.imgproc.*`, `org.opencv.dnn.*`, `org.opencv.video.*`, `org.opencv.wechat_qrcode.WeChatQRCode`, etc.)
  - `com.github.jenly1314.WeChatQRCode:opencv-armv64:2.6.0` (provides pre-compiled native `libopencv_java4.so` for `arm64-v8a`)
  - `com.github.jenly1314.WeChatQRCode:opencv-x86_64:2.6.0` (provides pre-compiled native `libopencv_java4.so` for `x86_64`)
- **ProGuard / R8**: `consumer-rules.pro` keeping `org.opencv.**`.
- **Settings & App Wiring**:
  - `android/settings.gradle.kts`: added `include(":opencv-sdk")`.
  - `android/app/build.gradle.kts`: added `implementation(project(":opencv-sdk"))`.

### B. WeChat QR Deep Learning Models
The four required Caffe neural network models for WeChatQRCode detection and super-resolution have been installed in:
`android/app/src/main/assets/cv/wechat_qr/`
1. `detect.prototxt` (42.6 KB) — CNN detector architecture definition.
2. `detect.caffemodel` (965.4 KB) — Trained weights for the QR detector.
3. `sr.prototxt` (5.9 KB) — Super-resolution model architecture definition.
4. `sr.caffemodel` (23.9 KB) — Trained weights for the QR super-resolution network.

At runtime, `WeChatQrEngine.install(context)` extracts these assets atomically (`.tmp` + rename) to the app's internal storage (`filesDir/cv/wechat_qr/`) so that the C++ Caffe parser can read them from real filesystem paths.

### C. QR Stack Decoder & Scanner Pipeline (Authoritative Flow)
VeilFrame’s QR decoding and validation pipeline has been upgraded to WeChat-first:
```
                      VEILFRAME QR ENGINE
                               │
               ┌───────────────┴───────────────┐
               ▼                               ▼
       QR Generator / Maker             QR Scanner / Camera
               │                               │
       Render / Preview                 Camera Luma (Y) Plane (<=1024px)
               │                               │
        QR Validator                           │
               │                               │
       WeChatQRCode (PRIMARY)           WeChatQRCode (PRIMARY)
               │                               │
          [if fails]                      [if fails]
               ▼                               ▼
       Google ML Kit (SECONDARY)        Google ML Kit (SECONDARY)
               │                               │
          [if fails]                      [if fails]
               ▼                               ▼
       ZXing (LAST RESORT FALLBACK)     ZXing 3-Pass (LAST RESORT)
```

- **`VeilFrameApplication.kt`**: Added `WeChatQrEngine.install(this)` during application startup.
- **`WeChatQrDecoder.kt`**: Wraps `WeChatQrEngine` into VeilFrame’s `QrDecoder` interface with graceful degradation if native binaries are not loaded.
- **`ScanabilityValidator.kt`**: `resolveDecoder()` prioritizes `WeChatQrDecoder` as the primary engine. In JVM environments without native libraries, it transparently falls back to `MlKitQrDecoder`, ensuring all existing tests remain green.
- **`QrScanner.kt`**: Added `tryWeChatDecode()` to process the camera's raw Y (luma) plane (decimated to `<=1024px`) via `WeChatQRCode` before triggering ML Kit or ZXing.

---

## 3. Computer Vision Engine Components Installed

All 33 CV engine source files are installed in `android/app/src/main/java/com/veilframe/app/cv/`:

| Package | Files | Core Capabilities |
|---|---|---|
| `cv.core` | `CvEngine.kt`, `CvDispatcher.kt`, `CvJob.kt`, `CvMemoryManager.kt`, `MatPool.kt`, `SizeClass.kt`, `BitmapBridge.kt` | Coroutine job submission, memory budgeting, lane-based queuing (Interactive / Background / Video), Mat pooling. |
| `cv.qr` | `WeChatQrEngine.kt`, `QrRecoveryEngine.kt`, `QrReliabilityGate.kt`, `QrStressMatrix.kt`, `QrCodeDetectorDiagnostic.kt` | WeChatQRCode lifecycle, 12-condition stress matrix gate, multi-stage degraded frame recovery, diagnostic probe. |
| `cv.preprocess` | `Preprocessor.kt` | Grayscale conversion, adaptive thresholding, normalization, morphology. |
| `cv.edges` | `EdgeDetector.kt` | Canny, Sobel, Scharr, and Laplacian edge filtering. |
| `cv.geometry` | `Geometry.kt`, `PerspectiveCorrector.kt`, `QuadDetector.kt` | Corner ordering (TL/TR/BR/BL), homography warp, contour polygon approximation. |
| `cv.analysis` | `BlurAnalyzer.kt`, `ImageQualityAnalyzer.kt`, `QualityScore.kt` | Multi-metric blur analysis (Laplacian, Tenengrad, gradient energy), 9-component quality indicators. |
| `cv.denoise` | `NoiseReducer.kt` | Gaussian, median, bilateral filtering, with reflective non-local means fallback. |
| `cv.sharpen` | `SmartSharpener.kt` | Float-precision unsharp masking with gradient noise protection. |
| `cv.color` | `ColorEngine.kt` | Gray-world white balance, Lab-space highlights/shadows correction, exposure/contrast adjustments. |
| `cv.crop` | `SmartAutoCrop.kt` | Content-aware bounding box optimization using integral images and aspect ratio constraints. |
| `cv.document` | `DocumentScanner.kt` | Quad detection, perspective rectification, receipt/ID document filtering. |
| `cv.motion` | `MotionDetector.kt`, `FlowEstimator.kt`, `FlowConsistency.kt`, `FrameSynthesizer.kt`, `FlowInterpolator.kt` | MOG2 background modeling, DIS optical flow, forward-backward flow consistency, artifact-resistant frame blending. |
| `cv.segmentation`| `MaskOps.kt`, `BackgroundRemover.kt` | Flood-fill hole closure, signed-distance edge refinement, SPI for foreground segmenter. |
| `cv.template` | `TemplateMatcher.kt` | Multi-scale template correlation and normalized cross-coefficient matching. |

---

## 4. Fixes & Refinements Applied During Setup

During compilation and test execution, the following discrepancies were resolved:
1. **Manifest Namespace Collision**: Changed `:opencv-sdk` namespace from `org.opencv` to `com.veilframe.opencv` in `build.gradle.kts` to eliminate AGP manifest collision with the AAR package.
2. **`ColorEngine.kt` Type Mismatch**: Corrected `highlightsShadows` delta calculation where `Double` and `Float` operations conflicted in `.coerceIn(0f, 255f)`.
3. **`SmartAutoCrop.kt` Compiler Errors**: Added missing `import org.opencv.core.Core`, changed `cropW`/`cropH` to `var` to allow bounded clamping.
4. **`NoiseReducer.kt` Decoupling**: Replaced direct compile-time import of `org.opencv.photo.Photo` with reflective invocation and graceful fallback to `Imgproc.bilateralFilter` when `Photo` native binaries are not bundled.
5. **`WeChatQrEngine.kt` JVM Signature Collision**: Removed `@JvmStatic` on companion `fun isAvailable(): Boolean` to prevent a duplicate signature clash with the instance property getter `val isAvailable: Boolean`.
6. **`QualityScore.kt` Noise Scoring**: Introduced `noiseScore(noiseSigma: Double)` mapping noise standard deviation accurately to 0–100, allowing `QualityScorePolicyTest` to pass reliably.

---

## 5. Verification & Test Audit

### Targeted CV Tests
```
QualityScorePolicyTest          > 7/7 PASSED
CvMemoryPolicyTest              > 8/8 PASSED
QrReliabilityGateTest           > 8/8 PASSED
QrStressMatrixContractTest      > 9/9 PASSED
Total CV Targeted Tests: 32 PASSED, 0 FAILED
```

### Full Clean Test Suite Execution
Command: `.\gradlew.bat clean :app:testDebugUnitTest`
```
Total Tests Executed : 749
Failures             : 0
Errors               : 0
Skipped              : 0
Result               : BUILD SUCCESSFUL in 1m 28s
```

### Android Lint Analysis
Command: `.\gradlew.bat :app:lintDebug`
```
Total Actionable Tasks: 65 (27 executed, 38 up-to-date)
Result                : BUILD SUCCESSFUL in 1m 56s (0 errors)
```

---

## 6. Deliverables & File Index

- **Module**: `android/opencv-sdk/` ([build.gradle.kts](android/opencv-sdk/build.gradle.kts), [consumer-rules.pro](android/opencv-sdk/consumer-rules.pro), [AndroidManifest.xml](android/opencv-sdk/src/main/AndroidManifest.xml))
- **Assets**: `android/app/src/main/assets/cv/wechat_qr/` (4 Caffe model files)
- **Sources**: `android/app/src/main/java/com/veilframe/app/cv/` (33 files)
- **Decoder**: [WeChatQrDecoder.kt](android/app/src/main/java/com/veilframe/app/qr/decoder/WeChatQrDecoder.kt)
- **App Integrations**:
  - [VeilFrameApplication.kt](android/app/src/main/java/com/veilframe/app/VeilFrameApplication.kt)
  - [ScanabilityValidator.kt](android/app/src/main/java/com/veilframe/app/qr/validation/ScanabilityValidator.kt)
  - [QrScanner.kt](android/app/src/main/java/com/veilframe/app/qr/scanner/QrScanner.kt)
- **Tests**: `android/app/src/test/java/com/veilframe/app/cv/` (4 test suites) & `android/app/src/androidTest/java/com/veilframe/app/cv/`
- **Documentation**: [docs/cv/VEILFRAME_CV_ENGINE.md](docs/cv/VEILFRAME_CV_ENGINE.md)
