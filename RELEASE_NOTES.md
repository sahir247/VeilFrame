# VeilFrame Release Notes

## v2.2.9 — October 2026 (Stable)

**Major Release: Material 3 Expressive UI v3.0, Motion Lab (Optical Flow), Document Scanner Adjust & Stabilization, AI Upscaler Streaming, and OpenCV 4.14.0 Native Engine.**

### New Features & Highlights

1. **Material 3 Expressive UI ("Quiet Intensity") ([ADR 0005](docs/adr/0005-m3-expressive-design-system.md))**:
   - **Expressive Foundation:** Upgraded to Material Components 1.14.0 with root `Theme.Material3Expressive.DayNight.NoActionBar`.
   - **Monochrome & Dynamic Colors:** Full surface-container ladders (Lowest to Highest) across light, dark, and AMOLED modes with 5 accent palettes (Monochrome, Forest Sage, Deep Ocean, Warm Amber, Cyber Violet).
   - **Spring Motion Physics:** Real-world spring dynamics (`VfSprings v3.1` using immutable `SpringSpec` architecture) replacing overshoot interpolators; tactile press-bounce, completion flourish, and reduced-motion contract.
   - **System Insets Contract:** Centralized `WorkspaceInsets` contract applying edge-to-edge padding across all 12 workspace roots, preventing toolbar and dock occlusion under system bars.
   - **Clipping Sweep:** Converted 126 fixed-height buttons and 22 rows to `wrap_content`, eliminating label clipping across the larger expressive typography scale.

2. **Motion Lab (10th Native Workspace — Alpha)**:
   - **Optical Flow Frame Synthesis:** New dedicated workspace wiring the `cv/motion` engine (`FlowEstimator`, `FrameSynthesizer`) for 2× and 4× FPS video frame interpolation.
   - **Dual Estimation Algorithms:** DIS Optical Flow for fast mobile processing and Farnebäck Optical Flow for high-quality dense motion vectors.
   - **Memory & Storage Safety:** Flat pairwise Mat memory management, temporary frame scratch cleanup, and direct export to MediaStore (`Movies/VeilFrame`).

3. **Document Scanner Hardening & Adjust Dialog**:
   - **Real-Time QuadStabilizer:** Exponential Moving Average (EMA, α=0.4) corner smoothing with 48px tolerance and hysteresis, eliminating viewfinder corner strobing.
   - **Per-Page Image Adjust:** Interactive per-page adjustment dialog for Exposure (−2..+2 stops), Contrast (0.5..2×), and Saturation (0..2×) powered by `cv/color/ColorEngine`.
   - **CameraX Lifecycle Discipline:** Single-flight capture lock (`isCapturing`) and OOM-guarded bitmap decoding preventing camera executor race conditions.
   - **Progressive Export Sheet:** Redesigned document export bottom sheet with progressive disclosure ("More options ⇄ Fewer options").

4. **Background Remover Gallery Export**:
   - **Direct MediaStore Save:** Saves transparent PNG exports directly to the device gallery (`Pictures/VeilFrame`) with Android 10+ `IS_PENDING` compliance, fixing previous SAF handoff failures.
   - **Persistent Mirror:** Synchronously mirrors exports to `filesDir/exports` for immediate Library visibility.

5. **AI Image Upscaler Streaming & Memory Governor ([ADR 0006](docs/adr/0006-cv-governance-dynamic-memory.md))**:
   - **Streaming Band Processing:** `StreamingPngWriter` and `UpscaleForegroundService` stream output bands directly to disk, enabling massive multi-megapixel upscaling without OOM crashes.
   - **Dynamic Memory Governor:** Allocates tile sizes and execution paths based on live `MemAvailable`, dynamically tuning performance across 6–16 GB devices.
   - **Mathematical Resampling:** Precomputed Lanczos-3 weight kernels and bicubic fallbacks for zero-hallucination processing.
   - **Quantized Model Pipeline:** Production tool (`tools/model_pipeline/quantize_upscale_models.py`) with PSNR validation gates (FP16 $\ge$ 55 dB, INT8 $\ge$ 35 dB).

6. **Unified Library Synchronization**:
   - **Hybrid Indexing:** Merges MediaStore query results (`DATA LIKE '%/VeilFrame/%'`) with app-internal directory exports so all previous creations appear in the Library.

7. **OpenCV 4.14.0 SDK & Computer Vision Reliability**:
   - **In-Tree Custom SDK:** Complete custom OpenCV 4.14.0 SDK with WeChatQRCode and native ABIs.
   - **Phase B6 Cancellation Checkpoints:** 34 granular cancellation checkpoints across GrabCut, quality metrics, contour detection, and Lanczos loops for instant abort responsiveness.

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
