"""
veilframe.folder.ai_bundle.formats.aibundle — Native .aibundle format renderer.
Conforms strictly to the VeilFrame AI Program Bundle Format v1 standard.
Plain UTF-8 text underneath with deterministic two-layer protocol.
"""

from __future__ import annotations

from collections import Counter
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
    ROADMAP.md | excluded | Context budget exhausted (file requires ~966 tokens, budget remaining: 0 tokens)
    """
    if not excluded_files:
        return ["(no files excluded)"]

    from collections import Counter, defaultdict
    dir_files: Dict[str, List[str]] = defaultdict(list)
    loose_files: List[Tuple[str, str]] = []

    for f, reason in excluded_files:
        norm = f.relative_path.replace("\\", "/").strip("/")
        parts = norm.split("/")
        if len(parts) > 1:
            top_dir = parts[0] + "/"
            dir_files[top_dir].append(reason)
        else:
            loose_files.append((norm, reason))

    lines: List[str] = []
    # Add directories sorted by file count descending
    for dir_name, reasons in sorted(dir_files.items(), key=lambda x: -len(x[1])):
        count = len(reasons)
        budget_reasons = [r for r in reasons if "budget" in r.lower()]
        security_reasons = [r for r in reasons if "security" in r.lower()]
        if budget_reasons:
            rep_reason = f"Context budget exhausted ({len(budget_reasons)} files)"
        elif security_reasons:
            rep_reason = f"Security exclusion ({len(security_reasons)} files)"
        else:
            rep_reason = Counter(reasons).most_common(1)[0][0]

        lines.append(f"{dir_name} | {count:,} files | {rep_reason}")

    # Add loose files
    for file_name, reason in loose_files:
        lines.append(f"{file_name} | excluded | {reason}")

    return lines


def render_security_section(
    scan_result: ScanResult,
    included_files: Any,
    excluded_files: Optional[List[Tuple[FileRecord, str]]] = None,
) -> List[str]:
    """
    Render structured security audit findings:
    1. Dedicated sensitive credential files excluded from context.
    2. Redacted secrets in included source files.
    """
    if excluded_files is None and isinstance(included_files, list):
        # Backward compatibility: called as render_security_section(scan_result, excluded_files)
        actual_excluded: List[Tuple[FileRecord, str]] = included_files
        actual_included: List[Tuple[FileRecord, str, int]] = []
    else:
        actual_included = included_files or []
        actual_excluded = excluded_files or []

    lines: List[str] = []

    # 1. Dedicated sensitive credential files excluded from context
    from veilframe.folder.security.sensitive_files import is_sensitive_filepath
    excluded_cred_files = [
        (f, reason) for f, reason in actual_excluded
        if is_sensitive_filepath(f.path)[0] or f.effective_category in (FileCategory.SECRET, FileCategory.CREDENTIAL, FileCategory.PRIVATE_KEY)
    ]
    if excluded_cred_files:
        lines.append("EXCLUDED CREDENTIAL FILES:")
        for sf, reason in excluded_cred_files:
            rel_p = sf.relative_path.replace("\\", "/")
            lines.append(f"  • {rel_p} | excluded | {reason}")

    # 2. Redacted secrets / inline alerts in included context files
    included_with_alerts = [
        f for f, _, _ in included_files
        if getattr(f, "secret_alerts", None)
    ]
    if included_with_alerts:
        lines.append("REDACTED INLINE SECRETS (included with safe mask):")
        for f in included_with_alerts:
            rel_p = f.relative_path.replace("\\", "/")
            for alert_desc in f.secret_alerts:
                lines.append(f"  • {rel_p} | redacted | {alert_desc}")

    if not excluded_cred_files and not included_with_alerts:
        lines.append("STATUS: CLEAN | 0 sensitive credentials or security leaks detected in bundle")

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
    inc_cat_counts: Counter[FileCategory] = Counter()
    for f, _, _ in included_files:
        inc_cat_counts[f.effective_category] += 1

    project_body = [
        f"Name: {meta['name']}",
        f"Root: {meta['root']}",
        f"Detected Languages: {', '.join(meta['languages']) if meta['languages'] else 'None'}",
        f"Detected Ecosystems: {', '.join(meta['ecosystems']) if meta['ecosystems'] else 'Generic'}",
        f"Frameworks: {', '.join(meta['frameworks']) if meta.get('frameworks') else 'None detected'}",
        f"Libraries: {', '.join(meta['libraries']) if meta.get('libraries') else 'None detected'}",
        f"Build Systems: {', '.join(meta['build_systems']) if meta.get('build_systems') else 'Standard'}",
        f"Package Managers: {', '.join(meta['package_managers']) if meta.get('package_managers') else 'Standard'}",
        f"Total Files on Disk: {meta['total_files']:,} ({meta['total_size_formatted']})",
        f"  - Discovered Source Files: {meta['source_count']:,}",
        f"  - Discovered Test Files: {meta['test_count']:,}",
        f"  - Discovered Doc Files: {meta['doc_count']:,}",
        f"  - Discovered Config / Manifest: {meta['config_count']:,}",
        f"Included in Context: {len(included_files):,} files (~{total_tokens:,} tokens)",
        f"  - Included Source: {inc_cat_counts[FileCategory.SOURCE]:,}",
        f"  - Included Docs: {inc_cat_counts[FileCategory.DOCUMENTATION]:,}",
        f"  - Included Manifests / Config: {inc_cat_counts[FileCategory.MANIFEST] + inc_cat_counts[FileCategory.CONFIG]:,}",
        f"  - Included Tests: {inc_cat_counts[FileCategory.TEST]:,}",
        f"Excluded from Context: {len(excluded_files):,} files",
        f"  - Dependencies Excluded: {meta['dependency_count']:,}",
        f"  - Caches/Build Excluded: {meta['cache_count']:,}",
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
    sec_lines = render_security_section(scan_result, included_files, excluded_files)
    sections.append(f"@SECURITY\n" + "\n".join(sec_lines))

    # 12. @END
    sections.append("@END")

    return "\n\n".join(sections) + "\n"
