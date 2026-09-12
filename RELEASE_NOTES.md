# VeilFrame v2.0.0 Release Notes

## Release Title
**VeilFrame v2.0.0 — Auditable Multimedia Privacy Compiler & Hardware-Accelerated Engine**

---

## Release Artifacts & Checksums

| Artifact | Type | File Size | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| `VeilFrame.exe` | Standalone Single-File Windows x64 Executable | 200.59 MB | `013646FE1B3BE9AC055752B7A77BF6304736F18C923A4D289B84C8614C4460A7` |
| `veilframe-2.0.0-py3-none-any.whl` | Universal Python Wheel Package | 244.14 KB | `F882DA71A4AFDABCA192C561C71CC341F04DD859790CBA10487651FA0110D5C7` |
| `veilframe-2.0.0.tar.gz` | Source Distribution Archive | 234.56 KB | `97E20845DD3406BE14B3EE6687C9864C67646B4928FA995A190C236C476C452F` |

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
# Expected Hash: 013646FE1B3BE9AC055752B7A77BF6304736F18C923A4D289B84C8614C4460A7
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
