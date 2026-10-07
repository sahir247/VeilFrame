# VeilFrame Release Notes

## v2.2.9 — October 2026

**OpenCV 4.14.0 native SDK, Computer Vision subsystem & UI redesign architecture.**
- **OpenCV 4.14.0 SDK Integration:** In-tree native OpenCV 4.14.0 build with WeChatQRCode and Videoio modules for ARM64 and x86_64.
- **Computer Vision Subsystem:** Native CV engine featuring `QuadDetector` (document and polygon geometry), `TemplateMatcher` (pattern matching), and `FlowEstimator` (dense optical flow).
- **Build Stability:** In-process Kotlin compilation and preserved native library debug symbols across builds.
- **Modern Toolchain:** Updated to latest Android SDK command-line tools.
- **UI Redesign Baseline:** Architectural foundation ([ADR 0004](docs/adr/0004-android-ui-redesign-architecture.md)) for the upcoming modular multi-workspace shell.

## v2.2.8 — September 2026

**QR Code Studio (8th native tool), AMOLED Dark mode, WhatsApp 16 MiB ceiling & Passport 600x600 preset.**
- Added QR Code Studio with 11 visual styles (Basic, Bubble, 2.5D, DSJ, Image Fill, Image Overlay, Image Resample, Line, Random Rectangle, Function, Style Function), live CameraX scanner, and safe 11-format payload parser.
- Enforced 16 MiB video size ceiling for WhatsApp Status with iterative rate control and bounded DAR preservation.
- Introduced strict AMOLED Dark `#000000` styling with per-Activity lifecycle setup.
- Added Passport 600x600 px export preset and proactive bitmap recycling in Image Studio.
- Implemented floating dock scroll-to-bottom auto-hide and pure vector SVG iconography.

### Upgrade notes

- Existing Android installations upgrade cleanly via the in-app updater verifying against `update.json` version `2.2.8` (`versionCode = 228`) with mandatory SHA-256 and signing certificate validation.
- WhatsApp Status video processing strictly guarantees outputs under 16 MiB with automatic iterative retry and trim fallback.
- Image Studio Passport preset guarantees 600x600 px output dimensions.

### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.8-py3-none-any.whl`

For Python installation:

```bash
pip install veilframe-2.2.8-py3-none-any.whl
```

### Verification & Compatibility

- SHA-256 integrity checksums are published with each release asset.
- Windows x64, Linux Debian/Ubuntu/portable, macOS Apple Silicon, Android API 26+ (target API 35), Python 3.10+.

---

## Previous releases

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
