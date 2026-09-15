"""
veilframe.folder.cli — CLI subcommands for folder analysis, duplicate detection, and reports.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Optional

from veilframe.cli_ui import (
    Style,
    badge_info,
    badge_pass,
    badge_warn,
    print_card,
    print_section_header,
    print_table,
)
from veilframe.folder.config import ScanConfig, ScanProfile
from veilframe.folder.exporter import FolderExporter
from veilframe.folder.models import format_bytes
from veilframe.folder.scanner import FolderScanner


def add_folder_subparsers(subparsers: argparse._SubParsersAction) -> None:
    """Register 'folder' subcommands on the main VeilFrame CLI parser."""
    p_folder = subparsers.add_parser("folder", help="High-performance folder analyzer and duplicate scanner")
    folder_sub = p_folder.add_subparsers(dest="folder_command", help="Folder analysis actions")

    # 1. scan
    p_scan = folder_sub.add_parser("scan", help="Scan a directory and analyze file metadata and size")
    p_scan.add_argument("target", help="Directory path to scan")
    p_scan.add_argument("-p", "--profile", default="quick", choices=["quick", "full_meta", "integrity", "duplicates", "custom"], help="Scan profile preset")
    p_scan.add_argument("--hash", choices=["sha256", "sha1", "md5", "none"], help="Cryptographic hash algorithm")
    p_scan.add_argument("--duplicates", action="store_true", help="Enable duplicate file detection")
    p_scan.add_argument("--depth", type=int, help="Maximum recursion depth limit")
    p_scan.add_argument("--no-recursive", action="store_true", help="Do not traverse subfolders")
    p_scan.add_argument("--hidden", action="store_true", help="Include hidden files and folders")
    p_scan.add_argument("--workers", type=int, default=0, help="Number of parallel hash workers (0 = auto)")
    p_scan.add_argument("--exclude-dir", action="append", help="Exclude directory name (can specify multiple)")
    p_scan.add_argument("--exclude-ext", action="append", help="Exclude file extension (can specify multiple)")
    p_scan.add_argument("-e", "--export", help="Path to export report (format inferred from .txt, .csv, .json, .md, .html)")
    p_scan.add_argument("--json", action="store_true", help="Print JSON summary to stdout")
    p_scan.set_defaults(func=cmd_folder_scan)

    # 2. dupes
    p_dupes = folder_sub.add_parser("dupes", help="Scan a directory specifically for duplicate files")
    p_dupes.add_argument("target", help="Directory path to scan for duplicates")
    p_dupes.add_argument("--hash", default="sha256", choices=["sha256", "sha1", "md5"], help="Hash algorithm for verification")
    p_dupes.add_argument("-e", "--export", help="Path to export report (e.g. dupes.json, dupes.html)")
    p_dupes.add_argument("--json", action="store_true", help="Output duplicate groups in JSON format")
    p_dupes.set_defaults(func=cmd_folder_dupes)

    # 3. stats
    p_stats = folder_sub.add_parser("stats", help="Compute quick distribution and size statistics for a folder")
    p_stats.add_argument("target", help="Directory path to summarize")
    p_stats.add_argument("--json", action="store_true", help="Output summary in JSON format")
    p_stats.set_defaults(func=cmd_folder_stats)

    # 4. export
    p_exp = folder_sub.add_parser("export", help="Scan a folder and directly export a comprehensive report")
    p_exp.add_argument("target", help="Directory path to scan")
    p_exp.add_argument("-o", "--output", required=True, help="Destination report file (.html, .json, .csv, .md, .txt)")
    p_exp.add_argument("-p", "--profile", default="quick", choices=["quick", "full_meta", "integrity", "duplicates", "custom"], help="Scan profile")
    p_exp.set_defaults(func=cmd_folder_export)


def _build_config_from_args(args: argparse.Namespace) -> ScanConfig:
    profile_name = getattr(args, "profile", "quick")
    config = ScanConfig.from_profile(profile_name)

    if getattr(args, "hash", None):
        h = args.hash.lower()
        if h == "none":
            config.include_hash = False
            config.hash_algorithm = "none"
        else:
            config.include_hash = True
            config.hash_algorithm = h

    if getattr(args, "duplicates", False):
        config.detect_duplicates = True

    if getattr(args, "depth", None) is not None:
        config.max_depth = args.depth

    if getattr(args, "no_recursive", False):
        config.recursive = False

    if getattr(args, "hidden", False):
        config.include_hidden = True

    if getattr(args, "workers", 0) > 0:
        config.hash_workers = args.workers

    if getattr(args, "exclude_dir", None):
        for d in args.exclude_dir:
            config.excluded_dirs.add(d)

    if getattr(args, "exclude_ext", None):
        for ext in args.exclude_ext:
            config.excluded_extensions.add(ext.lower() if ext.startswith(".") else f".{ext.lower()}")

    return config


def cmd_folder_scan(args: argparse.Namespace) -> None:
    """Execute folder scan command."""
    target_path = os.path.abspath(args.target)
    if not os.path.isdir(target_path):
        print(f"Error: Target path '{target_path}' is not a valid directory.", file=sys.stderr)
        sys.exit(1)

    config = _build_config_from_args(args)
    scanner = FolderScanner(config=config)

    def _progress_cb(files_cnt: int, fld_cnt: int, curr_item: str):
        if not getattr(args, "json", False):
            sys.stdout.write(f"\rScanning... {files_cnt:,} files | {fld_cnt:,} folders")
            sys.stdout.flush()

    res = scanner.scan(target_path, on_progress=_progress_cb)
    if not getattr(args, "json", False):
        sys.stdout.write("\r" + " " * 60 + "\r")
        sys.stdout.flush()

    if getattr(args, "export", None):
        exporter = FolderExporter(res)
        out_file = exporter.export(args.export)
        if not getattr(args, "json", False):
            print(f"Report exported to: {out_file}")

    if getattr(args, "json", False):
        exporter = FolderExporter(res)
        print(exporter.to_json())
        return

    # Formatted terminal display
    _display_scan_summary(res)


def cmd_folder_dupes(args: argparse.Namespace) -> None:
    """Execute duplicate-focused folder scan."""
    target_path = os.path.abspath(args.target)
    if not os.path.isdir(target_path):
        print(f"Error: Target path '{target_path}' is not a valid directory.", file=sys.stderr)
        sys.exit(1)

    config = ScanConfig.from_profile(ScanProfile.DUPLICATES)
    if getattr(args, "hash", None):
        config.hash_algorithm = args.hash

    scanner = FolderScanner(config=config)
    res = scanner.scan(target_path)

    if getattr(args, "export", None):
        exporter = FolderExporter(res)
        out_file = exporter.export(args.export)
        print(f"Duplicate report exported to: {out_file}")

    if getattr(args, "json", False):
        payload = {
            "root_path": res.root_path,
            "duplicate_groups_count": len(res.stats.duplicate_groups) if res.stats else 0,
            "duplicate_wasted_bytes": res.stats.duplicate_wasted_bytes if res.stats else 0,
            "duplicate_groups": [g.to_dict() for g in res.stats.duplicate_groups] if res.stats else [],
        }
        print(json.dumps(payload, indent=2))
        return

    _display_duplicate_summary(res)


def cmd_folder_stats(args: argparse.Namespace) -> None:
    """Execute quick statistical summary."""
    target_path = os.path.abspath(args.target)
    if not os.path.isdir(target_path):
        print(f"Error: Target path '{target_path}' is not a valid directory.", file=sys.stderr)
        sys.exit(1)

    config = ScanConfig.from_profile(ScanProfile.QUICK)
    scanner = FolderScanner(config=config)
    res = scanner.scan(target_path)

    if getattr(args, "json", False):
        print(json.dumps(res.stats.to_dict() if res.stats else {}, indent=2))
        return

    _display_scan_summary(res)


def cmd_folder_export(args: argparse.Namespace) -> None:
    """Execute scan and directly write output report."""
    target_path = os.path.abspath(args.target)
    if not os.path.isdir(target_path):
        print(f"Error: Target path '{target_path}' is not a valid directory.", file=sys.stderr)
        sys.exit(1)

    config = ScanConfig.from_profile(getattr(args, "profile", "quick"))
    scanner = FolderScanner(config=config)
    res = scanner.scan(target_path)

    exporter = FolderExporter(res)
    out_file = exporter.export(args.output)
    print(f"Successfully exported {os.path.basename(out_file)} ({format_bytes(os.path.getsize(out_file))})")


def _display_scan_summary(res) -> None:
    stats = res.stats
    if not stats:
        return

    print_section_header("VEILFRAME FOLDER ANALYSIS SUMMARY")
    
    card_data = {
        "Root Directory": stats.root_path,
        "Total Files": f"{stats.total_files:,}",
        "Total Folders": f"{stats.total_folders:,}",
        "Total Size": f"{stats.format_total_size()} ({stats.total_size_bytes:,} bytes)",
        "Scan Duration": f"{stats.duration_seconds:.2f}s ({stats.scan_speed_files_per_sec:.1f} items/s)",
        "Max Depth": str(stats.max_depth),
        "Errors": str(stats.error_count),
    }
    if stats.duplicate_groups:
        card_data["Duplicate Groups"] = f"{len(stats.duplicate_groups)} ({stats.format_wasted_size()} wasted)"
    
    print_card("Scan Overview", card_data)

    if stats.extension_distribution:
        ext_rows = [
            [
                e.extension,
                f"{e.file_count:,}",
                e.to_dict()["total_size_formatted"],
                f"{e.percentage_size:.1f}%",
            ]
            for e in stats.extension_distribution[:8]
        ]
        print_table("Top File Types", ["Extension", "Count", "Size", "% Space"], ext_rows)

    if stats.largest_files:
        large_rows = [
            [str(i), f.name, f.format_size(), f.relative_path]
            for i, f in enumerate(stats.largest_files[:5], 1)
        ]
        print_table("Largest Files", ["#", "File Name", "Size", "Relative Path"], large_rows)


def _display_duplicate_summary(res) -> None:
    stats = res.stats
    if not stats:
        return

    print_section_header("DUPLICATE FILE DETECTION RESULTS")

    if not stats.duplicate_groups:
        print("  No duplicate files found.")
        return

    print(f"Found {len(stats.duplicate_groups)} duplicate groups wasting {stats.format_wasted_size()}:\n")
    for idx, g in enumerate(stats.duplicate_groups[:10], 1):
        print(f"Group #{idx} — {g.file_count} copies x {format_bytes(g.size)} (Wasted: {format_bytes(g.wasted_bytes)})")
        print(f"  Hash: {g.hash_value}")
        for f in g.files:
            print(f"    - {f.relative_path}")
        print()
