"""
Unit tests for veilframe folder CLI subcommands.
"""

import io
import json
import os
import shutil
import sys
import tempfile
import unittest

from veilframe.cli import main


class TestFolderCLI(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="veilframe_cli_folder_test_")
        with open(os.path.join(self.temp_dir, "test1.txt"), "wb") as f:
            f.write(b"Hello World")
        with open(os.path.join(self.temp_dir, "test2.txt"), "wb") as f:
            f.write(b"Hello World")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_cli_folder_scan_json(self):
        old_stdout = sys.stdout
        old_argv = sys.argv
        try:
            sys.stdout = io.StringIO()
            sys.argv = ["veilframe", "folder", "scan", self.temp_dir, "--json"]
            main()
            output = sys.stdout.getvalue()
            data = json.loads(output)
            self.assertEqual(data["root_path"], os.path.abspath(self.temp_dir))
            self.assertEqual(data["stats"]["total_files"], 2)
        finally:
            sys.stdout = old_stdout
            sys.argv = old_argv

    def test_cli_folder_dupes_json(self):
        old_stdout = sys.stdout
        old_argv = sys.argv
        try:
            sys.stdout = io.StringIO()
            sys.argv = ["veilframe", "folder", "dupes", self.temp_dir, "--json"]
            main()
            output = sys.stdout.getvalue()
            data = json.loads(output)
            self.assertEqual(data["duplicate_groups_count"], 1)
            self.assertEqual(data["duplicate_wasted_bytes"], 11)
        finally:
            sys.stdout = old_stdout
            sys.argv = old_argv

    def test_cli_folder_stats_json(self):
        old_stdout = sys.stdout
        old_argv = sys.argv
        try:
            sys.stdout = io.StringIO()
            sys.argv = ["veilframe", "folder", "stats", self.temp_dir, "--json"]
            main()
            output = sys.stdout.getvalue()
            data = json.loads(output)
            self.assertEqual(data["total_files"], 2)
            self.assertEqual(data["total_size_bytes"], 22)
        finally:
            sys.stdout = old_stdout
            sys.argv = old_argv

    def test_cli_folder_export(self):
        out_report = os.path.join(self.temp_dir, "out.json")
        old_stdout = sys.stdout
        old_argv = sys.argv
        try:
            sys.stdout = io.StringIO()
            sys.argv = ["veilframe", "folder", "export", self.temp_dir, "-o", out_report]
            main()
            self.assertTrue(os.path.exists(out_report))
            with open(out_report, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.assertEqual(len(data["files"]), 2)
        finally:
            sys.stdout = old_stdout
            sys.argv = old_argv

    def test_cli_folder_ai_aibundle(self):
        out_bundle = os.path.join(self.temp_dir, "test.aibundle")
        old_stdout = sys.stdout
        old_argv = sys.argv
        try:
            sys.stdout = io.StringIO()
            sys.argv = ["veilframe", "folder", "ai", self.temp_dir, "-o", out_bundle, "-f", "aibundle"]
            main()
            self.assertTrue(os.path.exists(out_bundle))
            with open(out_bundle, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("@VEILFRAME_BUNDLE", content)
            self.assertIn("version=1", content)
            self.assertIn("@PROJECT", content)
            self.assertIn("@TREE", content)
            self.assertIn("@FILES", content)
            self.assertIn("@END", content)
        finally:
            sys.stdout = old_stdout
            sys.argv = old_argv


if __name__ == "__main__":
    unittest.main()
