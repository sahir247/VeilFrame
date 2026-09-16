"""
veilframe.folder.ai_bundle.bundle_config — Configuration parameters for AI bundle generation and token budgeting.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set

from veilframe.folder.models.classification import FileCategory


class BundleFormat(str, Enum):
    AIBUNDLE = "aibundle"
    MARKDOWN = "markdown"
    TEXT = "text"
    JSON = "json"
    ZIP = "zip"
    HTML = "html"


@dataclass
class BundleConfig:
    """Configures AI bundle filtering, token ceilings, content compression, and formats."""

    # 1. Token Budgeting
    target_tokens: Optional[int] = 128_000  # Default modern context window; None = unlimited
    max_single_file_tokens: Optional[int] = None  # None = no single file token limit
    truncate_oversized: bool = False  # NEVER truncate included files by default; always preserve complete source

    # 2. Bundle Format
    format: BundleFormat = BundleFormat.AIBUNDLE

    # 3. Content Filters
    include_tests: bool = True
    include_documentation: bool = True
    include_configs: bool = True
    include_scripts: bool = True

    # 4. Context Optimization & Security
    compress_context: bool = False  # Keep full files intact
    redact_secrets: bool = True
    summarize_databases: bool = True
    summarize_models: bool = True
    summarize_datasets: bool = True

    # 5. Sections to Include in Output
    include_summary: bool = True
    include_tree: bool = True
    include_manifests: bool = True
    include_relationships: bool = True
    include_excluded_list: bool = True
    include_security_warnings: bool = True

    # 6. Manual Overrides
    pinned_files: Set[str] = field(default_factory=set)    # Relative paths always included
    excluded_files: Set[str] = field(default_factory=set)  # Relative paths always excluded

    def to_dict(self) -> Dict[str, Any]:
        return {
            "target_tokens": self.target_tokens,
            "max_single_file_tokens": self.max_single_file_tokens,
            "format": self.format.value,
            "include_tests": self.include_tests,
            "include_documentation": self.include_documentation,
            "include_configs": self.include_configs,
            "include_scripts": self.include_scripts,
            "compress_context": self.compress_context,
            "redact_secrets": self.redact_secrets,
            "pinned_files": sorted(list(self.pinned_files)),
            "excluded_files": sorted(list(self.excluded_files)),
        }
