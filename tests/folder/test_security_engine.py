"""
Unit tests for veilframe.folder.security (entropy, secret patterns, SecretDetector).
"""

import os
import tempfile
import unittest

from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.security.entropy import calculate_entropy, is_high_entropy
from veilframe.folder.security.secret_detector import SecretDetector, mask_secret
from veilframe.folder.security.sensitive_files import is_sensitive_filename


class TestSecurityEngine(unittest.TestCase):
    def test_entropy_calculation(self):
        # Low entropy strings (repetitive)
        self.assertLess(calculate_entropy("aaaaaaaaaaaaaaaaaaaa"), 0.5)
        self.assertFalse(is_high_entropy("aaaaaaaaaaaaaaaaaaaa"))

        # High entropy strings (cryptographic hashes, API keys)
        high_str = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        self.assertGreater(calculate_entropy(high_str), 4.0)
        self.assertTrue(is_high_entropy(high_str, threshold=4.0))

    def test_mask_secret(self):
        masked = mask_secret("AKIAIOSFODNN7EXAMPLE")
        self.assertTrue(masked.startswith("AKIA"))
        self.assertTrue(masked.endswith("MPLE"))
        self.assertIn("*", masked)

    def test_sensitive_filenames(self):
        self.assertTrue(is_sensitive_filename(".env"))
        self.assertTrue(is_sensitive_filename(".env.production"))
        self.assertTrue(is_sensitive_filename("id_rsa"))
        self.assertTrue(is_sensitive_filename("cert.pem"))
        self.assertTrue(is_sensitive_filename("server.key"))
        self.assertFalse(is_sensitive_filename("main.py"))
        self.assertFalse(is_sensitive_filename("README.md"))

    def test_secret_detector_content_scan(self):
        detector = SecretDetector()
        with tempfile.NamedTemporaryFile(mode="w", delete=False, suffix=".py") as tmp:
            tmp.write('AWS_KEY = "AKIA1234567890ABCDEF"\n')
            tmp_path = tmp.name

        try:
            rec = FileRecord(id=1, name=os.path.basename(tmp_path), path=tmp_path, relative_path="config.py", size=50)
            alerts = detector.scan_file(rec)
            self.assertEqual(len(alerts), 1)
            self.assertEqual(alerts[0].rule_id, "sec.aws_access_key")
            self.assertEqual(rec.effective_category, FileCategory.SECRET)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)


if __name__ == "__main__":
    unittest.main()
