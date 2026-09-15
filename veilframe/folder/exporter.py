"""
veilframe.folder.exporter — Export engine supporting TXT, CSV, JSON, Markdown, and interactive HTML reports.
"""

from __future__ import annotations

import csv
import html
import json
import os
from collections import defaultdict
from typing import Any, Dict, List, Optional

from veilframe.folder.models import (
    DuplicateGroup,
    ExtensionStat,
    FileRecord,
    FolderRecord,
    ScanResult,
    format_bytes,
)


class FolderExporter:
    """Multi-format report generator for scan results."""

    def __init__(self, result: ScanResult) -> None:
        self.result = result
        self.stats = result.stats
        self.files = result.files
        self.folders = result.folders
        self.errors = result.errors
        self.config = result.config

    @staticmethod
    def generate_default_filename(root_path: str, ext: str = "html") -> str:
        """
        Generate standard sanitized report filename based on the scanned folder name.
        Example: PrivacyVideoCleaner_v1_source_scan_report.html
        """
        norm_path = os.path.normpath(root_path).rstrip("/\\")
        folder_name = os.path.basename(norm_path)
        if not folder_name:
            drive, _ = os.path.splitdrive(norm_path)
            if drive:
                folder_name = f"drive_{drive.rstrip(':')}"
            else:
                folder_name = "root"
        elif folder_name in ("\\", "/", ":", "."):
            folder_name = "root"

        clean_name = "".join(c if (c.isalnum() or c in ("-", "_", " ")) else "_" for c in folder_name).strip().replace(" ", "_")
        clean_name = clean_name.strip("_")
        if not clean_name:
            clean_name = "folder"
        clean_ext = ext.lstrip(".")
        return f"{clean_name}_scan_report.{clean_ext}"

    def export(self, output_path: str, format_type: Optional[str] = None) -> str:
        """
        Export scan results to specified destination path.
        Automatically detects format from file extension if format_type is None.
        """
        out_abs = os.path.abspath(output_path)
        os.makedirs(os.path.dirname(out_abs), exist_ok=True)

        if not format_type:
            _, ext = os.path.splitext(out_abs)
            format_type = ext.lstrip(".").lower()

        format_type = format_type.lower()
        if format_type in ("txt", "text"):
            content = self.to_txt()
        elif format_type == "csv":
            content = self.to_csv()
        elif format_type == "json":
            content = self.to_json()
        elif format_type in ("md", "markdown"):
            content = self.to_markdown()
        elif format_type in ("html", "htm"):
            content = self.to_html()
        else:
            raise ValueError(f"Unsupported export format: {format_type}")

        with open(out_abs, "w", encoding="utf-8") as f:
            f.write(content)

        return out_abs

    def build_ascii_tree(self, max_depth: int = 5, max_files_per_dir: int = 25) -> str:
        """
        Generate an accurate, formatted hierarchical ASCII/Unicode tree.
        Includes full uncut cryptographic hash for hashed files.
        """
        if not self.folders:
            return "(No directory structure recorded)"

        # Index folders by id and parent_id
        folder_by_id: Dict[int, FolderRecord] = {f.id: f for f in self.folders}
        children_by_parent: Dict[Optional[int], List[FolderRecord]] = defaultdict(list)
        for f in self.folders:
            if f.parent_id is not None and f.parent_id in folder_by_id:
                children_by_parent[f.parent_id].append(f)

        # Index files by folder_id
        files_by_folder: Dict[int, List[FileRecord]] = defaultdict(list)
        for fl in self.files:
            files_by_folder[fl.folder_id].append(fl)

        lines: List[str] = []
        root_fld = self.folders[0]
        lines.append(f"{root_fld.name}/ ({root_fld.format_size()}, {root_fld.total_files:,} files, {root_fld.total_folders:,} subfolders)")

        def _render_node(folder_id: int, prefix: str, current_depth: int):
            if current_depth > max_depth:
                sub_flds = children_by_parent.get(folder_id, [])
                sub_fls = files_by_folder.get(folder_id, [])
                if sub_flds or sub_fls:
                    lines.append(f"{prefix}└── ... [{len(sub_flds)} subfolders, {len(sub_fls)} files collapsed]")
                return

            sub_flds = sorted(children_by_parent.get(folder_id, []), key=lambda x: x.name.lower())
            flist = sorted(files_by_folder.get(folder_id, []), key=lambda x: (-x.size, x.name.lower()))

            total_items = len(sub_flds) + len(flist)
            item_count = 0

            # Render Subdirectories
            for idx, sub_f in enumerate(sub_flds):
                item_count += 1
                is_last_item = (item_count == total_items)
                connector = "└── " if is_last_item else "├── "
                next_prefix = prefix + ("    " if is_last_item else "│   ")

                lines.append(f"{prefix}{connector}{sub_f.name}/ ({sub_f.format_size()}, {sub_f.total_files} files)")
                _render_node(sub_f.id, next_prefix, current_depth + 1)

            # Render Direct Files
            if flist:
                files_to_show = flist[:max_files_per_dir]
                omitted_count = len(flist) - len(files_to_show)

                for idx, fl in enumerate(files_to_show):
                    item_count += 1
                    is_last_item = (item_count == total_items and omitted_count == 0)
                    connector = "└── " if is_last_item else "├── "

                    h_tag = f" [{fl.hash_value}]" if fl.hash_value else ""
                    lines.append(f"{prefix}{connector}{fl.name} ({fl.format_size()}){h_tag}")

                if omitted_count > 0:
                    omitted_size = sum(f.size for f in flist[max_files_per_dir:])
                    lines.append(f"{prefix}└── ... (+{omitted_count} more files, {format_bytes(omitted_size)})")

        _render_node(root_fld.id, "", 1)
        return "\n".join(lines)

    def _get_active_columns(self) -> List[Dict[str, str]]:
        """Determine which columns to include based on scan configuration and populated data."""
        cols = [
            {"key": "name", "title": "File Name"},
            {"key": "relative_path", "title": "Relative Path"},
        ]

        has_size = self.config.include_size if self.config else True
        if has_size or any(f.size > 0 for f in self.files):
            cols.append({"key": "size", "title": "Size"})

        has_ext = self.config.include_extension if self.config else True
        if has_ext or any(f.extension for f in self.files):
            cols.append({"key": "extension", "title": "Extension"})

        has_mod = self.config.include_modified if self.config else True
        if has_mod or any(f.modified for f in self.files):
            cols.append({"key": "modified", "title": "Modified"})

        has_created = self.config.include_created if self.config else False
        if has_created or any(f.created for f in self.files):
            cols.append({"key": "created", "title": "Created"})

        has_accessed = self.config.include_accessed if self.config else False
        if has_accessed or any(f.accessed for f in self.files):
            cols.append({"key": "accessed", "title": "Accessed"})

        has_perms = self.config.include_permissions if self.config else False
        if has_perms or any(f.permissions for f in self.files):
            cols.append({"key": "permissions", "title": "Permissions"})

        has_hash = self.config.include_hash if self.config else False
        algo_name = (self.config.hash_algorithm if self.config else "SHA-256").upper()
        if has_hash or any(f.hash_value for f in self.files):
            cols.append({"key": "hash", "title": f"Hash ({algo_name})"})

        return cols

    def to_txt(self) -> str:
        """Generate structured text report with summary, directory tree, and file inventory."""
        lines: List[str] = []
        lines.append("=" * 80)
        lines.append(" VEILFRAME FOLDER ANALYSIS REPORT")
        lines.append("=" * 80)
        lines.append(f"Root Directory : {self.result.root_path}")
        if self.stats:
            lines.append(f"Total Files    : {self.stats.total_files:,}")
            lines.append(f"Total Folders  : {self.stats.total_folders:,}")
            lines.append(f"Total Size     : {self.stats.format_total_size()} ({self.stats.total_size_bytes:,} bytes)")
            lines.append(f"Scan Duration  : {self.stats.duration_seconds:.2f}s ({self.stats.scan_speed_files_per_sec:.1f} items/s)")
            lines.append(f"Errors Found   : {self.stats.error_count}")
            if self.stats.duplicate_groups:
                lines.append(f"Duplicates     : {len(self.stats.duplicate_groups)} groups ({self.stats.format_wasted_size()} wasted space)")
        lines.append("-" * 80)

        # Extension stats
        if self.stats and self.stats.extension_distribution:
            lines.append("\nEXTENSION BREAKDOWN:")
            lines.append(f"{'Extension':<15} {'Files':<10} {'Total Size':<15} {'% Space'}")
            lines.append("-" * 55)
            for e in self.stats.extension_distribution[:15]:
                lines.append(f"{e.extension:<15} {e.file_count:<10} {e.to_dict()['total_size_formatted']:<15} {e.percentage_size:.1f}%")

        # Largest files
        if self.stats and self.stats.largest_files:
            lines.append("\nTOP LARGEST FILES:")
            for i, f in enumerate(self.stats.largest_files[:10], 1):
                lines.append(f" {i:2d}. {f.name} ({f.format_size()}) -> {f.relative_path}")

        # Duplicates (Full 64-char Hash)
        if self.stats and self.stats.duplicate_groups:
            lines.append("\nDUPLICATE FILE GROUPS:")
            for i, g in enumerate(self.stats.duplicate_groups[:10], 1):
                lines.append(f" Group #{i}: {g.file_count} copies x {format_bytes(g.size)} = {format_bytes(g.wasted_bytes)} wasted (Hash: {g.hash_value})")
                for f in g.files:
                    lines.append(f"   - {f.relative_path}")

        # Directory structure tree (with full hashes)
        lines.append("\nDIRECTORY STRUCTURE:")
        lines.append("-" * 80)
        lines.append(self.build_ascii_tree(max_depth=6, max_files_per_dir=25))

        return "\n".join(lines)

    def to_csv(self) -> str:
        """Generate CSV export of all scanned files with full hashes and all metadata."""
        from io import StringIO
        output = StringIO()
        writer = csv.writer(output)
        writer.writerow([
            "ID", "Name", "Relative Path", "Absolute Path", "Extension",
            "Size (Bytes)", "Size Formatted", "Modified", "Created", "Accessed",
            "Permissions", "Hash", "Algorithm", "Is Hidden", "Is Symlink", "Error"
        ])

        for f in self.files:
            writer.writerow([
                f.id,
                f.name,
                f.relative_path,
                f.path,
                f.extension,
                f.size,
                f.format_size(),
                f.modified_datetime.isoformat() if f.modified_datetime else "",
                f.created_datetime.isoformat() if f.created_datetime else "",
                f.accessed_datetime.isoformat() if f.accessed_datetime else "",
                f.permissions or "",
                f.hash_value or "",
                f.hash_algorithm or "",
                "True" if f.is_hidden else "False",
                "True" if f.is_symlink else "False",
                f.error or "",
            ])
        return output.getvalue()

    def to_json(self) -> str:
        """Generate JSON export of scan metadata, statistics, and records."""
        payload: Dict[str, Any] = {
            "root_path": self.result.root_path,
            "config": self.result.config.to_dict() if hasattr(self.result.config, "to_dict") else str(self.result.config),
            "stats": self.stats.to_dict() if self.stats else None,
            "folders": [f.to_dict() for f in self.folders],
            "files": [f.to_dict() for f in self.files],
            "errors": [e.to_dict() for e in self.errors],
        }
        return json.dumps(payload, indent=2, ensure_ascii=False)

    def to_markdown(self) -> str:
        """Generate Markdown document with tables, summary metrics, directory tree, and full file inventory."""
        folder_name = os.path.basename(os.path.normpath(self.result.root_path)) or "Folder"
        lines: List[str] = []
        lines.append(f"# VeilFrame Folder Analysis: `{folder_name}`\n")
        lines.append(f"**Root Path**: `{self.result.root_path}`  ")
        if self.stats:
            lines.append(f"**Total Files**: {self.stats.total_files:,} | **Total Folders**: {self.stats.total_folders:,} | **Total Size**: {self.stats.format_total_size()}  ")
            lines.append(f"**Duration**: {self.stats.duration_seconds:.2f}s ({self.stats.scan_speed_files_per_sec:.1f} items/s) | **Errors**: {self.stats.error_count}\n")

        # Extension table
        if self.stats and self.stats.extension_distribution:
            lines.append("## File Type Distribution\n")
            lines.append("| Extension | File Count | Total Size | % of Total Size |")
            lines.append("| :--- | :--- | :--- | :--- |")
            for e in self.stats.extension_distribution[:15]:
                lines.append(f"| `{e.extension}` | {e.file_count:,} | {e.to_dict()['total_size_formatted']} | {e.percentage_size:.1f}% |")
            lines.append("")

        # Largest files table
        if self.stats and self.stats.largest_files:
            lines.append("## Largest Files\n")
            lines.append("| # | File Name | Size | Relative Path |")
            lines.append("| :--- | :--- | :--- | :--- |")
            for idx, f in enumerate(self.stats.largest_files[:10], 1):
                lines.append(f"| {idx} | `{f.name}` | {f.format_size()} | `{f.relative_path}` |")
            lines.append("")

        # Duplicate groups (with full uncut hash)
        if self.stats and self.stats.duplicate_groups:
            lines.append(f"## Duplicate Files ({len(self.stats.duplicate_groups)} Groups — {self.stats.format_wasted_size()} Wasted)\n")
            for idx, g in enumerate(self.stats.duplicate_groups[:10], 1):
                lines.append(f"### Duplicate Group #{idx} ({g.file_count} copies, {format_bytes(g.wasted_bytes)} wasted)")
                lines.append(f"- **File Size**: {format_bytes(g.size)}")
                lines.append(f"- **Full Hash**: `{g.hash_value}`")
                lines.append("- **Copies**:")
                for f in g.files:
                    lines.append(f"  - `{f.relative_path}`")
                lines.append("")

        # Comprehensive Scanned File Inventory (All Selected Metadata + Full SHA-256)
        cols = self._get_active_columns()
        if self.files:
            lines.append("## Scanned Files Inventory\n")
            header_row = "| # | " + " | ".join(c["title"] for c in cols) + " |"
            sep_row = "| :--- | " + " | ".join(":---" for _ in cols) + " |"
            lines.append(header_row)
            lines.append(sep_row)

            # Limit inventory rendering in Markdown to top 250 files to maintain editor responsiveness
            limit = 250
            for idx, f in enumerate(self.files[:limit], 1):
                row_vals = [str(idx)]
                for c in cols:
                    k = c["key"]
                    if k == "name":
                        row_vals.append(f"`{f.name}`")
                    elif k == "relative_path":
                        row_vals.append(f"`{f.relative_path}`")
                    elif k == "size":
                        row_vals.append(f.format_size())
                    elif k == "extension":
                        row_vals.append(f"`{f.extension}`" if f.extension else "")
                    elif k == "modified":
                        row_vals.append(f.modified_datetime.strftime("%Y-%m-%d %H:%M:%S") if f.modified_datetime else "")
                    elif k == "created":
                        row_vals.append(f.created_datetime.strftime("%Y-%m-%d %H:%M:%S") if f.created_datetime else "")
                    elif k == "accessed":
                        row_vals.append(f.accessed_datetime.strftime("%Y-%m-%d %H:%M:%S") if f.accessed_datetime else "")
                    elif k == "permissions":
                        row_vals.append(f.permissions or "")
                    elif k == "hash":
                        row_vals.append(f"`{f.hash_value}`" if f.hash_value else "")
                lines.append("| " + " | ".join(row_vals) + " |")

            if len(self.files) > limit:
                lines.append(f"\n*Note: Displaying first {limit} of {len(self.files):,} files. Full dataset available in HTML, JSON, and CSV exports.*\n")
            else:
                lines.append("")

        # Directory structure tree (with full hashes)
        lines.append("## Directory Tree Structure\n")
        lines.append("```text")
        lines.append(self.build_ascii_tree(max_depth=5, max_files_per_dir=20))
        lines.append("```\n")

        return "\n".join(lines)

    def to_html(self) -> str:
        """Generate interactive, responsive HTML report with collapsible folder tree and copyable full hashes."""
        folder_name = os.path.basename(os.path.normpath(self.result.root_path)) or "Folder"
        title = html.escape(f"VeilFrame Analysis - {folder_name}")
        root_esc = html.escape(self.result.root_path)

        total_files = f"{self.stats.total_files:,}" if self.stats else str(len(self.files))
        total_folders = f"{self.stats.total_folders:,}" if self.stats else str(len(self.folders))
        total_size = self.stats.format_total_size() if self.stats else format_bytes(sum(f.size for f in self.files))
        duration = f"{self.stats.duration_seconds:.2f}s" if self.stats else "N/A"
        wasted_size = self.stats.format_wasted_size() if self.stats else "0 B"

        ext_rows = ""
        if self.stats:
            for e in self.stats.extension_distribution[:12]:
                ext_rows += f"""
                <tr>
                    <td><code>{html.escape(e.extension)}</code></td>
                    <td>{e.file_count:,}</td>
                    <td>{html.escape(e.to_dict()['total_size_formatted'])}</td>
                    <td>
                        <div style="background: rgba(255,255,255,0.1); border-radius: 4px; overflow: hidden; height: 10px; width: 80px;">
                            <div style="background: #38bdf8; height: 100%; width: {min(100, max(2, e.percentage_size))}%;"></div>
                        </div>
                    </td>
                </tr>
                """

        largest_rows = ""
        if self.stats:
            for idx, f in enumerate(self.stats.largest_files[:10], 1):
                largest_rows += f"""
                <tr>
                    <td>{idx}</td>
                    <td><strong>{html.escape(f.name)}</strong></td>
                    <td>{html.escape(f.format_size())}</td>
                    <td><code>{html.escape(f.relative_path)}</code></td>
                </tr>
                """

        dupe_sections = ""
        if self.stats and self.stats.duplicate_groups:
            for idx, g in enumerate(self.stats.duplicate_groups[:10], 1):
                file_items = "".join([f"<li><code>{html.escape(f.relative_path)}</code></li>" for f in g.files])
                dupe_sections += f"""
                <div class="card dupe-card">
                    <h4>Duplicate Group #{idx} <span class="badge badge-warn">{g.file_count} copies &bull; {format_bytes(g.wasted_bytes)} wasted</span></h4>
                    <p style="color: #94a3b8; font-size: 0.85rem; margin: 4px 0;">
                        Full Hash: <code class="copyable-hash" title="Click to copy full hash" onclick="copyHash('{html.escape(g.hash_value)}')">{html.escape(g.hash_value)}</code> | Size: {format_bytes(g.size)}
                    </p>
                    <ul class="file-list">
                        {file_items}
                    </ul>
                </div>
                """

        # Build Interactive HTML Collapsible Tree with Full Hashes
        folder_by_id = {f.id: f for f in self.folders}
        children_by_parent: Dict[Optional[int], List[FolderRecord]] = defaultdict(list)
        for f in self.folders:
            if f.parent_id is not None and f.parent_id in folder_by_id:
                children_by_parent[f.parent_id].append(f)

        files_by_folder: Dict[int, List[FileRecord]] = defaultdict(list)
        for fl in self.files:
            files_by_folder[fl.folder_id].append(fl)

        def _render_html_tree(folder_id: int, depth: int) -> str:
            fld = folder_by_id.get(folder_id)
            if not fld:
                return ""

            sub_flds = sorted(children_by_parent.get(folder_id, []), key=lambda x: x.name.lower())
            flist = sorted(files_by_folder.get(folder_id, []), key=lambda x: (-x.size, x.name.lower()))

            open_attr = "open" if depth < 2 else ""
            res_html = f"""
            <details class="tree-dir" {open_attr}>
                <summary>
                    <span class="folder-name">{html.escape(fld.name)}/</span>
                    <span class="tree-meta">{fld.format_size()} &bull; {fld.total_files} files</span>
                </summary>
                <div class="tree-children">
            """

            for sf in sub_flds:
                res_html += _render_html_tree(sf.id, depth + 1)

            if flist:
                res_html += '<ul class="tree-files">'
                for fl in flist[:60]:
                    hash_badge = (
                        f'<code class="copyable-hash tree-hash" title="Click to copy full hash" '
                        f'onclick="copyHash(\'{html.escape(fl.hash_value)}\')">[{html.escape(fl.hash_value)}]</code>'
                    ) if fl.hash_value else ""
                    res_html += f'<li><span class="file-name">{html.escape(fl.name)}</span> <span class="file-size">{fl.format_size()}</span> {hash_badge}</li>'
                if len(flist) > 60:
                    res_html += f'<li class="tree-omitted">... and {len(flist) - 60} more files in this directory</li>'
                res_html += '</ul>'

            res_html += '</div></details>'
            return res_html

        root_tree_html = _render_html_tree(self.folders[0].id, 0) if self.folders else "<p>No folder structure</p>"

        # Build Interactive Inventory Table
        cols = self._get_active_columns()
        th_elements = "".join(f"<th>{html.escape(c['title'])}</th>" for c in cols)

        inv_rows = ""
        inv_limit = 500
        for idx, fl in enumerate(self.files[:inv_limit], 1):
            row_tds = ""
            for c in cols:
                k = c["key"]
                if k == "name":
                    row_tds += f"<td><strong>{html.escape(fl.name)}</strong></td>"
                elif k == "relative_path":
                    row_tds += f"<td><code>{html.escape(fl.relative_path)}</code></td>"
                elif k == "size":
                    row_tds += f"<td>{html.escape(fl.format_size())}</td>"
                elif k == "extension":
                    row_tds += f"<td><code>{html.escape(fl.extension)}</code></td>"
                elif k == "modified":
                    m_str = fl.modified_datetime.strftime("%Y-%m-%d %H:%M:%S") if fl.modified_datetime else ""
                    row_tds += f"<td>{html.escape(m_str)}</td>"
                elif k == "created":
                    c_str = fl.created_datetime.strftime("%Y-%m-%d %H:%M:%S") if fl.created_datetime else ""
                    row_tds += f"<td>{html.escape(c_str)}</td>"
                elif k == "accessed":
                    a_str = fl.accessed_datetime.strftime("%Y-%m-%d %H:%M:%S") if fl.accessed_datetime else ""
                    row_tds += f"<td>{html.escape(a_str)}</td>"
                elif k == "permissions":
                    row_tds += f"<td><code>{html.escape(fl.permissions or '')}</code></td>"
                elif k == "hash":
                    if fl.hash_value:
                        row_tds += f'<td><code class="copyable-hash" title="Click to copy full hash" onclick="copyHash(\'{html.escape(fl.hash_value)}\')">{html.escape(fl.hash_value)}</code></td>'
                    else:
                        row_tds += "<td>—</td>"
            inv_rows += f"<tr>{row_tds}</tr>\n"

        return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title}</title>
    <style>
        :root {{
            --bg: #0d1117;
            --card-bg: #161b22;
            --text: #e6edf3;
            --text-dim: #8b949e;
            --accent: #38bdf8;
            --accent-dim: #0284c7;
            --border: #30363d;
            --warn: #f43f5e;
            --success: #10b981;
        }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background-color: var(--bg);
            color: var(--text);
            margin: 0;
            padding: 24px;
            line-height: 1.5;
        }}
        .container {{
            max-width: 1250px;
            margin: 0 auto;
        }}
        h1, h2, h3, h4 {{
            margin-top: 0;
            color: #fff;
            letter-spacing: -0.02em;
        }}
        .header {{
            margin-bottom: 24px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 16px;
        }}
        .badge {{
            background: #2563eb;
            color: #fff;
            padding: 2px 8px;
            border-radius: 999px;
            font-size: 0.75rem;
            font-weight: 600;
        }}
        .badge-warn {{
            background: var(--warn);
        }}
        .grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 14px;
            margin-bottom: 24px;
        }}
        .card {{
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 14px;
        }}
        .stat-value {{
            font-size: 1.6rem;
            font-weight: 700;
            color: var(--accent);
            margin-top: 4px;
        }}
        .stat-label {{
            color: var(--text-dim);
            font-size: 0.8rem;
            text-transform: uppercase;
            letter-spacing: 0.05em;
        }}
        table {{
            width: 100%;
            border-collapse: collapse;
            margin-top: 8px;
        }}
        th, td {{
            text-align: left;
            padding: 8px 10px;
            border-bottom: 1px solid var(--border);
            font-size: 0.88rem;
        }}
        th {{
            color: var(--text-dim);
            font-size: 0.75rem;
            text-transform: uppercase;
            letter-spacing: 0.05em;
        }}
        code {{
            font-family: Consolas, monospace;
            background: rgba(0,0,0,0.3);
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 0.85rem;
            color: var(--accent);
        }}
        .copyable-hash {{
            cursor: pointer;
            word-break: break-all;
            display: inline-block;
            transition: all 0.15s ease;
            user-select: all;
            border: 1px solid rgba(56, 189, 248, 0.2);
        }}
        .copyable-hash:hover {{
            background: rgba(56, 189, 248, 0.2);
            border-color: var(--accent);
            color: #ffffff;
            box-shadow: 0 0 8px rgba(56, 189, 248, 0.4);
        }}
        .dupe-card {{
            margin-bottom: 12px;
        }}
        .file-list {{
            margin: 8px 0 0 0;
            padding-left: 20px;
            color: var(--text-dim);
            font-size: 0.9rem;
        }}
        .search-bar {{
            margin-bottom: 12px;
        }}
        .search-bar input {{
            width: 100%;
            background: #0d1117;
            border: 1px solid var(--border);
            border-radius: 4px;
            color: #fff;
            padding: 8px 12px;
            box-sizing: border-box;
            font-size: 0.9rem;
        }}
        .search-bar input:focus {{
            outline: none;
            border-color: var(--accent);
        }}
        /* Interactive Tree */
        .tree-container {{
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 16px;
            margin-top: 24px;
            font-family: Consolas, monospace;
            font-size: 0.88rem;
        }}
        details.tree-dir {{
            margin: 4px 0;
            padding-left: 12px;
            border-left: 1px solid rgba(255,255,255,0.08);
        }}
        details.tree-dir > summary {{
            cursor: pointer;
            padding: 3px 6px;
            border-radius: 4px;
            list-style: none;
            user-select: none;
        }}
        details.tree-dir > summary:hover {{
            background: rgba(255,255,255,0.05);
        }}
        .folder-name {{
            font-weight: 700;
            color: #60a5fa;
        }}
        .tree-meta {{
            color: var(--text-dim);
            font-size: 0.78rem;
            margin-left: 8px;
        }}
        .tree-files {{
            list-style: none;
            padding-left: 24px;
            margin: 4px 0;
        }}
        .tree-files li {{
            padding: 2px 0;
            color: #cbd5e1;
        }}
        .file-size {{
            color: var(--text-dim);
            font-size: 0.8rem;
            margin-left: 6px;
        }}
        .tree-hash {{
            font-size: 0.75rem;
            margin-left: 6px;
            color: #93c5fd;
        }}
        .tree-omitted {{
            color: #eab308;
            font-style: italic;
        }}
        /* Toast notification */
        #toast {{
            visibility: hidden;
            min-width: 250px;
            background-color: #1e293b;
            color: #fff;
            text-align: center;
            border-radius: 6px;
            border: 1px solid var(--accent);
            padding: 12px 16px;
            position: fixed;
            z-index: 1000;
            left: 50%;
            bottom: 30px;
            transform: translateX(-50%);
            font-size: 0.88rem;
            box-shadow: 0 4px 12px rgba(0,0,0,0.5);
        }}
        #toast.show {{
            visibility: visible;
            animation: fadein 0.3s, fadeout 0.3s 2.2s;
        }}
        @keyframes fadein {{
            from {{ bottom: 10px; opacity: 0; }}
            to {{ bottom: 30px; opacity: 1; }}
        }}
        @keyframes fadeout {{
            from {{ bottom: 30px; opacity: 1; }}
            to {{ bottom: 10px; opacity: 0; }}
        }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>VeilFrame Folder Analysis</h1>
            <p style="color: var(--text-dim); margin: 0;">Target: <code>{root_esc}</code></p>
        </div>

        <div class="grid">
            <div class="card">
                <div class="stat-label">Total Files</div>
                <div class="stat-value">{total_files}</div>
            </div>
            <div class="card">
                <div class="stat-label">Total Folders</div>
                <div class="stat-value">{total_folders}</div>
            </div>
            <div class="card">
                <div class="stat-label">Total Size</div>
                <div class="stat-value">{total_size}</div>
            </div>
            <div class="card">
                <div class="stat-label">Duplicate Space Wasted</div>
                <div class="stat-value" style="color: var(--warn);">{wasted_size}</div>
            </div>
        </div>

        <div class="grid" style="grid-template-columns: 1fr 1fr;">
            <div class="card">
                <h3>File Types</h3>
                <table>
                    <thead>
                        <tr>
                            <th>Type</th>
                            <th>Count</th>
                            <th>Size</th>
                            <th>Share</th>
                        </tr>
                    </thead>
                    <tbody>
                        {ext_rows}
                    </tbody>
                </table>
            </div>

            <div class="card">
                <h3>Top Largest Files</h3>
                <table>
                    <thead>
                        <tr>
                            <th>#</th>
                            <th>Name</th>
                            <th>Size</th>
                            <th>Path</th>
                        </tr>
                    </thead>
                    <tbody>
                        {largest_rows}
                    </tbody>
                </table>
            </div>
        </div>

        {f'<div style="margin-top: 24px;"><h3>Duplicate File Groups</h3>{dupe_sections}</div>' if dupe_sections else ''}

        <div class="card" style="margin-top: 24px;">
            <h3>Scanned Files Inventory</h3>
            <p style="color: var(--text-dim); font-size: 0.85rem; margin-top: -4px;">Click on any SHA-256 hash to copy the full 64-character hash to your clipboard.</p>
            <div class="search-bar">
                <input type="text" id="inventorySearch" placeholder="Filter files by name, path, extension, or hash..." onkeyup="filterInventoryTable()">
            </div>
            <div style="overflow-x: auto; max-height: 480px;">
                <table id="inventoryTable">
                    <thead>
                        <tr>
                            {th_elements}
                        </tr>
                    </thead>
                    <tbody>
                        {inv_rows}
                    </tbody>
                </table>
            </div>
            {f'<p style="color: var(--text-dim); font-size: 0.78rem; margin-top: 8px;">Showing first {inv_limit} of {len(self.files):,} files.</p>' if len(self.files) > inv_limit else ''}
        </div>

        <div class="tree-container">
            <h3>Directory Tree Structure</h3>
            <p style="color: var(--text-dim); font-size: 0.8rem; margin-top: -4px;">Click folders to expand or collapse subtrees. Click any hash to copy to clipboard.</p>
            {root_tree_html}
        </div>
    </div>

    <div id="toast">SHA-256 copied to clipboard!</div>

    <script>
        function copyHash(hash) {{
            if (!hash) return;
            navigator.clipboard.writeText(hash).then(function() {{
                showToast("Copied full hash to clipboard: " + hash.substring(0, 16) + "...");
            }}).catch(function(err) {{
                showToast("Copied: " + hash);
            }});
        }}

        function showToast(msg) {{
            var x = document.getElementById("toast");
            x.textContent = msg;
            x.className = "show";
            setTimeout(function(){{ x.className = x.className.replace("show", ""); }}, 2500);
        }}

        function filterInventoryTable() {{
            var input = document.getElementById("inventorySearch");
            var filter = input.value.toLowerCase();
            var table = document.getElementById("inventoryTable");
            var tr = table.getElementsByTagName("tr");

            for (var i = 1; i < tr.length; i++) {{
                var text = tr[i].textContent || tr[i].innerText;
                if (text.toLowerCase().indexOf(filter) > -1) {{
                    tr[i].style.display = "";
                }} else {{
                    tr[i].style.display = "none";
                }}
            }}
        }}
    </script>
</body>
</html>"""
