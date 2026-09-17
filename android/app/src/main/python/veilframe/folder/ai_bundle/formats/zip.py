"""
veilframe.folder.ai_bundle.formats.zip — Curated clean project archive writer.
"""

from __future__ import annotations

import io
import zipfile
from typing import List, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.formats.markdown import render_markdown_bundle
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult


def render_zip_bundle(
    scan_result: ScanResult,
    config: BundleConfig,
    included_files: List[Tuple[FileRecord, str, int]],
    excluded_files: List[Tuple[FileRecord, str]],
    total_tokens: int,
) -> bytes:
    """Create a curated, clean ZIP archive containing only included project files and bundle manifest."""
    buffer = io.BytesIO()

    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        # 1. Add AI Bundle documentation summary at root of ZIP
        doc_content = render_markdown_bundle(scan_result, config, included_files, excluded_files, total_tokens)
        zf.writestr("AI_PROJECT_BUNDLE.md", doc_content)

        # 2. Add each curated file
        for f, content, _ in included_files:
            arcname = f.relative_path.replace("\\", "/").lstrip("/")
            zf.writestr(arcname, content)

    buffer.seek(0)
    return buffer.getvalue()
