"""
tests/image/test_redteam.py — Tests for the red-team probe suite and adversarial engine.
"""

import io
import unittest
import numpy as np
from PIL import Image

from veilframe.image.models.status import CheckStatus
from veilframe.image.redteam.engine import build_default_engine, RedTeamEngine
from veilframe.image.redteam.probes.metadata_probe import MetadataProbe
from veilframe.image.redteam.probes.thumbnail_probe import ThumbnailProbe
from veilframe.image.redteam.probes.container_probe import ContainerProbe
from veilframe.image.redteam.probes.face_probe import FaceProbe


class TestRedTeamSuite(unittest.TestCase):

    def test_metadata_probe_passes_on_clean_image(self):
        img = Image.new("RGB", (64, 64), color=(100, 100, 100))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=90)
        clean_bytes = buf.getvalue()

        probe = MetadataProbe()
        res = probe.attack(original_bytes=clean_bytes, sanitized_bytes=clean_bytes)
        self.assertEqual(res.status, CheckStatus.PASS)

    def test_thumbnail_probe_passes_on_clean_image(self):
        img = Image.new("RGB", (64, 64), color=(100, 100, 100))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=90)
        clean_bytes = buf.getvalue()

        probe = ThumbnailProbe()
        res = probe.attack(original_bytes=clean_bytes, sanitized_bytes=clean_bytes)
        self.assertEqual(res.status, CheckStatus.PASS)

    def test_redteam_engine_aggregation(self):
        img = Image.new("RGB", (64, 64), color=(100, 100, 100))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=90)
        clean_bytes = buf.getvalue()

        engine = build_default_engine()
        res = engine.run(
            original_bytes=clean_bytes,
            sanitized_bytes=clean_bytes,
            original_array=np.zeros((64, 64, 3), dtype=np.float32),
            sanitized_array=np.zeros((64, 64, 3), dtype=np.float32),
        )
        self.assertEqual(res.overall_status, CheckStatus.PASS)
        self.assertEqual(res.probes_failed, 0)
        self.assertGreaterEqual(res.probes_run, 7)


if __name__ == "__main__":
    unittest.main()
