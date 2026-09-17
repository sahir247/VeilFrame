# VeilFrame Release Notes

## VeilFrame v2.2.1

> **Release Date:** September 2026  
> **Target SDK / Platforms:** Windows (x64), Linux (Debian/Ubuntu & Portable), macOS (Apple Silicon), Android (API 35, ARM64 APK), Python 3.10+

VeilFrame v2.2.1 delivers an extensive mobile architecture and UX redesign, replacing the single 4-tab screen with a modular **App Shell** comprising a product-grade **Home Dashboard / Launcher** and **4 independent dedicated tool workflows** (AI Bundle, Video Cleaner, Image Cleaner, Folder Scanner), a **built-in in-app update system** via GitHub Releases, true SAF recursive directory traversal, a non-clipping Clear button affordance, and a minimal monochrome dark theme with desaturated matte characteristic status colors.

---

### 🌟 Top Features of v2.2.1

1. **Home Launcher & Dashboard Architecture**
   - **Product Hub**: Replaced the cramped multi-tab layout with an ergonomic Home Dashboard featuring a hero brand card, interactive tool launchers, core capabilities overview, and a community resource footer.
   - **4 Dedicated Tool Workflows**: AI Bundle, Video Cleaner, Image Cleaner, and Folder Scanner operate as standalone tools with dedicated back navigation (`← Back`), tailored headers, and scoped settings.

2. **Built-in GitHub Releases In-App Updates with Cryptographic Verification**
   - **Monotonic Version Code Comparison**: Uses Android's integer `versionCode` (`remoteVersionCode > installedVersionCode`) parsed from repository manifest `android/update.json`, eliminating raw tag string comparison anomalies.
   - **Cryptographic SHA-256 Validation**: Computes streaming SHA-256 digest on downloaded APK chunks, automatically aborting installation if checksums mismatch.
   - **Package Archive Inspection**: Validates package identity via `packageManager.getPackageArchiveInfo()` ensuring `packageName == "com.veilframe.app"` before prompting user install.
   - **Interactive Download Modal**: Live progress dialog showing downloaded MB / total MB (`20.7 MB / 28.4 MB (73%)`) with instant cancellation support.
   - **Non-blocking Startup**: Asynchronous background update discovery never stalls Home launch, gracefully failing to local-first operation if offline.

3. **JobState Machine & Universal 4-Step Tool Lifecycle**
   - **Formal JobState**: `IDLE`, `PREPARING`, `SCANNING`, `PROCESSING`, `FINALIZING`, `COMPLETE`, `FAILED`, `CANCELLED`.
   - **Standardized Workflow**: Target Selection → Configuration → Execution Telemetry → Dedicated Result Summary Card.
   - **Persistent Per-Tool State**: Navigating to Home and returning preserves each tool's mounted targets, options, and generated artifacts during the session.
   - **Task-Driven Folder Scanner Modes**: Dynamic configuration presets for `Quick Audit`, `Deep Forensic`, and `Duplicate Hunt`.

4. **True SAF Recursive Directory Traversal**
   - Resolved directory tree URI handling using `DocumentFile.fromTreeUri`.
   - Recursively traverses subdirectories, streams genuine file contents into local staging cache, and computes accurate file counts and cumulative byte sizes.

5. **Responsive Wrapping Controls & 2-Tier Sticky Action Dock**
   - Multi-row wrapping chip groups (`app:singleLine="false"`) eliminate horizontal clipping across phone screen widths.
   - 2-tier sticky action dock (Tier 1: full-width Primary execute button; Tier 2: 50/50 split Save Result and Share buttons) prevents text truncation or awkward wraps like `EXPO\nRT` or `SH\nAR\nE`.
   - Prominent, non-clipping `Clear` target affordance with distinct active red outline and trash vector icon.

6. **Minimal Monochrome Dark Palette & Matte Characteristic Status Colors**
   - Deep obsidian surfaces (`#101012`), matte surface cards (`#18181C`), input chips (`#222228`), and hairline strokes (`#2E2E36`).
   - Matte platinum focal points (`#E4E4E7`) paired with soft, desaturated characteristic status tones: sage green (`#6AA878`), warm ochre amber (`#C9944D`), terracotta red (`#BA5D60`), and slate steel (`#7E95AC`).

7. **Smart Base Defaults**
   - **Folder Audit**: Quick Audit default with SHA-256 hashing **OFF** by default (saving battery & CPU on large projects).
   - **Video Cleaner**: Audio stripping **OFF** by default (preserves audio unless explicitly muted).
   - **Image Cleaner**: Standard 95% quality default.
   - **AI Bundler**: Standard 64K token budget with Markdown format default.

---

## VeilFrame v2.2.0

> **Release Date:** September 2026  
> **Target SDK / Platforms:** Windows (x64), Linux (Debian/Ubuntu & Portable), macOS (Apple Silicon), Android (API 35, ARM64/Universal), Python 3.10+

VeilFrame v2.2.0 introduces native **Android packaging (APK & AAB)**, **modular language-family secret detection** across 14 ecosystems, an **AI Context Bundler** with the native `.aibundle` v1 protocol, an asynchronous **desktop GUI background worker** eliminating scan lag, and an automated multi-platform release pipeline.

---

### 🌟 Top Features of v2.2.0

1. **AI Project Intelligence & `.aibundle` v1 Protocol**
   - Packages entire codebases into token-bounded context for LLMs (Claude, GPT-4o, Gemini).
   - **Zero-Truncation Guarantee**: Selected source files are never sliced or chopped into fragments; files are either delivered 100% complete or cleanly deferred if exceeding budget.
   - **Multi-Format Export**: Native `.aibundle` v1, GitHub-flavored Markdown, self-contained interactive HTML, structured JSON, clean ZIP archives, and plain text.
   - **4th Primary GUI Tab**: Dedicated `AI Project Lister` with live token budget sliders (`32k` to `1M+`), interactive file tree with token consumption badges, and non-blocking background generation.

2. **Modular Language-Family Secret Detection**
   - 10 dedicated detectors (`python`, `javascript`, `go`, `rust`, `jvm`, `c_family`, `php`, `shell`, `config`, `generic`) replace crude global regexes.
   - In-situ secret masking (`[REDACTED]`) preserving surrounding code syntax, types, and structure.
   - Zero false positives on method chains, Windows paths, documentation URLs, and package lockfile hashes.
   - Automatic exclusion of credential files (`.env`, `*.pem`, `id_rsa`).

3. **Native Android Platform Support (API 35)**
   - Standalone ARM64 APK (`VeilFrame-android-arm64.apk`) for direct testing and sideloading.
   - Universal Android App Bundle (`VeilFrame-release.aab`) for Google Play Store distribution.
   - Powered by Chaquopy Python 3.11, mobile FFmpegKit, and least-privilege Scoped Storage (`READ_MEDIA_*`).

4. **Intelligent Codebase Summarization**
   - **Lockfile Summarizer**: Condenses multi-megabyte lockfiles (`uv.lock`, `package-lock.json`, `Cargo.lock`, `poetry.lock`) into concise package lists, stripping millions of tokens of hashes and download URLs.
   - **Database & Model Summaries**: Extracts SQLite table schemas and row counts; produces metadata summaries for ML models and binaries without dumping unreadable byte streams.
   - **Collapsed Directory Trees**: Collapses vendor folders (`node_modules/`, `.venv/`) into single-line summaries to conserve token budget.

---

### 📋 Changelog (v2.2.0)

#### 🤖 AI Context & Folder Engine
- Added `veilframe.folder.ai_bundle` containing priority knapsack allocation, context compression, and multi-format renderers.
- Implemented `TextReadResult` with multi-encoding fallback (UTF-8, UTF-8-SIG, UTF-16, CP1252, Latin-1) and non-silent binary data rejection.
- Implemented AST and brace-language structural signature outliners for oversized file summarization.
- Integrated PathSpec `.gitignore` compliance into directory traversal.

#### 🔒 Security & Privacy
- Added modular secret detectors under `veilframe.folder.security.detectors/`.
- Implemented Shannon entropy scoring with heuristic false-positive suppression.
- Added automatic exclusion rules for sensitive credentials and environment files.

#### 🖥️ Desktop GUI (PySide6)
- Added `AI Project Lister` as the 4th top-level mode in the segmented mode switcher.
- Added `BundleGenerationWorker` background threading to eliminate UI freezing during scans.
- Added live token consumption badges and interactive token budget slider.

#### 📱 Android & Packaging
- Added full Android project structure under `android/` with ViewBinding, ForegroundService, and MediaBackend.
- Updated `build.sh` with `android-apk` and `android-aab` build targets.
- Fixed GitHub Actions Android SDK setup action using explicit `platforms;android-35` and `build-tools;35.0.0`.
- Integrated automated GitHub Actions release workflow building Windows, macOS, Linux, Android, and Python packages.

---

### 💻 Platform-Specific Installation & Usage

#### Windows (x86_64)
- **Download**: `VeilFrame-windows-x86_64.exe`
- **Usage**:
  - Double-click `VeilFrame-windows-x86_64.exe` to launch the Desktop GUI.
  - Or run from Command Prompt / PowerShell:
    ```powershell
    .\VeilFrame-windows-x86_64.exe gui
    .\VeilFrame-windows-x86_64.exe folder ai .\my_project -t 128000 -o bundle.aibundle
    ```

#### Linux (Debian / Ubuntu & Portable)
- **Debian / Ubuntu Package**:
  ```bash
  sudo dpkg -i VeilFrame-linux-x86_64.deb
  sudo apt-get install -f  # resolve any missing system dependencies
  veilframe gui            # launch GUI from terminal or application launcher
  ```
- **Portable Tarball**:
  ```bash
  tar -xzf VeilFrame-linux-x86_64.tar.gz
  ./VeilFrame gui
  ```

#### macOS (Apple Silicon ARM64)
- **Installer (DMG)**:
  - Open `VeilFrame-macos-arm64.dmg` and drag `VeilFrame.app` to `/Applications`.
- **Portable Tarball**:
  ```bash
  tar -xzf VeilFrame-macos-arm64.tar.gz
  open VeilFrame.app
  ```

#### Android (API 26+, Target API 35)
- **Testing & Sideloading (APK)**:
  ```bash
  adb install -r VeilFrame-android-arm64.apk
  ```
- **Google Play Distribution (AAB)**:
  - Upload `VeilFrame-release.aab` directly to the Google Play Console Release Track.

#### Python Package (Universal Wheel)
- **Installation via pip**:
  ```bash
  pip install veilframe-2.2.0-py3-none-any.whl
  # or from source:
  pip install veilframe
  ```
- **CLI Commands**:
  ```bash
  veilframe sanitize input.mp4 -o output.mp4
  veilframe image sanitize photo.jpg -o clean.png
  veilframe folder ai ./my_project -t 128000 -o context.aibundle
  veilframe gui
  ```

---

## 📜 Previous Releases

<details>
<summary><b>VeilFrame v2.0.2</b> — High-Performance Folder Analyzer, Staged Duplicate Finder & SHA-256 Reporting</summary>

- High-performance selective directory analysis via single-pass `os.scandir()`.
- 3-stage duplicate detection (size partitioning, 16 KiB head/tail fingerprinting, parallel streaming full SHA-256).
- SQLite directory caching and full 64-character SHA-256 reporting across interactive HTML, Markdown, CSV, and JSON.
- 3-mode segmented PySide6 desktop interface (Video Sanitizer, Image Privacy, Folder Analyzer).

</details>

<details>
<summary><b>VeilFrame v2.0.1 & v2.0.0</b> — Bounded Forensic Disruption & Cryptographic Provenance</summary>

- Initial release of the 5-contract QualityGate and 3-tier visual fidelity gate.
- Video domain: Bayer CFA PRNU dithering, 2D DCT median shift, and acoustic ENF notch filtration within 5% and 10% policy ceilings.
- Image domain: Container stripping, representation normalization, and solid redactions with 7 independent red-team probes.
- RFC 8785 Canonical JSON manifests signed with Ed25519 asymmetric keys.

</details>
