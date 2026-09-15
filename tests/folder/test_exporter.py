"""
Unit tests for FolderExporter.
"""

import json
import os
import shutil
import tempfile
import unittest

from veilframe.folder.config import ScanConfig
from veilframe.folder.exporter import FolderExporter
from veilframe.folder.models import (
    DuplicateGroup,
    ExtensionStat,
    FileRecord,
    FolderRecord,
    ScanResult,
    ScanStats,
)


class TestFolderExporter(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="veilframe_export_test_")
        self.full_sha256 = "d9578852f83562ecfcd6de699292721c2f05ab426e6f9eba7193ddf450ecce88"

        folders = [FolderRecord(id=1, name="root", path="/root", relative_path=".", depth=0, total_size=2000, direct_files=2)]
        files = [
            FileRecord(
                id=1,
                folder_id=1,
                name="file_a.txt",
                relative_path="file_a.txt",
                path="/root/file_a.txt",
                extension=".txt",
                size=1000,
                hash_value=self.full_sha256,
                hash_algorithm="sha256",
            ),
            FileRecord(
                id=2,
                folder_id=1,
                name="file_b.txt",
                relative_path="file_b.txt",
                path="/root/file_b.txt",
                extension=".txt",
                size=1000,
                hash_value=self.full_sha256,
                hash_algorithm="sha256",
            ),
        ]
        dupe_grp = DuplicateGroup(size=1000, hash_value=self.full_sha256, files=files)
        stats = ScanStats(
            root_path="/root",
            total_files=2,
            total_folders=1,
            total_size_bytes=2000,
            duration_seconds=0.5,
            scan_speed_files_per_sec=4.0,
            extension_distribution=[
                ExtensionStat(extension=".txt", file_count=2, total_size=2000, percentage_files=100.0, percentage_size=100.0)
            ],
            duplicate_groups=[dupe_grp],
            duplicate_wasted_bytes=1000,
            largest_files=files,
        )

        self.result = ScanResult(
            config=ScanConfig(include_hash=True, hash_algorithm="sha256"),
            root_path="/root",
            files=files,
            folders=folders,
            stats=stats,
        )
        self.exporter = FolderExporter(self.result)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_generate_default_filename(self):
        name1 = FolderExporter.generate_default_filename("c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source", "html")
        self.assertEqual(name1, "PrivacyVideoCleaner_v1_source_scan_report.html")

        name2 = FolderExporter.generate_default_filename("/home/user/my folder test", "md")
        self.assertEqual(name2, "my_folder_test_scan_report.md")

        name3 = FolderExporter.generate_default_filename("C:\\", "json")
        self.assertEqual(name3, "drive_C_scan_report.json")

        name4 = FolderExporter.generate_default_filename("/", "txt")
        self.assertEqual(name4, "root_scan_report.txt")

    def test_to_txt_includes_full_hash(self):
        txt = self.exporter.to_txt()
        self.assertIn("VEILFRAME FOLDER ANALYSIS REPORT", txt)
        self.assertIn("Total Files    : 2", txt)
        self.assertIn("file_a.txt", txt)
        # Verify full 64-character hash is in tree and duplicate groups
        self.assertIn(self.full_sha256, txt)
        self.assertNotIn(self.full_sha256[:8] + "...", txt)

    def test_to_csv_includes_full_hash(self):
        csv_str = self.exporter.to_csv()
        self.assertIn("ID,Name,Relative Path", csv_str)
        self.assertIn("file_a.txt", csv_str)
        self.assertIn("file_b.txt", csv_str)
        self.assertIn(self.full_sha256, csv_str)

    def test_to_json_includes_full_hash(self):
        json_str = self.exporter.to_json()
        data = json.loads(json_str)
        self.assertEqual(data["root_path"], "/root")
        self.assertEqual(len(data["files"]), 2)
        self.assertEqual(data["files"][0]["hash_value"], self.full_sha256)
        self.assertEqual(data["stats"]["total_size_bytes"], 2000)

    def test_to_markdown_includes_inventory_and_full_hash(self):
        md = self.exporter.to_markdown()
        self.assertIn("# VeilFrame Folder Analysis", md)
        self.assertIn("| Extension | File Count |", md)
        self.assertIn("Duplicate Group", md)
        self.assertIn("Scanned Files Inventory", md)
        # Verify full 64-character hash is preserved
        self.assertIn(self.full_sha256, md)

    def test_to_html_includes_inventory_and_full_hash(self):
        html_str = self.exporter.to_html()
        self.assertIn("<!DOCTYPE html>", html_str)
        self.assertIn("VeilFrame Folder Analysis", html_str)
        self.assertIn("file_a.txt", html_str)
        self.assertIn("Scanned Files Inventory", html_str)
        # Verify full 64-character hash is present
        self.assertIn(self.full_sha256, html_str)

    def test_build_ascii_tree_full_hash(self):
        tree = self.exporter.build_ascii_tree()
        self.assertIn(self.full_sha256, tree)
        self.assertNotIn("hash1...", tree)

    def test_export_file(self):
        out_html = os.path.join(self.temp_dir, "report.html")
        saved = self.exporter.export(out_html)
        self.assertTrue(os.path.exists(saved))
        self.assertGreater(os.path.getsize(saved), 100)


if __name__ == "__main__":
    unittest.main()
