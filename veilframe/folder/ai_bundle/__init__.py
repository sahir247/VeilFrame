"""
veilframe.folder.ai_bundle — Project Intelligence and AI Program Bundle subsystem.
"""

from veilframe.folder.ai_bundle.bundle_builder import AIBundleBuilder, AIBundleResult
from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat
from veilframe.folder.ai_bundle.bundle_writer import write_bundle_to_disk
from veilframe.folder.ai_bundle.context_compressor import (
    prepare_file_content,
    summarize_binary_or_model,
    summarize_sqlite_database,
)
from veilframe.folder.ai_bundle.file_selector import FileSelector
from veilframe.folder.ai_bundle.manifest_renderer import render_manifest_summaries
from veilframe.folder.ai_bundle.priority_engine import rank_files_by_priority
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata, render_project_summary_text
from veilframe.folder.ai_bundle.relationship_renderer import render_project_relationships
from veilframe.folder.ai_bundle.token_estimator import estimate_tokens
from veilframe.folder.ai_bundle.tree_renderer import build_project_tree

__all__ = [
    "AIBundleBuilder",
    "AIBundleResult",
    "BundleConfig",
    "BundleFormat",
    "FileSelector",
    "estimate_tokens",
    "rank_files_by_priority",
    "prepare_file_content",
    "summarize_sqlite_database",
    "summarize_binary_or_model",
    "generate_project_metadata",
    "render_project_summary_text",
    "build_project_tree",
    "render_manifest_summaries",
    "render_project_relationships",
    "write_bundle_to_disk",
]
