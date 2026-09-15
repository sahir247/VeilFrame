"""
Unit tests for staged DuplicateFinder.
"""

import os
import shutil
import tempfile
import unittest

from veilframe.folder.duplicate import DuplicateFinder
from veilframe.folder.models import FileRecord


class TestDuplicateFinder(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="veilframe_dupe_test_")

        # Create duplicate groups:
        # Group 1: 3 identical files of 500 bytes ("ALPHA" * 100) -> wasted = 2 * 500 = 1000 bytes
        # Group 2: 2 identical files of 300 bytes ("BETA" * 75)   -> wasted = 1 * 300 = 300 bytes
        # Unique file: 500 bytes but different content ("GAMMA" * 100)
        # Unique file: 100 bytes ("DELTA" * 20)

        self.files: list[FileRecord] = []
        fid = 1

        def _make_file(name: str, content: bytes) -> FileRecord:
            nonlocal fid
            p = os.path.join(self.temp_dir, name)
            with open(p, "wb") as f:
                f.write(content)
            rec = FileRecord(
                id=fid,
                name=name,
                path=p,
                relative_path=name,
                size=len(content),
            )
            fid += 1
            return rec

        # Group 1 (3 files)
        g1_data = b"ALPHA" * 100
        self.files.append(_make_file("g1_a.bin", g1_data))
        self.files.append(_make_file("g1_b.bin", g1_data))
        self.files.append(_make_file("g1_c.bin", g1_data))

        # Group 2 (2 files)
        g2_data = b"BETA" * 75
        self.files.append(_make_file("g2_a.bin", g2_data))
        self.files.append(_make_file("g2_b.bin", g2_data))

        # Collision candidate in size (500 bytes) with different content
        diff_data = b"GAMMA" * 100
        self.files.append(_make_file("diff_500.bin", diff_data))

        # Unique file (100 bytes)
        self.files.append(_make_file("unique.bin", b"DELTA" * 20))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_staged_duplicate_detection(self):
        finder = DuplicateFinder(hash_algorithm="sha256", workers=2)
        groups = finder.find_duplicates(self.files, staged=True)

        self.assertEqual(len(groups), 2)

        # First group should be the one with most wasted space (Group 1: 1000 bytes)
        self.assertEqual(groups[0].size, 500)
        self.assertEqual(groups[0].file_count, 3)
        self.assertEqual(groups[0].wasted_bytes, 1000)

        # Second group (Group 2: 300 bytes)
        self.assertEqual(groups[1].size, 300)
        self.assertEqual(groups[1].file_count, 2)
        self.assertEqual(groups[1].wasted_bytes, 300)

        # diff_500.bin should NOT be in Group 1
        g1_names = [f.name for f in groups[0].files]
        self.assertIn("g1_a.bin", g1_names)
        self.assertIn("g1_b.bin", g1_names)
        self.assertIn("g1_c.bin", g1_names)
        self.assertNotIn("diff_500.bin", g1_names)


if __name__ == "__main__":
    unittest.main()
