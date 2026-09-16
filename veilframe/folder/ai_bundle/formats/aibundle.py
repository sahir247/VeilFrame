"""
veilframe.folder.ai_bundle.formats.aibundle — Native .aibundle format renderer.
Conforms strictly to the VeilFrame AI Program Bundle Format v1 standard.
Plain UTF-8 text underneath with deterministic two-layer protocol.
"""

from __future__ import annotations

import datetime
import os
from typing import Any, Dict, List, Optional, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.manifest_renderer import render_manifest_summaries
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata, render_project_summary_text
from veilframe.folder.ai_bundle.relationship_renderer import render_project_relationships
from veilframe.folder.ai_bundle.tree_renderer import build_project_tree
from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.models.scan_result import ScanResult


def aggregate_excluded_summary(excluded_files: List[Tuple[FileRecord, str]]) -> List[str]:
    """
    Summarize excluded files compactly by directory or pattern instead of dumping thousands of file paths.
    E.g.
    node_modules/ | 23,418 files | dependency
    .venv/ | 4,200 files | environment
    assets/*.mp4 | 15 files | media
    .env | excluded | sensitive
    """
    if not excluded_files:
        return ["(no files excluded)"]

    dir_counts: Dict[str, Tuple[int, str]] = {}
    loose_files: List[Tuple[str, str]] = []

    for f, reason in excluded_files:
        norm = f.relative_path.replace("\\", "/").strip("/")
        parts = norm.split("/")
        if len(parts) > 1:
            top_dir = parts[0] + "/"
            if top_dir in dir_counts:
                count, r = dir_counts[top_dir]
                dir_counts[top_dir] = (count + 1, r or reason)
            else:
                dir_counts[top_dir] = (1, reason)
        else:
            loose_files.append((norm, reason))

    lines: List[str] = []
    # Add directories sorted by file count descending
    for dir_name, (count, reason) in sorted(dir_counts.items(), key=lambda x: -x[1][0]):
        lines.append(f"{dir_name} | {count:,} files | {reason}")

    # Add loose files
    for file_name, reason in loose_files:
        lines.append(f"{file_name} | excluded | {reason}")

    return lines


def render_security_section(
    scan_result: ScanResult,
    excluded_files: List[Tuple[FileRecord, str]],
) -> List[str]:
    """Render security audit summary of sensitive files and alerts."""
    lines: List[str] = []

    # 1. Any secret files excluded
    secret_files = [
        (f, reason) for f, reason in excluded_files
        if f.is_secret or "secret" in reason.lower() or "sensitive" in reason.lower() or f.effective_category == FileCategory.SECRET
    ]
    for sf, reason in secret_files:
        lines.append(f"{sf.relative_path.replace(chr(92), '/')} | excluded | {reason or 'sensitive'}")

    # 2. Any security alerts from scan
    if scan_result.security_alerts:
        for alert in scan_result.security_alerts:
            lines.append(f"{alert.relative_path.replace(chr(92), '/')} | {alert.rule_id} | {alert.description}")

    if not lines:
        lines.append("clean | 0 secrets or security vulnerabilities detected in bundle")

    return lines


def render_native_aibundle(
    scan_result: ScanResult,
    config: BundleConfig,
    included_files: List[Tuple[FileRecord, str, int]],  # (file_rec, prepared_content, token_count)
    excluded_files: List[Tuple[FileRecord, str]],        # (file_rec, reason)
    total_tokens: int,
) -> str:
    """
    Render the official native VeilFrame .aibundle v1 text protocol.
    Structure:
    @VEILFRAME_BUNDLE
    @PROJECT
    @SUMMARY
    @TREE
    @ECOSYSTEMS
    @DEPENDENCIES
    @ENTRY_POINTS
    @RELATIONSHIPS
    @FILE_INDEX
    @FILES
      @FILE path="..." type="..." language="..."
      <<<
      ...
      >>>
    @EXCLUDED
    @SECURITY
    @END
    """
    meta = generate_project_metadata(scan_result)
    sections: List[str] = []

    # 0. HEADER
    now_str = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    header_lines = [
        "@VEILFRAME_BUNDLE",
        "version=1",
        f"project={meta['name']}",
        f"root={meta['root']}",
        f"timestamp={now_str}",
        f"total_tokens={total_tokens}",
        f"included_files={len(included_files)}",
        f"excluded_files={len(excluded_files)}",
    ]
    sections.append("\n".join(header_lines))

    # 1. @PROJECT
    project_body = [
        f"Name: {meta['name']}",
        f"Root: {meta['root']}",
        f"Detected Languages: {', '.join(meta['languages']) if meta['languages'] else 'None'}",
        f"Detected Ecosystems: {', '.join(meta['ecosystems']) if meta['ecosystems'] else 'Generic'}",
        f"Frameworks: {', '.join(sorted([e for e in meta['ecosystems'] if e in ('react', 'next', 'vue', 'angular', 'svelte', 'django', 'flask', 'fastapi', 'laravel', 'rails', 'spring', 'dotnet')])) or 'None detected'}",
        f"Build Systems: {', '.join(sorted([e for e in meta['ecosystems'] if e in ('cmake', 'make', 'gradle', 'maven', 'cargo', 'webpack', 'vite', 'turborepo')])) or 'Standard'}",
        f"Package Managers: {', '.join(sorted([e for e in meta['ecosystems'] if e in ('npm', 'yarn', 'pnpm', 'pip', 'poetry', 'uv', 'cargo', 'composer', 'bundler', 'nuget')])) or 'Standard'}",
        f"Total Files on Disk: {meta['total_files']:,}",
        f"Included in Context: {len(included_files):,}",
        f"Excluded from Context: {len(excluded_files):,}",
        f"Source Files: {meta['source_count']:,}",
        f"Test Files: {meta['test_count']:,}",
        f"Config Files: {meta['config_count']:,}",
        f"Doc Files: {meta['doc_count']:,}",
        f"Dependencies Excluded: {meta['dependency_count']:,}",
        f"Caches/Build Excluded: {meta['cache_count']:,}",
        f"Estimated Context Tokens: {total_tokens:,} tokens",
    ]
    sections.append(f"@PROJECT\n" + "\n".join(project_body))

    # 2. @SUMMARY
    if config.include_summary:
        summary_text = render_project_summary_text(meta).strip()
        if summary_text:
            sections.append(f"@SUMMARY\n{summary_text}")

    # 3. @TREE (Logical project tree with collapsed excluded directories)
    if config.include_tree:
        files_for_tree = [f for f, _, _ in included_files]
        tree_text = build_project_tree(files_for_tree, excluded_files=excluded_files).strip()
        sections.append(f"@TREE\n{tree_text}")

    # 4. @ECOSYSTEMS
    if meta['ecosystems']:
        sections.append(f"@ECOSYSTEMS\n" + "\n".join(meta['ecosystems']))

    # 5. @DEPENDENCIES
    if config.include_manifests:
        all_recs = [f for f, _, _ in included_files]
        manifest_text = render_manifest_summaries(all_recs).strip()
        if manifest_text and "No standard" not in manifest_text:
            sections.append(f"@DEPENDENCIES\n{manifest_text}")

    # 6. @ENTRY_POINTS
    entry_records = [
        f for f, _, _ in included_files
        if f.is_entry_point or (f.priority_score and f.priority_score >= 99)
    ]
    if entry_records:
        entry_lines = [
            f"• {f.relative_path.replace(chr(92), '/')} | {f.language or 'source'} | priority={f.priority_score}"
            for f in entry_records
        ]
        sections.append(f"@ENTRY_POINTS\n" + "\n".join(entry_lines))

    # 7. @RELATIONSHIPS
    if config.include_relationships:
        all_recs = [f for f, _, _ in included_files]
        rel_text = render_project_relationships(all_recs, scan_result.project_graph).strip()
        if rel_text and "No intra-project" not in rel_text:
            sections.append(f"@RELATIONSHIPS\n{rel_text}")

    # 8. @FILE_INDEX
    index_lines = ["ID   | Path | Type | Language | Size | Tokens"]
    for i, (f, _, tokens) in enumerate(included_files):
        fid = f"F{i + 1:03d}"
        rel_p = f.relative_path.replace("\\", "/")
        cat = f.effective_category.value
        lang = f.language or "text"
        size_str = format_bytes(f.size)
        index_lines.append(f"{fid} | {rel_p} | {cat} | {lang} | {size_str} | ~{tokens:,}")
    sections.append(f"@FILE_INDEX\n" + "\n".join(index_lines))

    # 9. @FILES
    files_blocks: List[str] = ["@FILES"]
    for i, (f, content, _) in enumerate(included_files):
        fid = f"F{i + 1:03d}"
        rel_p = f.relative_path.replace("\\", "/")
        cat = f.effective_category.value
        lang = (f.language or "text").lower()
        role = "entry_point" if f.is_entry_point else cat

        file_header = f'@FILE id="{fid}" path="{rel_p}" type="{role}" language="{lang}"'
        clean_content = content.strip()
        files_blocks.append(f"{file_header}\n<<<\n{clean_content}\n>>>")

    sections.append("\n\n".join(files_blocks))

    # 10. @EXCLUDED
    if config.include_excluded_list:
        ex_lines = aggregate_excluded_summary(excluded_files)
        sections.append(f"@EXCLUDED\n" + "\n".join(ex_lines))

    # 11. @SECURITY
    sec_lines = render_security_section(scan_result, excluded_files)
    sections.append(f"@SECURITY\n" + "\n".join(sec_lines))

    # 12. @END
    sections.append("@END")

    return "\n\n".join(sections) + "\n"
