"""
Unit tests for veilframe.folder.ai_bundle.bundle_builder across all output formats.
"""

import io
import json
import os
import tempfile
import unittest
import zipfile

from veilframe.folder.ai_bundle.bundle_builder import AIBundleBuilder
from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat
from veilframe.folder.models.classification import AIAction, ClassificationResult, FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult, ScanStats


class TestBundleBuilder(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        
        # Create dummy project files
        self.f_main_path = os.path.join(self.tmp_dir.name, "main.py")
        with open(self.f_main_path, "w") as f:
            f.write("def main():\n    print('hello world')\n")

        self.f_readme_path = os.path.join(self.tmp_dir.name, "README.md")
        with open(self.f_readme_path, "w") as f:
            f.write("# Sample Project\nDocumentation.\n")

        self.f_sec_path = os.path.join(self.tmp_dir.name, ".env")
        with open(self.f_sec_path, "w") as f:
            f.write("SECRET_KEY=123456789\n")

        f_main = FileRecord(
            id=1, name="main.py", path=self.f_main_path, relative_path="main.py",
            size=len("def main():\n    print('hello world')\n"),
            language="Python", is_entry_point=True, priority_score=100,
            classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE)
        )
        f_readme = FileRecord(
            id=2, name="README.md", path=self.f_readme_path, relative_path="README.md",
            size=len("# Sample Project\nDocumentation.\n"),
            language="Markdown", priority_score=98,
            classification=ClassificationResult(category=FileCategory.DOCUMENTATION, action=AIAction.INCLUDE)
        )
        f_sec = FileRecord(
            id=3, name=".env", path=self.f_sec_path, relative_path=".env",
            size=len("SECRET_KEY=123456789\n"),
            is_secret=True, priority_score=0,
            classification=ClassificationResult(category=FileCategory.SECRET, action=AIAction.WARN, reason="Sensitive env file")
        )

        stats = ScanStats(
            root_path=self.tmp_dir.name,
            total_files=3,
            total_size_bytes=f_main.size + f_readme.size + f_sec.size,
            detected_ecosystems={"python"},
        )

        self.scan_result = ScanResult(
            config=None,
            root_path=self.tmp_dir.name,
            files=[f_main, f_readme, f_sec],
            stats=stats,
        )

    def tearDown(self):
        self.tmp_dir.cleanup()

    def test_native_aibundle_format(self):
        cfg = BundleConfig(format=BundleFormat.AIBUNDLE)
        builder = AIBundleBuilder(cfg)
        res = builder.build(self.scan_result)

        self.assertFalse(res.is_binary)
        content = str(res.content)
        self.assertIn("@VEILFRAME_BUNDLE", content)
        self.assertIn("version=1", content)
        self.assertIn("@PROJECT", content)
        self.assertIn("@TREE", content)
        self.assertIn("@FILES", content)
        self.assertIn('@FILE id="F001"', content)
        self.assertIn('path="main.py"', content)
        self.assertIn("<<<", content)
        self.assertIn(">>>", content)
        self.assertIn("@EXCLUDED", content)
        self.assertIn("@SECURITY", content)
        self.assertIn("@END", content)
        # .env must be excluded due to secret status
        self.assertIn(".env", content)
        self.assertEqual(res.included_count, 2)
        self.assertEqual(res.excluded_count, 1)

    def test_html_format(self):
        cfg = BundleConfig(format=BundleFormat.HTML)
        builder = AIBundleBuilder(cfg)
        res = builder.build(self.scan_result)

        self.assertFalse(res.is_binary)
        content = str(res.content)
        self.assertIn("<!DOCTYPE html>", content)
        self.assertIn("VeilFrame AI Project Bundle", content)
        self.assertIn("main.py", content)
        self.assertEqual(res.included_count, 2)
        self.assertEqual(res.excluded_count, 1)

    def test_markdown_format(self):
        cfg = BundleConfig(format=BundleFormat.MARKDOWN)
        builder = AIBundleBuilder(cfg)
        res = builder.build(self.scan_result)

        self.assertFalse(res.is_binary)
        content = str(res.content)
        self.assertIn("# ", content)
        self.assertIn("```python", content)
        self.assertIn("def main():", content)

    def test_json_format(self):
        cfg = BundleConfig(format=BundleFormat.JSON)
        builder = AIBundleBuilder(cfg)
        res = builder.build(self.scan_result)

        self.assertFalse(res.is_binary)
        parsed = json.loads(str(res.content))
        self.assertEqual(parsed["format_version"], 1)
        self.assertEqual(parsed["statistics"]["included_files_count"], 2)
        self.assertEqual(len(parsed["files"]), 2)

    def test_zip_format(self):
        cfg = BundleConfig(format=BundleFormat.ZIP)
        builder = AIBundleBuilder(cfg)
        res = builder.build(self.scan_result)

        self.assertTrue(res.is_binary)
        zip_bytes = bytes(res.content)
        with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
            namelist = zf.namelist()
            self.assertIn("AI_PROJECT_BUNDLE.md", namelist)
            self.assertIn("main.py", namelist)
            self.assertIn("README.md", namelist)
    def test_no_truncation_on_included_files(self):
        # Create a large source file
        large_lines = [f"def function_{i}():\n    return {i} * 42\n" for i in range(300)]
        large_content = "\n".join(large_lines)
        large_path = os.path.join(self.tmp_dir.name, "large_service.py")
        with open(large_path, "w") as f:
            f.write(large_content)

        f_large = FileRecord(
            id=10, name="large_service.py", path=large_path, relative_path="large_service.py",
            size=len(large_content), language="Python", priority_score=95,
            classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE)
        )

        scan_result = ScanResult(
            config=None,
            root_path=self.tmp_dir.name,
            files=[f_large],
            stats=ScanStats(root_path=self.tmp_dir.name, total_files=1, total_size_bytes=len(large_content))
        )

        cfg = BundleConfig(format=BundleFormat.AIBUNDLE)
        builder = AIBundleBuilder(cfg)
        res = builder.build(scan_result)

        content = str(res.content)
        self.assertNotIn("TRUNCATED", content)
        self.assertIn("function_0", content)
        self.assertIn("function_299", content)
        self.assertEqual(res.included_count, 1)


if __name__ == "__main__":
    unittest.main()
