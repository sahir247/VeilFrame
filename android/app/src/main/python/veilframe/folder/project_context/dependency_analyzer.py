"""
veilframe.folder.project_context.dependency_analyzer — Analysis of ambiguous vendor/third-party directories.
"""

from __future__ import annotations

import os
from typing import Set, Tuple

AMBIGUOUS_DIRS = {"vendor", "third_party", "external", "deps", "lib"}


def analyze_vendor_status(
    rel_path: str,
    active_ecosystems: Set[str],
    has_manifest_in_tree: bool = False,
) -> Tuple[bool, str]:
    """
    Determine whether an ambiguous folder path (like vendor/ or third_party/)
    represents third-party external dependencies or project-owned code.
    
    Returns:
        (is_external_vendor: bool, reason: str)
    """
    norm = rel_path.replace("\\", "/").strip("/")
    segments = norm.split("/")
    first_seg = segments[0].lower()

    if first_seg not in AMBIGUOUS_DIRS:
        return False, "Standard project path"

    # Go vendoring convention
    if first_seg == "vendor" and "go" in active_ecosystems:
        return True, "Go vendored dependencies (vendor/)"

    # PHP Composer vendoring convention
    if first_seg == "vendor" and "php" in active_ecosystems:
        return True, "PHP Composer dependencies (vendor/)"

    # Ruby Bundler vendoring convention
    if norm.startswith("vendor/bundle") and "ruby" in active_ecosystems:
        return True, "Ruby Bundler vendored gems"

    # C/C++ third_party / external
    if first_seg in ("third_party", "external") and ("c" in active_ecosystems or "cpp" in active_ecosystems):
        return True, f"External third-party C/C++ dependency tree ({first_seg}/)"

    return False, "Project-owned source directory"
