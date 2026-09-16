"""
Unit tests for veilframe.folder.metadata.language_detector.
"""

import os
import tempfile
import unittest

from veilframe.folder.metadata.language_detector import detect_language


class TestLanguageDetector(unittest.TestCase):
    def test_extension_detection(self):
        self.assertEqual(detect_language("main.py"), "Python")
        self.assertEqual(detect_language("index.ts"), "TypeScript")
        self.assertEqual(detect_language("app.jsx"), "JavaScript")
        self.assertEqual(detect_language("lib.rs"), "Rust")
        self.assertEqual(detect_language("server.go"), "Go")
        self.assertEqual(detect_language("Program.cs"), "C#")
        self.assertEqual(detect_language("main.cpp"), "C++")
        self.assertEqual(detect_language("main.c"), "C")
        self.assertEqual(detect_language("app.rb"), "Ruby")
        self.assertEqual(detect_language("script.sh"), "Shell")
        self.assertEqual(detect_language("query.sql"), "SQL")

    def test_exact_filename_detection(self):
        self.assertEqual(detect_language("Dockerfile"), "Dockerfile")
        self.assertEqual(detect_language("Makefile"), "Makefile")
        self.assertEqual(detect_language("CMakeLists.txt"), "CMake")
        self.assertEqual(detect_language("Jenkinsfile"), "Groovy")
        self.assertEqual(detect_language("Gemfile"), "Ruby")

    def test_shebang_detection(self):
        with tempfile.NamedTemporaryFile(mode="w", delete=False, suffix=".unknown") as tmp:
            tmp.write("#!/usr/bin/env python3\nprint('hello')\n")
            tmp_path = tmp.name

        try:
            detected = detect_language(tmp_path)
            self.assertEqual(detected, "Python")
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def test_unknown_fallback(self):
        self.assertIsNone(detect_language("mystery.xyz123"))


if __name__ == "__main__":
    unittest.main()
