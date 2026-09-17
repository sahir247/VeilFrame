"""
veilframe.folder.ai_bundle.project_summary — High-level project summary and statistics generation.
"""

from __future__ import annotations

import os
from collections import Counter
from typing import Any, Dict, List, Optional

from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.models.scan_result import ScanResult


def generate_project_metadata(scan_result: ScanResult) -> Dict[str, Any]:
    """Compile high-level metadata from scan result, manifests, and project context."""
    root_name = os.path.basename(os.path.abspath(scan_result.root_path)) or scan_result.root_path
    
    # 1. Languages breakdown
    lang_counts: Counter[str] = Counter()
    for f in scan_result.files:
        if f.language and f.language != "Unknown":
            lang_counts[f.language] += 1
    top_languages = [lang for lang, _ in lang_counts.most_common(10)]

    # 2. Ecosystems
    ecosystems = sorted(list(scan_result.ecosystems))

    # 3. Categorized file counts
    cat_counts: Counter[FileCategory] = Counter()
    for f in scan_result.files:
        cat_counts[f.effective_category] += 1

    # 4. Entry points
    entry_points = [
        f.relative_path for f in scan_result.files
        if f.is_entry_point or (f.priority_score and f.priority_score >= 99)
    ]

    # 5. Extract Project Intelligence from Manifests & Lockfiles
    manifest_name: Optional[str] = None
    manifest_version: Optional[str] = None
    frameworks: Set[str] = set()
    libraries: Set[str] = set()
    build_systems: Set[str] = set()
    pkg_managers: Set[str] = set()

    all_filenames = {f.name.lower(): f for f in scan_result.files}

    # Check lockfiles/tools for package managers
    if "uv.lock" in all_filenames:
        pkg_managers.add("uv")
    if "poetry.lock" in all_filenames:
        pkg_managers.add("poetry")
    if "pipfile" in all_filenames or "pipfile.lock" in all_filenames:
        pkg_managers.add("pipenv")
    if "requirements.txt" in all_filenames:
        pkg_managers.add("pip")
    if "package-lock.json" in all_filenames:
        pkg_managers.add("npm")
    if "pnpm-lock.yaml" in all_filenames:
        pkg_managers.add("pnpm")
    if "yarn.lock" in all_filenames:
        pkg_managers.add("yarn")
    if "bun.lockb" in all_filenames or "bun.lock" in all_filenames:
        pkg_managers.add("bun")
    if "cargo.lock" in all_filenames:
        pkg_managers.add("cargo")
    if "gemfile.lock" in all_filenames:
        pkg_managers.add("bundler")
    if "composer.lock" in all_filenames:
        pkg_managers.add("composer")

    # Check common build systems
    if "cmakelists.txt" in all_filenames:
        build_systems.add("CMake")
    if "makefile" in all_filenames:
        build_systems.add("Make")
    if any(k.startswith("vite.config") for k in all_filenames):
        build_systems.add("Vite")
    if any(k.startswith("webpack.config") for k in all_filenames):
        build_systems.add("Webpack")
    if "cargo.toml" in all_filenames:
        build_systems.add("Cargo")
    if "pom.xml" in all_filenames:
        build_systems.add("Maven")
    if "build.gradle" in all_filenames or "build.gradle.kts" in all_filenames:
        build_systems.add("Gradle")

    # Parse primary manifests
    from veilframe.folder.project_context.manifest_parser import parse_manifest
    manifest_candidates = [
        "pyproject.toml", "package.json", "cargo.toml", "go.mod", "pom.xml"
    ]
    for mf_name in manifest_candidates:
        if mf_name in all_filenames:
            mf_record = all_filenames[mf_name]
            info = parse_manifest(mf_record.path)
            if info:
                if not manifest_name and info.project_name:
                    manifest_name = info.project_name
                if not manifest_version and info.version:
                    manifest_version = info.version

                all_deps = {k.lower(): k for k in {**info.dependencies, **info.dev_dependencies}.keys()}

                # Frameworks (Application skeletons / architectures)
                if "pyside6" in all_deps or "pyside2" in all_deps:
                    frameworks.add("PySide6")
                if "pyqt6" in all_deps or "pyqt5" in all_deps:
                    frameworks.add("PyQt")
                if "fastapi" in all_deps:
                    frameworks.add("FastAPI")
                if "flask" in all_deps:
                    frameworks.add("Flask")
                if "django" in all_deps:
                    frameworks.add("Django")
                if "react" in all_deps:
                    frameworks.add("React")
                if "next" in all_deps:
                    frameworks.add("Next.js")
                if "vue" in all_deps:
                    frameworks.add("Vue")
                if "express" in all_deps:
                    frameworks.add("Express")
                if "svelte" in all_deps:
                    frameworks.add("Svelte")
                if "flutter" in all_deps:
                    frameworks.add("Flutter")

                # Libraries & Toolkits
                if "opencv-python" in all_deps or "opencv-python-headless" in all_deps or "cv2" in all_deps:
                    libraries.add("OpenCV")
                if "numpy" in all_deps:
                    libraries.add("NumPy")
                if "pillow" in all_deps or "pil" in all_deps:
                    libraries.add("Pillow")
                if "cryptography" in all_deps:
                    libraries.add("cryptography")
                if "pyyaml" in all_deps:
                    libraries.add("PyYAML")
                if "pathspec" in all_deps:
                    libraries.add("pathspec")
                if "torch" in all_deps or "pytorch" in all_deps:
                    libraries.add("PyTorch")
                if "pandas" in all_deps:
                    libraries.add("pandas")
                if "requests" in all_deps:
                    libraries.add("requests")
                if "pytest" in all_deps:
                    libraries.add("pytest")

                if mf_name == "pyproject.toml":
                    try:
                        with open(mf_record.path, "r", encoding="utf-8", errors="ignore") as mf_f:
                            mf_text = mf_f.read()
                            if "setuptools" in mf_text:
                                build_systems.add("setuptools")
                            if "poetry-core" in mf_text or "poetry.core" in mf_text:
                                build_systems.add("poetry-core")
                            if "hatchling" in mf_text:
                                build_systems.add("hatchling")
                            if "flit_core" in mf_text:
                                build_systems.add("flit")
                    except Exception:
                        pass

    if manifest_name and manifest_version:
        project_display_name = f"{manifest_name} v{manifest_version}"
    elif manifest_name:
        project_display_name = manifest_name
    else:
        project_display_name = root_name

    return {
        "name": project_display_name,
        "raw_name": manifest_name or root_name,
        "version": manifest_version,
        "root": scan_result.root_path,
        "languages": top_languages,
        "ecosystems": ecosystems,
        "frameworks": sorted(list(frameworks)),
        "libraries": sorted(list(libraries)),
        "build_systems": sorted(list(build_systems)),
        "package_managers": sorted(list(pkg_managers)),
        "entry_points": sorted(entry_points),
        "total_files": scan_result.stats.total_files if scan_result.stats else len(scan_result.files),
        "total_size": scan_result.stats.total_size_bytes if scan_result.stats else sum(f.size for f in scan_result.files),
        "total_size_formatted": format_bytes(scan_result.stats.total_size_bytes if scan_result.stats else sum(f.size for f in scan_result.files)),
        "source_count": cat_counts[FileCategory.SOURCE],
        "test_count": cat_counts[FileCategory.TEST],
        "config_count": cat_counts[FileCategory.CONFIG] + cat_counts[FileCategory.MANIFEST],
        "doc_count": cat_counts[FileCategory.DOCUMENTATION],
        "dependency_count": cat_counts[FileCategory.DEPENDENCY] + cat_counts[FileCategory.VENDOR],
        "cache_count": cat_counts[FileCategory.CACHE] + cat_counts[FileCategory.BUILD_OUTPUT] + cat_counts[FileCategory.GENERATED],
        "security_warnings": len(scan_result.security_alerts),
    }


def render_project_summary_text(meta: Dict[str, Any]) -> str:
    """Render a concise text summary block for headers."""
    lines = [
        f"Project Name: {meta['name']}",
        f"Root Directory: {meta['root']}",
        f"Primary Languages: {', '.join(meta['languages']) if meta['languages'] else 'None detected'}",
        f"Detected Ecosystems: {', '.join(meta['ecosystems']) if meta['ecosystems'] else 'Generic'}",
    ]
    if meta['entry_points']:
        lines.append(f"Entry Points: {', '.join(meta['entry_points'][:5])}")
    lines.extend([
        f"Total Inventory: {meta['total_files']:,} files ({meta['total_size_formatted']})",
        f"  - Source Files: {meta['source_count']:,}",
        f"  - Test Files: {meta['test_count']:,}",
        f"  - Configurations & Manifests: {meta['config_count']:,}",
        f"  - Documentation Files: {meta['doc_count']:,}",
        f"  - Dependencies Excluded: {meta['dependency_count']:,}",
        f"  - Build / Cache Excluded: {meta['cache_count']:,}",
    ])
    if meta['security_warnings'] > 0:
        lines.append(f"  - Security Alerts: {meta['security_warnings']} potential secrets detected")
    return "\n".join(lines)
