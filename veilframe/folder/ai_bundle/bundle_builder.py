"""
veilframe.folder.ai_bundle.bundle_builder — Orchestrates AI context selection, compression, and bundle generation.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple, Union

from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat
from veilframe.folder.ai_bundle.bundle_writer import write_bundle_to_disk
from veilframe.folder.ai_bundle.context_compressor import prepare_file_content
from veilframe.folder.ai_bundle.file_selector import FileSelector
from veilframe.folder.ai_bundle.formats.aibundle import render_native_aibundle
from veilframe.folder.ai_bundle.formats.html import render_html_bundle
from veilframe.folder.ai_bundle.formats.json import render_json_bundle
from veilframe.folder.ai_bundle.formats.markdown import render_markdown_bundle
from veilframe.folder.ai_bundle.formats.text import render_text_bundle
from veilframe.folder.ai_bundle.formats.zip import render_zip_bundle
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult


@dataclass
class AIBundleResult:
    """Outcome of an AI project bundle generation."""

    content: Union[str, bytes]
    format: BundleFormat
    total_tokens: int
    included_count: int
    excluded_count: int
    output_path: Optional[str] = None
    stats: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_binary(self) -> bool:
        return isinstance(self.content, bytes)

    def write(self, path: str) -> int:
        """Persist this bundle result to the designated path."""
        bytes_written = write_bundle_to_disk(self.content, path)
        self.output_path = path
        return bytes_written


class AIBundleBuilder:
    """Builds AI-ready project context packages across multiple formats."""

    def __init__(self, config: Optional[BundleConfig] = None) -> None:
        self.config = config or BundleConfig()

    def build(
        self,
        scan_result: ScanResult,
        output_path: Optional[str] = None,
    ) -> AIBundleResult:
        """
        Execute file selection, compression/redaction, and formatting.
        """
        # 1. Select files under token budget
        selector = FileSelector(self.config)
        included_records, excluded_records, _ = selector.select_files(scan_result.files)

        # 2. Prepare file contents and calculate precise token count
        prepared_files: List[Tuple[FileRecord, str, int]] = []
        total_tokens = 0

        for f in included_records:
            content, tokens = prepare_file_content(
                f,
                max_tokens=self.config.max_single_file_tokens,
                redact_secrets=self.config.redact_secrets,
                truncate_oversized=getattr(self.config, "truncate_oversized", False),
            )
            prepared_files.append((f, content, tokens))
            total_tokens += tokens

        # 3. Render according to requested format
        fmt = self.config.format
        content: Union[str, bytes]

        if fmt == BundleFormat.AIBUNDLE:
            content = render_native_aibundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        elif fmt == BundleFormat.MARKDOWN:
            content = render_markdown_bundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        elif fmt == BundleFormat.TEXT:
            content = render_text_bundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        elif fmt == BundleFormat.JSON:
            content = render_json_bundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        elif fmt == BundleFormat.ZIP:
            content = render_zip_bundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        elif fmt == BundleFormat.HTML:
            content = render_html_bundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)
        else:
            content = render_native_aibundle(scan_result, self.config, prepared_files, excluded_records, total_tokens)

        result = AIBundleResult(
            content=content,
            format=fmt,
            total_tokens=total_tokens,
            included_count=len(prepared_files),
            excluded_count=len(excluded_records),
            stats={
                "target_budget": self.config.target_tokens,
                "languages": list(scan_result.languages),
                "ecosystems": list(scan_result.ecosystems),
            },
        )

        # 4. Optional disk write
        if output_path:
            result.write(output_path)

        return result
