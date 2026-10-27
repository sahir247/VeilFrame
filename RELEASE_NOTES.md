# VeilFrame Release Notes

## v2.3.0 — October 2026 (Stable)

**Major Release: Motion Lab RIFE v4 AI Neural 60 FPS Synthesis, Full Tencent NCNN Native C++ Migration (Zero ONNX Dependencies), Multi-Vendor Hardware Validation (Qualcomm Adreno & MediaTek Mali-G615), Adaptive Detail Enhancer with Touch Brush/Eraser, Smooth Vector PDF Signature Pad, Symmetrical PDF Studio Hub, and Silent Foreground/Background Download Engine.**

---

### 🌟 Executive Highlights

VeilFrame **v2.3.0** marks a transformative milestone in offline on-device media engineering. This release completely eliminates external ONNX Runtime dependencies in favor of high-throughput, native Tencent NCNN C++ execution across computer vision, facial redaction, and super-resolution models. Motion Lab receives a dedicated AI neural frame synthesis pipeline powered by RIFE v4 targeting smooth 60 FPS video outputs. Background removal is elevated with an Adaptive Detail Enhancer featuring interactive touch brush and eraser controls. PDF Studio introduces a high-precision smooth Bézier vector signature pad with 3.5× supersampling, alongside an overhaul of the background model download engine with silent dialogs and auto-promotion to system status bar notifications.

---

### 🚀 What's New in v2.3.0

#### 1. 🎬 Motion Lab: RIFE v4 AI Neural Frame Synthesis & 60 FPS Target Mode
- **Neural Intermediate Flow Estimation (RIFE v4):** Added state-of-the-art RIFE v4 neural frame interpolation running alongside classical OpenCV DIS and Farnebäck optical flow algorithms.
- **Dedicated 60 FPS Target (Default):** Introduced a prominent "60 fps" rate selector, automatically chosen whenever RIFE mode is active. The engine dynamically calculates required intermediate temporal timestep schedules:
  - **Standard Sources (24–30 FPS):** Interpolates at $t=0.5$ (2× multiplier) to achieve a solid 60.000 FPS.
  - **Low-Framerate Sources ($\le 20$ FPS):** Interpolates at $t=0.25, 0.50, 0.75$ (4× multiplier) to ensure smooth 60 FPS rendering.
- **Clean YUV420p MediaStore Export:** Re-encoded intermediate video streams are committed directly to `Movies/VeilFrame` in the device gallery with universal hardware player compatibility.
- **Dual Execution Engine:** Automatic hardware Vulkan compute acceleration with seamless multi-threaded CPU OpenMP fallback.

#### 2. ⚡ Complete Tencent NCNN Native C++ Migration & ONNX Elimination
- **100% Zero ONNX Dependencies:** Completely eliminated ONNX Runtime Android dependencies (`com.microsoft.onnxruntime`) and all `.onnx` models, eliminating binary bloat and native library overhead.
- **Native U^2-Net-P Matting (`matting_jni.cpp`):** High-speed quantized U^2-Net-P segmentation model (`u2netp.param` / `u2netp.bin`) executing directly in native C++ memory for instantaneous human and portrait segmentation.
- **Native UltraFace PII Redaction (`pii_ncnn_jni.cpp`):** Real-time face detection powered by native NCNN RFB-320 (`RFB-320.param` / `RFB-320.bin`), running with stride-6 tensor packing and category-specific isolation.
- **Native RealSR Super-Resolution (`realsr_jni.cpp`):** 2×, 3×, and 4× photo and anime super-resolution running on native Tencent NCNN C++ with dedicated Vulkan compute queues.

#### 3. 📱 Multi-Vendor Physical Hardware & Vulkan Compute Validation
- **Broad Fleet Certification:** Verified through physical test execution across leading Android chipsets:
  - **Qualcomm Snapdragon:** Validated on Snapdragon 8 Gen 1/2/3 (Adreno 730/740) Vulkan pipelines.
  - **MediaTek Dimensity:** Validated on Dimensity 7300 (`motorola edge 50 neo`, Mali-G615 MC2) running Android 16.
- **Vulkan Hardware Diagnostics:** Fully verified dual Vulkan compute queues (`queueC=0[2]`, `queueT=0[2]`) with 100% tile-boundary continuity and zero memory corruption under heavy 48-tile 5K super-resolution workloads.
- **Cancellation Safety:** Instant thread-safe cancellation across all native C++ inference loops without resource leaks or zombie processes.

#### 4. 🎨 Adaptive Detail Enhancer & Interactive Canvas Refinement
- **Dual-Model Hybrid Removal:** Combines coarse foreground segmentation with morphological trimap generation and a guided alpha refinement filter for fine hair and boundary delineation.
- **Interactive Refine Canvas (`RefineBrushCanvasView`):** High-precision on-screen touch refinement:
  - **Brush & Eraser Modes:** Unconditionally remove stray background remnants or restore clipped foreground details.
  - **Real-Time Stroke Preview:** Concentric circular overlay dynamically previewing brush outer bounds and inner hardness during stroke size adjustments.
  - **Touch Gesture Isolation:** Smooth panning and two-finger zooming without UI scroll interference or touch event conflicts.
  - **Full Undo/Redo History:** Comprehensive snapshot stack allowing quick undo/redo across refinement operations.

#### 5. ✍️ PDF Studio Hub Uniformity & Smooth Vector Signature Pad
- **Symmetrical 10-Card Grid:** Standardized all PDF Studio tool cards to a uniform `112dp` height with consistent 2-line descriptions, resolving previous card size irregularities.
- **Smooth Bézier Signature Pad (`SignaturePadView`):** Replaced jagged line drawing with Catmull-Rom cubic Bézier curve interpolation, velocity-sensitive stroke width tapering, and single-tap dot generation.
- **3.5× Supersampled Vector Export:** Crisp PDFBox vector signature embedding with anti-aliased edge smoothing, eliminating pixelation upon PDF page placement.

#### 6. 📥 Resilient Background Download Engine & Clean Status Notifications
- **Persistent Background Service (`ModelDownloadBackgroundService`):** Background download management with complete lifecycle safety; navigating away from the model manager or backgrounding the app no longer aborts downloads.
- **Silent In-Dialog Experience:** Suppresses redundant status bar notifications while the user remains in the download dialog, automatically promoting to system notifications only when the app is backgrounded.
- **Cleaned Notification Copy:** Stripped legacy `"Safe to close app"` text and simplified status messages to clean, actionable alerts (`"Ready to install."`, `"${modelName} ready to use."`).

#### 7. 🛡️ System Security & Runtime Permissions
- **Android 13+ Notification Prompt:** Native runtime `POST_NOTIFICATIONS` permission dialog requested cleanly on initial application launch.
- **Monotonic Version Governance:** Centralized `AppVersionProvider` and enforced monotonic integer `versionCode = 230` across all update validation pathways.

---

### 📦 Downloads & Installation

Download pre-built production release artifacts from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases/tag/v2.3.0):

| Platform | Package / Asset | Architecture & Requirements |
|---|---|---|
| **Android** | `VeilFrame-android-arm64.apk` | ARM64-v8a (Android 8.0+ / API 26+) |
| **Windows** | `VeilFrame-windows-x86_64.exe` | x86_64 (Windows 10/11 64-bit) |
| **Linux (Debian/Ubuntu)** | `VeilFrame-linux-x86_64.deb` | x86_64 (glibc 2.31+) |
| **Linux (Portable)** | `VeilFrame-linux-x86_64.tar.gz` | x86_64 (Standalone executable) |
| **macOS (Installer)** | `VeilFrame-macos-arm64.dmg` | Apple Silicon (macOS 11.0+) |
| **macOS (Portable)** | `VeilFrame-macos-arm64.tar.gz` | Apple Silicon (macOS 11.0+) |
| **Python Package** | `veilframe-2.3.0-py3-none-any.whl` | Python 3.10+ Universal Wheel |

```bash
# Install Python package
pip install veilframe-2.3.0-py3-none-any.whl
```

### 🔒 Verification & Compatibility
- **Cryptographic Provenance:** SHA-256 digests and Ed25519 signatures are published with every release asset.
- **Zero Cloud Guarantee:** 100% offline, local-only processing with zero analytics, telemetry, or network leaks.

---

## Previous releases

### v2.2.9 — October 2026
**Material 3 Expressive UI v3.0, Motion Lab (Optical Flow 2×/4× FPS), Video Studio Instant Preview & YUV420p, QR Code Studio EF Parity, Document Scanner Adjust & Insets.**
Introduced Material 3 Expressive UI foundation with spring physics dynamics (`VfSprings v3.1`), centralized `WorkspaceInsets` contract across all 14 workspace roots, sub-100ms instant ExoPlayer preview, universal YUV420p video encoding, EFQRCode 7.0.3 parity normalization with pure vector SVG export, real-time QuadStabilizer document scanning, and Phase B6 CV cancellation checkpoints.

### v2.2.8 — September 2026
**QR Code Studio (8th native tool), AMOLED Dark mode, WhatsApp 16 MiB ceiling & Passport 600x600 preset.**
Added QR Code Studio with 11 visual styles and CameraX scanner; enforced 16 MiB video size ceiling for WhatsApp Status; added AMOLED Dark styling, Passport 600x600 px export preset, and vector UI assets.


### v2.2.7 — September 2026
**WhatsApp HD/FHD bounded resolution, 4-stage Floating Action Dock, Passport 600x600 preset, AMOLED Dark theming & 2025–2026 AI super-resolution model lineup.**
Decoupled WhatsApp Status from forced 9:16 with deterministic DAR preservation and even-dimension invariants; added zero-QNN compliance, adaptive hardware-aware AI inference, and 24dp card radii across all 8 tool cards.

### v2.2.6 — September 2026
**AI Image Upscaler (7th tool), accurate file-size estimation & Video Studio speed/rotation/crop additions.**
Offline neural super-resolution via ONNX Runtime with Point 7 models, tiled cubic Hermite feathering, and fixed BMP/JPEG/WebP/PNG size estimation anomalies.

### v2.2.5 — September 2026
**Native GPL FFmpegKit, Video Studio Colour Grading, Text Watermark Studio & Target Size Optimizer.**
Dropped Python/Chaquopy from Android (APK ~79 MB); added 15 cinematic colour profiles, 12-font watermarks, multi-pass bitrate solver, and full multi-container/multi-codec export.

### v2.2.4 — September 2026
**Mobile Image & Video Studios, visual timeline trimmer & APK Signature Scheme V2/V3 enforcement.**
Introduced smart quality-targeted image editing, interactive dual-thumb video trim controls, and platform presets (Discord, WhatsApp, Email). Eliminated unsigned APK fallbacks.

### v2.2.3 — September 2026
**Edge-to-edge insets, multi-hop update redirects & format-aware batch ZIP processing.**
Refined command-center launcher with 3-step workflow presentation, collapsible telemetry console, and persistent release signing identity.

### v2.2.2 — September 2026
**Responsive HTML scan reports, dynamic changelog sync & persistent light/dark theme toggle.**
Added touch-friendly report tables with copyable hashes; improved chip, switch, and action-dock state visibility.

### v2.2.1 — September 2026
**Android home dashboard, dedicated AI/Video/Image/Folder workflows & cryptographically verified in-app updates.**
Universal job-state lifecycle, recursive SAF directory traversal, and persistent per-tool state across sessions.

### v2.2.0 — September 2026
**AI Context Bundler & `.aibundle` v1 format with token-bounded, zero-truncation exports.**
Language-aware secret detection, entropy scoring, AST outlining, and native Android packaging across all platforms.

### v2.0.x
**Folder Analyzer, staged duplicate detection, SQLite caching & PySide6 desktop workflows.**
Established sanitization quality gates, visual-fidelity checks, cryptographically signed audit manifests, and media privacy engines.

---

For implementation details, see [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), and the [project roadmap](ROADMAP.md).
