#!/usr/bin/env python3
"""Pin ephemeral OpenCV Android Gradle wrapper templates to a verified local ZIP.

Called after cloning upstream OpenCV, but before its expensive native builds.
Only the cloned upstream templates are edited; no global Gradle config changes.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import re
import sys
import zipfile

GRADLE_VERSION = "8.11.1"
GRADLE_BIN_SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
TEMPLATE_PATHS = (
    "platforms/android/aar-template/gradle/wrapper/gradle-wrapper.properties",
    "platforms/android/gradle-wrapper/gradle/wrapper/gradle-wrapper.properties.in",
)


def prepare(opencv_root: Path, distribution_zip: Path) -> None:
    archive = distribution_zip.resolve(strict=True)
    hasher = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    actual_sha256 = hasher.hexdigest()
    if actual_sha256 != GRADLE_BIN_SHA256:
        raise RuntimeError(
            f"OpenCV upstream Gradle {GRADLE_VERSION} archive SHA-256 mismatch: "
            f"expected {GRADLE_BIN_SHA256}, got {actual_sha256}"
        )
    if not zipfile.is_zipfile(archive):
        raise RuntimeError(f"Verified Gradle distribution is not a ZIP: {archive}")

    # Gradle .properties uses a backslash-escaped URL colon.
    local_url = archive.as_uri().replace(":", r"\:")
    for relative_path in TEMPLATE_PATHS:
        template = opencv_root / relative_path
        original = template.read_text(encoding="utf-8")
        if not re.search(r"(?m)^distributionUrl=.*$", original):
            raise RuntimeError(f"Missing Gradle wrapper distributionUrl: {template}")
        if GRADLE_VERSION not in original and "@GRADLE_VERSION@" not in original:
            raise RuntimeError(f"Unexpected upstream Gradle version in {template}")

        patched = re.sub(
            r"(?m)^distributionUrl=.*$",
            lambda _: f"distributionUrl={local_url}",
            original,
            count=1,
        )
        patched = re.sub(r"(?m)^distributionSha256Sum=.*\n?", "", patched)
        patched = patched.rstrip("\n") + f"\ndistributionSha256Sum={GRADLE_BIN_SHA256}\n"
        template.write_text(patched, encoding="utf-8")
        print(f"Configured OpenCV wrapper with SHA-256-verified local Gradle ZIP: {template}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_opencv_gradle_wrapper.py OPENCV_ROOT VERIFIED_GRADLE_ZIP")
    prepare(Path(sys.argv[1]), Path(sys.argv[2]))
