# VeilFrame AI Super-Resolution & Restoration Model Catalog

## Overview
VeilFrame delivers 100% offline, on-device image super-resolution and mathematical reconstruction. The upscale subsystem is built exclusively upon **native Tencent NCNN C++ binaries (`realsr_jni.cpp`)** accelerated via Vulkan GPU compute with multi-threaded CPU fallback, guaranteeing zero cloud dependencies and zero ONNX Runtime dependencies in upscaling.

All neural models are deployed with pinned SHA-256 cryptographic digests, exact byte counts, approved origin enforcement, fail-closed HTTP 200 verification, and atomic rollback on download failures.

---

## 1. Verified Native NCNN Neural Models (Production Reference)

| Model Name | Model ID | Native Scale | Checkpoint Size | Parameter SHA-256 | Binary SHA-256 | Genre / Quality Focus | License |
|---|---|:---:|:---:|---|---|---|---|
| **Real-ESRGAN x4plus (NCNN)** | `ncnn-realesrgan-x4plus` | **4×** | 33.5 MB | `35330ececcea33b6c397a72548e788d5d53becee4734c50b7fada36e89f10a86` | `713ee713b0353afaa27976f0563a64a5043bd70b9bd8936c2e26e25ebcdbcddf` | General Photography & Universal Restoration | BSD-3-Clause |
| **AnimeVideoV3 2× (NCNN)** | `ncnn-realesr-animevideov3-x2` | **2×** | 1.25 MB | `b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04` | `548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d` | Compact Anime & Animation Upscaling | BSD-3-Clause |
| **AnimeVideoV3 3× (NCNN)** | `ncnn-realesr-animevideov3-x3` | **3×** | 1.25 MB | `d1a5755008791d09b57e3425fc9dd0bd26b00fdf79c606210bc0e693f8230881` | `548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d` | Compact Anime & Digital Illustration 3× | BSD-3-Clause |
| **AnimeVideoV3 4× (NCNN)** | `ncnn-realesr-animevideov3-x4` | **4×** | 1.25 MB | `850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e` | `548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d` | Compact Anime & Line Art 4× | BSD-3-Clause |
| **RealSR DF2K 4× (NCNN)** | `ncnn-realsr-df2k-x4` | **4×** | 33.5 MB | `a7f31e75518ad279c179202bf77da9eca3141124a67e5261d97235849e6cc323` | `a3f5eb53906ef54d5c5d125b2c9dd5f570d9dbb779e3d934df14b5375fd923c1` | DIV2K / Flickr2K Natural Photo Fidelity | BSD-3-Clause |
| **RealSR DF2K JPEG 4× (NCNN)** | `ncnn-realsr-df2k-jpeg-x4` | **4×** | 33.5 MB | `a7f31e75518ad279c179202bf77da9eca3141124a67e5261d97235849e6cc323` | `e862e88c3f346a3b8506d839633f5e2a16461bb8bfb3e211152f40d807a4f51c` | JPEG De-artifacting & Compressed Photos | BSD-3-Clause |
| **Real-ESRGAN HFA2k 4× (NCNN)** | `ncnn-realesrgan-hfa2k-x4` | **4×** | 33.5 MB | `766758eacc2bc8e81b196f8bb777771452ac9666f77198957cff533188366384` | `45f90b75c6044fb00a83b046b782a7b3fa4aee1f174d8993cbf1dbb8f04e511a` | High-Frequency Portrait & Facial Texture | BSD-3-Clause |

---

## 2. Built-in Deterministic Mathematical Resamplers (Zero Download)

For workflows where mathematical transparency and zero generative hallucination are mandatory (forensic analysis, legal evidence, pixel art), VeilFrame provides built-in algorithmic filters:

| Filter Name | Model ID | Supported Scales | Compute Engine | Characteristic | Ideal Use Case |
|---|---|:---:|---|---|---|
| **Lanczos (3-Lobe)** | `lanczos-3` | **2×, 3×, 4×** | Native Multi-Threaded Sinc Kernel | High-fidelity sinc reconstruction with edge preservation and anti-ringing windowing. | Forensic photographic analysis, document enlargement. |
| **Bicubic Spline** | `bicubic` | **2×, 3×, 4×** | OpenCV Bicubic Spline | Smooth continuous-tone interpolation without harsh boundary stepping. | Continuous gradients, graphics, portraits. |
| **Nearest Neighbor** | `nearest` | **2×, 3×, 4×** | Direct Pixel Duplication | 100% pixel-exact scaling with zero smoothing or anti-aliasing. | Pixel art, retro assets, binary masks. |

---

## 3. Scale Factor Transparency & Model-Aware UI

VeilFrame enforces strict scale honesty across all renderers and controllers:
- **No Artificial Multi-Pass Stacking**: VeilFrame does not tack on unannounced secondary passes to claim false 6× or 8× scaling.
- **Model-Aware Scale Toggle**: The UI scale button group (`2× Scale`, `3× Scale`, `4× Scale`) dynamically enables only the scale factors legitimately supported by the selected model:
  - 2× models enable `2× Scale`.
  - 3× models enable `3× Scale`.
  - 4× models enable `4× Scale`.
  - Algorithmic mathematical filters support `2×`, `3×`, and `4×`.
- Automatic scale resolution dynamically re-binds scale targets if a user switches between models with different native capabilities.

---

## 4. Native Runtime Architecture & Security

```text
┌────────────────────────────────────────────────────────┐
│             VeilFrame Image Upscaler UI                │
└───────────────────────────┬────────────────────────────┘
                            │
               UpscaleModelResolver & Engine
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
  [ Algorithmic Path ]                   [ Neural NCNN Path ]
  • Lanczos-3 Kernels                   • NcnnNative JNI (`realsr_jni.cpp`)
  • Bicubic Spline                      • Tencent NCNN Engine
  • Nearest Neighbor                    • Vulkan GPU Acceleration
                                        • Multi-Threaded CPU Fallback
                                        • FP16 Half-Precision Tiling
```

### 1. Zero ONNX in Upscaler
- Super-resolution runs through native C++ JNI (`realsr_jni.cpp`, `realsr.cpp`).
- ONNX Runtime is completely excluded from upscaling (ONNX is only utilized in the background remover for IsNet / BiRefNet matting).

### 2. Download Security & Atomic Rollback
- **Allowed Origin Enforcement**: Model downloads and redirects are strictly restricted to approved hosts (`github.com`, `githubusercontent.com`, `huggingface.co`, `hf.co`).
- **Fail-Closed HTTP Verification**: Downloads require HTTP 200 responses. Connections are immediately closed upon non-200 status to prevent socket exhaustion.
- **Strict Integrity Checks**: Partial downloads are written to `.part` files, verified against declared `Content-Length` and SHA-256 hashes, and atomically committed to target files with backup rollback upon activation failure.
