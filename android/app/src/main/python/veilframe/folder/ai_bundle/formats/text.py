"""
veilframe.folder.ai_bundle.formats.text — Clean plaintext project bundle renderer.
"""

from __future__ import annotations

from typing import List, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata, render_project_summary_text
from veilframe.folder.ai_bundle.tree_renderer import build_project_tree
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult


def render_text_bundle(
    scan_result: ScanResult,
    config: BundleConfig,
    included_files: List[Tuple[FileRecord, str, int]],
    excluded_files: List[Tuple[FileRecord, str]],
    total_tokens: int,
) -> str:
    """Render plain text bundle with clear section boundaries."""
    meta = generate_project_metadata(scan_result)
    sep = "=" * 60
    sub_sep = "-" * 40

    parts: List[str] = [
        sep,
        f"PROJECT BUNDLE: {meta['name']}",
        f"Estimated Tokens: ~{total_tokens:,}",
        sep,
        "",
        render_project_summary_text(meta),
        "",
    ]

    if config.include_tree:
        files_for_tree = [f for f, _, _ in included_files]
        tree = build_project_tree(files_for_tree)
        parts.extend([
            sub_sep,
            "DIRECTORY STRUCTURE:",
            sub_sep,
            tree,
            "",
        ])

    for f, content, tokens in included_files:
        parts.extend([
            sep,
            f"FILE: {f.relative_path}",
            f"CATEGORY: {f.effective_category.value} | LANGUAGE: {f.language or 'Text'} | TOKENS: ~{tokens:,}",
            sep,
            content.strip(),
            "",
        ])

    if scan_result.security_alerts:
        parts.extend([
            sub_sep,
            "SECURITY WARNINGS:",
            sub_sep,
        ])
        for alert in scan_result.security_alerts:
            parts.append(f"• {alert.relative_path} ({alert.rule_id}): {alert.description}")
        parts.append("")

    return "\n".join(parts)
