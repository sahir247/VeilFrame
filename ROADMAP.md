# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v2.2.6 CURRENT (Production Release)
- **AI Image Upscaler Subsystem (7th Independent Tool)**:
  - On-Device Neural Super-Resolution: Runs isolated ONNX Runtime models with zero cloud uploads or telemetry.
  - Point 7 Model Architecture: Real-ESRGAN General 2× (33.8 MB), Real-ESRGAN General 4× (33.8 MB), Real-ESRGAN Anime 4× (9.1 MB), plus native mathematical sinc Lanczos (3-lobe), Bicubic spline, and Nearest neighbor filters.
  - Tiled Super-Resolution Processing: Subdivides images into memory-safe tiles with 32px overlap and cubic Hermite feathering to prevent seam artifacts and out-of-memory crashes on mobile GPUs/CPUs.
  - Dynamic Model Download Manager: In-app model management with resumable streaming, automatic HTTP redirect resolution, SHA-256 integrity verification, and zero initial APK bloat.
- **Accurate File Size Estimation Overhaul**:
  - BMP Exact Row Padding: Computes precise 24-bit aligned scanline widths (`((24 * w + 31) / 32) * 4`), resolving severe size over-estimation bugs.
  - Format-Specific Empirical Solvers: Calibrated entropy estimation across JPEG, WebP, PNG, TIFF, GIF, HEIF/AVIF, and video bitrates (CRF, codecs, speed presets, and audio channels).
- **Video Studio & Compressor Overhaul**:
  - Compression Speed Presets: Added Slow (Maximum Quality, Smallest Size), Medium (Balanced), and Fast (Rapid Processing) options replacing hardcoded ultrafast settings.
  - Video Rotation & Canvas Flip: 90° CW, 180°, 270° CW rotation, horizontal/vertical flipping, 4:3 and 3:4 aspect ratios, and custom margin crop slider (0–40%).
  - Trim-Bounded Playback Clamping: Player seek, scrub, and playback loop are strictly clamped within selected trim range.
- **Image Studio Scale > 100% Live Preview**:
  - Live preview immediately reflects zoom/scale slider values exceeding 100% on the main workspace and applies safe full-resolution export with GC fallback.

### v2.2.5 (Previous Release)
- **Target File Size Quality Solver**: Automated iterative binary search optimizer (5–95% quality range, $\le 7$ iterations) for target file size budgets (KB/MB) without quality degradation guesswork.
- **Mobile & Desktop Image Studio Enhancements**:
  - Text Watermark & Overlay Studio: Custom overlay text, presets, 9-point spatial anchoring, font size slider, color palette selection, and drop-shadow contrast protection.
  - Canvas Orientation & Alpha Fill: Lossless horizontal & vertical flipping, and alpha background replacement for transparent images (White, Black, Transparent).
  - High-Fidelity Comparative Inspection: Real-time side-by-side before/after comparison with live file size badges and percentage savings calculations.
- **Expanded Video Engine & Formats**:
  - Multi-Container Output Support: Export to MP4, MOV, MKV, WebM, AVI, and animated GIF.
  - Multi-Codec Video Encoding: Support for H.264 (`libx264`), H.265 (`libx265`), VP9 (`libvpx-vp9`), and AV1 (`libsvtav1`).
  - Audio Studio Processing: Audio volume gain scaling (0%–200%), channel remixing (Keep, Stereo, Mono), and codec encoding (AAC, MP3, Opus, FLAC, Mute).
  - Video Spatial & Temporal Transforms: Horizontal & vertical flip filters, 90°/180°/270° rotation, customizable framerates (15, 24, 30, 60 fps), and playback speed multiplier chaining (0.25x–4.0x).
- **Desktop GUI Modernization**: Modernized dark-mode cards (`#0f172a`), drag-and-drop file ingestion drop-zone, and comparative inspection cards with one-click folder reveal and system media player launching.
- **Android UI Hardening**: Strict single-line button constraints across all layouts, eliminating label clipping or wrapping across diverse device screen densities.

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
