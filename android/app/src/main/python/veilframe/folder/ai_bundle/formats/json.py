"""
veilframe.folder.ai_bundle.formats.json — Structured JSON project bundle renderer.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult


def render_json_bundle(
    scan_result: ScanResult,
    config: BundleConfig,
    included_files: List[Tuple[FileRecord, str, int]],
    excluded_files: List[Tuple[FileRecord, str]],
    total_tokens: int,
) -> str:
    """Render bundle as structured JSON string."""
    meta = generate_project_metadata(scan_result)

    payload: Dict[str, Any] = {
        "format_version": 1,
        "generator": "VeilFrame 2.x",
        "project": meta,
        "statistics": {
            "included_files_count": len(included_files),
            "excluded_files_count": len(excluded_files),
            "estimated_tokens": total_tokens,
        },
        "security_alerts": [
            {
                "path": a.relative_path,
                "rule_id": a.rule_id,
                "description": a.description,
                "severity": a.severity,
            }
            for a in scan_result.security_alerts
        ],
        "files": [
            {
                "path": f.relative_path,
                "category": f.effective_category.value,
                "language": f.language,
                "size_bytes": f.size,
                "tokens": tokens,
                "is_entry_point": f.is_entry_point,
                "priority_score": f.priority_score,
                "content": content,
            }
            for f, content, tokens in included_files
        ],
        "excluded_files": [
            {
                "path": f.relative_path,
                "reason": reason,
            }
            for f, reason in excluded_files
        ],
    }

    return json.dumps(payload, indent=2)
