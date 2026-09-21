# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v2.2.8 CURRENT (Production Release)
- **Theming Architecture & Dynamic Color Lifecycle**:
  - Per-Activity theme lifecycle in `Activity.onCreate` before `setContentView()`, eliminating UI flicker.
  - Strict AMOLED Dark `#000000` surface themes, status/navigation bars, and high-contrast variant `#0A0A0C`.
  - Palette styles (`Monochrome`, `Forest Sage`, `Deep Ocean`, `Warm Amber`, `Cyber Violet`) active when Dynamic Color is disabled, seamless Monet Dynamic Color overlay when enabled.
- **WhatsApp Video Rate Control & Canonical Geometry**:
  - Non-negotiable 16 MiB size ceiling with iterative dynamic retry loop (up to 2 passes with safety factor `0.92`).
  - Stage 1 trim verification (`allowedError = maxOf(0.5, requestedDuration * 0.05)`) with automatic fallback to Stage 2 single-pass transcode trim.
  - Bounded DAR validator with 3% tolerance and strict even dimension invariant `(dimension / 2) * 2`.
  - Cancellable non-blocking FFmpeg execution via `suspendCancellableCoroutine`.
  - Source geometry, SAR, and rotation capture via `WhatsappStatusMediaAnalyzer`.
- **Floating Action Dock State Machine & Generation Token Guard**:
  - Unified 4-stage reactive controller (`EMPTY` $\to$ `READY` $\to$ `PROCESSING` $\to$ `COMPLETED`) across all tool docks.
  - Generation token guard (`activeGeneration`) preventing asynchronous race conditions on media switching.
  - Floating Material card styling with 28dp rounded corners, 10dp elevation, and navigation bar inset responsiveness.
  - Separate reporting of processed items versus successfully exported files.
- **Image Studio TransformPlan & Memory Lifecycle**:
  - Authoritative `ImageTransformPlan` unifying preview, probe estimation, and export parameters.
  - Passport 600×600 px preset enforcement with strict 1:1 cropping and document export sizing.
  - Empirical probe encoding for honest pre-export file size estimation.
  - Explicit bitmap memory lifecycle management with intermediate preview and result bitmap recycling.
- **Publisher Security & Certificate Verification**:
  - Dual publisher certificate verification in `AppUpdateManager` supporting installed app signature matching or pinned release certificate fallback.
- **AI Model Registry & Device-Bound Performance Cache**:
  - Reclassified experimental SOTA models lacking verified checksums to `DeploymentStatus.ANDROID_EXPERIMENTAL`.
  - Introduced typed `ModelRuntimeSpec` validating tensor dimension multiples, scale factors, channel formats, and opset requirements.
  - Device-bound performance caching binding benchmarks to specific SoC hardware architectures (`Build.HARDWARE`).
- **Foreground Service Job Tracking & Android 15 Support**:
  - `VeilFrameProcessingService` with structured `ProcessingJob` tracking (`activeJob`), failure propagation, and API 35 `onTimeout()` lifecycle handling.

### v2.2.7 (Previous Release)
- **WhatsApp Status Bounded Resolution Architecture**:
  - Decoupled resolution scaling from forced 9:16 aspect ratio: HD (1280×720) and FHD (1920×1080) bounded limits.
  - Deterministic DAR preservation for Original aspect mode across all standard ratios without cropping or distortion.
  - On-demand aspect cropping for explicit target ratios and strict even integer dimensions invariant `(dimension / 2) * 2`.
- **Video Studio Constraints & State Invariants**:
  - Honest estimation: eliminated phantom calculations when empty, displaying "No video selected" and "Select a video to calculate".
  - Rigid mutual exclusivity: WhatsApp Status enforces MP4 + H.264 locking; GIF reverts to Auto (CRF) and disables/mutes audio.
  - Empty state tool gray-out: edit tools and audio chip group disabled with `alpha = 0.38f`.
- **Floating Action Dock State Machine**:
  - 4-stage reactive controller (`EMPTY` $\to$ `READY` $\to$ `PROCESSING` $\to$ `COMPLETED`).
  - Saved state indicator with 2.5s auto-revert timer and `onEditApplied()` state reset.
- **Image Studio Passport (600×600) Preset**:
  - Dedicated 1:1 aspect constraint and exact 600×600 px document export sizing for official compliance.
- **AMOLED Dark Theming & UI Modernization**:
  - Strict "AMOLED Dark" styling, Dynamic Color awareness notice, post-inflation theme application.
  - 24dp card corner radii and categorical reorganization (`MEDIA STUDIOS`, `PRIVACY CLEANERS`, `FORENSICS & AI`).
- **2025–2026 AI Super-Resolution Catalog**:
  - Expanded lineup: SAT-light 2×/4×, SAFMNv3 2×/4×, Real-SAFMN++ 4×, ESPAN 4×, PFT-light 4×, DRCT 4×, PlainUSR 4×.
  - Real-ESRGAN General 4× re-labeled to "Legacy Standard", with reference-only models filtered from device pickers.
- **Adaptive AI Inference Architecture (Zero QNN)**:
  - Benchmark-driven execution planning across NNAPI and CPU/XNNPACK backends.
  - Progressive worker concurrency and adaptive thermal step-downs.
  - Decoupled Java heap and native memory budgets with zero-worker edge-case rejection.
  - Complete elimination of Qualcomm QNN SDK code, headers, and dependencies.

### v2.2.6 (Previous Release)
- **AI Image Upscaler Subsystem**: 7th tool running on-device super-resolution with Point 7 models (Real-ESRGAN 2×/4×, Anime 4×, Lanczos, Bicubic, Nearest) and tiled Hermite feathering.
- **Accurate File Size Estimation**: Exact 24-bit DWORD scanline padding (`((24 * w + 31) / 32) * 4`), resolving BMP size over-estimation bugs, and calibrated multi-format models.
- **Video Studio & Compressor**: Added Slow, Medium, and Fast compression presets, video rotation, canvas flip, 4:3/3:4 aspect, and custom margin crop slider (0–40%).
- **Image Studio Scale > 100%**: Main workspace live preview zoom with full-resolution export memory safeguards.

### v2.2.5 (Previous Release)
- **Native GPL FFmpegKit**: Upgraded Android video engine to native GPL `FFmpegKit 8.1.7` bundling `libx264` and `libx265`, dropping Python/Chaquopy from Android.
- **Video Studio Colour Grading**: 15 cinematic profiles with 60fps hardware-accelerated live preview rendering.
- **Target File Size Quality Solver**: Automated iterative binary search optimizer under non-negotiable size ceilings.
- **Studio Modernization**: Reorganized output settings card, text watermark studio (12 fonts, 16 colors, 9-point anchoring), multi-container export (MP4, MOV, MKV, WebM, AVI, GIF), multi-codec suite, and audio channel remixing.

### v2.2.4 (Previous Release)
- **APK Signature Scheme V2/V3**: Exclusively uses V2/V3 block-level signing, disabling legacy V1 JAR signatures and preventing `META-INF/MANIFEST.MF` verification failures.
- **Strict Release Pipeline Integrity**: Build pipeline rejects unsigned APK fallbacks and validates release signatures against actual `minSdkVersion 26`.
- **Mobile Image Studio**: Comprehensive mobile image editing studio with smart compression, aspect-ratio cropping, custom scaling, EXIF scrubbing, artistic color grading, and lossless rotation.
- **Mobile Video Compressor**: Interactive video compression suite featuring visual timeline range trimming (with dual-thumb controls for arbitrary span selection), platform target presets, resolution scaling, and playback speed adjustment.
- **Target Compression Profiles**: Preconfigured compression targets for Web, Discord, Email, and WhatsApp with real-time target estimation.
- **Unified Cross-Platform Core Engine**: Complete parity across Android Chaquopy runtime, desktop PySide6, and terminal CLI.

### v2.2.3 (Previous Release)
- **Status Bar Window Inset Handling**: Zero toolbar clipping under system status bars, camera cutouts, and display notches.
- **Dedicated Release Signing Identity**: Configurable release keystore preventing package update conflicts on manual and in-app updates.
- **In-App Updater CDN Redirect Following**: Follows multi-hop AWS S3 CDN redirects and automatically resumes install upon unknown sources authorization.
- **Dynamic Container Format Matching**: Format chips dynamically match selected video (.mp4, .mkv, .webm) and image (.jpg, .png, .webp) extensions.
- **Batch Folder Processing**: Cleans folders with mixed media formats and packages results into organized ZIP archives.
- **Dynamic Privacy Impact Card**: Real-time privacy profile and sanitization preview before execution.
- **Empty vs. Mounted Target UI**: Clean empty state and mounted details with active Change/Clear controls (no disabled Clear buttons).
- **Collapsible Execution Monitor**: Clean telemetry progress metrics with expandable monospace console.
- **Context-Aware Action Dock**: Transitions through Select Target, Execute, Cancel, and Run Another.
- **Persistent Tool Preferences**: Remembers configured options per engine across app launches.

### v2.2.2 (Previous Release)
- **Mobile Usability & UX Refinements (v2.2.2)**:
  - **Adaptive Mobile HTML Reports**: CSS `@media` breakpoints, `.table-responsive` touch-scroll wrappers, compact directory tree padding, and mobile hash truncation.
  - **Real Target Path Reporting**: Scanned directory display path preserved end-to-end without internal Android sandbox cache leakage.
  - **High-Contrast State Affordances**: Dynamic `ColorStateList` with luminous checked chip highlights, high-contrast text, checkmark icons, and clear switch states.
  - **Zero-Clipping Action Buttons**: Responsive wrapping action controls and compact padding across all mobile device viewports.
  - **Dynamic GitHub Changelog Sync**: Automatic release notes sync from repository manifests and GitHub Releases with offline caching.
  - **System-Wide Light / Dark Mode**: Full Material 3 `DayNight` theme toggle with persistent preferences.
- **Mobile Architecture & In-App Updates (v2.2.1)**:
  - **App Shell & 4 Dedicated Tool Workflows**: Home launcher dashboard with isolated workflows for AI Bundle, Video Cleaner, Image Cleaner, and Folder Scanner.
  - **In-App Update Engine**: Monotonic integer `versionCode` comparison, streaming SHA-256 integrity verification, and package archive inspection.
  - **JobState Machine & Persistent Sessions**: 8-phase state machine with session preservation across Home navigation.
  - **True SAF Recursive Directory Traversal**: Recursive document streaming into local staging workspace.
- **Quad-Domain Architecture (Video, Image, Storage, AI Context)**:
  - **Video Sanitization Pipeline**: Multi-pass container atom stripping, SEI NAL removal, Bayer CFA PRNU dither, 2D DCT block perturbation, and acoustic ENF mains notch filtration.
  - **Image Privacy Compiler**: Multi-layer deterministic compilation pipeline (Layer A Container Sanitization, Layer B Representation Normalization, Layer C Isolated Semantic Redaction).
  - **Folder Analyzer & Duplicate Scanner**: High-performance selective directory analysis with zero unselected I/O overhead, SQLite indexing repository, parallel streaming cryptographic hashing, and 3-tier staged duplicate detection.
- **Independent 5-Contract QualityGate**:
  - Privacy Contract: Zero residual facial, plate, text, or QR/barcode detections across independent probes.
  - Geometry Contract: Exact preservation of spatial canvas dimensions ($\|Observed - Expected\|_\infty = 0$).
  - Fidelity Contract: Strict pixel preservation in non-redacted areas ($D_{TV} \le \text{budget}$, $\text{SSIM} \ge \text{target}$).
  - Integrity Contract: Strict alpha binary quantization ($\alpha \in \{0, 1\}$) with zero anti-aliasing edge leaks.
  - Completeness Contract: Complete structural bounding-box coverage across all requested regions.
- **Independent Red-Team Probe Suite**:
  - 7 Level-3 Fingerprint-Distinct probes (Face, License Plate, OCR Text, QR/Barcode, Container Residuals, Alpha Fringe, Palette Indexing).
- **3-Mode Segmented PySide6 GUI**:
  - Modern desktop interface with real-time `Video Sanitizer`, `Image Privacy`, and `Folder Analyzer` mode switchers, live animated progressive directory tree, pulsing telemetry progress bar, and clipboard context menus.
- **Full SHA-256 Cryptographic Integrity & Multi-Format Reporting**:
  - Full 64-character SHA-256 hash preservation across all reports with interactive one-click copying.
  - Interactive HTML dashboard reports with client-side live search, Markdown inventory tables, JSON, CSV, and formatted text.
  - Automated standard folder naming: `<scanned_folder_name>_scan_report.<ext>`.
- **Cryptographic Provenance**:
  - RFC 8785 Canonical JCS JSON manifests bound with Ed25519 digital signatures and SHA-256 bitstream digests.
  - Ephemeral and persistent signing modes with pinned public key fingerprints.
- **Unified CLI Suite**:
  - Complete CLI commands (`veilframe sanitize`, `veilframe image sanitize`, `veilframe image verify`, `veilframe image inspect`, `veilframe folder scan`, `veilframe folder dupes`, `veilframe folder stats`, `veilframe folder export`, `veilframe doctor`, `veilframe presets`).

---

### v2.1 UPCOMING: Hardware Acceleration & High-Throughput Batch Processing
- **Zero-Copy GPU Paths**: Direct GPU texture sharing for real-time video and image batch redaction.
- **Async Cloud Batch Dispatcher**: Multi-threaded worker queue for large-scale directory and cloud bucket batch sanitization.
- **Advanced Audio Neutralization**: Expanded harmonic notch filtering and acoustic watermark neutralization.

---

### v2.2 UPCOMING: Advanced Forensic Consensus & Distributed Provenance
- **Multi-Parser Consensus**: Cross-validation of container syntax using both `ffprobe`, `MediaInfo`, and native Rust parsers.
- **ExifTool Deep Forensic Audit**: Optional deep-inspection pass for proprietary vendor metadata blocks.
- **Hardware Security Module (HSM) Integration**: Direct PKCS#11 HSM support for enterprise cryptographic audit signing.
- **Audit Reproducibility CLI**: `veilframe audit-reproduce <audit_bundle_dir>` for 1-click deterministic re-verification.

---

## Desktop Evolution & Native Architecture Planning

### Cross-Platform Desktop UI Complete Redesign (Windows, macOS, Linux)
- **Complete Client Re-architecture in a Native/Systems Language**:
  - Planning an end-to-end rewrite of the desktop user interface and core runtime in a dedicated systems language (such as Rust / Swift / C++ / Flutter) to replace Python/PySide6.
  - **Native OS Integration**: Native titlebars, Mica/Fluent design on Windows 11, Liquid Glass/Cocoa integration on macOS, and native Wayland/GTK4 client decorations on Linux.
  - **Zero-Copy Native Media Pipelines**: Direct GPU-accelerated video decoding/encoding pipelines (DirectX 12 / VideoToolbox / VA-API) with zero intermediate memory copying.
  - **Minimal Distribution Footprint**: Self-contained, statically-linked native executables with near-instant startup time and ultra-low RAM footprint.
