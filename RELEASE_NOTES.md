# VeilFrame v2.0.0 Release Notes

## Release Title
**VeilFrame v2.0.0 — Auditable Multimedia Privacy Compiler & Hardware-Accelerated Engine**

---

## Release Artifacts & Checksums

| Artifact | Type | File Size | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| `VeilFrame.exe` | Standalone Single-File Windows x64 Executable | 200.58 MB | `7362BDF696C56CF906EC39C1DFE757911C1CE59FE425AAF7038C23845CE3F7EE` |

---

## Key Highlights & Architectural Enhancements

### 1. Hardware-Accelerated GPU Encoding with Deterministic CPU Fallback
- Added hardware acceleration support across major GPU vendors:
  - **NVIDIA**: `h264_nvenc`, `hevc_nvenc`, `av1_nvenc`
  - **Intel**: `h264_qsv`, `hevc_qsv`, `av1_qsv`
  - **AMD**: `h264_amf`, `hevc_amf`, `av1_amf`
  - **Apple Silicon / macOS**: `h264_videotoolbox`, `hevc_videotoolbox`
  - **Software CPU Fallback**: Deterministic `libx264`, `libx265`, `libsvtav1`
- Includes runtime probe mechanism (`get_hardware_capabilities()`) running micro-encoding checks at `192x144` against the active FFmpeg binary to ensure codec and driver stability before processing.

### 2. Universal Multi-Format Conversion Support
- **Video Encoders & Containers**:
  - MP4 (`.mp4`), Matroska (`.mkv`), WebM (`.webm`), QuickTime (`.mov`), Audio Video Interleave (`.avi`), MPEG-TS (`.ts`)
- **Image Compilers & Formats**:
  - JPEG (`.jpg`, `.jpeg`), PNG (`.png`), WebP (`.webp`), TIFF (`.tiff`, `.tif`), BMP (`.bmp`), GIF (`.gif`), ICO (`.ico`), PPM (`.ppm`)
- Automatic container bitexact stream tagging, metadata scrubbing, and quarantine policy validation for converted assets.

### 3. EXIF & Forensic Metadata Inspector
- Real-time forensic container audit in `ImageInfoWidget`:
  - **Hardware Device**: Camera manufacturer, model, and sensor serial identifiers.
  - **Temporal Signatures**: DateTimeOriginal and digitized timestamps.
  - **Optical Calibration**: Lens specifications, focal length, and aperture.
  - **Software Fingerprints**: Processing software, firmware revisions, and editing tags.
  - **Geolocation Threat Warning**: Prominently highlights GPS latitude, longitude, and altitude coordinate tags requiring mandatory Layer A container purge.

### 4. Interactive Missing Dependency Doctor
- Automatic detection of missing binaries (FFmpeg, FFprobe, and auxiliary modules).
- Interactive on-demand installation modal prompting:
  - `"<Component> is missing. Do you want to install it automatically?"`
  - Enforces invariant button alignment: **`[Cancel]` on the left** and **`[OK]` on the right**.
  - Background asynchronous multi-threaded download and installation directly into `~/.veilframe/bin/`.

### 5. Tactile UI Experience & Visual Feedback
- Integrated `ButtonClickBlinkFilter` for responsive button micro-flash tactile feedback upon interaction.
- Enhanced dark-mode UI with subtle hover accents, pressed states, custom scrollbars, and high-DPI crisp SVG iconography.
- Zero-emoji codebase policy strictly maintained across all interfaces, logs, reports, and documentation.

### 6. Full Cryptographic Verification & Auditability
- Multi-layer image privacy compilation with 7 adversarial red-team probes (Face, License Plate, Text, QR Code, Thumbnail, Container, Metadata).
- RFC 8785 Canonical JSON output manifests with Ed25519 digital signatures.
- Continuous Integration and full test suite verification (216/216 passing automated tests).

---

## Verification & Installation

### Running the Standalone Executable
Download `VeilFrame.exe` and execute directly on Windows x64. No Python installation or external runtime dependencies are required.

To verify binary integrity:
```powershell
Get-FileHash -Path .\VeilFrame.exe -Algorithm SHA256
# Expected Hash: 7362BDF696C56CF906EC39C1DFE757911C1CE59FE425AAF7038C23845CE3F7EE
```

### Running from Source
```bash
git clone https://github.com/sahir247/VeilFrame.git
cd VeilFrame
python -m venv .venv
.\.venv\Scripts\activate
pip install -e .
python run.py
```
