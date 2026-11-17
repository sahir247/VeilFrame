#!/usr/bin/env python3
"""Fail-closed VeilFrame release assembly: production artifacts only."""
import argparse
import hashlib
import json
import re
import shutil
try:
    import tomllib  # Python 3.11+
except ModuleNotFoundError:
    import tomli as tomllib  # Python 3.10 (declared dependency)
import zipfile
from pathlib import Path

class ReleaseAssetError(ValueError):
    pass

def require(ok, message):
    if not ok:
        raise ReleaseAssetError(message)

def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def assemble(repo, downloaded, output):
    project = tomllib.loads((repo / "pyproject.toml").read_text(encoding="utf-8"))
    version = project["project"]["version"]
    updater = json.loads((repo / "android/update.json").read_text(encoding="utf-8"))
    gradle = (repo / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    code = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", gradle)
    name = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle)
    require(code is not None and name is not None, "Android Gradle version missing")
    require(version == updater.get("versionName") == name.group(1)
            and updater.get("tag") == "v" + version
            and updater.get("versionCode") == int(code.group(1)),
            "Version mismatch in pyproject.toml, build.gradle.kts or update.json")
    require(updater.get("apk") == "VeilFrame-android-arm64.apk",
            "Unexpected updater APK filename")
    expected = {
        "VeilFrame-windows-x86_64": {"VeilFrame-windows-x86_64.exe"},
        "VeilFrame-linux-x86_64": {
            "VeilFrame-linux-x86_64.deb", "VeilFrame-linux-x86_64.tar.gz",
        },
        "VeilFrame-macos-arm64-tar": {"VeilFrame-macos-arm64.tar.gz"},
        "VeilFrame-macos-arm64-dmg": {"VeilFrame-macos-arm64.dmg"},
        "VeilFrame-python-dist": {
            f"veilframe-{version}-py3-none-any.whl", f"veilframe-{version}.tar.gz",
        },
        "VeilFrame-android-packages": {"VeilFrame-android-arm64.apk"},
    }
    require(downloaded.is_dir(), "Release artifact directory missing")
    require({p.name for p in downloaded.iterdir()} == set(expected),
            "Missing release group or unexpected debug/non-release artifact")
    require(not output.exists() or not any(output.iterdir()),
            "Destination must be empty before assembling release")
    for group, names in expected.items():
        directory = downloaded / group
        require(directory.is_dir() and not directory.is_symlink(),
                f"Missing release artifact group {group}")
        found = {p.relative_to(directory).as_posix()
                 for p in directory.rglob("*") if p.is_file() or p.is_symlink()}
        require(found == names, f"{group} contains unexpected/missing files: {found ^ names}")
        for filename in names:
            path = directory / filename
            require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0,
                    f"Missing/empty/unsafe release asset: {path}")
    output.mkdir(parents=True, exist_ok=True)
    files = []
    for group, names in expected.items():
        for filename in sorted(names):
            shutil.copyfile(downloaded / group / filename, output / filename)
            files.append(filename)
    apk = output / "VeilFrame-android-arm64.apk"
    require(zipfile.is_zipfile(apk), "Release APK is not a valid ZIP")
    with zipfile.ZipFile(apk) as archive:
        require({"AndroidManifest.xml", "classes.dex"} <= set(archive.namelist()),
                "Release APK missing manifest/classes.dex")
    updater["sha256"] = checksum(apk)
    (output / "update.json").write_text(json.dumps(updater, indent=2) + "\n",
                                        encoding="utf-8")
    files.append("update.json")
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{checksum(output / name)}  {name}\n" for name in sorted(files)),
        encoding="utf-8")
    return sorted(files) + ["SHA256SUMS.txt"]

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--download-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        files = assemble(args.repo_root, args.download_root, args.output)
        print("Verified production-only release assets:\n" + "\n".join(files))
    except (ReleaseAssetError, OSError, ValueError, KeyError) as error:
        parser.exit(1, f"::error::Release asset validation failed: {error}\n")
