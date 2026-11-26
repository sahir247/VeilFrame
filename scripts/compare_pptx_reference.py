#!/usr/bin/env python3
"""Strict same-source, same-size PowerPoint/VeilFrame raster comparison.

No image registration, resizing, tolerance-based PASS, or invented reference.
A mismatch is a diagnostic failure, not evidence of a renderer bug by itself.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else _digest_310(stream)


def _digest_310(stream):
    result = hashlib.sha256()
    for chunk in iter(lambda: stream.read(65536), b""):
        result.update(chunk)
    return result.hexdigest()


def manifest(folder, role):
    with (folder / "manifest.json").open(encoding="utf-8-sig") as stream:
        data = json.load(stream)
    if data.get("schema") != 1 or not re.fullmatch(r"[0-9a-f]{64}", str(data.get("source_sha256", ""))):
        raise ValueError(f"{role}: invalid source identity")
    count = data.get("slide_count")
    if type(count) is not int or not 1 <= count <= 1000:
        raise ValueError(f"{role}: invalid slide count")
    if not isinstance(data.get("renderer_version"), str) or not data["renderer_version"].strip():
        raise ValueError(f"{role}: missing renderer version")
    expected_renderer = "Microsoft PowerPoint" if role == "reference" else "VeilFrame Android"
    if data.get("renderer") != expected_renderer:
        raise ValueError(f"{role}: expected {expected_renderer}")
    if role == "candidate" and not re.fullmatch(r"[0-9a-f]{40}", data["renderer_version"]):
        raise ValueError("candidate: expected exact tested git commit")
    names = {f"slide-{i:04d}.png" for i in range(1, count + 1)}
    actual = {p.name for p in folder.glob("slide-*.png")}
    if actual != names:
        raise ValueError(f"{role}: missing or unexpected slide files")
    return data


def load_rgb(path):
    if path.stat().st_size > 128 * 1024 * 1024:
        raise ValueError(f"{path}: image byte budget exceeded")
    with Image.open(path) as image:
        if image.format != "PNG" or image.width > 8192 or image.height > 8192 or image.width * image.height > 16_777_216:
            raise ValueError(f"{path}: invalid PNG or image dimensions")
        rgba = np.asarray(image.convert("RGBA"))
        if np.any(rgba[:, :, 3] != 255):
            raise ValueError(f"{path}: nonopaque image, background must be explicit")
        return rgba[:, :, :3].copy()


def compare(reference, candidate):
    reference, candidate = Path(reference), Path(candidate)
    r, c = manifest(reference, "reference"), manifest(candidate, "candidate")
    if r["source_sha256"] != c["source_sha256"]:
        raise ValueError("source SHA-256 mismatch")
    if r["slide_count"] != c["slide_count"]:
        raise ValueError("slide count mismatch")
    results = []
    for i in range(1, r["slide_count"] + 1):
        name = f"slide-{i:04d}.png"
        rp, cp = reference / name, candidate / name
        a, b = load_rgb(rp), load_rgb(cp)
        if a.shape != b.shape:
            raise ValueError(f"{name}: pixel dimensions mismatch (no resize allowed)")
        delta = np.abs(a.astype(np.int16) - b.astype(np.int16))
        changed = np.any(delta != 0, axis=2)
        count = int(np.count_nonzero(changed))
        bounds = None
        if count:
            ys, xs = np.nonzero(changed)
            bounds = [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]
        results.append({
            "slide": i, "width": int(a.shape[1]), "height": int(a.shape[0]),
            "reference_png_sha256": digest(rp), "candidate_png_sha256": digest(cp),
            "different_pixels": count, "different_pixel_fraction": count / changed.size,
            "mean_absolute_channel_error": float(delta.mean()),
            "max_channel_error": int(delta.max()), "difference_bounds_xyxy": bounds,
            "exact": count == 0,
        })
    return {
        "schema": 1, "reference": r, "candidate": c, "slides": results,
        "exact_pixel_match": all(s["exact"] for s in results),
        "interpretation": "Exact equality for supplied rasters only; not a universal Office parity claim.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = compare(args.reference, args.candidate)
        with args.report.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2)
            stream.write("\n")
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(f"Reference comparison refused: {exc}", file=sys.stderr)
        return 2
    return 0 if report["exact_pixel_match"] else 1


if __name__ == "__main__":
    sys.exit(main())
