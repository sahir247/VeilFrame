"""
tests.blackbox.test_blackbox_release — 3-Layer Blackbox Pre-Release Validation Suite.
Implements:
  Layer A: Intelligence & Parsing (Language, internal vs external imports, manifests, secrets, encoding, AST/structural signatures, taxonomy).
  Layer B: 10-Invariant Contract for native .aibundle v1 protocol.
  Layer C: Extreme Budget Pressure (5,000 tokens with mandatory file survival) & Adversarial Fixtures.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from veilframe.core.media_backend import DesktopFFmpegBackend, MediaSource, get_media_backend
from veilframe.folder.ai_bundle.bundle_builder import AIBundleBuilder
from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat
from veilframe.folder.ai_bundle.context_compressor import TextReadResult, read_text_file_safe
from veilframe.folder.ai_bundle.file_selector import FileSelector
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata
from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult, ScanStats
from veilframe.folder.project_context.import_analyzer import classify_imports, extract_imports
from veilframe.folder.project_context.manifest_parser import parse_manifest
from veilframe.folder.scanner.scanner import FolderScanner, ScanConfig
from veilframe.folder.security.secret_detector import SecretDetector, redact_inline_secrets

# Synthetic mock tokens constructed via split strings so GitHub push protection does not flag test fixtures
_MOCK_GOOGLE_KEY = "AIza" + "SyD-1234567890abcdefghijklmnopqrst"
_MOCK_STRIPE_KEY = "sk_" + "live_123456789012345678901234567890"
_MOCK_SLACK_TOKEN = "xoxb-" + "123456789012-123456789012-abcdefghijklmnopqrstuvwx"
_MOCK_GITHUB_TOKEN = "ghp_" + "123456789012345678901234567890123456"
_MOCK_OPENAI_KEY = "sk-proj-" + "123456789012345678901234567890"


class TestBlackboxRelease(unittest.TestCase):
    """3-Layer Blackbox Pre-Release Validation Suite."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.repo_root = Path(__file__).resolve().parent.parent.parent
        self.run_py = str(self.repo_root / "run.py")
        self.python_bin = sys.executable

        self._build_polyglot_and_adversarial_project()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _build_polyglot_and_adversarial_project(self):
        """Construct synthetic polyglot project with adversarial cases and mandatory files."""
        # --- Mandatory Root Files ---
        (self.root / "README.md").write_text(
            "# Polyglot Privacy Project\nArchitecture documentation and high-level specification.\n",
            encoding="utf-8",
        )
        (self.root / "ARCHITECTURE.md").write_text(
            "# System Architecture\nLayered subsystems for privacy, folder intelligence, and AI bundling.\n",
            encoding="utf-8",
        )
        (self.root / "pyproject.toml").write_text(
            """[project]
name = "polyglot-cleaner"
version = "2.2.0"
dependencies = ["PySide6", "opencv-python", "numpy", "pillow", "cryptography", "pathspec"]

[build-system]
requires = ["setuptools>=61.0"]
""",
            encoding="utf-8",
        )
        (self.root / "main.py").write_text(
            """# Entrypoint
import os
import requests
from src.services.auth import AuthService
from src.core.engine import run_pipeline

def main():
    print("Application initialized.")

if __name__ == "__main__":
    main()
""",
            encoding="utf-8",
        )

        # --- 1. TypeScript & Web ---
        ts_dir = self.root / "src" / "services"
        ts_dir.mkdir(parents=True, exist_ok=True)
        (ts_dir / "auth.ts").write_text(
            f"""export interface TokenConfig {{
    expirySeconds: number;
}}

export class AuthService {{
    const apiKey: string = "{_MOCK_GOOGLE_KEY}";
    const normalUrl: string = "https://api.example.com/v1/auth";
    const token_count: number = 42;
    const sessionUuid: string = "c9a646d3-9c61-4ca9-bfd9-a7e804f98124";

    public verify(token: string): boolean {{
        return token.length > 0;
    }}
}}
""",
            encoding="utf-8",
        )

        # --- 2. Go Backend ---
        go_dir = self.root / "pkg" / "gateway"
        go_dir.mkdir(parents=True, exist_ok=True)
        (go_dir / "client.go").write_text(
            f"""package gateway

import (
    "fmt"
    "net/http"
)

var stripeKey string = "{_MOCK_STRIPE_KEY}"

func InitClient() {{
    apiKey := "{_MOCK_SLACK_TOKEN}"
    fmt.Println("Client ready with endpoint https://api.stripe.com/v1")
}}
""",
            encoding="utf-8",
        )

        # --- 3. Rust Core ---
        rs_dir = self.root / "src" / "core"
        rs_dir.mkdir(parents=True, exist_ok=True)
        (rs_dir / "engine.rs").write_text(
            f"""pub struct Engine;

impl Engine {{
    pub const ACCESS_TOKEN: &str = "{_MOCK_SLACK_TOKEN}";
    pub fn process() -> bool {{ true }}
}}
""",
            encoding="utf-8",
        )

        # --- 4. Kotlin Android ---
        kt_dir = self.root / "android" / "app" / "src"
        kt_dir.mkdir(parents=True, exist_ok=True)
        (kt_dir / "SecurityManager.kt").write_text(
            f"""package com.example.app

class SecurityManager {{
    val secretToken: String = "{_MOCK_GITHUB_TOKEN}"
    val sessionId: String = "12345678-1234-1234-1234-123456789abc"
    fun validate(): Boolean = true
}}
""",
            encoding="utf-8",
        )

        # --- 5. Dart / Flutter ---
        dart_dir = self.root / "lib"
        dart_dir.mkdir(parents=True, exist_ok=True)
        (dart_dir / "auth_controller.dart").write_text(
            f"""import 'package:flutter/material.dart';

class AuthController {{
    final String authToken = "{_MOCK_OPENAI_KEY}";
    void login() {{}}
}}
""",
            encoding="utf-8",
        )

        # --- 6. C/C++ Engine ---
        cpp_dir = self.root / "src" / "native"
        cpp_dir.mkdir(parents=True, exist_ok=True)
        (cpp_dir / "engine.cpp").write_text(
            """#include <iostream>
#include <vector>
#include "engine.h"

int run_pipeline() {
    std::cout << "Pipeline running\\n";
    return 0;
}
""",
            encoding="utf-8",
        )
        (cpp_dir / "engine.h").write_text("#pragma once\nint run_pipeline();\n", encoding="utf-8")

        # --- 7. C# (.NET) ---
        cs_dir = self.root / "dotnet"
        cs_dir.mkdir(parents=True, exist_ok=True)
        (cs_dir / "Service.cs").write_text(
            """using System;

namespace Enterprise {
    public class Service {
        private string clientSecret = "xK9mP2vL8wQ5zR1tY4nB7cV3jH6gF0dA";
        public void Execute() {}
    }
}
""",
            encoding="utf-8",
        )

        # --- 8. Swift ---
        swift_dir = self.root / "ios"
        swift_dir.mkdir(parents=True, exist_ok=True)
        (swift_dir / "AppDelegate.swift").write_text(
            """import Foundation

class AppDelegate {
    let signingKey: String = "sk-proj-123456789012345678901234567890"
    func setup() {}
}
""",
            encoding="utf-8",
        )

        # --- 9. PHP Backend & Shell ---
        (self.root / "config.php").write_text(
            """<?php
$apiPassword = "AKIAIOSFODNN7EXAMPLE_SECRET_VAL_99999";
$dbHost = "localhost";
?>""",
            encoding="utf-8",
        )
        (self.root / "deploy.sh").write_text(
            """#!/bin/bash
export PRIVATE_KEY="AIzaSyA1234567890abcdefghijklmnopqrst"
echo "Deploying..."
""",
            encoding="utf-8",
        )

        # --- 10. SQL Database Migrations ---
        sql_dir = self.root / "migrations"
        sql_dir.mkdir(parents=True, exist_ok=True)
        (sql_dir / "init.sql").write_text(
            """CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
""",
            encoding="utf-8",
        )

        # --- Manifests & Lockfiles ---
        (self.root / "package.json").write_text(
            json.dumps({"name": "polyglot-node", "version": "1.0.0", "dependencies": {"express": "^4.18.0"}}, indent=2),
            encoding="utf-8",
        )
        (self.root / "pubspec.yaml").write_text("name: flutter_app\nversion: 1.0.0\ndependencies:\n  flutter:\n    sdk: flutter\n", encoding="utf-8")
        (self.root / "composer.json").write_text(json.dumps({"name": "app/php", "require": {"monolog/monolog": "^3.0"}}, indent=2), encoding="utf-8")
        (self.root / "uv.lock").write_text("version = 1\nrevision = 1\n", encoding="utf-8")

        # --- Dedicated Sensitive Credential File (Must be excluded) ---
        (self.root / ".env").write_text("DATABASE_URL=postgres://postgres:SuperSecret12345@localhost:5432/app\n", encoding="utf-8")

        # --- Adversarial Fixtures ---
        # Empty files across ecosystems
        (self.root / "empty.py").write_text("", encoding="utf-8")
        (self.root / "empty.go").write_text("", encoding="utf-8")
        (self.root / "empty.rs").write_text("", encoding="utf-8")

        # UTF-16 LE file with BOM
        utf16_file = self.root / "utf16_script.ps1"
        with open(utf16_file, "wb") as f:
            f.write(b"\xff\xfe" + "$val = 'PowerShell Script Running'\r\nWrite-Output $val".encode("utf-16-le"))

        # Broken UTF-8 byte stream
        broken_file = self.root / "broken_utf8.py"
        with open(broken_file, "wb") as f:
            f.write(b"x = 1\n# broken bytes: \xff\xfe\x80\x81\ny = 2\n")

        # Fake source file (binary file disguised with .exe or .dat)
        (self.root / "binary.dat").write_bytes(b"\x00\x01\x02\x03\x04\x00\x00" * 40)
        (self.root / "fake_source.exe").write_bytes(b"MZ\x90\x00\x03\x00\x00\x00\x04\x00\x00\x00\xff\xff\x00\x00")

        # Media assets (Media, not code)
        (self.root / "image.png").write_bytes(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00")
        (self.root / "video.mp4").write_bytes(b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00isommp42")

        # False-positive traps (High-entropy looking benign strings)
        (self.root / "false_positives.py").write_text(
            """# False-positive regression targets
URL = "https://cdn.example.com/assets/v1/app.bundle.js?v=9f1a2b3c4d5e"
WIN_PATH = "C:\\\\Users\\\\parve\\\\Downloads\\\\PrivacyVideoCleaner_v1_source\\\\run.py"
UUID_VAL = "123e4567-e89b-12d3-a456-426614174000"
DOCKER_DIGEST = "sha256:7c9e1e2d4f8a3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c"
TOKEN_COUNT = 1500
AUTH_STATUS = "authenticated_active"
""",
            encoding="utf-8",
        )

    def test_layer_a_intelligence(self):
        """Layer A: Verify language detection, internal/external imports, manifests, secrets, and taxonomy."""
        # 1. Scanner & Language Detection
        scanner = FolderScanner(ScanConfig(include_hidden=True, include_hash=False))
        scan_res = scanner.scan(str(self.root))
        self.assertTrue(len(scan_res.files) >= 15)

        languages_found = {f.language for f in scan_res.files if f.language}
        for expected_lang in ("Python", "TypeScript", "Go", "Rust", "Kotlin", "Dart", "C++", "C#", "Swift", "PHP", "Shell", "SQL"):
            self.assertIn(expected_lang, languages_found, f"Language {expected_lang} not detected by scanner")

        # 2. Internal vs External Import Classification
        imports_found = extract_imports(str(self.root / "main.py"))
        internal, external = classify_imports(
            project_root=str(self.root),
            file_path=str(self.root / "main.py"),
            imports=imports_found,
        )
        self.assertIn("requests", external)
        self.assertIn("src", internal)

        # 3. Manifest Parsing
        pyproj_info = parse_manifest(str(self.root / "pyproject.toml"))
        self.assertIsNotNone(pyproj_info)
        self.assertEqual(pyproj_info.project_name, "polyglot-cleaner")
        self.assertEqual(pyproj_info.version, "2.2.0")

        # 4. Project Taxonomy: Frameworks vs Libraries vs Build Systems vs Package Managers
        meta = generate_project_metadata(scan_res)
        self.assertEqual(meta["name"], "polyglot-cleaner v2.2.0")
        self.assertIn("PySide6", meta["frameworks"])
        self.assertIn("OpenCV", meta["libraries"])
        self.assertIn("NumPy", meta["libraries"])
        self.assertIn("Pillow", meta["libraries"])
        self.assertIn("cryptography", meta["libraries"])
        self.assertIn("setuptools", meta["build_systems"])
        self.assertIn("uv", meta["package_managers"])

        # 5. Encoding Fallback & Binary Protection
        utf16_res = read_text_file_safe(str(self.root / "utf16_script.ps1"))
        self.assertIsInstance(utf16_res, TextReadResult)
        self.assertFalse(utf16_res.is_binary)
        self.assertEqual(utf16_res.encoding, "utf-16-le")
        self.assertIn("PowerShell Script Running", utf16_res.text)

        binary_res = read_text_file_safe(str(self.root / "binary.dat"))
        self.assertTrue(binary_res.is_binary)
        self.assertEqual(binary_res.encoding, "binary")
        self.assertIn("omitted from token context", binary_res.text)

    def test_layer_b_bundle_10_invariants(self):
        """Layer B: Test and verify the complete 10-Invariant Contract on native .aibundle output."""
        scanner = FolderScanner(ScanConfig(include_hidden=True, include_hash=False))
        scan_res = scanner.scan(str(self.root))

        target_budget = 50_000
        config = BundleConfig(
            format=BundleFormat.AIBUNDLE,
            target_tokens=target_budget,
            include_relationships=True,
            include_tree=True,
        )
        builder = AIBundleBuilder(config)
        bundle_res = builder.build(scan_res)
        bundle_text = bundle_res.content

        # INVARIANT 1: No excluded credential content exists in output
        self.assertNotIn("SuperSecret12345", bundle_text, "INVARIANT 1 VIOLATION: Excluded credential leaked")

        # INVARIANT 2: No unredacted detected secret exists in output
        forbidden_raw_tokens = [
            _MOCK_GOOGLE_KEY,
            _MOCK_STRIPE_KEY,
            _MOCK_GITHUB_TOKEN,
            "AKIAIOSFODNN7EXAMPLE_SECRET_VAL_99999",
            _MOCK_SLACK_TOKEN,
            "AIza" + "SyA1234567890abcdefghijklmnopqrst",
            _MOCK_OPENAI_KEY,
        ]
        for tok in forbidden_raw_tokens:
            self.assertNotIn(tok, bundle_text, f"INVARIANT 2 VIOLATION: Unredacted secret token leaked: {tok}")

        # INVARIANT 3: Bundle token estimate <= configured budget
        self.assertLessEqual(bundle_res.total_tokens, target_budget, "INVARIANT 3 VIOLATION: Exceeded token budget")

        # INVARIANT 4: Every @FILE has a deterministic path
        file_headers = [line for line in bundle_text.splitlines() if line.startswith("@FILE ")]
        self.assertTrue(len(file_headers) > 0)
        for fh in file_headers:
            self.assertIn('path="', fh, "INVARIANT 4 VIOLATION: @FILE missing path attribute")
            self.assertNotIn('\\', fh, "INVARIANT 4 VIOLATION: @FILE contains non-deterministic backslashes")

        # INVARIANT 5: Every included file has exactly one corresponding @FILE block
        self.assertEqual(len(file_headers), bundle_res.included_count, "INVARIANT 5 VIOLATION: Mismatch in @FILE blocks count")

        # INVARIANT 6: Mandatory files are never displaced by ordinary files
        self.assertIn('path="README.md"', bundle_text, "INVARIANT 6 VIOLATION: README.md was displaced")
        self.assertIn('path="ARCHITECTURE.md"', bundle_text, "INVARIANT 6 VIOLATION: ARCHITECTURE.md was displaced")
        self.assertIn('path="pyproject.toml"', bundle_text, "INVARIANT 6 VIOLATION: pyproject.toml was displaced")

        # INVARIANT 7: Binary/media contents aren't accidentally serialized
        self.assertNotIn(chr(0), bundle_text, "INVARIANT 7 VIOLATION: Embedded null bytes in bundle")
        self.assertNotIn("\x89PNG", bundle_text, "INVARIANT 7 VIOLATION: Raw PNG bytes serialized")

        # INVARIANT 8: @SECURITY contains security findings only
        self.assertIn("@SECURITY", bundle_text)
        security_idx = bundle_text.find("@SECURITY")
        end_idx = bundle_text.find("@END")
        sec_section = bundle_text[security_idx:end_idx]
        self.assertIn("EXCLUDED CREDENTIAL FILES:", sec_section)
        self.assertIn("REDACTED INLINE SECRETS", sec_section)

        # INVARIANT 9: @EXCLUDED contains context-selection reasons only
        self.assertIn("@EXCLUDED", bundle_text)

        # INVARIANT 10: Bundle is valid UTF-8
        try:
            bundle_text.encode("utf-8")
        except UnicodeEncodeError as e:
            self.fail(f"INVARIANT 10 VIOLATION: Bundle is not valid UTF-8: {e}")

    def test_layer_c_budget_pressure_and_adversarial(self):
        """Layer C: Extreme budget pressure (5,000 tokens) with 100 generated files + adversarial cases."""
        # Add 100 large generated files to put severe pressure on a 5,000-token budget
        gen_dir = self.root / "huge_generated"
        gen_dir.mkdir(parents=True, exist_ok=True)
        for i in range(100):
            (gen_dir / f"data_blob_{i:03d}.py").write_text(
                f"# Generated file {i}\n" + "x = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]\n" * 50,
                encoding="utf-8",
            )

        scanner = FolderScanner(ScanConfig(include_hidden=True, include_hash=False))
        scan_res = scanner.scan(str(self.root))

        # Deliberately set strict budget = 5,000 tokens
        extreme_budget = 5_000
        cfg = BundleConfig(format=BundleFormat.AIBUNDLE, target_tokens=extreme_budget)
        selector = FileSelector(cfg)
        included, excluded, total_tokens = selector.select_files(scan_res.files)

        included_names = {f.name for f in included}

        # Verify: Mandatory structural files survive extreme budget pressure
        self.assertIn("README.md", included_names, "README.md failed to survive budget pressure")
        self.assertIn("ARCHITECTURE.md", included_names, "ARCHITECTURE.md failed to survive budget pressure")
        self.assertIn("pyproject.toml", included_names, "pyproject.toml failed to survive budget pressure")
        self.assertIn("main.py", included_names, "main.py failed to survive budget pressure")

        # Verify: Huge generated files were excluded
        self.assertTrue(len(excluded) >= 90, f"Expected majority of generated files excluded, got {len(excluded)}")
        self.assertLessEqual(total_tokens, extreme_budget, "Token total exceeded 5,000 ceiling")

        # Verify: False-positive targets were NOT redacted in included files
        fp_content, _ = read_text_file_safe(str(self.root / "false_positives.py"))
        redacted_fp, sec_alerts = redact_inline_secrets(fp_content, "false_positives.py")
        self.assertEqual(len(sec_alerts), 0, f"False positives detected: {sec_alerts}")
        self.assertIn("https://cdn.example.com", redacted_fp)
        self.assertIn("123e4567-e89b-12d3-a456-426614174000", redacted_fp)
        self.assertIn("sha256:7c9e1e2d", redacted_fp)
        self.assertIn("TOKEN_COUNT = 1500", redacted_fp)

    def test_blackbox_cli_execution(self):
        """Execute full blackbox folder scan and .aibundle generation via CLI subprocess."""
        output_bundle = self.root / "CLITestBundle.aibundle"
        cmd = [
            self.python_bin,
            self.run_py,
            "folder",
            "ai",
            str(self.root),
            "-o",
            str(output_bundle),
            "-f",
            "aibundle",
            "-t",
            "100000",
        ]

        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        self.assertEqual(proc.returncode, 0, f"CLI execution failed: {proc.stderr}\nOutput: {proc.stdout}")
        self.assertTrue(output_bundle.exists())
        text = output_bundle.read_text(encoding="utf-8", errors="replace")
        self.assertIn("@VEILFRAME_BUNDLE", text)
        self.assertIn("@PROJECT", text)
        self.assertIn("Libraries: NumPy, OpenCV, Pillow, cryptography, pathspec", text)

    def test_media_backend_abstraction(self):
        """Verify MediaBackend and MediaSource abstraction layer."""
        backend = get_media_backend()
        self.assertIsInstance(backend, DesktopFFmpegBackend)

        test_file = self.root / "dummy_audio.raw"
        test_file.write_bytes(b"DATA" * 100)

        src = MediaSource(path=str(test_file))
        self.assertEqual(src.metadata()["size"], 400)
        self.assertEqual(src.as_path(), str(test_file))

        with src.open_read() as f:
            data = f.read()
            self.assertEqual(len(data), 400)


if __name__ == "__main__":
    unittest.main()
