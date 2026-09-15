# VeilFrame v2.0.2 Release Notes

## Major Release: High-Performance Folder Analyzer, Staged Duplicate Finder & Full SHA-256 Reporting

VeilFrame v2.0.2 introduces a comprehensive, high-throughput **Folder Analyzer and Duplicate Scanner Subsystem**, complete **64-character SHA-256 cryptographic integrity preservation**, dynamic multi-format reporting with **interactive HTML dashboards**, automated **folder-based report naming**, and a polished **3-mode segmented desktop interface**.

---

## Key Highlights in v2.0.2

### 1. High-Performance Selective Folder Analyzer
- **Selective Field Traversal**: Powered by single-pass `os.scandir()` traversal. The scanner guarantees that unchecked metadata fields (created, accessed, permissions, hash) incur **zero filesystem I/O overhead**.
- **Configurable Scan Presets**:
  - `Quick Scan`: File names, extensions, sizes, and modified timestamps.
  - `Full Metadata`: Complete attributes including creation time, access time, and POSIX/Windows permissions.
  - `Integrity Scan`: Full metadata plus end-to-end cryptographic hashing (SHA-256, SHA-1, or MD5).
  - `Duplicate Finder`: Staged candidate pruning and hash verification.
  - `Custom Configuration`: Granular checkboxes, max recursion depth limits, directory exclusions, and extension filters.
- **SQLite Indexing & Cache Repository**: Persistent or memory-backed indexing supporting instant multi-field keyword filtering, extension aggregations, and fast duplicate lookup queries.

### 2. Staged Duplicate Detection Engine (10–100x Speedup)
- **Staged 3-Tier Pipeline**:
  - **Stage 1 (Size Partitioning)**: Group files by exact byte size; singleton sizes are eliminated instantly with zero file reads.
  - **Stage 2 (Head/Tail Fingerprinting)**: For collision candidates, reads at most 16 KiB (8 KiB head + 8 KiB tail + file size) regardless of whether the file is 10 MB or 50 GB.
  - **Stage 3 (Full Cryptographic Hash)**: Only files with identical Stage 2 fingerprints are fully streamed through the parallel cryptographic hash engine to confirm byte-for-byte identity.
- **Parallel Streaming Hasher**: Bounded `ThreadPoolExecutor` with 1 MiB chunked streaming to prevent memory ballooning on massive media files.

### 3. Full 64-Character SHA-256 Integrity & Interactive Reports
- **Zero Truncation Policy**: Cryptographic hashes are stored and exported as full 64-hexadecimal-character strings across all reports (Markdown, HTML, TXT, CSV, JSON) and ASCII tree hierarchies.
- **Dynamic Scanned Files Inventory**: Markdown and HTML reports dynamically generate tables displaying every user-selected attribute (Name, Relative Path, Size, Extension, Modified, Created, Accessed, Permissions, Full SHA-256).
- **Interactive HTML Report**:
  - Built-in live search bar with real-time client-side table filtering.
  - Interactive collapsible directory hierarchy.
  - Clickable copyable hash badges (`<code class="copyable-hash">`) that instantly copy the full 64-character SHA-256 hash to the clipboard with animated toast notification feedback.
- **Standardized Folder Naming**: Automatically pre-fills `<scanned_folder_name>_scan_report.<ext>` (e.g. `PrivacyVideoCleaner_v1_source_scan_report.html`) while granting full flexibility to rename and select alternative formats.

### 4. Desktop GUI 3-Mode Segmented Switcher & UI Polish
- **Segmented Mode Switcher**: Seamlessly switch between `Video Sanitizer`, `Image Privacy`, and `Folder Analyzer` with exclusive state management.
- **Live Progressive Tree**: Animated directory tree populates in real-time as the filesystem traversal streams in the background.
- **Pulsing Progress & Telemetry**: Animated gradient progress bar displaying live items/sec scan throughput, item counters, and millisecond elapsed timers.
- **Clipboard Integration & Context Menus**:
  - Single-click / double-click on hash columns copies the complete uncut SHA-256 to the clipboard.
  - Right-click context menus provide fast actions: *Copy Full SHA-256 Hash*, *Copy Name*, *Copy Relative Path*, *Copy Absolute Path*, and *Open Containing Folder*.

### 5. Unified CLI Subcommands
- `veilframe folder scan <target>`: Scan directory with configurable profile, hash algorithms, depth, and exclusions.
- `veilframe folder dupes <target>`: Scan specifically for duplicate files with staged hashing and wasted space accounting.
- `veilframe folder stats <target>`: Compute quick size rollups, file type distributions, and summary metrics.
- `veilframe folder export <target> -o <report>`: Directly export scan results to HTML, Markdown, JSON, CSV, or TXT.

### 6. Cross-Platform Release Matrix & Standalone Packages
VeilFrame v2.0.2 now officially provides standalone native distributions for **Windows x64**, **Linux x64** (.deb & .tar.gz), and **macOS Apple Silicon ARM64** (.dmg & .tar.gz) alongside universal Python wheels:

| Distribution Artifact | Runner / OS | Architecture | Purpose / Description |
|---|---|---|---|
| `VeilFrame-windows-x86_64.exe` | `windows-latest` | x86_64 | Standalone single-file executable (GUI & CLI) |
| `VeilFrame-linux-x86_64.deb` | `ubuntu-22.04` | x86_64 | Native Debian/Ubuntu package with desktop menu entry & icons |
| `VeilFrame-linux-x86_64.tar.gz` | `ubuntu-22.04` | x86_64 | Standalone portable Linux archive (extract & run) |
| `VeilFrame-macos-arm64.dmg` | `macos-latest` | ARM64 | Native Apple Silicon disk image (Drag-to-Applications) |
| `VeilFrame-macos-arm64.tar.gz` | `macos-latest` | ARM64 | Standalone portable macOS archive (`VeilFrame.app` bundle) |
| `veilframe-2.0.2-py3-none-any.whl` | Universal | Any | Universal Python Wheel (`pip install`) |
| `veilframe-2.0.2.tar.gz` | Universal | Any | Source distribution package |
| `SHA256SUMS.txt` | Release Gate | — | Cryptographic SHA-256 integrity verification |

---

## Operating System Installation & Execution Guide

### 🪟 Windows (x86_64)
1. Download `VeilFrame-windows-x86_64.exe` and `SHA256SUMS.txt`.
2. Verify checksum:
   ```powershell
   Get-FileHash -Path .\VeilFrame-windows-x86_64.exe -Algorithm SHA256
   ```
3. Run GUI: Double-click `VeilFrame-windows-x86_64.exe` or execute `.\VeilFrame-windows-x86_64.exe gui`.
4. Run CLI: `.\VeilFrame-windows-x86_64.exe doctor --json` or `.\VeilFrame-windows-x86_64.exe folder scan <dir>`.

### 🐧 Linux (x86_64)

#### Option A: Native Debian/Ubuntu Package (`.deb`)
Recommended for Ubuntu, Debian, Linux Mint, Pop!_OS:
```bash
# 1. Download and install .deb
sudo dpkg -i VeilFrame-linux-x86_64.deb
sudo apt-get install -f  # resolves any missing runtime libraries

# 2. Launch from desktop application menu or terminal
veilframe gui      # or simply 'veilframe'
veilframe doctor   # CLI health probe
```

#### Option B: Portable Archive (`.tar.gz`)
Works on any Linux distribution (Ubuntu, Fedora, Arch, openSUSE):
```bash
tar -xzf VeilFrame-linux-x86_64.tar.gz
chmod +x VeilFrame
./VeilFrame gui
```

**System Graphics Runtime Libraries:**
VeilFrame bundles Python, PySide6, OpenCV, NumPy, Cryptography, and FFmpeg. On minimal or headless distributions, install standard graphics/GL runtime libraries:
```bash
# Debian / Ubuntu / Mint:
sudo apt-get update && sudo apt-get install -y ffmpeg libegl1 libgl1 libglx-mesa0 libxkbcommon-x11-0
# Fedora / RHEL:
sudo dnf install -y ffmpeg mesa-libGL mesa-libEGL libxkbcommon-x11
# Arch Linux:
sudo pacman -S ffmpeg mesa libxkbcommon
```

### 🍎 macOS (Apple Silicon ARM64)

#### Option A: Drag-to-Applications Disk Image (`.dmg`)
1. Download `VeilFrame-macos-arm64.dmg`.
2. Double-click to mount the disk image.
3. Drag **VeilFrame.app** into the **Applications** folder.
4. Launch VeilFrame from Launchpad, Spotlight, or Applications.

#### Option B: Portable App Archive (`.tar.gz`)
```bash
tar -xzf VeilFrame-macos-arm64.tar.gz
open VeilFrame.app
```

**macOS Gatekeeper Note:**
As an open-source community release, this build is not notarized with a paid Apple Developer ID. If macOS displays a Gatekeeper security warning on first launch:
- Right-click `VeilFrame.app` in Finder / Applications and select **Open**, OR
- Clear quarantine attribute via terminal:
  ```bash
  xattr -d com.apple.quarantine /Applications/VeilFrame.app
  ```

### 🐍 Universal Python Package (Any Platform)
Install directly into any Python 3.10+ virtual environment:
```bash
pip install veilframe-2.0.2-py3-none-any.whl
# or from source:
pip install -e .
```

### 🛠️ Local Multi-Target POSIX Build Script (`build.sh`)
Linux and macOS developers can build and package standalone binaries and native installers locally:
```bash
chmod +x build.sh
./build.sh clean     # Clean previous builds
./build.sh build     # Build standalone binary with PyInstaller
./build.sh test      # Run full test suite & GUI smoke test
./build.sh package   # Package .dmg (macOS) or .deb & .tar.gz (Linux)
./build.sh all       # Run clean, build, test, and package in one command
```

---

### 7. Full Test Suite & Quality Verification
- Added dedicated `tests/test_gui_smoke.py` verifying offscreen Qt platform initialization, PySide6 widgets, and mode switching.
- Added 31 unit tests covering folder scanning, staged hashing, duplicate pruning, and export generation.
- **247 total tests passing** across video, image, quality gate, folder, and GUI smoke suites.

---

# VeilFrame v2.0.1 Release Notes

## Production Engineering, Performance Hardening & Bug Fixes

VeilFrame v2.0.1 is a comprehensive production engineering release addressing performance bottlenecks, process window flashing, dependency verification reliability, UI state management, dead code elimination, and cryptographic provenance consistency across CLI, GUI, and standalone executable distributions.

---

## Key Highlights & Fixes in v2.0.1

### 1. Flashing Console Window Suppression & Subprocess Hardening
- **Zero Flashing Windows**: Passed `creationflags=subprocess.CREATE_NO_WINDOW` (`0x08000000`) across all Windows subprocess executions (hardware probes, FFmpeg transcoding, FFprobe analysis, sanitization, and preview generation).
- **Sub-Millisecond Hardware Discovery**: Implemented instant (<1ms) Win32 `EnumDisplayDevicesW` GPU query via `ctypes` and parallelized candidate encoder validation via `ThreadPoolExecutor`.

### 2. FFmpeg / FFprobe Live Verification & Download Resilience
- **Functional Execution Probes**: Replaced naive static file-size checks with live `-version` process execution tests (`timeout=5`, `CREATE_NO_WINDOW`) in `is_ffmpeg_installed()`, `is_ffprobe_installed()`, and `audit_environment()`.
- **Precedence & Shim Elimination**: Prioritized persistent user binaries (`~/.veilframe/bin/`) over PyInstaller bundle paths, enforced >5MB binary size filters in packaging to eliminate package manager shims, and unified version detection across Environment Doctor and the Main Window.
- **Multi-Mirror Download Fallback**: Added multi-mirror failover URLs (BtbN GitHub builds, gyan.dev release, and codexffmpeg release) with a 600s total wall-clock timeout and chunked progress reporting.
- **Dual Binary Extraction**: Automatically extracts and verifies both `ffmpeg.exe` and `ffprobe.exe` into persistent user storage (`~/.veilframe/bin/`).

### 3. Transform-Domain (DCT) Perturbation Vectorization (100–500x Speedup)
- **Vectorized Matrix Projections**: Replaced pure-Python nested loops in `hash_perturbation.py` with precomputed orthonormal Type-II DCT projection matrices ($D M D^T$ and $D^T X D$).
- **Zero Extra Dependencies**: Pure NumPy BLAS matrix multiplications preserving exact mathematical precision down to $10^{-15}$ machine epsilon while drastically improving execution throughput.

### 4. Core Pipeline Robustness & Tag Normalization
- **Fail-Fast Directory Creation**: Enforced `dst_path.parent.mkdir(parents=True, exist_ok=True)` at pipeline initialization to eliminate crashes on non-existent output paths.
- **Case-Insensitive Tag Filtering**: Normalized container tag keys to lowercase before whitelist validation, eliminating false-positive privacy leak reports on standard MP4 metadata tags (`creation_time`, `CREATION_TIME`, `major_brand`).
- **Epoch-0 Timestamp Normalization**: Expanded Epoch-0 matching to handle ISO timestamp variations (`1970-01-01T00:00:00.000000Z`, `1970-01-01T00:00:00Z`, `1970-01-01 00:00:00`).
- **Enhanced Stderr Error Diagnostics**: Expanded stderr tail capture to 6000 characters and added heuristic hints for missing video streams or unsupported codecs.
- **Safe Auto-Trim Bounds**: Guarded `calculate_trim()` against zero or invalid stream durations to prevent unintended fallbacks.

### 5. GUI & CLI Polish
- **Exclusive Mode Toggle**: Bound Video Sanitizer and Image Privacy Compiler toggle buttons to an exclusive `QButtonGroup` in `MainWindow`, preventing desynchronized UI states.
- **Pre-Flight Dependency Interception**: Guarded video loading and processing start with live `is_ffmpeg_installed()` checks, prompting an interactive installer dialog before execution.
- **Accurate Path Display**: Resolved platform paths in `DependencyInstallerDialog` via `get_user_bin_dir()` instead of Unix tilde shorthands on Windows.
- **Non-TTY Color Code Suppression**: Fixed `_supports_color()` to cleanly return `False` when stdout is piped or redirected to files, eliminating ANSI code pollution in log streams.
- **Dynamic Policy Thresholds**: Formatted text audit reports now dynamically reflect active `VisualBudgetPolicy` constraints.

### 6. Packaging, Antivirus Safety & Dead Code Cleanup
- **Console Handle Leak Fix**: Fixed Windows `CONOUT$` / `CONIN$` handle lifecycle in `run.py` with explicit tracking and `atexit` cleanup handlers.
- **Antivirus False-Positive Protection**: Disabled UPX compression (`upx=False`) in `VeilFrame.spec` to prevent heuristic false positives by antivirus scanners and CI build failures.
- **Dead Code Elimination**: Removed obsolete research and calibration scripts from `tools/` and removed broken `benchmark` command from the CLI.
- **Automated CI Release Pipeline**: Added `workflow_dispatch` trigger and `actions/upload-artifact@v4` workflow run packaging to `.github/workflows/ci.yml`.

---

## Architectural Capabilities

### 1. Hardware-Accelerated GPU Encoding with Deterministic Fallback
- **NVIDIA**: `h264_nvenc`, `hevc_nvenc`, `av1_nvenc`
- **Intel**: `h264_qsv`, `hevc_qsv`, `av1_qsv`
- **AMD**: `h264_amf`, `hevc_amf`, `av1_amf`
- **Apple Silicon / macOS**: `h264_videotoolbox`, `hevc_videotoolbox`
- **Software CPU Fallback**: Deterministic `libx264`, `libx265`, `libsvtav1`

### 2. Universal Multi-Format Conversion Support
- **Video Containers**: MP4 (`.mp4`), Matroska (`.mkv`), WebM (`.webm`), QuickTime (`.mov`), Audio Video Interleave (`.avi`), MPEG-TS (`.ts`)
- **Image Compilers**: JPEG (`.jpg`, `.jpeg`), PNG (`.png`), WebP (`.webp`), TIFF (`.tiff`, `.tif`), BMP (`.bmp`), GIF (`.gif`), ICO (`.ico`), PPM (`.ppm`)
- **Folder Reports**: Interactive HTML (`.html`), Markdown (`.md`), JSON (`.json`), CSV (`.csv`), Plain Text (`.txt`)

### 3. Full Cryptographic Verification & Auditability
- Multi-layer image privacy compilation with 7 adversarial red-team probes.
- RFC 8785 Canonical JSON output manifests with Ed25519 digital signatures.
- Full automated test suite verification (246/246 passing tests).

---

## Verification & Installation

### Running the Standalone Executable
Download `VeilFrame.exe` and `SHA256SUMS.txt` from the GitHub release assets and execute directly on Windows x64. No Python installation or external runtime dependencies are required.

To verify binary integrity:
```powershell
Get-FileHash -Path .\VeilFrame.exe -Algorithm SHA256
```
Compare the resulting hash with the corresponding entry in `SHA256SUMS.txt`.

### Running from Source
```bash
git clone https://github.com/sahir247/VeilFrame.git
cd VeilFrame
python -m venv .venv
.\.venv\Scripts\activate
pip install -e .
python run.py
```
