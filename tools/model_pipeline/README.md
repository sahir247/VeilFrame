# VeilFrame Model Pipeline — quantized variant publishing (F15)

Fail-closed tooling for producing and publishing **fp16 / int8** variants of the
Real-ESRGAN-family ONNX catalog, per `docs/UPSCALER_STABILITY_FIX_PLAN.md` (F15)
and ADR 0006.

## Why quantized variants

| Precision | Size | Speed (typical, arm64) | Quality | Runtime support |
|---|---|---|---|---|
| FP32 (current) | 1× | 1× | reference | ✅ |
| **FP16** | ~0.5× | ~1.5–2× on fp16-capable CPUs/GPUs | PSNR ≥ 55 dB vs fp32 (visually lossless) | ✅ existing `ModelInfo.isFp16` path handles fp16 I/O tensors |
| **INT8** (dynamic, QInt8 weights) | ~0.25–0.4× | ~2–3× on XNNPACK | PSNR 35–45 dB (gated ≥ 35) | ✅ float I/O preserved — drop-in |

Smaller downloads matter for the 6–16 GB mid-range fleet this app targets
(less RAM pressure from weights, faster cold model load, less storage).

## Workflow

```bash
pip install "onnx>=1.14" "onnxruntime>=1.17" numpy

# 1. Collect the fp32 parents (file stem = registry id, e.g. realesrgan-general-4x.onnx)
python tools/model_pipeline/quantize_upscale_models.py \
    --models-dir models/onnx --out dist/quantized

# 2. Inspect dist/quantized/manifest.json — every entry carries PSNR, max|diff|,
#    SHA-256, byte size, and "published": true ONLY when the gate passed.
#    Gated-fail artifacts are deleted, never published (fail-closed).

# 3. Upload the passing artifacts to GitHub Releases (same channel as the
#    fp32 catalog; CI can attach them to the release job).

# 4. Paste dist/quantized/registry_snippet.kt into UpscaleModelRegistry,
#    replacing "<FILL: GitHub Releases asset URL>" with the REAL URL.
#    registerQuantizedVariant() refuses empty URL/sha — placeholders cannot ship.

# 5. (Next iteration) resolver preference: once variants are live, teach
#    UpscaleModelResolver to prefer int8 on CONSERVATIVE/NORMAL memory tiers
#    and fp16 on HIGH tiers — one small, testable change; deliberately NOT
#    auto-wired before real artifacts exist (no speculative behavior).
```

## Gates & guarantees

- Verification runs the variant and its fp32 parent on the **same seeded
  reference tile** (RGB [0,1] NCHW, default 256px, seed recorded in manifest).
- Geometry check: output shape must equal fp32 output shape; integer scale parsed.
- Default gates: fp16 ≥ 55 dB, int8 ≥ 35 dB (`--gate-fp16-psnr/--gate-int8-psnr`).
- The Android side re-verifies **SHA-256 before every run** (existing engine
  behavior), so a corrupted or swapped asset can never execute.
- `UpscaleModel.precision/variantOf/memoryClassBytes/recommendedBackend` are
  additive metadata fields (defaults keep every existing entry/test valid).

## Honest limitations

- PSNR on a seeded noise tile is a *screening* gate, not a perceptual study;
  before promoting a variant to DEFAULT for a preset, run the repo's calibration
  corpus (`calibration/`) through both variants and compare against the
  existing SSIM ≥ 0.95 / PSNR ≥ 30 dB production invariants.
- int8 dynamic quantization quality is model-dependent; anime models usually
  tolerate it better than general-photo models — gate per artifact, publish
  per artifact.
- NNAPI/QNN delegation of fp16 graphs is vendor-dependent; the on-device
  benchmark ladder (OnnxSessionManager) measures the real winner per device.
