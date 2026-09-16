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
    """Compile high-level metadata from scan result and project context."""
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

    return {
        "name": root_name,
        "root": scan_result.root_path,
        "languages": top_languages,
        "ecosystems": ecosystems,
        "entry_points": sorted(entry_points),
        "total_files": scan_result.stats.total_files,
        "total_size": scan_result.stats.total_size_bytes,
        "total_size_formatted": format_bytes(scan_result.stats.total_size_bytes),
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
