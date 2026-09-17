# VeilFrame Release Notes

## v2.2.3 — September 2026

VeilFrame v2.2.3 is a stability, security, and usability release focused on reliable Android updates, clearer workflows, and format-preserving media sanitization.

### Highlights

- **Android UI reliability:** Applies edge-to-edge status-bar insets so headers, titles, and navigation controls do not clip under system UI.
- **Reliable in-app updates:** Follows multi-hop GitHub/CDN redirects, resumes installation after the user grants install permission, and grants package-installer URI access explicitly.
- **Safe release upgrades:** Android release builds use a dedicated signing configuration and CI-managed keystore secrets, preventing signature mismatches during updates.
- **Format-aware batch processing:** The new **Original / Auto** option detects source containers and preserves formats across mixed video and image batches. Batch output is packaged as a structured ZIP archive.
- **Refined workflows:** Adds a command-center launcher, three-step workflow presentation, clearer empty/mounted target states, context-aware actions, privacy-impact previews, a collapsible telemetry console, and persistent per-tool preferences.
- **Version consistency:** Synchronizes the Python package, embedded engine, build metadata, lockfile, and Android update manifest at version `2.2.3`.

### Upgrade notes

- Existing Android installations can update in place when the APK is signed with the same production release key.
- If Android blocks installation, enable **Install unknown apps** for the app performing the installation, then return to VeilFrame; the pending installation will resume.
- The **Original / Auto** format option is recommended when processing individual files or directories containing mixed formats.

### Downloads and installation

Download the appropriate artifact from the [GitHub Releases page](https://github.com/sahir247/VeilFrame/releases):

- Android: `VeilFrame-android-arm64.apk`
- Windows: `VeilFrame-windows-x86_64.exe`
- Linux: `VeilFrame-linux-x86_64.deb` or `VeilFrame-linux-x86_64.tar.gz`
- macOS Apple Silicon: `VeilFrame-macos-arm64.dmg` or `VeilFrame-macos-arm64.tar.gz`
- Python: `veilframe-2.2.3-py3-none-any.whl`

For Android sideloading:

```bash
adb install -r VeilFrame-android-arm64.apk
```

For Python installation:

```bash
pip install veilframe-2.2.3-py3-none-any.whl
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