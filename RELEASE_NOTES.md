# VeilFrame Release Notes

## v2.3.1 — November 2026

**VeilNet-Nano On-Device Neural PRNU Purification, Fused Coarse-Grid Isotropic Remap, Zero-Bundle On-Demand Model Policy (RIFE & Lift-and-Shift Hosted on GitHub), Bundled In-App Forensic Validation Report, and Complete Native Memory Leak Audit.**

---

### <img src="docs/images/icons/overview.svg" width="18" height="18" alt="" /> Executive Highlights

VeilFrame **v2.3.1** introduces our groundbreaking on-device neural sensor fingerprint purification pipeline (**VeilNet-Nano**), neutralizing both classical PRNU photo-response non-uniformity cross-correlation (|sPCE| < 5.0, 6-strip block sPCE < 8.0) and deep neural camera attribution classifiers (ResNet50 / MISLnet). To keep the APK package ultra-lean, large neural weights (RIFE optical flow v4 series and ColorOS Lift & Shift) adopt a strict **zero-bundle on-demand policy**, hosted on GitHub and fetched dynamically via the in-app Model Manager. The release also bundles the comprehensive Dresden dataset forensic audit report, incorporates full native memory leak hardening across all OpenCV Mats and Bitmaps, updates ADR-0007, and enforces a strict zero-emoji documentation standard using clean SVG icons.

---

### <img src="docs/images/icons/quickstart.svg" width="18" height="18" alt="" /> What's New in v2.3.1

#### 1. <img src="docs/images/icons/security.svg" width="16" height="16" alt="" /> VeilNet-Nano NCNN On-Device Neural Purifier (Bundled 17.62 KB)
- **Ultra-Compact Neural Architecture:** Deploys a customized, under-parameterized 8,643-parameter FP16 NCNN neural model (`veilnet_nano.ncnn.bin` at only 17.62 KB and `.param` at 4.30 KB) bundled directly inside the APK assets.
- **Dilation-4 Wide-Band De-correlation:** Operates across 15x15 receptive fields to scramble mid-frequency sensor manifolds that survive downstream ISP compression, while mathematically prohibiting identity-collapse or pixel memorization.
- **Seam-Free Halo Sliding Tiling:** Native C++ JNI implementation (`veilnet_jni.cpp`) processes arbitrarily high-resolution photos (4K, 8K, 50MP, 100MP) via 480px core tiles with 16px reflection-padded halos, eliminating boundary seams and blocking artifacts.

#### 2. <img src="docs/images/icons/image_studio.svg" width="16" height="16" alt="" /> Fused Coarse-Grid Isotropic Remap (Defeating 6-Strip Block PCE)
- **Uniform 0.8% Inward Zoom ($s_x = s_y = 0.992$):** Replaced legacy asymmetric cropping with exact 1:1 isotropic inward scaling, completely eliminating aspect ratio distortion while neutralizing localized block matching.
- **Crushed Block sPCE Leakage:** Validated against real Dresden Camera Database sensor profiles (e.g., Agfa DC-830i, Nikon Coolpix, Canon PowerShot), slashing 6-strip block maximum sPCE from an untreated **113.04** down to **4.08** (comfortably passing the forensic detection threshold of < 8.0).
- **Sub-Pixel Chroma De-correlation:** Smooths Cr/Cb chroma channels ($3\times 3, \sigma=0.65$) and applies a subtle luminance S-curve LUT (+/- 2 levels) to neutralize inter-channel demosaicing manifolds exploited by deep surrogate forensic juries.

#### 3. <img src="docs/images/icons/downloads.svg" width="16" height="16" alt="" /> Zero-Bundle On-Demand Model Policy (RIFE & Lift-and-Shift)
- **Lean APK Packaging:** Large neural models are strictly decoupled from bundled assets:
  - **Lift & Shift Matting (`image_matting_mobile.tflite` ~8.4 MB):** Hosted on GitHub and downloaded on-demand when the user selects Lift & Shift mode or triggers download in the Background Remover Model Manager.
  - **RIFE Neural Optical Flow (v4.15-lite, v4.22-lite, v4.25, v4.26 ~5–12 MB each):** Downloaded on-demand via the Motion Lab Model Selector dialog with real-time wavy progress indicators and multi-tier CDN/GitHub mirrors.
- **SHA-256 Pinned Integrity:** All on-demand model downloads are verified against cryptographic SHA-256 checksums before atomic staging into app storage.

#### 4. <img src="docs/images/icons/benchmarks.svg" width="16" height="16" alt="" /> Bundled In-App Forensic Validation Report
- **Asset Report Integration:** Bundled `VEILFRAME_FORENSIC_VALIDATION_REPORT.md` inside `assets/reports/` and `docs/reports/`, viewable directly in the built-in offline Markdown Viewer with KaTeX math and Mermaid diagrams.
- **Physical Device & Real Dataset Verification:** Features rigorous side-by-side PSNR (> 39.5 dB), SSIM (> 0.975), and Polimi PCE benchmarks proving absolute visual transparency with complete forensic immunity.

#### 5. <img src="docs/images/icons/overview.svg" width="16" height="16" alt="" /> Comprehensive Native Memory & Thread-Safety Audit
- **OpenCV Mat RAII Tracking:** All intermediate matrices in `PrnuSanitizerEngine`, `ImageStudioController`, and `VideoStudioController` are collected in scoped registries and safely released in `finally` blocks, preventing memory leaks during batch photo processing.
- **Bitmap Recycling Guards:** Explicit recycling guards ensure no duplicate bitmap allocation leaks occur during multi-stage preview rendering.
- **Native JNI Scope Hardening:** Hardened `BitmapPixelScope` and `GpuInstanceLease` RAII handlers across `veilnet_jni.cpp`, `matting_jni.cpp`, and `rife_jni.cpp` to prevent native buffer or GPU compute lock retention.

#### 6. <img src="docs/images/icons/security.svg" width="16" height="16" alt="" /> Trust Boundary & Supply-Chain Hardening
- **Cryptographic Model Verification:** All RIFE neural weights undergo streaming SHA-256 hash checks and strict file size bounding against pinned digests before being accepted into app storage.
- **Strict Host Whitelist:** Restricted download domains strictly to official GitHub and jsDelivr endpoints, eliminating broad subdomain wildcards.
- **PII Scan Assurance:** Introduced structured multimodal scan summaries (`PiiScanSummary`), ensuring initialization or detector runtime failures are explicitly surfaced rather than falsely reported as clean scans.
- **COCO Classification Precision:** Refined YOLOv8n mappings to accurately classify full vehicles and electronic devices, eliminating spurious license-plate or financial-card labels.
- **Real-Time PRNU Self-Audit:** main PRNU sanitization path runs in-memory self-audit calculations, reporting verified numerical sPCE and 6-strip metrics only upon empirical confirmation.
- **CI Android Release Gate:** Pinned `release-gate` in GitHub Actions to depend strictly on `android-upscaler-verify` alongside cross-platform unit test matrices.

---

### <img src="docs/images/icons/downloads.svg" width="18" height="18" alt="" /> Downloads & Installation

Download production release artifacts from the [GitHub Releases page](https://github.com/Sahir247/VeilFrame/releases/tag/v2.3.1):

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

### <img src="docs/images/icons/security.svg" width="18" height="18" alt="" /> Verification & Compatibility
- **Cryptographic Provenance:** SHA-256 digests and Ed25519 signatures are published with every release asset.
- **Zero Cloud Guarantee:** 100% offline, local-only processing with zero analytics, telemetry, or network leaks.

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
