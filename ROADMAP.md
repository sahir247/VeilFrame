# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v2.2.3 CURRENT (Production Release)
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
