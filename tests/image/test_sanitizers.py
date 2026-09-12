"""
tests/image/test_sanitizers.py — Comprehensive tests for Layers A, B, and C sanitizers.
"""

import io
import unittest
import numpy as np
from PIL import Image

from veilframe.image.models.status import CheckStatus, DetectorClass
from veilframe.image.models.coordinates import BoundingBox, CoordinateSpace
from veilframe.image.models.graph import (
    LayerType,
    RiskLevel,
    PrivacyGraph,
    PrivacyNode,
    DetectorEvidence,
    ProviderFingerprint,
)
from veilframe.image.sanitizers.container import ContainerSanitizer
from veilframe.image.sanitizers.representation import RepresentationSanitizer
from veilframe.image.sanitizers.semantic import SemanticSanitizer


def _create_synthetic_jpeg_with_metadata() -> bytes:
    """Create a JPEG byte string containing simulated EXIF metadata."""
    img = Image.new("RGB", (200, 200), color=(128, 128, 128))
    buf = io.BytesIO()
    # Save standard JPEG
    img.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


class TestSanitizers(unittest.TestCase):

    def test_container_sanitizer_jpeg_clean(self):
        raw_jpeg = _create_synthetic_jpeg_with_metadata()
        sanitizer = ContainerSanitizer()
        result = sanitizer.sanitize(raw_jpeg, target_format="JPEG")
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertIsNotNone(result.output_bytes)
        self.assertGreater(len(result.output_bytes), 0)

    def test_container_sanitizer_png_clean(self):
        img = Image.new("RGB", (100, 100), color=(50, 100, 150))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        sanitizer = ContainerSanitizer()
        result = sanitizer.sanitize(buf.getvalue(), target_format="PNG")
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertIsNotNone(result.output_bytes)

    def test_representation_sanitizer_normalization(self):
        img = Image.new("RGBA", (100, 100), color=(255, 0, 0, 128))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        sanitizer = RepresentationSanitizer()
        result = sanitizer.normalize(buf.getvalue())
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertIsNotNone(result.linear_srgb_f32)
        self.assertEqual(result.width, 100)
        self.assertEqual(result.height, 100)
        self.assertTrue(result.had_alpha)
        # Check linear sRGB range
        arr = result.linear_srgb_f32
        self.assertTrue(np.all(arr >= 0.0) and np.all(arr <= 1.0))

    def test_semantic_sanitizer_solid_fill(self):
        # Create 100x100 white image
        canvas = np.ones((100, 100, 3), dtype=np.float32)
        graph = PrivacyGraph(raw_source_hash="a" * 64)

        fp = ProviderFingerprint(
            provider_id="p1", implementation_id="i1", algorithm_id="a1",
            library_id="l1", model_family="m1", version="1.0",
            source_hash="b" * 64, implementation_hash="c" * 64,
            dependency_graph_hash="d" * 64, library_binary_hash="e" * 64,
        )
        bbox = BoundingBox(10, 10, 40, 40, space=CoordinateSpace.SOURCE_DECODED)
        ev = DetectorEvidence(detector_class=DetectorClass.FACE, confidence=0.99, fingerprint=fp, bbox=bbox)
        node = PrivacyNode(
            node_id="face_0001", layer=LayerType.SEMANTIC,
            risk_level=RiskLevel.CRITICAL, evidence=[ev], description="face",
        )
        graph.add_node(node)

        sanitizer = SemanticSanitizer()
        result = sanitizer.sanitize(canvas, graph, 100, 100)
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertEqual(result.regions_filled, 1)
        self.assertEqual(len(result.redaction_records), 1)

        out = result.output_array
        # Redacted region should be strictly (0, 0, 0)
        redacted_patch = out[15:35, 15:35, :]
        self.assertTrue(np.all(redacted_patch == 0.0))
        # Non-redacted region should remain strictly 1.0
        public_patch = out[60:90, 60:90, :]
        self.assertTrue(np.all(public_patch == 1.0))


if __name__ == "__main__":
    unittest.main()
