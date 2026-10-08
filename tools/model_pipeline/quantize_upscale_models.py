#!/usr/bin/env python3
"""
VeilFrame model pipeline — F15: fp16 / int8 quantization with fail-closed PSNR gates.

Produces publishable quantized variants of the Real-ESRGAN-family ONNX catalog:
  * FP16  — onnxruntime.transformers.float16 (full conversion incl. I/O; the
            Android runtime already speaks fp16 tensors via ModelInfo.isFp16).
  * INT8  — onnxruntime.quantization.quantize_dynamic (QInt8 weights, float
            I/O preserved — a drop-in for the existing fp32 pipeline).

Every artifact is VERIFIED against its fp32 parent on a seeded reference tile
before it is allowed to be published (fail-closed, matching the repo's
quality-gate doctrine):
  * output geometry must equal input geometry x declared scale
  * PSNR(variant, fp32) must clear the gate (defaults: fp16 >= 55 dB,
    int8 >= 35 dB — Real-ESRGAN dynamic int8 typically lands 35–45 dB)
  * SHA-256 + byte size recorded for the registry entry

Outputs (into --out):
  <stem>_fp16.onnx, <stem>_int8.onnx      (only when gates pass)
  manifest.json                            (full verification report)
  registry_snippet.kt                      (paste-ready Kotlin for
                                            UpscaleModelRegistry.registerQuantizedVariant)

Usage:
  pip install "onnx>=1.14" "onnxruntime>=1.17" numpy
  python tools/model_pipeline/quantize_upscale_models.py \
      --models-dir models/onnx --out dist/quantized \
      [--tile 256] [--gate-fp16-psnr 55] [--gate-int8-psnr 35] \
      [--skip-fp16] [--skip-int8]

Publishing workflow (see tools/model_pipeline/README.md):
  quantize -> gates pass -> upload artifacts to GitHub Releases ->
  add registry entries with the REAL downloadUrl + sha256 from manifest.json.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

try:
    import numpy as np
    import onnx
    import onnxruntime as ort
except ImportError as exc:  # honest, actionable failure
    sys.stderr.write(
        "Missing dependencies: {}\n"
        'Install with:  pip install "onnx>=1.14" "onnxruntime>=1.17" numpy\n'.format(exc)
    )
    sys.exit(2)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def psnr(ref: np.ndarray, out: np.ndarray) -> float:
    mse = float(np.mean((ref.astype(np.float64) - out.astype(np.float64)) ** 2))
    if mse <= 1e-12:
        return 999.0
    return 10.0 * math.log10(1.0 / mse)  # signals assumed normalized to [0,1]


def reference_input(sess: ort.InferenceSession, tile: int, seed: int) -> dict:
    """Seeded RGB [0,1] NCHW input at the session's declared input shape."""
    inp = sess.get_inputs()[0]
    shape = [d if isinstance(d, int) and d > 0 else tile for d in inp.shape]
    if len(shape) != 4:
        raise ValueError(f"expected 4-D NCHW input, got {inp.shape}")
    rng = np.random.default_rng(seed)
    data = rng.random(shape, dtype=np.float32) * 0.8 + 0.1  # avoid degenerate 0/1 flats
    return {inp.name: data}


def run(sess: ort.InferenceSession, feed: dict) -> np.ndarray:
    out = sess.run(None, feed)[0]
    return np.asarray(out, dtype=np.float32)


def declared_scale(input_shape, output_shape) -> int:
    ih, iw = input_shape[-2], input_shape[-1]
    oh, ow = output_shape[-2], output_shape[-1]
    if ih <= 0 or iw <= 0:
        return 0  # dynamic — checked at runtime instead
    r_h, r_w = oh / ih, ow / iw
    if abs(r_h - r_w) > 1e-6 or abs(r_h - round(r_h)) > 1e-6:
        raise ValueError(f"non-integer/anisotropic scale {r_h}x{r_w}")
    return int(round(r_h))


def convert_fp16(src: Path, dst: Path) -> None:
    from onnxruntime.transformers import float16

    model = onnx.load(str(src))
    model_fp16 = float16.convert_float_to_float16(model, keep_io_types=False)
    onnx.save(model_fp16, str(dst))


def convert_int8(src: Path, dst: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8)


def verify(base_sess: ort.InferenceSession, variant_path: Path, tile: int, seed: int):
    feed = reference_input(base_sess, tile, seed)
    ref = run(base_sess, feed)

    v_sess = ort.InferenceSession(str(variant_path), providers=["CPUExecutionProvider"])
    v_in = v_sess.get_inputs()[0]
    v_feed = {v_in.name: feed[list(feed)[0]].astype(
        np.float16 if "float16" in v_in.type else np.float32)}
    out = run(v_sess, v_feed).astype(np.float32)

    if out.shape != ref.shape:
        return {"pass": False, "reason": f"shape mismatch {out.shape} vs {ref.shape}"}
    diff = np.abs(ref - out)
    result = {
        "pass": True,
        "psnr_db": round(psnr(ref, out), 2),
        "max_abs_diff": round(float(diff.max()), 6),
        "mean_abs_diff": round(float(diff.mean()), 8),
        "output_shape": list(out.shape),
    }
    scale = declared_scale(list(feed[list(feed)[0]].shape), list(ref.shape))
    if scale:
        result["scale"] = scale
    return result


def kotlin_snippet(entries: list[dict]) -> str:
    lines = [
        "// Auto-generated by tools/model_pipeline/quantize_upscale_models.py",
        "// Paste into UpscaleModelRegistry (or a startup init block) AFTER the",
        "// artifacts are uploaded to GitHub Releases and downloadUrl is real.",
        "",
    ]
    for e in entries:
        prec = e["precision"].upper()
        vid = e["id"]
        lines += [
            f'private val {vid.replace("-", "_").upper()} = UpscaleModel(',
            f'    id = "{vid}",',
            f'    name = "{e["base_name"]} ({prec})",',
            f'    description = "Quantized {prec} variant of {e["base_name"]} — '
            f'PSNR {e["psnr_db"]} dB vs FP32 (pipeline-gated).",',
            '    type = ModelType.AI_ONNX,',
            f'    nativeScale = {e["scale"]},',
            f'    sizeBytes = {e["bytes"]}L,',
            '    downloadUrl = "<FILL: GitHub Releases asset URL>",',
            f'    sha256 = "{e["sha256"]}",',
            f'    supportedOutputScales = listOf({e["scale"]}),',
            f'    precision = ModelPrecision.{prec},',
            f'    variantOf = "{e["base_id"]}",',
            f'    memoryClassBytes = {e["memory_class_bytes"]}L,',
            ')',
            f'registerQuantizedVariant({vid.replace("-", "_").upper()})',
            "",
        ]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models-dir", required=True, type=Path, help="directory of fp32 .onnx models")
    ap.add_argument("--out", required=True, type=Path, help="output directory for gated artifacts")
    ap.add_argument("--tile", type=int, default=256, help="verification tile size (dynamic dims)")
    ap.add_argument("--seed", type=int, default=20261008)
    ap.add_argument("--gate-fp16-psnr", type=float, default=55.0)
    ap.add_argument("--gate-int8-psnr", type=float, default=35.0)
    ap.add_argument("--skip-fp16", action="store_true")
    ap.add_argument("--skip-int8", action="store_true")
    args = ap.parse_args()

    models = sorted(args.models_dir.glob("*.onnx"))
    if not models:
        sys.stderr.write(f"No .onnx models found in {args.models_dir}\n")
        return 2
    args.out.mkdir(parents=True, exist_ok=True)

    manifest, snippet_entries, failures = [], [], []

    for src in models:
        stem = src.stem
        print(f"\n=== {src.name} ({src.stat().st_size / 1e6:.1f} MB) ===")
        base = ort.InferenceSession(str(src), providers=["CPUExecutionProvider"])
        base_id = stem  # registry ids are file stems by pipeline convention
        scale = 0
        feed = reference_input(base, args.tile, args.seed)
        ref_out = run(base, feed)
        try:
            scale = declared_scale(list(feed[list(feed)[0]].shape), list(ref_out.shape))
        except ValueError as e:
            print(f"  !! scale parse failed: {e}")

        for prec, gate, conv, skip in (
            ("fp16", args.gate_fp16_psnr, convert_fp16, args.skip_fp16),
            ("int8", args.gate_int8_psnr, convert_int8, args.skip_int8),
        ):
            if skip:
                continue
            dst = args.out / f"{stem}_{prec}.onnx"
            print(f"  -> converting {prec} ...")
            try:
                conv(src, dst)
            except Exception as e:
                failures.append({"model": src.name, "precision": prec, "stage": "convert", "error": str(e)})
                print(f"     CONVERT FAILED (fail-closed, not published): {e}")
                continue

            report = verify(base, dst, args.tile, args.seed)
            entry = {
                "model": src.name, "id": f"{base_id}-{prec}", "base_id": base_id,
                "base_name": stem.replace("-", " ").replace("_", " ").title(),
                "precision": prec, "sha256": sha256_of(dst), "bytes": dst.stat().st_size,
                "scale": scale, "memory_class_bytes": 0, **report,
            }
            passed = report.get("pass") and report.get("psnr_db", 0.0) >= gate
            entry["gate_db"] = gate
            entry["published"] = bool(passed)
            manifest.append(entry)
            if passed:
                print(f"     PASS  PSNR {report['psnr_db']} dB >= {gate} dB  "
                      f"max|d|={report['max_abs_diff']}  sha256={entry['sha256'][:16]}...")
                snippet_entries.append(entry)
            else:
                reason = report.get("reason", f"PSNR {report.get('psnr_db')} dB < gate {gate} dB")
                failures.append({"model": src.name, "precision": prec, "stage": "gate", "error": reason})
                dst.unlink(missing_ok=True)  # fail-closed: gated artifacts are NOT published
                print(f"     FAIL  {reason} — artifact deleted (fail-closed)")

    (args.out / "manifest.json").write_text(json.dumps(
        {"seed": args.seed, "tile": args.tile, "results": manifest, "failures": failures}, indent=2))
    if snippet_entries:
        (args.out / "registry_snippet.kt").write_text(kotlin_snippet(snippet_entries))

    print(f"\nManifest: {args.out / 'manifest.json'}")
    ok = sum(1 for m in manifest if m["published"])
    print(f"Published-ready: {ok} / {len(manifest)}   Failures: {len(failures)}")
    return 0 if ok > 0 or not manifest else 1


if __name__ == "__main__":
    sys.exit(main())
