"""
tests/image/test_pipeline_and_cli.py — End-to-end integration tests for ImagePrivacyPipeline, ImagePublisher, and CLI.
"""

import io
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image

from veilframe.image.models.status import CheckStatus, PublicationState
from veilframe.image.pipeline import ImagePrivacyPipeline
from veilframe.image.publisher import ImagePublisher, PublicationError
from veilframe.image.cli import (
    cmd_image_sanitize,
    cmd_image_verify,
    cmd_image_inspect,
    cmd_image_doctor,
)


def _generate_test_image(path: Path, width: int = 120, height: int = 120) -> None:
    img = Image.new("RGB", (width, height), color=(180, 200, 220))
    img.save(path, format="JPEG", quality=95)


class TestPipelineAndCLI(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.dir_path = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_pipeline_e2e(self):
        input_path = self.dir_path / "test_input.jpg"
        output_path = self.dir_path / "test_output.jpg"
        _generate_test_image(input_path)

        pipeline = ImagePrivacyPipeline()
        result = pipeline.run(
            input_source=input_path,
            output_path=output_path,
        )

        self.assertTrue(result.is_success)
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertTrue(output_path.exists())
        self.assertIsNotNone(result.publication_result)
        self.assertTrue(result.publication_result.is_committed)
        self.assertTrue(result.publication_result.manifest_path.exists())
        self.assertTrue(result.publication_result.sig_path.exists())
        self.assertTrue(result.publication_result.sha256_path.exists())
        self.assertTrue(result.publication_result.pubkey_path.exists())

        # Verify publication integrity holds
        self.assertTrue(result.manifest.publication_integrity_holds())

    def test_cli_sanitize_and_verify_e2e(self):
        input_path = self.dir_path / "cli_input.jpg"
        output_path = self.dir_path / "cli_output.jpg"
        _generate_test_image(input_path)

        # 1. Sanitize via CLI
        class SanitizeArgs:
            input = str(input_path)
            output = str(output_path)
            format = "JPEG"
            detectors = "face,plate"
            margin = 10
            audit_dir = None
            signing_key = None
            key_id = "test-key"
            json = True
            quiet = False

        ret = cmd_image_sanitize(SanitizeArgs())
        self.assertEqual(ret, 0)
        self.assertTrue(output_path.exists())

        manifest_path = self.dir_path / f"{output_path.name}.manifest.json"
        self.assertTrue(manifest_path.exists())

        # 2. Verify via CLI
        class VerifyArgs:
            image = str(output_path)
            manifest = str(manifest_path)
            json = True

        ver_ret = cmd_image_verify(VerifyArgs())
        self.assertEqual(ver_ret, 0)

        # 3. Tamper with output image and confirm verification fails
        corrupt_bytes = bytearray(output_path.read_bytes())
        corrupt_bytes[len(corrupt_bytes) // 2] ^= 0xFF
        output_path.write_bytes(corrupt_bytes)

        tamper_ret = cmd_image_verify(VerifyArgs())
        self.assertEqual(tamper_ret, 1)

    def test_cli_inspect_and_doctor(self):
        input_path = self.dir_path / "inspect_input.jpg"
        _generate_test_image(input_path)

        class InspectArgs:
            image = str(input_path)
            json = True

        ret_insp = cmd_image_inspect(InspectArgs())
        self.assertEqual(ret_insp, 0)

        class DoctorArgs:
            json = True

        ret_doc = cmd_image_doctor(DoctorArgs())
        self.assertEqual(ret_doc, 0)

    def test_publisher_rollback_preserves_existing_target_on_error(self):
        """ImagePublisher must preserve pre-existing target file if manifest generation or commit fails."""
        from veilframe.image.publisher import ImagePublisher
        from veilframe.image.gate.image_gate import GateVerdict
        from unittest.mock import patch

        output_path = self.dir_path / "important_existing_output.png"
        original_content = b"CRITICAL_USER_DATA_DO_NOT_DELETE"
        output_path.write_bytes(original_content)

        publisher = ImagePublisher()
        candidate_bytes = b"NEW_CANDIDATE_DATA"
        publisher.stage(candidate_bytes)

        verdict = GateVerdict(
            geometry_status=CheckStatus.PASS,
            privacy_status=CheckStatus.PASS,
            completeness_status=CheckStatus.PASS,
            independence_status=CheckStatus.PASS,
            fidelity_status=CheckStatus.PASS,
            overall_status=CheckStatus.PASS,
            publication_state=PublicationState.VERIFIED,
        )
        publisher.verify(verdict)

        # Trigger exception during commit
        with patch("veilframe.image.publisher._rfc8785_canonical", side_effect=RuntimeError("Simulated disk error")):
            res = publisher.commit(
                output_path=output_path,
                manifest=None,
            )

        self.assertFalse(res.is_committed)
        self.assertEqual(res.state, PublicationState.QUARANTINED)
        # Confirm the original pre-existing file is intact and was not lost
        self.assertTrue(output_path.exists())
        self.assertEqual(output_path.read_bytes(), original_content)
        self.assertIn("Publication failed during commit", res.error_message)

    def test_pipeline_fail_closed_on_detector_error(self):
        """Pipeline must fail closed (privacy contract FAIL) if a primary detector raises DetectorUnavailableError."""
        from unittest.mock import patch
        from veilframe.image.detectors.base import DetectorUnavailableError
        from veilframe.image.detectors.face import PrimaryFaceDetector

        input_path = self.dir_path / "fail_closed_input.jpg"
        output_path = self.dir_path / "fail_closed_output.jpg"
        _generate_test_image(input_path)

        pipeline = ImagePrivacyPipeline()

        with patch.object(PrimaryFaceDetector, "detect", side_effect=DetectorUnavailableError("Missing cascade")):
            result = pipeline.run(
                input_source=input_path,
                output_path=output_path,
            )

        self.assertFalse(result.is_success)
        self.assertEqual(result.status, CheckStatus.FAIL)
        self.assertEqual(result.verdict.privacy_status, CheckStatus.FAIL)
        self.assertTrue(any("DetectorUnavailableError" in r or "FACE" in r for r in result.failure_reasons))


if __name__ == "__main__":
    unittest.main()
