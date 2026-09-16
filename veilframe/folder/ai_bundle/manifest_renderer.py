"""
veilframe.folder.ai_bundle.manifest_renderer — Renders discovered package manifests and dependency tables.
"""

from __future__ import annotations

import os
from typing import Dict, List, Optional

from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.project_context.manifest_parser import parse_manifest


def render_manifest_summaries(files: List[FileRecord]) -> str:
    """Find all manifest files and render a structured dependency overview."""
    manifest_files = [
        f for f in files
        if f.effective_category == FileCategory.MANIFEST or f.name in (
            "package.json", "pyproject.toml", "Cargo.toml", "go.mod", "pom.xml",
            "build.gradle", "requirements.txt", "Gemfile", "composer.json", "pubspec.yaml"
        )
    ]

    if not manifest_files:
        return "No standard package manifests detected in project."

    sections: List[str] = []
    for mf in manifest_files:
        info = parse_manifest(mf.path)
        if not info:
            continue

        pkg_name = getattr(info, "project_name", None) or getattr(info, "name", None) or "unnamed"
        lines = [
            f"MANIFEST: {mf.relative_path}",
            f"Package Name: {pkg_name}",
            f"Version: {info.version or 'unspecified'}",
        ]
        if info.description:
            lines.append(f"Description: {info.description}")

        if info.dependencies:
            lines.append(f"Dependencies ({len(info.dependencies)}):")
            for dep, ver in sorted(info.dependencies.items())[:30]:
                lines.append(f"  - {dep}: {ver or '*'}")
            if len(info.dependencies) > 30:
                lines.append(f"  ... ({len(info.dependencies) - 30} more dependencies)")

        if info.dev_dependencies:
            lines.append(f"Dev Dependencies ({len(info.dev_dependencies)}):")
            for dep, ver in sorted(info.dev_dependencies.items())[:20]:
                lines.append(f"  - {dep}: {ver or '*'}")
            if len(info.dev_dependencies) > 20:
                lines.append(f"  ... ({len(info.dev_dependencies) - 20} more dev dependencies)")

        if info.scripts:
            lines.append(f"Scripts / Commands ({len(info.scripts)}):")
            for sname, scmd in sorted(info.scripts.items())[:15]:
                lines.append(f"  - {sname}: {scmd}")

        sections.append("\n".join(lines))

    return "\n\n".join(sections) if sections else "No dependency manifests parsed."
