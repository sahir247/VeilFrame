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

VeilFrame v2.2.7 delivers a major refinement across Video Studio, Image Studio, AI Image Upscaler, and the Android Home Dashboard. Key advancements include bounded resolution scaling for WhatsApp Status with deterministic display aspect ratio (DAR) preservation, complete elimination of phantom estimation calculations in empty states, rigid container and codec mutual exclusivity locks, a 4-stage reactive Floating Action Dock, an international Passport 600×600 document crop preset, strict AMOLED Dark theming with Android Monet Dynamic Color awareness, modern 2025–2026 AI super-resolution architectures, 24dp card corner radii, and a streamlined hardware-aware AI inference engine with zero-QNN compliance.

- **WhatsApp Status Bounded Resolution Architecture:**
  - **Decoupled Resolution & Bounded Limits:** Decoupled status encoding from forced 9:16 aspect ratios, establishing bounded resolutions for HD 720p (max long side 1280, max short side 720, 1900 kbps) and FHD 1080p (max long side 1920, max short side 1080, 3800 kbps).
  - **Deterministic DAR Preservation:** In Original aspect mode, media scales to fit strictly within the bounding box without cropping, letterboxing, or aspect distortion across all standard aspect ratios:
    - 16:9 Landscape: `1280×720` (HD) / `1920×1080` (FHD)
    - 9:16 Portrait: `720×1280` (HD) / `1080×1920` (FHD)
    - 1:1 Square: `720×720` (HD) / `1080×1080` (FHD)
    - 4:5 Portrait: `720×900` (HD) / `1080×1350` (FHD)
    - 4:3 Landscape: `960×720` (HD) / `1440×1080` (FHD)
  - **Aspect Cropping On Demand:** When an explicit target aspect ratio (such as 9:16) is chosen for landscape media, center-cropping is applied prior to bounded scaling.
  - **Even Dimension Invariant:** All scaled video output dimensions strictly satisfy the even integer requirement `(dimension / 2) * 2` for hardware encoder compatibility.

- **Video Studio Constraints & State Invariants:**
  - **Phantom Estimate Elimination:** In empty states when no video is loaded, the studio UI strictly displays `No video selected` under BEFORE and `Select a video to calculate` under ESTIMATED OUTPUT. Fallback dimension calculations (which previously produced ~135 KB or ~24 MB placeholders) are completely eliminated.
  - **Container & Codec Mutual Exclusivity:**
    - Selecting WhatsApp Status automatically enforces container `MP4` and codec `H.264`, and disables format and codec chips (`alpha = 0.38f`) while keeping checkmarks visible. The Scale tool button is disabled (`alpha = 0.38f`) because resolution is governed by the dedicated Status Resolution selector.
    - Selecting `GIF (Animated)` automatically reverts target size to `Auto (CRF)`, hides video codec chips, and disables/mutes audio controls.
    - Standard social targets (Discord 25 MB, Nitro 50 MB, Email 8 MB, Web 10 MB) set bitrate ceilings without locking format or codec.
  - **Empty State Tool Gray-Out:** When no video is loaded, all 6 edit tools (`Trim`, `Scale`, `Colour`, `Speed`, `Aspect`, `Audio`) and the audio chip group (`chipGroupVidAudio`, `chipVidAudioKeep`, `chipVidAudioRemove`) are disabled with `alpha = 0.38f`.

- **Floating Action Dock State Controller:**
  - **4-Stage Reactive State Machine:** Unified state controller managing transitions across `EMPTY` (Pick a File), `READY` (Start Processing), `PROCESSING` (Progress indicator), and `COMPLETED` (dual `[ Save Video/Image ]` and `[ Share ]` actions).
  - **Saved State Revert:** Tapping Save displays a checkmark and "Saved" status for 2.5 seconds before gracefully reverting.
  - **Edit Reset:** Applying parameter changes (`onEditApplied()`) automatically resets the dock back to `READY`, ensuring modifications require re-encoding before export.

- **Image Studio Passport (600×600) Preset:**
  - Added dedicated `Passport (600×600)` preset enforcing a strict 1:1 aspect constraint in `CropOverlayView` and exporting directly to official 600×600 px dimensions for visa and passport document compliance.

- **AMOLED Dark Theming & Dynamic Color Awareness:**
  - Standardized theme naming strictly to "AMOLED Dark" across settings and theme dialogs.
  - Added dynamic notification (`tvDynamicColorNotice`) informing users when custom palette options are managed by Android Monet Dynamic Color, with palette chips disabled (`alpha = 0.38f`).
  - Applied theme post-inflation to eliminate visual startup flicker.

- **2025–2026 AI Super-Resolution Model Lineup:**
  - Expanded neural model catalog with contemporary architectures: SAT-light 2×/4×, SAFMNv3 2×/4×, Real-SAFMN++ 4×, ESPAN 4×, PFT-light 4×, DRCT 4×, and PlainUSR 4×.
  - Re-labeled Real-ESRGAN General 4× as "Legacy Standard".
  - Structured `SELECTABLE_MODELS` to filter out Tier C / Reference-Only models from on-device pickers.

- **Adaptive AI Inference Architecture (Zero QNN):**
  - **Hardware-Aware Planner:** Evaluates NNAPI and CPU/XNNPACK backends via empirical micro-benchmarking.
  - **Progressive Worker Concurrency:** Scalable concurrency search without arbitrary CPU-core caps on accelerators.
  - **Dual-Domain Memory Safety:** Decoupled Java heap and native memory budgeting with zero-worker edge-case rejection.
  - **Zero-QNN Compliance:** Fully removed all Qualcomm QNN SDK code, headers, and dependencies.

- **Home Dashboard Categorization & 24dp Card Radii:**
  - Upgraded all 8 tool cards in `scrollHome` to `app:cardCornerRadius="24dp"`.
  - Reorganized tools into three clear categories: `MEDIA STUDIOS` (Video Studio, Image Studio, AI Image Upscaler), `PRIVACY CLEANERS` (Video Cleaner, Image Cleaner), and `FORENSICS & AI` (Folder Scanner, AI Bundle, Markdown Viewer).

### Upgrade notes

- Existing Android installations upgrade cleanly via the in-app updater verifying against `update.json` version `2.2.7` (`versionCode = 227`) with mandatory SHA-256 and signing certificate validation.
- WhatsApp Status encoding now defaults to native aspect ratio preservation within HD (720p) / FHD (1080p) bounding boxes; explicit 9:16 center-cropping is available under the Aspect tool.
- All neural upscaler models download on-demand directly from HuggingFace with cryptographic SHA-256 verification and store privately inside the application sandbox.

### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.7-py3-none-any.whl`

For Python installation:

```bash
pip install veilframe-2.2.7-py3-none-any.whl
```

### Verification & Compatibility

- SHA-256 integrity checksums are published with each release asset.
- Windows x64, Linux Debian/Ubuntu/portable, macOS Apple Silicon, Android API 26+ (target API 35), Python 3.10+.

---

## Previous releases

### v2.2.6 — September 2026

- **AI Image Upscaler Subsystem:** 7th independent tool running 100% offline neural super-resolution via ONNX Runtime with Point 7 models (Real-ESRGAN 2×/4×, Anime 4×, Lanczos, Bicubic, Nearest), tiled processing with cubic Hermite feathering, and resumable model downloading.
- **Accurate File Size Estimation:** Fixed BMP estimation anomaly with exact 24-bit DWORD scanline padding (`((24 * w + 31) / 32) * 4`), and overhauled empirical size models for JPEG, WebP, PNG, TIFF, GIF, HEIF/AVIF, and video bitrates.
- **Video Studio Enhancements:** Added Slow, Medium, and Fast compression speed presets, video rotation (90°, 180°, 270°), canvas flips, 4:3 and 3:4 aspects, custom margin crop slider (0–40%), and trim-bounded timeline playback.
- **Image Studio Live Zoom:** Live preview reflects scaling factors above 100% with full-resolution export memory safeguards.

### v2.2.5 — September 2026

- **Native GPL FFmpegKit:** Upgraded Android video engine to native GPL `FFmpegKit 8.1.7` bundling `libx264` and `libx265`, dropping Python/Chaquopy from Android and reducing APK size to ~79 MB.
- **Video Studio Colour Grading:** Added 15 cinematic colour profiles with 60fps hardware-accelerated live preview rendering.
- **Studio Layout Reorganization:** Output settings card unified with target size presets, container formats, codecs, CRF, resolution, and audio.
- **Text Watermark Studio:** Custom overlays with 12 font styles, 16 color chips, hex color input, and 9-point spatial anchoring.
- **Target Size Optimizer:** Automated iterative quality solver maximizing quality under non-negotiable size ceilings.
- **Multi-Container & Multi-Codec:** Export support for MP4, MOV, MKV, WebM, AVI, GIF with H.264, H.265, VP9, and Stream Copy.
- **Audio Studio Processing:** Volume gain scaling (0%–200%), channel remixing (Stereo, Mono), and codec encoding (AAC, MP3, Opus, FLAC).
- **Batch Processing & Lifecycle:** Multi-selection batch studio, Media3 PlayerView timeline trimmer, and 10-second idle back navigation memory purge.

### v2.2.4 — September 2026

VeilFrame v2.2.4 introduces dedicated Mobile Image & Video Studios, visual timeline range trimming, platform-targeted compression presets, and strict APK Signature Scheme V2/V3 enforcement.

- **Pure APK Signature Scheme V2/V3:** Release builds disable legacy V1 JAR signatures and sign exclusively with APK Signature Scheme V2/V3, resolving `META-INF/MANIFEST.MF` verification failures while maintaining full compatibility with Android 8.0+ (API 26+).
- **Release pipeline integrity:** Eliminates unsigned APK fallbacks in build scripts and adds automated `apksigner` cryptographic verification enforcing `--min-sdk-version 26` in CI.
- **Mobile Image Studio:** Introduces a comprehensive editing studio featuring smart quality-targeted compression, aspect-ratio cropping, dimensional scaling, complete EXIF/metadata scrubbing, artistic color grading filters, and lossless rotation.
- **Mobile Video Compressor & Visual Trimmer:** Adds interactive visual timeline range trimming with dual-thumb controls allowing user-defined cut points anywhere in the video (e.g. trimming 4–6s out of a 10s video), target platform presets (Discord 8MB/25MB/50MB, WhatsApp 16MB, Email 25MB, Web 10MB), resolution scaling, audio stripping/transcoding, and playback speed adjustment.
- **Target compression profiles:** Adds multi-pass bitrate calculation and dimension scaling profiles for web and social platforms with real-time target size estimation.
- **Cross-platform CLI & core parity:** Unified `veilframe-compress` engine across desktop GUI, terminal CLI, and embedded Android Chaquopy runtime with full batch processing support.
- **Zero deprecated identifiers:** Completely audited codebase and layouts removing legacy references and ensuring strict compliance with vector drawable standards.
- **Version consistency:** Synchronizes all platform packaging, update manifests, build metadata, lockfiles, and core engines at version `2.2.4`.

### v2.2.3 — September 2026

- Applied edge-to-edge status-bar insets so headers, titles, and navigation controls do not clip under system UI.
- Improved in-app updates to follow multi-hop GitHub/CDN redirects and resume installation after permissions are granted.
- Configured dedicated release signing identity and CI-managed keystore secrets.
- Added format-aware batch processing preserving source containers and packaging output into structured ZIP archives.
- Refined workflows with a command-center launcher, 3-step workflow presentation, clearer target states, context-aware actions, and collapsible telemetry console.

### v2.2.2 — September 2026

- Added responsive mobile HTML scan reports with touch-friendly tables and copyable hash values.
- Preserved the selected target path in folder reports.
- Improved chip, switch, and action-dock state visibility and removed common button clipping.
- Added dynamic GitHub changelog synchronization and a persistent light/dark theme toggle.

### v2.2.1 — September 2026

- Introduced the Android home dashboard and dedicated workflows for AI Bundling, Video Cleaning, Image Cleaning, and Folder Scanning.
- Added cryptographically verified in-app updates, download progress, cancellation, and package validation.
- Added a universal job-state lifecycle, recursive SAF directory traversal, responsive controls, and persistent per-tool state.

### v2.2.0 — September 2026

- Added the AI Context Bundler and `.aibundle` v1 format with token-bounded, zero-truncation exports.
- Added language-aware secret detection, entropy scoring, credential exclusions, and syntax-preserving redaction.
- Added native Android packaging and automated multi-platform release builds.
- Added lockfile, database, model, binary, and collapsed-directory summaries for efficient project context.

### v2.0.x

- Added the Folder Analyzer, staged duplicate detection, SQLite caching, SHA-256 reporting, and the PySide6 desktop workflows.
- Established the sanitization quality gates, visual-fidelity checks, cryptographically signed audit manifests, and media privacy engines.

For implementation details, see [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), and the [project roadmap](ROADMAP.md).
