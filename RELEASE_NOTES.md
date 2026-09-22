# VeilFrame Release Notes

## v2.2.8 — September 2026

VeilFrame v2.2.8 delivers an authoritative architectural correctness and security update across the entire application stack. Key highlights include dynamic theming with per-Activity lifecycle management and pure AMOLED black (`#000000`) surfaces, an iterative rate-controlled WhatsApp video pipeline with a non-negotiable 16 MiB ceiling, Stage 1 trim verification with automated fallback to single-pass transcode, a race-condition-free Floating Action Dock with generation token guards, authoritative `ImageTransformPlan` with Passport 600×600 px preset enforcement, probe encoding, proactive bitmap lifecycle recycling, dual publisher certificate verification in `AppUpdateManager`, model registry classification for experimental neural models, device-bound AI planner caching, and structured `ProcessingJob` foreground service management.

- **Dynamic Theming & AMOLED True Black Surfaces:**
  - **Per-Activity Theme Lifecycle:** Restructured theme and dynamic color application directly in `Activity.onCreate` before `setContentView()`, eliminating startup theme flashes and ensuring seamless runtime palette switching.
  - **AMOLED True Black:** Added pure black surfaces (`#000000`) for `android:windowBackground`, `colorSurface`, status bar, and navigation bar with high-contrast `#0A0A0C` surface variants.
  - **Palette System & Dynamic Color:** Seamlessly integrates custom theme palettes (`Monochrome`, `Forest Sage`, `Deep Ocean`, `Warm Amber`, `Cyber Violet`) when Dynamic Color is disabled, while harmonizing with Android Monet Dynamic Color when enabled.

- **WhatsApp Video Pipeline: 16 MiB Hard Ceiling & Canonical Geometry:**
  - **Iterative Rate Control Ceiling:** Enforces an absolute 16.0 MiB size ceiling with an iterative retry loop (up to 2 passes with safety factor `0.92`) that dynamically lowers video bitrate if actual output exceeds 16 MiB.
  - **Stage 1 Trim Verification & Fallback:** Validates intermediate stream-copy clips against requested trim boundaries. If stream copy fails or produces an invalid duration, automatically falls back to passing `-ss` and `-t` directly into Stage 2 for a single-pass transcode trim.
  - **Bounded DAR & Geometry Preservation:** Canonical resolution and aspect specification with strict even dimension invariants `(dimension / 2) * 2` and bounded DAR validation within 3% tolerance.
  - **Non-Blocking Cancellable FFmpeg:** Uses `suspendCancellableCoroutine` to enable instant cancellation of active encoding sessions.
  - **SAR & Rotation Tracking:** Extracts sample aspect ratio (`sample_aspect_ratio`) and display matrix rotation in `WhatsappStatusMediaAnalyzer`.

- **Floating Action Dock State Machine & Generation Token Guard:**
  - **Generation Token Guard:** Implemented `activeGeneration` token verification across Video Studio, Image Studio, and Cleaner tools, guaranteeing that stale asynchronous encoding or export tasks cannot overwrite the state of a newly selected media item.
  - **Unified 4-Stage State Machine:** Explicit reactive state transitions (`EMPTY` $\to$ `READY` $\to$ `PROCESSING` $\to$ `COMPLETED`) across all tool docks.
  - **Floating Dock UI Refinement:** Upgraded cleaner dock to a floating `MaterialCardView` with 28dp corner radius, 10dp elevation, and dynamic navigation bar inset padding.
  - **Exported vs. Processed Metrics:** Studio docks now cleanly track and report separate counters for processed items versus successfully exported files.

- **Image Studio Authoritative TransformPlan & Memory Lifecycle:**
  - **Authoritative `ImageTransformPlan`:** Unifies preview rendering, probe estimation, and export parameters into a single immutable specification.
  - **Passport 600×600 Preset Enforcement:** Enforces strict 1:1 aspect constraint in cropping and exports directly to 600×600 px dimensions for visa and passport document compliance.
  - **Empirical Probe Encoding:** Directly probe-encodes rendered bitmaps with the target codec, quality, and metadata policy for honest file size estimation.
  - **Proactive Bitmap Recycling:** Explicitly recycles intermediate preview and result bitmaps before allocating new bitmaps or exiting tools, eliminating native memory leaks.

- **Publisher Certificate Security Fix:**
  - **Dual Certificate Verification:** `AppUpdateManager` validates update APK signatures against either the currently installed application certificate or the pinned release signing certificate, preventing MITM update attacks while supporting debug development builds.

- **Model Registry & Device-Bound AI Planner Cache:**
  - **Experimental Model Classification:** Neural super-resolution models without cryptographically verified SHA-256 checksums are classified as `DeploymentStatus.ANDROID_EXPERIMENTAL`.
  - **Typed `ModelRuntimeSpec`:** Enforces strict runtime validation of input tensor dimension multiples, scale factors, channel formats, and opset requirements.
  - **Device-Bound Cache:** Binds performance benchmark profiles to specific SoC hardware architectures (`Build.HARDWARE`), preventing invalid cached execution plans across heterogeneous devices.

- **Foreground Service Job Identity & Lifecycle:**
  - **Structured `ProcessingJob`:** Tracks media processing tasks with explicit UUIDs, state transitions, and input/output URIs in `VeilFrameProcessingService`.
  - **Android 15 (API 35) Timeout Handling:** Implements `onTimeout()` callback to gracefully terminate background tasks and notify users before system kills.

### Upgrade notes

- Existing Android installations upgrade cleanly via the in-app updater verifying against `update.json` version `2.2.8` (`versionCode = 228`) with mandatory SHA-256 and signing certificate validation.
- WhatsApp Status video processing strictly guarantees outputs under 16 MiB with automatic iterative retry and trim fallback.
- Image Studio Passport preset guarantees 600×600 px output dimensions.

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
**WhatsApp HD/FHD bounded resolution, 4-stage Floating Action Dock, Passport 600×600 preset, AMOLED Dark theming & 2025–2026 AI super-resolution model lineup.**
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
