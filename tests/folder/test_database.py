"""
Unit tests for FolderDatabase repository.
"""

import unittest

from veilframe.folder.database import FolderDatabase
from veilframe.folder.models import (
    FileRecord,
    FolderRecord,
    ScanError,
)


class TestFolderDatabase(unittest.TestCase):
    def setUp(self):
        self.db = FolderDatabase(db_path=":memory:")

        # Ingest mock folder and file records
        folders = [
            FolderRecord(id=1, parent_id=None, name="root", path="/root", relative_path=".", depth=0),
            FolderRecord(id=2, parent_id=1, name="src", path="/root/src", relative_path="src", depth=1),
        ]
        self.db.insert_folders(folders)

        files = [
            FileRecord(id=1, folder_id=1, name="README.md", path="/root/README.md", relative_path="README.md", extension=".md", size=1024, hash_value="hash_a"),
            FileRecord(id=2, folder_id=2, name="main.py", path="/root/src/main.py", relative_path="src/main.py", extension=".py", size=2048, hash_value="hash_b"),
            FileRecord(id=3, folder_id=2, name="utils.py", path="/root/src/utils.py", relative_path="src/utils.py", extension=".py", size=4096, hash_value="hash_c"),
            FileRecord(id=4, folder_id=2, name="copy_main.py", path="/root/src/copy_main.py", relative_path="src/copy_main.py", extension=".py", size=2048, hash_value="hash_b"),
        ]
        self.db.insert_files(files)

    def test_search_by_name(self):
        results = self.db.search_files(name_query="main")
        self.assertEqual(len(results), 2)
        names = [f.name for f in results]
        self.assertIn("main.py", names)
        self.assertIn("copy_main.py", names)

    def test_search_by_extension(self):
        py_files = self.db.search_files(extension="py")
        self.assertEqual(len(py_files), 3)

        md_files = self.db.search_files(extension=".md")
        self.assertEqual(len(md_files), 1)
        self.assertEqual(md_files[0].name, "README.md")

    def test_search_by_size_range(self):
        large_files = self.db.search_files(min_size=2000, max_size=3000)
        self.assertEqual(len(large_files), 2)
        for f in large_files:
            self.assertEqual(f.size, 2048)

    def test_duplicate_groups(self):
        dupes = self.db.get_duplicate_groups()
        self.assertEqual(len(dupes), 1)
        self.assertEqual(dupes[0].hash_value, "hash_b")
        self.assertEqual(dupes[0].file_count, 2)
        self.assertEqual(dupes[0].size, 2048)
        self.assertEqual(dupes[0].wasted_bytes, 2048)

    def test_extension_stats(self):
        ext_stats = self.db.get_extension_stats()
        # .py: 3 files, 2048+4096+2048 = 8192 bytes
        # .md: 1 file, 1024 bytes
        self.assertEqual(len(ext_stats), 2)
        self.assertEqual(ext_stats[0].extension, ".py")
        self.assertEqual(ext_stats[0].file_count, 3)
        self.assertEqual(ext_stats[0].total_size, 8192)

    def test_errors_logging(self):
        err = ScanError(path="/root/locked", error_type="PermissionError", message="Access denied")
        self.db.insert_errors([err])
        errors = self.db.get_all_errors()
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].error_type, "PermissionError")


if __name__ == "__main__":
    unittest.main()
