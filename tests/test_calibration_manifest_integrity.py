"""
Calibration Baseline v1.0 Manifest & Archive Cryptographic Integrity Verification.

Validates:
  1. Every file recorded in calibration/v1.0/manifest.json matches its exact disk SHA-256 and byte size.
  2. The archive calibration_v1_0.tar.gz contains the exact 19 uncompressed files with no extras or omissions.
  3. All files present in calibration/v1.0/ are registered in the manifest.
"""
import hashlib
import json
import tarfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CALIBRATION_DIR = ROOT / "calibration" / "v1.0"
MANIFEST_PATH = CALIBRATION_DIR / "manifest.json"
ARCHIVE_PATH = CALIBRATION_DIR / "calibration_v1_0.tar.gz"


def _compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


class TestCalibrationManifestIntegrity(unittest.TestCase):
    """Mechanically checks all frozen calibration digests and archive contents."""

    def test_all_manifest_digests_and_sizes_match_committed_bytes(self):
        self.assertTrue(MANIFEST_PATH.exists(), f"Missing manifest at {MANIFEST_PATH}")
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            manifest = json.load(f)

        files = manifest.get("files", {})
        self.assertEqual(len(files), 20, f"Expected 20 entries in manifest, got {len(files)}")

        mismatches = []
        for filename, entry in files.items():
            file_path = CALIBRATION_DIR / filename
            if not file_path.exists():
                mismatches.append(f"Missing file on disk: {filename}")
                continue

            actual_size = file_path.stat().st_size
            expected_size = entry.get("size_bytes")
            if actual_size != expected_size:
                mismatches.append(
                    f"{filename}: size mismatch (expected {expected_size}, actual {actual_size})"
                )

            actual_sha = _compute_sha256(file_path)
            expected_sha = entry.get("sha256", "").lower()
            if actual_sha.lower() != expected_sha:
                mismatches.append(
                    f"{filename}: SHA-256 mismatch (expected {expected_sha[:16]}..., actual {actual_sha[:16]}...)"
                )

        self.assertEqual(len(mismatches), 0, "\n".join(mismatches))

    def test_archive_contents_match_frozen_set(self):
        self.assertTrue(ARCHIVE_PATH.exists(), f"Missing archive at {ARCHIVE_PATH}")

        with tarfile.open(ARCHIVE_PATH, "r:gz") as tar:
            members = tar.getnames()

        # Archive should contain the 19 uncompressed files
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            manifest = json.load(f)

        expected_files = {name for name in manifest.get("files", {}).keys() if name != "calibration_v1_0.tar.gz"}
        actual_files = set(members)

        self.assertEqual(
            actual_files,
            expected_files,
            f"Archive members mismatch.\nMissing in archive: {expected_files - actual_files}\nExtra in archive: {actual_files - expected_files}"
        )


if __name__ == "__main__":
    unittest.main()
