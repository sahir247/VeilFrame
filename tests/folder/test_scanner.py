"""
Unit tests for FolderScanner and scan profiles.
"""

import os
import shutil
import tempfile
import unittest

from veilframe.folder.config import ScanConfig, ScanProfile
from veilframe.folder.scanner import FolderScanner


class TestFolderScanner(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="veilframe_scan_test_")
        
        # Build a known directory structure
        # root/
        #   file1.txt (10 bytes)
        #   file2.py  (20 bytes)
        #   sub1/
        #     subfile1.txt (30 bytes)
        #     subfile2.jpg (40 bytes)
        #     sub2/
        #       deep.py (50 bytes)
        #   .hidden_dir/
        #     secret.txt (15 bytes)
        #   ignored_dir/
        #     ignored.txt (100 bytes)

        with open(os.path.join(self.temp_dir, "file1.txt"), "wb") as f:
            f.write(b"A" * 10)
        with open(os.path.join(self.temp_dir, "file2.py"), "wb") as f:
            f.write(b"B" * 20)

        sub1 = os.path.join(self.temp_dir, "sub1")
        os.makedirs(sub1, exist_ok=True)
        with open(os.path.join(sub1, "subfile1.txt"), "wb") as f:
            f.write(b"C" * 30)
        with open(os.path.join(sub1, "subfile2.jpg"), "wb") as f:
            f.write(b"D" * 40)

        sub2 = os.path.join(sub1, "sub2")
        os.makedirs(sub2, exist_ok=True)
        with open(os.path.join(sub2, "deep.py"), "wb") as f:
            f.write(b"E" * 50)

        hidden_dir = os.path.join(self.temp_dir, ".hidden_dir")
        os.makedirs(hidden_dir, exist_ok=True)
        with open(os.path.join(hidden_dir, "secret.txt"), "wb") as f:
            f.write(b"H" * 15)

        ignored_dir = os.path.join(self.temp_dir, "ignored_dir")
        os.makedirs(ignored_dir, exist_ok=True)
        with open(os.path.join(ignored_dir, "ignored.txt"), "wb") as f:
            f.write(b"I" * 100)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_quick_scan_profile(self):
        cfg = ScanConfig.from_profile(
            ScanProfile.QUICK,
            excluded_dirs={"ignored_dir"},
            include_hidden=False,
        )
        scanner = FolderScanner(config=cfg)
        res = scanner.scan(self.temp_dir)

        self.assertFalse(res.is_cancelled)
        # Files: file1.txt, file2.py, subfile1.txt, subfile2.jpg, deep.py = 5 files
        self.assertEqual(len(res.files), 5)
        # Total size = 10 + 20 + 30 + 40 + 50 = 150 bytes
        self.assertEqual(res.stats.total_size_bytes, 150)
        # Folders: root, sub1, sub2 = 3 folders
        self.assertEqual(len(res.folders), 3)

        # Hashes should NOT be computed in Quick scan profile (Performance rule)
        for f in res.files:
            self.assertIsNone(f.hash_value)

    def test_folder_size_rollup(self):
        cfg = ScanConfig.from_profile(
            ScanProfile.QUICK,
            excluded_dirs={"ignored_dir", ".hidden_dir"},
        )
        scanner = FolderScanner(config=cfg)
        res = scanner.scan(self.temp_dir)

        # Find root, sub1, and sub2
        root_fld = next(f for f in res.folders if f.depth == 0)
        sub1_fld = next(f for f in res.folders if f.name == "sub1")
        sub2_fld = next(f for f in res.folders if f.name == "sub2")

        # sub2 has deep.py (50 bytes)
        self.assertEqual(sub2_fld.total_size, 50)
        self.assertEqual(sub2_fld.total_files, 1)

        # sub1 has subfile1 (30) + subfile2 (40) + sub2 (50) = 120 bytes
        self.assertEqual(sub1_fld.total_size, 120)
        self.assertEqual(sub1_fld.total_files, 3)

        # root has file1 (10) + file2 (20) + sub1 (120) = 150 bytes
        self.assertEqual(root_fld.total_size, 150)
        self.assertEqual(root_fld.total_files, 5)

    def test_depth_limiting(self):
        cfg = ScanConfig(max_depth=1, excluded_dirs={"ignored_dir", ".hidden_dir"})
        scanner = FolderScanner(config=cfg)
        res = scanner.scan(self.temp_dir)

        # Depth 0: root, Depth 1: sub1 (sub2 at depth 2 should not be scanned)
        paths = [f.relative_path.replace("\\", "/") for f in res.files]
        self.assertIn("file1.txt", paths)
        self.assertIn("sub1/subfile1.txt", paths)
        self.assertNotIn("sub1/sub2/deep.py", paths)

    def test_exclusion_filters(self):
        cfg = ScanConfig(
            excluded_dirs={"sub1", "ignored_dir", ".hidden_dir"},
            excluded_extensions={".py"},
        )
        scanner = FolderScanner(config=cfg)
        res = scanner.scan(self.temp_dir)

        # Should only scan root, excluding .py -> only file1.txt
        self.assertEqual(len(res.files), 1)
        self.assertEqual(res.files[0].name, "file1.txt")

    def test_integrity_scan_profile(self):
        cfg = ScanConfig.from_profile(
            ScanProfile.INTEGRITY,
            excluded_dirs={"ignored_dir", ".hidden_dir"},
        )
        scanner = FolderScanner(config=cfg)
        res = scanner.scan(self.temp_dir)

        # All files should have SHA-256 computed
        for f in res.files:
            self.assertIsNotNone(f.hash_value)
            self.assertEqual(f.hash_algorithm, "sha256")
            self.assertEqual(len(f.hash_value), 64)

    def test_cancellation(self):
        cfg = ScanConfig(include_hash=True)
        scanner = FolderScanner(config=cfg)
        scanner.cancel()
        res = scanner.scan(self.temp_dir)
        self.assertTrue(res.is_cancelled)


if __name__ == "__main__":
    unittest.main()
