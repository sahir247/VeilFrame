"""
tests/image/test_detectors.py — Tests for face, plate, text, and code detectors.
"""

import unittest
import numpy as np

from veilframe.image.models.status import DetectorClass, IndependenceLevel
from veilframe.image.detectors.face import PrimaryFaceDetector, ProbeFaceDetector
from veilframe.image.detectors.plate import PrimaryPlateDetector, ProbePlateDetector
from veilframe.image.detectors.text import EASTTextDetector, MSERTextDetector
from veilframe.image.detectors.code import PrimaryQRCodeDetector, ProbeQRCodeDetector


class TestDetectors(unittest.TestCase):

    def test_face_detector_level3_independence(self):
        primary = PrimaryFaceDetector()
        probe = ProbeFaceDetector()
        self.assertEqual(primary.detector_class, DetectorClass.FACE)
        self.assertEqual(probe.detector_class, DetectorClass.FACE)

        # Independence between Primary (Haar) and Probe (DNN/HoG)
        level = primary.fingerprint.independence_level(probe.fingerprint)
        self.assertEqual(level, IndependenceLevel.LEVEL_3)

    def test_plate_detector_independence(self):
        primary = PrimaryPlateDetector()
        probe = ProbePlateDetector()
        self.assertEqual(primary.detector_class, DetectorClass.LICENSE_PLATE)
        self.assertEqual(probe.detector_class, DetectorClass.LICENSE_PLATE)
        level = primary.fingerprint.independence_level(probe.fingerprint)
        self.assertEqual(level, IndependenceLevel.LEVEL_3)

    def test_text_detector_independence(self):
        primary = EASTTextDetector()
        probe = MSERTextDetector()
        self.assertEqual(primary.detector_class, DetectorClass.TEXT)
        self.assertEqual(probe.detector_class, DetectorClass.TEXT)
        level = primary.fingerprint.independence_level(probe.fingerprint)
        self.assertEqual(level, IndependenceLevel.LEVEL_3)

    def test_code_detector_independence(self):
        primary = PrimaryQRCodeDetector()
        probe = ProbeQRCodeDetector()
        self.assertEqual(primary.detector_class, DetectorClass.QR_CODE)
        self.assertEqual(probe.detector_class, DetectorClass.QR_CODE)
        level = primary.fingerprint.independence_level(probe.fingerprint)
        self.assertEqual(level, IndependenceLevel.LEVEL_3)

    def test_detector_execution_on_blank_image(self):
        blank = np.zeros((100, 100, 3), dtype=np.float32)
        face_det = PrimaryFaceDetector()
        res = face_det.detect(blank)
        self.assertIsInstance(res, list)


if __name__ == "__main__":
    unittest.main()
