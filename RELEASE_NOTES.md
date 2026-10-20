# VeilFrame Release Notes

## v2.2.9 — October 2026 (Stable)

**Major Release: Material 3 Expressive UI v3.0, Motion Lab (Optical Flow 2×/4× FPS), Video Studio Instant Preview & YUV420p, QR Code Studio EFQRCode Parity & Vector SVG, Document Scanner Adjust & Stabilization, AI Upscaler Streaming, and OpenCV 4.14.0 Native Engine.**

### New Features & Highlights

1. **Material 3 Expressive UI ("Quiet Intensity") ([ADR 0005](docs/adr/0005-m3-expressive-design-system.md))**:
   - **Expressive Foundation:** Upgraded to Material Components 1.14.0 with root `Theme.Material3Expressive.DayNight.NoActionBar`.
   - **Monochrome & Dynamic Colors:** Full surface-container ladders (Lowest to Highest) across light, dark, and true `#000000` AMOLED modes with elevation tint suppression and 5 accent palettes.
   - **Spring Motion Physics:** Real-world spring dynamics (`VfSprings v3.1` using immutable `SpringSpec` architecture) replacing overshoot interpolators; tactile press-bounce, completion flourish, and reduced-motion contract.
   - **System Insets Contract:** Centralized `WorkspaceInsets` contract applying edge-to-edge padding across all 14 workspace roots, preventing toolbar and dock occlusion under system bars.
   - **Clipping Sweep:** Converted 126 fixed-height buttons and 22 rows to `wrap_content`, eliminating label clipping across the larger expressive typography scale.
   - **Process Safety:** Active task cancellation warning dialogs preventing accidental exit during ongoing media export or forensic transformation.

2. **Video Studio & Compressor Modernization**:
   - **Sub-100ms Instant Preview:** Direct URI handoff to Media3 ExoPlayer eliminating intermediate disk copies and cache stalls.
   - **Guaranteed YUV420p Compatibility:** Universal `-pix_fmt yuv420p` encoding across all export paths, guaranteeing playback compatibility across older hardware decoders and messaging apps.
   - **Timeline Range Trimming & Platform Presets:** Dual-thumb scrubber with millisecond precision and byte-bounded targets (WhatsApp 16 MB, Discord 25 MB).

3. **QR Code Studio (EFQRCode 7.0.3 Parity & Vector Export) ([ADR 0003](docs/adr/0003-efqrcode-parity-architecture-and-verification.md))**:
   - **EFQRCode 7.0.3 Parity Normalization:** Canonical Geometry IR normalization (`effectiveDesignForMode()`) enforcing deterministic shape, quiet zone, and node order parity.
   - **Vector SVG & Animated SVG Export:** Pure XML vector SVG export with module-level `<path>` and `<rect>` elements and animated GIF/SVG generation.
   - **Decode-First Validation:** On-device camera frame and gallery decoding with automatic fallback gating.
   - **Responsive 2×2 Action Grid:** Replaced crowded horizontal button stacks with a clean 2×2 action grid for Copy, Share, Save, and Scan actions.

4. **Motion Lab (14th Native Workspace — Alpha) ([ADR 0007](docs/adr/0007-motion-lab-optical-flow-synthesis.md))**:
   - **Optical Flow Frame Synthesis:** New dedicated workspace wiring the native OpenCV `cv/motion` engine (`FlowEstimator`, `FrameSynthesizer`) for 2× and 4× FPS video frame interpolation.
   - **Dual Estimation Algorithms:** DIS Optical Flow for fast mobile processing and Farnebäck Optical Flow for high-density motion vectors.
   - **Memory & Storage Safety:** Flat pairwise Mat memory management, temporary frame scratch cleanup, and direct export to MediaStore (`Movies/VeilFrame`).

5. **AI Context Bundler (`.aibundle` v1) Depth Limits**:
   - **Explicit Max Depth Limits:** Added `--max-depth` CLI flag and interactive UI depth stepper/toggle in Android and Python desktop environments, bounding recursive traversal and preventing runaway token consumption.
   - **Secret Redaction & Priority Knapsack:** Syntax-preserving entropy scans redacting API keys and lockfile outlining.

6. **Offline Markdown Studio Multi-Format Export & PDF Theming**:
   - **Unified "Save As" Menu:** Streamlined single-action menu for exporting to Markdown (`.md`), PDF (`.pdf`), and Microsoft Word (`.docx`).
   - **Print & PDF Theming:** Dedicated `@media print` CSS rules formatting documents with clean Light and Dark print palettes, page breaks, and formula scaling.

7. **Document Scanner Hardening & Adjust Dialog ([ADR 0008](docs/adr/0008-insets-contract-and-camera-stabilization.md))**:
   - **Real-Time QuadStabilizer:** Exponential Moving Average (EMA, α=0.4) corner smoothing with 48px tolerance and hysteresis, eliminating viewfinder corner strobing.
   - **Per-Page Image Adjust:** Interactive per-page adjustment dialog for Exposure (−2..+2 stops), Contrast (0.5..2×), and Saturation (0..2×) powered by `cv/color/ColorEngine`.
   - **CameraX Lifecycle Discipline:** Single-flight capture lock (`isCapturing`) and OOM-guarded bitmap decoding preventing camera executor race conditions.
   - **Progressive Export Sheet:** Redesigned document export bottom sheet with progressive disclosure ("More options ⇄ Fewer options").

8. **AI Image Upscaler Native NCNN / RealSR Architecture & Model-Aware UI ([ADR 0006](docs/adr/0006-cv-governance-dynamic-memory.md))**:
   - **Native NCNN Vulkan Engine:** Powered exclusively by native C++ Tencent NCNN binaries (`realsr_jni.cpp`) with Vulkan GPU compute acceleration and multi-threaded CPU fallback; zero ONNX runtime overhead in upscaling.
   - **Verified Pinned Models:** Pinned SHA-256 digests and exact byte sizes for `Real-ESRGAN x4plus`, `AnimeVideoV3` (2×, 3×, 4×), `RealSR DF2K 4×`, `RealSR DF2K JPEG 4×`, and `Real-ESRGAN HFA2k 4×`.
   - **Model-Aware Honest Scale Controls:** Dynamically binds UI toggles (`2×`, `3×`, `4×`) to legitimate model capabilities, eliminating artificial multi-pass 6× and 8× scalers.
   - **Harden Download & Rollback:** Fail-closed HTTP 200 verification, approved origin policy enforcement, Content-Length checks, and atomic file commitment with rollback.
   - **Mathematical Resampling:** Precomputed Lanczos-3 weight kernels, Bicubic spline, and Nearest Neighbor fallbacks for zero-hallucination processing.

9. **Native Standard Navigation & System Insets**:
   - **Native Transitions:** Replaced legacy manual coordinate translation controllers with native Android `TransitionManager`, `AutoTransition`, and standard window crossfades.
   - **Android 15 Predictive Back:** Full native back-gesture support without card origin leaks or activity context retention.
   - **Centralized Versioning:** Integrated `AppVersionProvider` sourcing dynamically from `BuildConfig.VERSION_NAME` (`v2.2.9`).

10. **Production ProGuard & R8 Minification**:
    - **Dead Code Stripping:** Removed universal keep rules to enable R8 tree-shaking, method inlining, and class minification.
    - **Granular Keep Surface:** Dedicated keep rules for JNI native boundaries (`NcnnNative`), OpenCV, TensorFlow Lite, ONNX Runtime (Rembg), ZXing, ML Kit, FFmpegKit, Media3, and layout custom views.

11. **Background Remover Gallery Export**:
    - **Direct MediaStore Save:** Saves transparent PNG exports directly to the device gallery (`Pictures/VeilFrame`) with Android 10+ `IS_PENDING` compliance, fixing previous SAF handoff failures.
    - **Persistent Mirror:** Synchronously mirrors exports to `filesDir/exports` for immediate Library visibility.

12. **Cryptographic Provenance & Neutral Audit State**:
    - **Neutral Initial State:** Clean initial verification dashboard without pre-populated failure markers.
    - **Ed25519-SHA512 Verification Card:** Instant validation of RFC 8785 JCS canonical JSON manifests and SHA-256 bitstream digests.

13. **OpenCV 4.14.0 SDK & Computer Vision Reliability**:
    - **In-Tree Custom SDK:** Complete custom OpenCV 4.14.0 SDK with WeChatQRCode and native ABIs (`arm64-v8a`, `x86_64`).
    - **Phase B6 Cancellation Checkpoints:** 34 granular cancellation checkpoints across GrabCut, quality metrics, contour detection, and Lanczos loops for instant abort responsiveness.
    - **Unified Library Synchronization:** Merges MediaStore query results (`DATA LIKE '%/VeilFrame/%'`) with app-internal directory exports.


### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.9-py3-none-any.whl`

For Python installation:

```bash
pip install veilframe-2.2.9-py3-none-any.whl
```

### Verification & Compatibility

- SHA-256 integrity checksums are published with each release asset.
- Windows x64, Linux Debian/Ubuntu/portable, macOS Apple Silicon, Android API 26+ (target API 35), Python 3.10+.

---

## Previous releases

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
