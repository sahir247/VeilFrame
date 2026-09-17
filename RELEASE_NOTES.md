# VeilFrame Release Notes

## v2.2.4 — September 2026

VeilFrame v2.2.4 introduces dedicated Mobile Image & Video Studios, visual timeline range trimming, platform-targeted compression presets, and strict APK Signature Scheme V2/V3 enforcement.

### Highlights

- **Pure APK Signature Scheme V2/V3:** Release builds disable legacy V1 JAR signatures and sign exclusively with APK Signature Scheme V2/V3, resolving `META-INF/MANIFEST.MF` verification failures while maintaining full compatibility with Android 8.0+ (API 26+).
- **Release pipeline integrity:** Eliminates unsigned APK fallbacks in build scripts and adds automated `apksigner` cryptographic verification enforcing `--min-sdk-version 26` in CI.
- **Mobile Image Studio:** Introduces a comprehensive editing studio featuring smart quality-targeted compression, aspect-ratio cropping, dimensional scaling, complete EXIF/metadata scrubbing, artistic color grading filters, and lossless rotation.
- **Mobile Video Compressor & Visual Trimmer:** Adds interactive visual timeline range trimming with dual-thumb controls allowing user-defined cut points anywhere in the video (e.g. trimming 4–6s out of a 10s video), target platform presets (Discord 8MB/25MB/50MB, WhatsApp 16MB, Email 25MB, Web 10MB), resolution scaling, audio stripping/transcoding, and playback speed adjustment.
- **Target compression profiles:** Adds multi-pass bitrate calculation and dimension scaling profiles for web and social platforms with real-time target size estimation.
- **Cross-platform CLI & core parity:** Unified `veilframe-compress` engine across desktop GUI, terminal CLI, and embedded Android Chaquopy runtime with full batch processing support.
- **Zero deprecated identifiers:** Completely audited codebase and layouts removing legacy references and ensuring strict compliance with vector drawable standards.
- **Version consistency:** Synchronizes all platform packaging, update manifests, build metadata, lockfiles, and core engines at version `2.2.4`.

### Upgrade notes

- Existing Android installations upgrade seamlessly in place using the production release key with APK Signature Scheme V2/V3.
- In-app updates automatically check `update.json` for version `2.2.4` and verify package integrity before prompting for installation.
- Visual video trimming uses stream-copying for fast, lossless cutting or re-encodes when combined with bitrate and resolution scaling.

### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.4-py3-none-any.whl`

For Android sideloading:

```bash
adb install -r VeilFrame-android-arm64.apk
```

For Python installation:

```bash
pip install veilframe-2.2.4-py3-none-any.whl
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