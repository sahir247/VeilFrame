"""
tests.folder.test_ai_bundle_fixes — Verifies fixes for AI bundle UI freezing, secret classification, context budget messaging, and metadata intelligence.
"""

import os
import tempfile
import unittest
from unittest.mock import MagicMock

from veilframe.folder.ai_bundle.bundle_builder import AIBundleBuilder, AIBundleResult
from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat
from veilframe.folder.ai_bundle.file_selector import FileSelector
from veilframe.folder.ai_bundle.formats.aibundle import render_native_aibundle
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata
from veilframe.folder.models.classification import AIAction, ClassificationResult, FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult, ScanStats
from veilframe.folder.security.secret_detector import SecretDetector


class TestAIBundleFixes(unittest.TestCase):
    """Test suite ensuring regressions do not recur for AI Bundle generation."""

    def test_secret_detection_false_positive_prevention(self):
        """Ensure benign code, method chains, URLs, and package hashes trigger 0 false positives."""
        detector = SecretDetector()

        benign_samples = [
            'h_out = ctypes.windll.kernel32.GetStdHandle(-11)',
            'if ctypes.windll.kernel32.AttachConsole(-1):',
            '[![Python](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://python.org)',
            '[[package]]\nname = "cryptography"\nhash = "sha256:1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b"',
            'a = Analysis(["run.py"], pathex=["c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source"])',
            'chmod +x ./build.sh && echo "Build finished successfully"',
        ]

        for sample in benign_samples:
            alerts = detector.scan_content(sample, file_path="sample.py")
            self.assertEqual(len(alerts), 0, f"False positive triggered for: {sample}")

    def test_inline_secret_included_with_redaction_not_excluded(self):
        """Python source containing a secret must be included with redaction, not excluded."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            secret_file = os.path.join(tmp_dir, "auth_service.py")
            with open(secret_file, "w", encoding="utf-8") as f:
                f.write('api_key = "AKIA1234567890ABCDEF"\ndef get_auth():\n    return api_key\n')

            detector = SecretDetector()
            f_rec = FileRecord(
                id=1,
                name="auth_service.py",
                path=secret_file,
                relative_path="auth_service.py",
                size=len('api_key = "AKIA1234567890ABCDEF"\ndef get_auth():\n    return api_key\n'),
                language="Python",
                classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE),
            )
            alerts = detector.scan_file(f_rec)
            self.assertTrue(len(alerts) >= 1)
            # Must NOT be marked as dedicated secret file
            self.assertFalse(f_rec.is_secret)
            self.assertEqual(f_rec.effective_category, FileCategory.SOURCE)

            # Test file selector includes it
            cfg = BundleConfig(format=BundleFormat.AIBUNDLE, target_tokens=10000)
            selector = FileSelector(cfg)
            included, excluded, tokens = selector.select_files([f_rec])
            self.assertEqual(len(included), 1)
            self.assertEqual(len(excluded), 0)

            # Test bundle builder redacts it
            builder = AIBundleBuilder(cfg)
            scan_res = ScanResult(
                config=None,
                root_path=tmp_dir,
                files=[f_rec],
                stats=ScanStats(root_path=tmp_dir, total_files=1),
            )
            res = builder.build(scan_res)
            self.assertIn("[REDACTED]", res.content)
            self.assertNotIn("AKIA1234567890ABCDEF", res.content)
            self.assertIn("REDACTED INLINE SECRETS", res.content)

    def test_dedicated_credential_file_excluded(self):
        """Dedicated credential files like .env or id_rsa must be excluded."""
        f_env = FileRecord(
            id=1,
            name=".env",
            path=".env",
            relative_path=".env",
            size=50,
            is_secret=True,
            classification=ClassificationResult(category=FileCategory.SECRET, action=AIAction.WARN),
        )

        cfg = BundleConfig(format=BundleFormat.AIBUNDLE, target_tokens=10000)
        selector = FileSelector(cfg)
        included, excluded, tokens = selector.select_files([f_env])
        self.assertEqual(len(included), 0)
        self.assertEqual(len(excluded), 1)
        self.assertIn("Security exclusion", excluded[0][1])

    def test_token_budget_messaging(self):
        """Context budget exhaustion must report remaining budget, not misleading 200k ceiling."""
        f1 = FileRecord(
            id=1,
            name="big_code.py",
            path="big_code.py",
            relative_path="big_code.py",
            size=4000,
            token_count=1000,
            classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE),
        )
        f2 = FileRecord(
            id=2,
            name="ROADMAP.md",
            path="ROADMAP.md",
            relative_path="ROADMAP.md",
            size=2000,
            token_count=500,
            classification=ClassificationResult(category=FileCategory.DOCUMENTATION, action=AIAction.INCLUDE),
        )

        cfg = BundleConfig(target_tokens=1200)
        selector = FileSelector(cfg)
        included, excluded, tokens = selector.select_files([f1, f2])

        self.assertEqual(len(included), 1)
        self.assertEqual(len(excluded), 1)
        excluded_reason = excluded[0][1]
        self.assertIn("Context budget exhausted", excluded_reason)
        self.assertIn("budget remaining: 200 tokens", excluded_reason)
        self.assertNotIn("Exceeds target token ceiling (1,200 tokens)", excluded_reason)

    def test_project_metadata_manifest_enrichment(self):
        """Verify @PROJECT metadata discovers frameworks, build systems, package managers, and version."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            pyproject_path = os.path.join(tmp_dir, "pyproject.toml")
            with open(pyproject_path, "w", encoding="utf-8") as f:
                f.write(
                    '[project]\n'
                    'name = "test-project"\n'
                    'version = "1.5.0"\n'
                    'dependencies = ["PySide6", "opencv-python", "numpy"]\n'
                    '[build-system]\n'
                    'requires = ["setuptools>=61.0"]\n'
                )

            uv_lock_path = os.path.join(tmp_dir, "uv.lock")
            with open(uv_lock_path, "w", encoding="utf-8") as f:
                f.write('version = 1\n')

            files = [
                FileRecord(id=1, name="pyproject.toml", path=pyproject_path, relative_path="pyproject.toml", size=200),
                FileRecord(id=2, name="uv.lock", path=uv_lock_path, relative_path="uv.lock", size=50),
            ]

            scan_res = ScanResult(
                config=None,
                root_path=tmp_dir,
                files=files,
                stats=ScanStats(root_path=tmp_dir, total_files=2),
            )

            meta = generate_project_metadata(scan_res)
            self.assertEqual(meta["name"], "test-project v1.5.0")
            self.assertIn("PySide6", meta["frameworks"])
            self.assertIn("OpenCV", meta["libraries"])
            self.assertIn("NumPy", meta["libraries"])
            self.assertIn("setuptools", meta["build_systems"])
            self.assertIn("uv", meta["package_managers"])

    def test_bundle_generation_worker(self):
        """Verify BundleGenerationWorker runs in background thread and emits progress."""
        from tests.conftest import get_or_create_test_qapp
        app = get_or_create_test_qapp()
        from veilframe.gui.folder.ai_program_lister_panel import BundleGenerationWorker

        with tempfile.TemporaryDirectory() as tmp_dir:
            file_p = os.path.join(tmp_dir, "test.py")
            with open(file_p, "w", encoding="utf-8") as f:
                f.write("print('hello')\n")
            f_rec = FileRecord(id=1, name="test.py", path=file_p, relative_path="test.py", size=15)
            scan_res = ScanResult(
                config=None,
                root_path=tmp_dir,
                files=[f_rec],
                stats=ScanStats(root_path=tmp_dir, total_files=1),
            )

            builder = AIBundleBuilder(BundleConfig())
            worker = BundleGenerationWorker(builder, scan_res)

            progress_events = []
            results = []
            worker.progress.connect(lambda c, t, m: progress_events.append((c, t, m)))
            worker.finished.connect(lambda res: results.append(res))

            worker.run()

            self.assertEqual(len(results), 1)
            self.assertIsInstance(results[0], AIBundleResult)
            self.assertTrue(len(progress_events) > 0)

    def test_positive_and_negative_secret_cases(self):
        """
        Verify both positive and negative secret cases according to the specification:
        Positive:
          - REAL AWS key -> detected
          - REAL private key -> detected
          - .env credentials -> detected & excluded
          - JWT token -> detected
          - High-entropy credential assignment -> detected
        Negative:
          - URL -> NOT detected
          - Windows path -> NOT detected
          - SHA256 lockfile hash -> NOT detected
          - Python method call -> NOT detected
          - UUID -> NOT detected
          - Normal high-entropy ID -> NOT detected
          - token_count -> NOT detected
        """
        from veilframe.folder.security.secret_detector import SecretDetector, redact_inline_secrets
        from veilframe.folder.security.sensitive_files import is_sensitive_filepath

        detector = SecretDetector()

        # --- POSITIVE CASES ---
        # 1. AWS Key
        aws_code = 'AWS_KEY = "AKIAIOSFODNN7EXAMPLE"'
        alerts = detector.scan_content(aws_code, "config.py")
        self.assertEqual(len(alerts), 1)
        redacted, _ = redact_inline_secrets(aws_code, "config.py")
        self.assertEqual(redacted, 'AWS_KEY = "[REDACTED]"')

        # 2. Private Key Header
        pkey_code = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0n...\n-----END RSA PRIVATE KEY-----"
        alerts = detector.scan_content(pkey_code, "key.py")
        self.assertTrue(len(alerts) >= 1)
        redacted, _ = redact_inline_secrets(pkey_code, "key.py")
        self.assertIn("[REDACTED_PRIVATE_KEY]", redacted)
        self.assertNotIn("MIIEowIBAAKCAQEA0n", redacted)

        # 3. .env credentials
        is_sens, _ = is_sensitive_filepath(".env")
        self.assertTrue(is_sens)
        is_sens, _ = is_sensitive_filepath(".env.production")
        self.assertTrue(is_sens)

        # 4. JWT Token
        jwt_code = 'AUTH_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"'
        alerts = detector.scan_content(jwt_code, "auth.py")
        self.assertEqual(len(alerts), 1)
        redacted, _ = redact_inline_secrets(jwt_code, "auth.py")
        self.assertIn('[REDACTED]', redacted)

        # 5. Generic high-entropy secret assignment
        secret_code = 'CLIENT_SECRET = "xK9mP2vL8wQ5zR1tY4nB7cV3jH6gF0dA"'
        alerts = detector.scan_content(secret_code, "app.py")
        self.assertEqual(len(alerts), 1)
        redacted, _ = redact_inline_secrets(secret_code, "app.py")
        self.assertEqual(redacted, 'CLIENT_SECRET = "[REDACTED]"')

        # --- NEGATIVE CASES (Must trigger ZERO alerts and NEVER be redacted) ---
        negative_cases = [
            # URL
            ('url = "https://example.com/api/v1/auth/token?query=active"', "client.py"),
            # Windows path
            ('log_path = "C:\\\\Users\\\\parve\\\\Downloads\\\\PrivacyVideoCleaner_v1_source\\\\run.py"', "paths.py"),
            # SHA256 in lockfile
            ('hash = "sha256:7c9e1e2d4f8a3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c"', "uv.lock"),
            # Python method call
            ('ctypes.windll.kernel32.SetFileAttributesW(path, 2)', "fs.py"),
            # UUID
            ('user_id = "123e4567-e89b-12d3-a456-426614174000"', "models.py"),
            # Normal high-entropy ID
            ('job_id = "job_9b1c7d2e4f0a"', "tasks.py"),
            ('css_class = "styles-a8f3b2c1"', "theme.py"),
            # token_count
            ('token_count = 12345', "stats.py"),
            ('max_tokens = 200000', "config.py"),
        ]

        for code_snippet, filename in negative_cases:
            alerts = detector.scan_content(code_snippet, filename)
            self.assertEqual(len(alerts), 0, f"False positive on negative test: {code_snippet}")
            redacted, _ = redact_inline_secrets(code_snippet, filename)
            self.assertEqual(redacted, code_snippet, f"Unwanted redaction on negative test: {code_snippet}")

    def test_ai_program_lister_progress_safety(self):
        """Verify _on_bundle_progress safely handles NoneType dialog and trailing callbacks."""
        from tests.conftest import get_or_create_test_qapp
        from PySide6.QtWidgets import QProgressDialog
        from veilframe.gui.folder.ai_program_lister_panel import AIProgramListerPanel

        app = get_or_create_test_qapp()
        panel = AIProgramListerPanel()

        # 1. Dialog active: normal update
        panel._bundle_progress_dlg = QProgressDialog("Initial", "Cancel", 0, 100, panel)
        panel._on_bundle_progress(50, 100, "Halfway...")
        self.assertEqual(panel._bundle_progress_dlg.value(), 50)
        self.assertEqual(panel._bundle_progress_dlg.labelText(), "Halfway...")

        # 2. Simulate re-entrant dismissal where dialog becomes None
        panel._bundle_progress_dlg.close()
        panel._bundle_progress_dlg = None

        # 3. Trailing progress callback arriving after completion MUST NOT raise AttributeError
        try:
            panel._on_bundle_progress(100, 100, "AI Bundle complete!")
        except AttributeError as e:
            self.fail(f"_on_bundle_progress raised AttributeError when dialog was None: {e}")

        # 4. Cancellation handler cleans up safely
        panel._bundle_progress_dlg = QProgressDialog("Testing cancel", "Cancel", 0, 100, panel)
        panel._on_bundle_canceled()
        self.assertIsNone(panel._bundle_progress_dlg)
        panel.deleteLater()

    def test_json_and_yaml_classification(self):
        """Verify that JSON and YAML/YML files are properly classified as CONFIG, CI_CD, or MANIFEST instead of UNKNOWN."""
        from veilframe.folder.classification.classifier import Classifier

        classifier = Classifier()

        cases = [
            ("config.json", FileCategory.CONFIG, AIAction.INCLUDE),
            ("settings.jsonc", FileCategory.CONFIG, AIAction.INCLUDE),
            ("data.json5", FileCategory.CONFIG, AIAction.INCLUDE),
            ("app.yaml", FileCategory.CONFIG, AIAction.INCLUDE),
            ("values.yml", FileCategory.CONFIG, AIAction.INCLUDE),
            (".github/workflows/deploy.yml", FileCategory.CI_CD, AIAction.INCLUDE),
            (".github/workflows/test.yaml", FileCategory.CI_CD, AIAction.INCLUDE),
            ("pubspec.yml", FileCategory.MANIFEST, AIAction.INCLUDE),
            ("deno.json", FileCategory.MANIFEST, AIAction.INCLUDE),
            ("package-lock.json", FileCategory.DEPENDENCY, AIAction.EXCLUDE),
            ("pnpm-lock.yaml", FileCategory.DEPENDENCY, AIAction.EXCLUDE),
            ("pnpm-lock.yml", FileCategory.DEPENDENCY, AIAction.EXCLUDE),
        ]

        for rel_p, expected_cat, expected_act in cases:
            rec = FileRecord(
                id=1,
                name=os.path.basename(rel_p),
                path=f"/fake/{rel_p}",
                relative_path=rel_p,
                extension=os.path.splitext(rel_p)[1],
                size=100,
            )
            res = classifier.classify_file(rec, active_ecosystems=set())
            self.assertEqual(res.category, expected_cat, f"Mismatch category for {rel_p}: got {res.category}, expected {expected_cat}")
            self.assertEqual(res.action, expected_act, f"Mismatch action for {rel_p}: got {res.action}, expected {expected_act}")

    def test_json_yaml_config_filtering(self):
        """Ensure unchecking include_configs correctly filters out JSON and YAML configuration files."""
        files = [
            FileRecord(id=1, name="main.py", path="/p/main.py", relative_path="main.py",
                       classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE)),
            FileRecord(id=2, name="settings.json", path="/p/settings.json", relative_path="settings.json",
                       classification=ClassificationResult(category=FileCategory.CONFIG, action=AIAction.INCLUDE)),
            FileRecord(id=3, name="deploy.yml", path="/p/deploy.yml", relative_path="deploy.yml",
                       classification=ClassificationResult(category=FileCategory.CONFIG, action=AIAction.INCLUDE)),
        ]

        # 1. include_configs = True -> all included
        cfg_inc = BundleConfig(target_tokens=None, include_configs=True)
        selector_inc = FileSelector(cfg_inc)
        inc, exc, _ = selector_inc.select_files(files)
        self.assertEqual(len(inc), 3)

        # 2. include_configs = False -> settings.json and deploy.yml excluded
        cfg_exc = BundleConfig(target_tokens=None, include_configs=False)
        selector_exc = FileSelector(cfg_exc)
        inc2, exc2, _ = selector_exc.select_files(files)
        self.assertEqual(len(inc2), 1)
        self.assertEqual(inc2[0].name, "main.py")
        exc_names = [f.name for f, _ in exc2]
        self.assertIn("settings.json", exc_names)
        self.assertIn("deploy.yml", exc_names)

    def test_unlimited_token_preserves_full_content_and_never_cuts(self):
        """Under unlimited token budget, ensure lockfiles and large files are NEVER truncated or cut midway."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            lockfile_path = os.path.join(tmp_dir, "package-lock.json")
            # Generate 5,000 bytes of realistic lockfile content
            packages = {f"pkg-{i}": {"version": f"1.0.{i}", "resolved": "https://registry.npmjs.org/..."} for i in range(120)}
            import json as _json
            full_lock_content = _json.dumps({"name": "test-pkg", "lockfileVersion": 3, "packages": packages}, indent=2)
            with open(lockfile_path, "w", encoding="utf-8") as f:
                f.write(full_lock_content)

            f_rec = FileRecord(
                id=1,
                name="package-lock.json",
                path=lockfile_path,
                relative_path="package-lock.json",
                size=len(full_lock_content),
                classification=ClassificationResult(category=FileCategory.DEPENDENCY, action=AIAction.INCLUDE),
            )

            # Unlimited tokens configuration
            unlimited_cfg = BundleConfig(target_tokens=None, max_single_file_tokens=None, truncate_oversized=True, compress_context=True)
            builder = AIBundleBuilder(unlimited_cfg)
            scan_res = ScanResult(config=None, root_path=tmp_dir, files=[f_rec], stats=ScanStats(root_path=tmp_dir, total_files=1))
            res = builder.build(scan_res)

            # In native bundle, full content MUST be present and not summarized to "LOCKFILE DEPENDENCY SUMMARY"
            bundle_str = res.content if isinstance(res.content, str) else res.content.decode("utf-8")
            self.assertNotIn("LOCKFILE DEPENDENCY SUMMARY", bundle_str)
            self.assertNotIn("... [TRUNCATED", bundle_str)
            self.assertIn('"pkg-119"', bundle_str)
            self.assertIn('"lockfileVersion": 3', bundle_str)

    def test_markdown_code_fence_escapes_inner_backticks(self):
        """Ensure markdown files containing triple backticks do not close the markdown fence early."""
        from veilframe.folder.ai_bundle.formats.markdown import render_markdown_bundle

        with tempfile.TemporaryDirectory() as tmp_dir:
            readme_content = "# Project Readme\n\n```python\nprint('hello world')\n```\n\nMore docs after code block."
            f_rec = FileRecord(
                id=1,
                name="README.md",
                path=os.path.join(tmp_dir, "README.md"),
                relative_path="README.md",
                size=len(readme_content),
                language="Markdown",
                classification=ClassificationResult(category=FileCategory.DOCUMENTATION, action=AIAction.INCLUDE),
            )
            scan_res = ScanResult(config=None, root_path=tmp_dir, files=[f_rec], stats=ScanStats(root_path=tmp_dir, total_files=1))
            cfg = BundleConfig(format=BundleFormat.MARKDOWN)
            md_output = render_markdown_bundle(scan_res, cfg, [(f_rec, readme_content, 50)], [], 50)

            # The fence must use 4 backticks (````) so that the inner ```python ... ``` does not terminate the block
            self.assertIn("````markdown", md_output)
            self.assertIn("More docs after code block.", md_output)
            # Must end with 4 backticks closing
            self.assertIn("````\n", md_output)

    def test_manifest_parser_supports_pubspec_yml_and_deno(self):
        """Verify parse_manifest handles pubspec.yml and deno.json."""
        from veilframe.folder.project_context.manifest_parser import parse_manifest

        with tempfile.TemporaryDirectory() as tmp_dir:
            # 1. pubspec.yml
            pub_path = os.path.join(tmp_dir, "pubspec.yml")
            with open(pub_path, "w", encoding="utf-8") as f:
                f.write("name: my_app\nversion: 2.1.0\ndependencies:\n  flutter:\n    sdk: flutter\n  provider: ^6.0.0\n")
            parsed_pub = parse_manifest(pub_path)
            self.assertIsNotNone(parsed_pub)
            self.assertEqual(parsed_pub.project_name, "my_app")
            self.assertEqual(parsed_pub.version, "2.1.0")
            self.assertIn("provider", parsed_pub.dependencies)

            # 2. deno.json
            deno_path = os.path.join(tmp_dir, "deno.json")
            with open(deno_path, "w", encoding="utf-8") as f:
                f.write('{"name": "@scope/pkg", "version": "0.5.0", "imports": {"std/": "https://deno.land/std@0.200.0/"}, "tasks": {"start": "deno run main.ts"}}')
            parsed_deno = parse_manifest(deno_path)
            self.assertIsNotNone(parsed_deno)
            self.assertEqual(parsed_deno.project_name, "@scope/pkg")
            self.assertEqual(parsed_deno.version, "0.5.0")
            self.assertIn("std/", parsed_deno.dependencies)
            self.assertIn("start", parsed_deno.scripts)


if __name__ == "__main__":
    unittest.main()

