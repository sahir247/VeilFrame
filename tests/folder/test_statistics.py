"""
Unit tests for StatisticsEngine.
"""

import unittest

from veilframe.folder.models import (
    FileRecord,
    FolderRecord,
    ScanError,
)
from veilframe.folder.statistics import StatisticsEngine


class TestStatisticsEngine(unittest.TestCase):
    def test_statistics_calculation(self):
        folders = [
            FolderRecord(id=1, name="root", depth=0, direct_files=1, direct_folders=1),
            FolderRecord(id=2, name="empty_dir", depth=1, direct_files=0, direct_folders=0),
        ]
        files = [
            FileRecord(id=1, folder_id=1, name="empty.txt", extension=".txt", size=0),
            FileRecord(id=2, folder_id=1, name="small.py", extension=".py", size=100),
            FileRecord(id=3, folder_id=1, name="large.mp4", extension=".mp4", size=5000),
        ]
        errors = [
            ScanError(id=1, path="/root/err", error_type="OSError", message="Disk fail")
        ]

        stats = StatisticsEngine.calculate(
            root_path="/test_root",
            files=files,
            folders=folders,
            errors=errors,
            duration_seconds=2.0,
        )

        self.assertEqual(stats.total_files, 3)
        self.assertEqual(stats.total_folders, 2)
        self.assertEqual(stats.total_size_bytes, 5100)
        self.assertEqual(stats.empty_files_count, 1)
        self.assertEqual(stats.empty_folders_count, 1)
        self.assertEqual(stats.max_depth, 1)
        self.assertEqual(stats.error_count, 1)
        self.assertEqual(stats.scan_speed_files_per_sec, 1.5)
        self.assertEqual(stats.largest_files[0].name, "large.mp4")
        self.assertEqual(stats.smallest_files[0].name, "small.py")


if __name__ == "__main__":
    unittest.main()
