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
                "path": (a.get("relative_path") or a.get("path") or a.get("file_path", "")) if isinstance(a, dict)
                else (getattr(a, "relative_path", None) or getattr(a, "file_path", "")) if not isinstance(a, str)
                else "",
                "rule_id": (a.get("rule_id") or a.get("rule", "SECRET")) if isinstance(a, dict)
                else getattr(a, "rule_id", "SECRET") if not isinstance(a, str)
                else "SECRET",
                "description": (a.get("description") or a.get("alert_type", "")) if isinstance(a, dict)
                else getattr(a, "description", str(a)) if not isinstance(a, str)
                else a,
                "severity": (a.get("severity", "HIGH")) if isinstance(a, dict)
                else getattr(a, "severity", "HIGH") if not isinstance(a, str)
                else "HIGH",
            }
            for a in scan_result.security_alerts
        ],
        "files": [
            {
                "path": getattr(f, "relative_path", str(f)),
                "category": f.effective_category.value if hasattr(f, "effective_category") else "source",
                "language": getattr(f, "language", None),
                "size_bytes": getattr(f, "size", 0),
                "tokens": tokens,
                "is_entry_point": getattr(f, "is_entry_point", False),
                "priority_score": getattr(f, "priority_score", None),
                "content": content,
            }
            for f, content, tokens in included_files
        ],
        "excluded_files": [
            {
                "path": getattr(f, "relative_path", str(f)),
                "reason": reason,
            }
            for f, reason in excluded_files
        ],
    }

    return json.dumps(payload, indent=2)
