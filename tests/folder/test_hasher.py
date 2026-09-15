"""
Unit tests for StreamHasher and ParallelHashEngine.
"""

import hashlib
import os
import shutil
import tempfile
import unittest

from veilframe.folder.hasher import (
    ParallelHashEngine,
    compute_file_hash,
    compute_quick_fingerprint,
)
from veilframe.folder.models import FileRecord


class TestHasher(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="veilframe_hash_test_")
        self.test_content = b"VeilFrame High Performance Streaming Hasher Test Content 1234567890" * 1000
        self.test_file = os.path.join(self.temp_dir, "sample.bin")
        with open(self.test_file, "wb") as f:
            f.write(self.test_content)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_compute_file_hash_sha256(self):
        expected = hashlib.sha256(self.test_content).hexdigest()
        actual = compute_file_hash(self.test_file, algorithm="sha256", chunk_size=1024)
        self.assertEqual(actual, expected)

    def test_compute_file_hash_sha1(self):
        expected = hashlib.sha1(self.test_content).hexdigest()
        actual = compute_file_hash(self.test_file, algorithm="sha1", chunk_size=1024)
        self.assertEqual(actual, expected)

    def test_compute_file_hash_md5(self):
        expected = hashlib.md5(self.test_content).hexdigest()
        actual = compute_file_hash(self.test_file, algorithm="md5", chunk_size=1024)
        self.assertEqual(actual, expected)

    def test_compute_quick_fingerprint(self):
        size = len(self.test_content)
        fp = compute_quick_fingerprint(self.test_file, file_size=size, sample_size=128)
        self.assertIsInstance(fp, str)
        self.assertGreater(len(fp), 0)

        # Identical file should have identical fingerprint
        copy_file = os.path.join(self.temp_dir, "sample_copy.bin")
        shutil.copy2(self.test_file, copy_file)
        fp_copy = compute_quick_fingerprint(copy_file, file_size=size, sample_size=128)
        self.assertEqual(fp, fp_copy)

    def test_parallel_hash_engine(self):
        files: list[FileRecord] = []
        for i in range(10):
            p = os.path.join(self.temp_dir, f"file_{i}.bin")
            with open(p, "wb") as f:
                f.write(f"content_{i}".encode("utf-8") * 500)
            files.append(FileRecord(id=i+1, name=f"file_{i}.bin", path=p, size=os.path.getsize(p)))

        engine = ParallelHashEngine(workers=4, algorithm="sha256")
        results = engine.hash_files(files)

        self.assertEqual(len(results), 10)
        for rec, h_val, err in results:
            self.assertIsNotNone(h_val)
            self.assertIsNone(err)
            self.assertEqual(rec.hash_value, h_val)


if __name__ == "__main__":
    unittest.main()
