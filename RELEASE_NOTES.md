# VeilFrame Release Notes

## v2.2.5 — September 2026

VeilFrame v2.2.5 delivers extensive image and video studio advancements across mobile and desktop, including an iterative target file size optimizer, text watermark studio with 9-point spatial anchoring, canvas flips and alpha background fills, multi-container video export (MP4, MOV, MKV, WebM, AVI, GIF), multi-codec encoding (H.264, H.265, VP9, AV1), comprehensive audio gain/channel remixing, and desktop interface modernization.

- **100% Native Android Kotlin Architecture:** Completely removed Python 3.11, Chaquopy, NumPy, Pillow, and Cryptography from the Android mobile app. VeilFrame Mobile now runs on a lean, high-performance native Kotlin architecture using AndroidX Media3 (ExoPlayer), FFmpegKit (`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`), AndroidX ExifInterface, and direct zero-copy Storage Access Framework (SAF) streaming, dropping APK size to ~79 MB.
- **In-App Update Crash Fix & Hardening:** Resolved an instant UI crash on the "Download & Install" button caused by view hierarchy parenting, corrected staging APK file extension handling for Android's package parser, and added explicit `<queries>` intent declarations for Android 11+ package installers.
- **Automated Multi-Platform Release Publishing:** Enhanced the release CI pipeline to trigger on GitHub `release` events and support parameter-driven `workflow_dispatch` runs, automatically attaching cross-platform standalone binaries (Windows EXE, Linux DEB/TAR, macOS DMG/TAR, Python Wheel, Android APK) and verified SHA256 checksums to public GitHub Releases.
- **Intentional Native Android FFmpegKit Backend:** Designates native `FFmpegKit` as the direct, primary video engine on Android, eliminating all subprocess and IPC overhead.
- **AndroidX Media3 ExoPlayer Video Studio:** Replaces legacy `MediaPlayer` with modern ExoPlayer bound to `TextureView`, delivering frame-accurate timeline seeking, visual loop trimming, and playback rate adjustments.
- **`copy` Codec Stream Invariant Rule:** Enforces strict stream-copy rules where spatial and temporal video transforms (flipping, rotation, scaling, aspect framing, speed multiplier, custom FPS) automatically promote `copy` to `libx264` with clear UI notices, preventing FFmpeg fatal filtergraph exits.
- **Dedicated GIF Animation Engine:** Treats GIF as an independent animation output mode (`VideoOutputMode.GIF`), automatically applying high-quality two-pass palette generation (`palettegen` + `paletteuse`), frame rate clamping, and audio stream stripping (`-an`).
- **Target File Size Quality Optimization Contract:** Maximizes visual quality subject to the hard upper-bound contract $\mathrm{actual\_size} \le \mathrm{target\_size}$ without unnecessary over-compression. For PNG, evaluates compression levels 1–9 with optimization, providing clear feedback if the target ceiling is unachievable without lossy conversion.
- **Bounded `atempo` Audio Speed Chaining:** Introduces a shared multi-stage `build_atempo_chain` algorithm supporting speeds from $0.25\times$ to $4.0\times$ by chaining filter passes strictly bounded within FFmpeg's native $[0.5, 2.0]$ window.
- **Text Watermark & Overlay Studio:** Adds custom text watermark overlays with instant preset tags (Confidential, Sample, Draft, Copyright), 9-point spatial anchoring (Top-Left through Bottom-Right), interactive font size slider, color palette picker (White, Black, Red, Yellow, Blue, Green, Gray), and high-contrast drop-shadow protection.
- **Canvas Flip & Alpha Background Fills:** Introduces lossless horizontal and vertical canvas flip transforms alongside transparent alpha background replacement (White, Black, Transparent) for transparent PNG and WebP assets, with solid background defaults for JPEG.
- **Multi-Container Video Pipeline:** Native and cross-platform export support for MP4, MOV, MKV, WebM, AVI, and animated GIF container formats with format-codec compatibility enforcement (e.g. Opus for WebM).
- **Multi-Codec Encoding Suite:** Adds support for H.264 (`libx264`), H.265 (`libx265`), VP9 (`libvpx-vp9`), AV1 (`libsvtav1`), and lossless stream copying (`copy`).
- **Audio Studio Processing:** Comprehensive audio adjustment including volume scaling from 0% (mute) to 200% gain, channel remixing (Keep original, Stereo mix, Mono downmix), and codec encoding across AAC, MP3, Opus, and FLAC (lossless without bitrate constraints).
- **Desktop GUI Modernization:** Revamped dark-mode studio interface (`#0f172a`), drag-and-drop file ingestion drop-zone, comparative before/after inspection card with instant "Reveal in File Explorer" and "Play / View Media" system integrations.
- **Android UI Hardening:** Enforces strict single-line button constraints (`android:maxLines="1"`, `android:singleLine="true"`, `android:ellipsize="end"`) across all mobile layouts, eliminating text clipping or awkward wrapping across varying display densities.
- **High-Fidelity Comparative Preview:** Real-time before vs. after comparison cards with dynamic file size badges and percentage savings calculations across mobile and desktop, strictly following an immutable pristine-source preview architecture.
- **Cryptographic In-App Update Hardening:** Mandatory zero-bypass SHA-256 verification (via `update.json` or `SHA256SUMS.txt`), HTTPS-only origin pinning (`github.com`, `raw.githubusercontent.com`, GitHub asset hosts, and configured S3 CDN origins), atomic staging downloads, package identity checks, and release-signing certificate verification before installation.
- **Synchronized Multi-Platform Parity:** Full algorithmic parity between the desktop core engine, terminal CLI (`veilframe compress`), and Android native FFmpegKit and media pipelines.

### Upgrade notes

- Existing Android installations upgrade cleanly via the in-app updater verifying against `update.json` version `2.2.5` (`versionCode = 225`) with mandatory SHA-256 and signing certificate validation.
- Image target size compression automatically maximizes quality under the target ceiling in $\le 7$ binary search passes.
- Android video processing directly invokes native FFmpegKit without Python subprocess reliance.
- Video exports to non-MP4 formats (MOV, MKV, WebM, AVI, GIF) in Android utilize direct SAF tree document creation for seamless gallery and file manager access.

### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.5-py3-none-any.whl`

For Android sideloading:

```bash
adb install -r VeilFrame-android-arm64.apk
```

For Python installation:

```bash
pip install veilframe-2.2.5-py3-none-any.whl
```

### Verification

Verify downloaded artifacts against the SHA-256 checksums published with the release. Android also validates the downloaded APK checksum and package identity before installation.

### Compatibility

- Windows x64
- Linux Debian/Ubuntu and portable distributions
- macOS Apple Silicon
- Android API 26+, target API 35
- Python 3.10+

## Previous releases

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
