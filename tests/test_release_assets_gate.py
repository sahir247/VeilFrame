"""Release publication accepts only complete production artifact sets."""
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.assemble_release_assets import ReleaseAssetError, assemble


class ReleaseArtifactGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.downloads = self.root / "downloads"
        self.out = self.root / "final_release"
        (self.repo / "android/app").mkdir(parents=True)
        (self.repo / "pyproject.toml").write_text('[project]\nversion = "2.3.1"\n')
        (self.repo / "android/app/build.gradle.kts").write_text(
            'versionCode = 231\nversionName = "2.3.1"\n')
        (self.repo / "android/update.json").write_text(json.dumps({
            "versionCode": 231, "versionName": "2.3.1", "tag": "v2.3.1",
            "apk": "VeilFrame-android-arm64.apk", "sha256": "a" * 64,
            "changelog": ["fixture"],
        }))
        self.assets = {
            "VeilFrame-windows-x86_64": ["VeilFrame-windows-x86_64.exe"],
            "VeilFrame-linux-x86_64": [
                "VeilFrame-linux-x86_64.deb", "VeilFrame-linux-x86_64.tar.gz"],
            "VeilFrame-macos-arm64-tar": ["VeilFrame-macos-arm64.tar.gz"],
            "VeilFrame-macos-arm64-dmg": ["VeilFrame-macos-arm64.dmg"],
            "VeilFrame-python-dist": [
                "veilframe-2.3.1-py3-none-any.whl", "veilframe-2.3.1.tar.gz"],
            "VeilFrame-android-packages": ["VeilFrame-android-arm64.apk"],
        }
        for group, filenames in self.assets.items():
            folder = self.downloads / group
            folder.mkdir(parents=True)
            for name in filenames:
                path = folder / name
                if name.endswith(".apk"):
                    with zipfile.ZipFile(path, "w") as archive:
                        archive.writestr("AndroidManifest.xml", b"manifest")
                        archive.writestr("classes.dex", b"dex")
                else:
                    path.write_bytes(b"nonempty test artifact")

    def test_complete_release_has_correct_sha_without_debug(self):
        names = assemble(self.repo, self.downloads, self.out)
        self.assertEqual(len(names), 10)
        self.assertNotIn("app-debug.apk", names)
        actual = hashlib.sha256(
            (self.downloads / "VeilFrame-android-packages/VeilFrame-android-arm64.apk").read_bytes()
        ).hexdigest()
        data = json.loads((self.out / "update.json").read_text())
        self.assertEqual(data["sha256"], actual)
        self.assertIn("VeilFrame-android-arm64.apk",
                      (self.out / "SHA256SUMS.txt").read_text())

    def test_missing_production_apk_rejected(self):
        (self.downloads / "VeilFrame-android-packages/VeilFrame-android-arm64.apk").unlink()
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_debug_apk_cannot_substitute(self):
        folder = self.downloads / "VeilFrame-android-packages"
        (folder / "VeilFrame-android-arm64.apk").unlink()
        (folder / "app-debug.apk").write_bytes(b"debug")
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_unexpected_debug_artifact_group_rejected(self):
        folder = self.downloads / "VeilFrame-android-debug-arm64"
        folder.mkdir()
        (folder / "app-debug.apk").write_bytes(b"debug")
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_mismatched_android_version_rejected(self):
        (self.repo / "android/app/build.gradle.kts").write_text(
            'versionCode = 232\nversionName = "2.3.2"\n')
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_invalid_release_apk_rejected(self):
        (self.downloads / "VeilFrame-android-packages/VeilFrame-android-arm64.apk").write_bytes(
            b"bad content")
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_extra_files_rejected(self):
        (self.downloads / "VeilFrame-python-dist/app-debug.apk").write_bytes(b"debug")
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)

    def test_partial_release_with_missing_platform_rejected(self):
        for path in (self.downloads / "VeilFrame-linux-x86_64").iterdir():
            path.unlink()
        with self.assertRaises(ReleaseAssetError):
            assemble(self.repo, self.downloads, self.out)


if __name__ == "__main__":
    unittest.main()
