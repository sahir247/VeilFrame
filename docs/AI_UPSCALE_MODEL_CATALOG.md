# VeilFrame AI Super-Resolution & Restoration Model Catalog

## Overview
VeilFrame separates image super-resolution into two complementary objectives:
1. **Fidelity**: Faithfully reconstruct and preserve actual ground-truth details present in the low-resolution source without generative hallucination.
2. **Perceptual Quality**: Synthesize realistic fine texture and sharp edges for degraded or compressed media.

This catalog establishes the official reference matrix for VeilFrame, mapping models across genres, quality targets, parameter budgets, ONNX runtime suitability, mobile feasibility tiers, and licensing requirements.

---

## 1. Mobile Feasibility Tiers

| Tier | Category | Deployment Target | Characteristics | Exemplars |
|---|---|---|---|---|
| **Tier A** | **Native Mobile / ONNX Ready** | On-device Android & iOS | Small footprint (<60 MB), efficient inference latency (<500ms per tile on NPU/GPU), verified ONNX conversion, FP16/INT8 support. | Real-ESRGAN x4plus, Real-ESRGAN Anime 6B, Real-ESRGAN x4v3, RealPLKSR 4×, SwinIR RealSR, Real-CUGAN, SPAN |
| **Tier B** | **Convertible / High-Performance Mobile** | High-end mobile / Tablet (Snapdragon 8 Gen 2+, Dimensity 9300+, Apple Silicon) | Medium footprint (60–250 MB), transformer or hybrid CNN-attention, requires tiled NPU execution and FP16 quantization. | HAT-S, HAT, DAT/DAT2, APISR |
| **Tier C** | **Desktop / Cloud Class** | Desktop workstation (GPU >= 8GB VRAM) / Cloud API | High parameter count (>500M to 1.4B+), diffusion/DiT architectures, iterative multi-step or heavy generative synthesis. | VOSR 2.0 (1.4B DiT), SUPIR, OSEDiff, SeeSR, DiffBIR |

---

## 2. Genre-Specific Model Matrix

| Genre | Model Name | Scale | Quality Focus | Parameters | Checkpoint Size (FP16/ORT) | ONNX Avail. | Android Tier | License | Primary Architectural Strength |
|---|---|---|---|---|---|---|---|---|---|
| **General Photos** | Real_HAT_GAN_SRx4_sharper | 4× | Perceptual sharpness | 20.8M | ~45 MB | Experimental | **Tier B** | Apache 2.0 | Hybrid window/channel self-attention with adversarial loss for maximum texture synthesis. |
| **General Photos** | Real-ESRGAN x4plus | 4× | Reliable restoration | 16.7M | 33.8 MB | **Official / Verified** | **Tier A** | BSD-3-Clause | 23 RRDB blocks; universal baseline for blind real-world degradation. |
| **Photo Fidelity** | SwinIR RealSR | 4× | High fidelity (low hallucination) | 11.8M | ~24 MB | **Verified** | **Tier A** | Apache 2.0 | Shifted-window transformer blocks; preserves ground-truth edge structures without inventing details. |
| **Photo Fidelity** | RealPLKSR | 4× | Fidelity + Efficiency | 7.2M | ~15 MB | **Verified** | **Tier A** | Apache 2.0 | Partial Large Kernel CNN; low memory footprint with high PSNR/SSIM retention. |
| **Web / JPEG** | NomosWebPhoto DAT2 | 4× | Compression artifact removal | 14.5M | ~30 MB | **Verified** | **Tier A** | MIT | Dual-Aggregation Transformer trained explicitly on real web compression and re-encoding artifacts. |
| **Web / JPEG** | SwinIR JPEG / RealSR | 4× | Deblocking & detail recovery | 11.8M | ~24 MB | **Verified** | **Tier A** | Apache 2.0 | Explicit deblocking priors for 4:2:0 chroma subsampling and DCT quantization noise. |
| **Anime / Manga** | APISR | 4× | Hand-drawn line sharpness | 10.2M | ~21 MB | Community | **Tier B** | Apache 2.0 | CVPR 2024 anime restoration model; restores thin lines and eliminates unwanted color fringing. |
| **Anime / Manga** | Real-CUGAN | 2×/3×/4× | Anti-aliasing + Color clarity | 4.8M | 5–10 MB | **Verified** | **Tier A** | MIT | Extremely lightweight; supports adjustable denoise strengths (-1 to 3) for clean anime frames. |
| **Anime / Manga** | Real-ESRGAN Anime 6B | 4× | Illustration lines & flats | 4.2M | 8.8 MB | **Official / Verified** | **Tier A** | BSD-3-Clause | 6 RRDB blocks; fast mobile execution without blocking artifacts. |
| **Portraits / Faces** | CodeFormer | 1× | Discrete codebook face prior | 13.5M | ~28 MB | **Verified** | **Tier A** | S-Lab (Academic) | Controllable fidelity slider (w=0.0 to 1.0) balancing perceptual beauty vs. identity preservation. |
| **Portraits / Faces** | GFPGAN | 1× | Facial structure restoration | 14.0M | ~29 MB | **Verified** | **Tier A** | Apache 2.0 | Facial component loss (eyes, mouth) with generative facial prior. |
| **Portraits / Faces** | RestoreFormer++ | 1× | Multi-scale face attention | 18.2M | ~38 MB | Community | **Tier B** | MIT | Fully-spatial vision transformer for blind face restoration in the wild. |
| **Lightweight / Mobile** | SPAN / SPAN-F | 4× | Maximum throughput | 3.5M | ~7 MB | **Verified** | **Tier A** | MIT | NTIRE 2024/2025 Challenge winner; ultra-fast hardware-friendly depthwise convolutions. |
| **Lightweight / Mobile** | Real-ESRGAN x4v3 | 4× | Low RAM footprint | 4.4M | ~9 MB | **Official / Verified** | **Tier A** | BSD-3-Clause | Compact generator for low-end mobile devices and continuous video frame upscaling. |
| **Architecture / CGI** | DAT / DAT2 | 4× | Structural geometry | 14.5M | ~30 MB | **Verified** | **Tier B** | MIT | Dual-Aggregation Transformer; excels at parallel lines, grids, brickwork, and thin railings. |
| **Severe / Archival** | VOSR 2.0 | 4× | Extreme restoration | 1.4B | ~2.8 GB | PyTorch Only | **Tier C** | OpenRAIL | One-step Diffusion Transformer (DiT); state-of-the-art structural detail generation. |
| **Severe / Archival** | SUPIR | 4× | Photorealistic generation | 1.2B | ~2.4 GB | PyTorch Only | **Tier C** | Non-Commercial | Text-guided diffusion upscaler; invents plausible sub-pixel microtextures. |

---

## 3. Recommended Two-Stage Portrait Pipeline Architecture

Blind portrait enhancement cannot be solved effectively by applying a single global 4× super-resolution model. Applying Real-ESRGAN to entire images often distorts eyes, teeth, and skin pores, while specialized face restorers blur non-face clothing and backgrounds.

VeilFrame implements a dedicated **Composite Portrait Architecture**:

```text
                     Input Portrait
                           │
                           ▼
                 Face Detection (YOLO / MediaPipe)
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
     Cropped Face ROI            Background & Body
             │                           │
             ▼                           ▼
     Neural Face Prior           Background Upscale
    (CodeFormer / GFPGAN)       (RealPLKSR / Real-ESRGAN)
             │                           │
             └─────────────┬─────────────┘
                           ▼
             Alpha Blend & Poisson Seamless Clone
                           │
                           ▼
                  High-Fidelity Output
```

### Steps:
1. **Face Localization**: Extract facial regions of interest (ROI) with 10% bounding padding.
2. **Neural Face Prior**: Run CodeFormer or GFPGAN on normalized 512×512 face chips.
3. **Background Upscale**: Scale the non-face background with RealPLKSR or Real-ESRGAN x4plus.
4. **Seamless Compositing**: Soft feathering and Poisson seamless cloning integrate the restored face back into the upscaled canvas without boundary artifacts.

---

## 4. Hardware Acceleration Guidelines

### Qualcomm Snapdragon (HTP / GPU via Standalone QNN Plugin EP)
- Snapdragon 8 Gen 2, 8 Gen 3, and 8 Gen Elite processors feature the Hexagon Tensor Processor (HTP).
- **Execution Provider**: Standalone QNN Plugin EP (`libonnxruntime_providers_qnn.so` + backend libraries `libQnnHtp.so` and `libQnnGpu.so`).
- **Precision**: FP16 / QDQ quantized on HTP; FP32 / FP16 on QNN GPU.
- **Tile Sizing**: Constrain input tiles to **256×256** or **384×384** to remain inside HTP on-chip TCM (Tightly Coupled Memory).

### Universal Android Hardware (NNAPI)
- **Execution Provider**: `NNAPI Execution Provider` (`sessionOptions.addNnapi()`).
- Delegates automatically to Vulkan GPU, MediaTek APU, Samsung NPU, or Google Tensor TPU.
- Requires tile dimension capping (≤512×512) to avoid driver fallback to slow CPU emulation.

### CPU / XNNPACK Fallback
- Constrain intra-op thread pool based on available CPU cores (`setIntraOpNumThreads`).
- Never saturate all CPU cores simultaneously on mobile, preserving responsive Android UI scheduling and avoiding device thermal throttling.

---

## 5. Summary & Recommendation for VeilFrame UI

In the VeilFrame user interface:
- **Real-ESRGAN General 4×**: Labeled as `"Reliable general-purpose photo restoration"`.
- Non-AI deterministic filters (**Lanczos3**, **Bicubic**, **Nearest Neighbor**) remain standard options for pure cryptographic/pixel verification where zero neural hallucination is mandatory.
