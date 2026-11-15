# VeilFrame Release Notes

## v2.3.1 — November 2026

**VeilNet-Nano On-Device Neural Sensor Purification, Lightweight On-Demand Model Delivery, Real-Time Forensic Verification, and Enhanced Stability.**

---

### <img src="docs/images/icons/overview.svg" width="18" height="18" alt="" /> Overview

VeilFrame **v2.3.1** introduces an advanced on-device neural sensor fingerprint purification engine (**VeilNet-Nano**), designed to protect user privacy against physical sensor identification and deep camera attribution models without compromising image quality. To deliver these powerful features while keeping installation sizes minimal, advanced frame synthesis and matting models are delivered on demand via an in-app Model Manager with cryptographic integrity verification. This release also brings enhanced privacy scanner reliability, real-time export verification, comprehensive memory optimizations, and full GNU General Public License v3.0 (GPL-3.0) compliance.

---

### <img src="docs/images/icons/quickstart.svg" width="18" height="18" alt="" /> Key Improvements in v2.3.1

#### 1. <img src="docs/images/icons/security.svg" width="16" height="16" alt="" /> VeilNet-Nano On-Device Neural Protection
- **Ultra-Compact Neural Architecture:** Integrated a lightweight 17 KB neural model directly into the app for real-time camera attribution and sensor fingerprint scrambling.
- **High-Fidelity Visual Preservation:** Applies subtle sub-pixel adjustments across wide receptive fields to eliminate identifiable sensor signatures while maintaining sharp visual details (PSNR > 39.5 dB).
- **Seam-Free High-Resolution Processing:** Native sliding-tile processing handles high-resolution captures (4K, 8K, 50MP+) seamlessly without boundary artifacts or memory exhaustion.

#### 2. <img src="docs/images/icons/image_studio.svg" width="16" height="16" alt="" /> Advanced Geometric & Color De-correlation
- **Isotropic Grid Remapping:** Implements uniform inward scaling and coarse-grid spatial shifts that neutralize localized sensor pattern matching across images.
- **Sub-Pixel Color Balance:** Subtle non-linear chroma and luminance adjustments prevent machine-learning classifiers from extracting demosaicing patterns.

#### 3. <img src="docs/images/icons/downloads.svg" width="16" height="16" alt="" /> Lightweight On-Demand Model Architecture
- **Minimal Base Download:** Heavy AI models (RIFE video frame interpolation and high-precision subject matting) are downloaded on demand when selected, keeping initial app downloads lean and fast.
- **Cryptographic Verification:** Every downloaded model is verified against pinned SHA-256 checksums before loading, protecting against corrupted or interrupted downloads.
- **Multi-Source Reliability:** Automatic fallback between high-speed CDN and repository mirrors ensures dependable model downloads worldwide.

#### 4. <img src="docs/images/icons/benchmarks.svg" width="16" height="16" alt="" /> Real-Time Export Verification
- **Automated Verification:** The sanitization pipeline measures residual sensor correlation directly on exported images, confirming effective privacy protection before sharing.
- **Transparent Status:** Clear, honest status indicators distinguish between active protection passes and fallback processing modes.

#### 5. <img src="docs/images/icons/privacy.svg" width="16" height="16" alt="" /> Enhanced Privacy & Redaction Engine
- **Accurate Object Categorization:** Refined vision models distinguish vehicles and handheld electronic devices accurately for privacy redaction.
- **Transparent Scanner Health:** Multimodal scan diagnostics clearly alert users if a background scanner engine encounters an issue, preventing false clean-scan assumptions.

#### 6. <img src="docs/images/icons/overview.svg" width="16" height="16" alt="" /> Performance & Reliability Hardening
- **Native Memory Optimization:** Comprehensive lifecycle management across native image buffers and computer vision matrices eliminates memory leaks during high-volume batch processing.
- **GPL-3.0 Compliance:** Standardized project licensing under the GNU General Public License v3.0, ensuring full legal clarity across all multimedia and forensic modules.

---

### <img src="docs/images/icons/downloads.svg" width="18" height="18" alt="" /> Downloads & Installation

Download production release packages from the [GitHub Releases page](https://github.com/Sahir247/VeilFrame/releases/tag/v2.3.1):

| Platform | Package / Asset | Architecture & Requirements |
|---|---|---|
| **Android** | `VeilFrame-android-arm64.apk` | ARM64-v8a (Android 8.0+ / API 26+) |
| **Windows** | `VeilFrame-windows-x86_64.exe` | x86_64 (Windows 10/11 64-bit) |
| **Linux (Debian/Ubuntu)** | `VeilFrame-linux-x86_64.deb` | x86_64 (glibc 2.31+) |
| **Linux (Portable)** | `VeilFrame-linux-x86_64.tar.gz` | x86_64 (Standalone executable) |
| **macOS (Installer)** | `VeilFrame-macos-arm64.dmg` | Apple Silicon (macOS 11.0+) |
| **macOS (Portable)** | `VeilFrame-macos-arm64.tar.gz` | Apple Silicon (macOS 11.0+) |
| **Python Package** | `veilframe-2.3.1-py3-none-any.whl` | Python 3.10+ Universal Wheel |

```bash
# Install Python package
pip install veilframe-2.3.1-py3-none-any.whl
```

### <img src="docs/images/icons/security.svg" width="18" height="18" alt="" /> Verification & Privacy
- **Cryptographic Provenance:** SHA-256 digests and Ed25519 signatures are published with every release package.
- **Zero Cloud Guarantee:** 100% offline, on-device processing with zero analytics, telemetry, or external network connections.

---

## Previous releases

### v2.3.0 — November 2026
**Multi-Modal Photo & Video AI PII Auto-Redaction, Motion Lab RIFE v4 AI 60 FPS Synthesis, ColorOS Lift & Shift Matting, QR Code Studio Overhaul, Material 3 Expressive Tonal Hierarchy.**  
Shipped complete ONNX elimination with native Tencent NCNN C++ migration across U^2-Net-P, UltraFace RFB-320, and RealSR; introduced Motion Lab RIFE v4 neural frame interpolation targeting smooth 60 FPS video with Vulkan GPU acceleration; integrated multi-modal AI PII auto-redaction (faces, plates, screens, text/Luhn credentials) for photos and tracked videos; added two-pass `libvidstab` EIS stabilization and multi-aspect auto-reframe; updated QR Code Studio with 12 visual styles and WeChatQRCode verification; and implemented Material 3 Expressive 4-tier surface containers and predictive back gesture motion.

### v2.2.9 — October 2026
**Material 3 Expressive UI v3.0, Motion Lab (Optical Flow 2×/4× FPS), Video Studio Instant Preview & YUV420p, QR Code Studio EF Parity, Document Scanner Adjust & Insets.**  
Introduced Material 3 Expressive UI foundation with spring physics dynamics (`VfSprings v3.1`), centralized `WorkspaceInsets` contract across all 14 workspace roots, sub-100ms instant ExoPlayer preview, universal YUV420p video encoding, EFQRCode 7.0.3 parity normalization with pure vector SVG export, real-time QuadStabilizer document scanning, and Phase B6 CV cancellation checkpoints.

### v2.2.8 — September 2026
**QR Code Studio (8th native tool), AMOLED Dark mode, WhatsApp 16 MiB ceiling & Passport 600x600 preset.**  
Added QR Code Studio with 11 visual styles and CameraX scanner; enforced 16 MiB video size ceiling for WhatsApp Status; added AMOLED Dark styling, Passport 600x600 px export preset, and vector UI assets.

### v2.2.7 — August 2026
**Initial release of the offline PrivacyVideoCleaner engine with Bayer CFA PRNU dither and 2D DCT shift.**  
Initial public release delivering offline-first video sanitization with Bayer CFA sensor fingerprint removal, 2D DCT frequency-domain disruption, acoustic ENF mains notch filtration, and 3-Tier QualityGate certification.
