#!/usr/bin/env python3
"""Report candidate Tink generated-code owners in cached JAR/AAR and APK DEX."""
import argparse
import io
import json
from pathlib import Path
import re
import zipfile

PREFIX = "com/google/crypto/tink/proto/"
DEX_PATTERN = re.compile(rb"Lcom/google/crypto/tink/proto/[A-Za-z0-9_/$]+;")
LIMIT = 128 * 1024 * 1024


def jar_classes(data):
    with zipfile.ZipFile(data) as archive:
        return [name for name in archive.namelist()
                if name.startswith(PREFIX) and name.endswith(".class")]


def scan_archive(path):
    found = []
    try:
        with zipfile.ZipFile(path) as archive:
            found.extend(jar_classes(path))
            if path.suffix == ".aar":
                for entry in archive.infolist():
                    if entry.filename.endswith(".jar") and entry.file_size <= LIMIT:
                        try:
                            found.extend(entry.filename + "!" + name
                                         for name in jar_classes(io.BytesIO(archive.read(entry))))
                        except (OSError, zipfile.BadZipFile, RuntimeError, ValueError):
                            continue
    except (OSError, zipfile.BadZipFile, RuntimeError, ValueError):
        pass
    return sorted(set(found))


def scan_apk(path):
    indicators = set()
    try:
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if re.fullmatch(r"classes\d*\.dex", entry.filename) and entry.file_size <= LIMIT:
                    for item in DEX_PATTERN.findall(archive.read(entry)):
                        indicators.add(item.decode("ascii", "replace"))
    except (OSError, zipfile.BadZipFile, RuntimeError, ValueError) as exc:
        return {"path": str(path), "error": str(exc)}
    return {"path": str(path), "dex_type_indicators": sorted(indicators)}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    p.add_argument("--apk", type=Path, action="append", default=[])
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    archives = sorted(path for path in args.cache.rglob("*")
                      if path.is_file() and path.suffix in (".jar", ".aar")) if args.cache.exists() else []
    owners = [{"path": str(path), "classes": matches}
              for path in archives if (matches := scan_archive(path))]
    result = {
        "scanned_archives": len(archives),
        "candidate_cached_archive_owners": owners,
        "apk_checks": [scan_apk(path) for path in args.apk if path.exists()],
        "limitations": [
            "Cached artifact ownership does not establish inclusion in the release APK.",
            "Absent APK checks mean no APK was supplied, not that APK classes are absent.",
            "DEX type-string indicators do not establish class definition or exploitability.",
            "Buildscript/plugin artifacts can be outside modules-2; inspect buildEnvironment too."
        ]
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"scanned_archives": len(archives),
                      "candidate_owners": [x["path"] for x in owners],
                      "apk_checks": len(result["apk_checks"])}, indent=2))


if __name__ == "__main__":
    main()
