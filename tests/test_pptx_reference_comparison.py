import json

import pytest
from PIL import Image

from scripts.compare_pptx_reference import compare


def capture(path, renderer, color, source="a" * 64, size=(8, 5)):
    path.mkdir()
    (path / "manifest.json").write_text(json.dumps({
        "schema": 1, "renderer": renderer, "renderer_version": "16.0" if renderer == "Microsoft PowerPoint" else "b" * 40,
        "source_sha256": source, "slide_count": 1,
    }), encoding="utf-8")
    Image.new("RGB", size, color).save(path / "slide-0001.png")
    return path


def test_reference_comparison_exact_and_changed_pixel(tmp_path):
    reference = capture(tmp_path / "reference", "Microsoft PowerPoint", "white")
    candidate = capture(tmp_path / "candidate", "VeilFrame Android", "white")
    assert compare(reference, candidate)["exact_pixel_match"]
    with Image.open(candidate / "slide-0001.png") as src:
        image = src.copy()
    image.putpixel((2, 3), (0, 255, 255))
    image.save(candidate / "slide-0001.png")
    report = compare(reference, candidate)
    assert not report["exact_pixel_match"]
    assert report["slides"][0]["different_pixels"] == 1
    assert report["slides"][0]["difference_bounds_xyxy"] == [2, 3, 3, 4]


def test_reference_comparison_rejects_source_and_dimension_mismatch(tmp_path):
    reference = capture(tmp_path / "reference", "Microsoft PowerPoint", "white")
    candidate = capture(tmp_path / "candidate", "VeilFrame Android", "white", source="c" * 64)
    with pytest.raises(ValueError, match="source SHA"):
        compare(reference, candidate)
    metadata = json.loads((candidate / "manifest.json").read_text())
    metadata["source_sha256"] = "a" * 64
    (candidate / "manifest.json").write_text(json.dumps(metadata))
    Image.new("RGB", (9, 5), "white").save(candidate / "slide-0001.png")
    with pytest.raises(ValueError, match="dimensions"):
        compare(reference, candidate)
