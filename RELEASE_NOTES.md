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
- **Dead Code Elimination**: Removed obsolete research and calibration scripts from `tools/` (`benchmark_performance.py`, `run_attribution_benchmarks.py`, `chimera_segmenter.py`, `hue_controlled_inspector.py`, `download_calibration_corpus.py`) and removed broken `benchmark` command from the CLI.
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

### 3. Full Cryptographic Verification & Auditability
- Multi-layer image privacy compilation with 7 adversarial red-team probes (Face, License Plate, Text, QR Code, Thumbnail, Container, Metadata).
- RFC 8785 Canonical JSON output manifests with Ed25519 digital signatures.
- Full automated test suite verification (210/210 passing tests).

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
