"""
veilframe.folder.statistics — Metric aggregation and dashboard statistics engine.
"""

from __future__ import annotations

from collections import defaultdict
from typing import List, Optional

from veilframe.folder.database import FolderDatabase
from veilframe.folder.models import (
    DuplicateGroup,
    ExtensionStat,
    FileRecord,
    FolderRecord,
    ScanError,
    ScanStats,
)


class StatisticsEngine:
    """Calculates comprehensive folder distribution, size, and depth metrics."""

    @staticmethod
    def calculate(
        root_path: str,
        files: List[FileRecord],
        folders: List[FolderRecord],
        errors: Optional[List[ScanError]] = None,
        duplicate_groups: Optional[List[DuplicateGroup]] = None,
        duration_seconds: float = 0.0,
        db: Optional[FolderDatabase] = None,
    ) -> ScanStats:
        """Compute full statistical summary for a scan."""
        err_list = errors or []
        dupe_list = duplicate_groups or []
        
        total_files = len(files)
        total_folders = len(folders)
        total_size = sum(f.size for f in files)
        
        speed = (total_files / duration_seconds) if duration_seconds > 0 else float(total_files)

        # Depth metrics
        max_depth = max((f.depth for f in folders), default=0)

        # Empty items
        empty_files = sum(1 for f in files if f.size == 0)
        empty_folders = sum(1 for f in folders if f.direct_files == 0 and f.direct_folders == 0)

        # Top largest and smallest files
        sorted_by_size = sorted([f for f in files if f.size > 0], key=lambda x: x.size, reverse=True)
        largest = sorted_by_size[:10]
        smallest = sorted(sorted_by_size, key=lambda x: x.size)[:10] if sorted_by_size else []

        # Extension distribution
        if db:
            ext_stats = db.get_extension_stats()
        else:
            ext_counts = defaultdict(int)
            ext_sizes = defaultdict(int)
            for f in files:
                ext = f.extension or "[No Extension]"
                ext_counts[ext] += 1
                ext_sizes[ext] += f.size

            ext_stats = []
            for ext, count in ext_counts.items():
                sz = ext_sizes[ext]
                pct_f = (count / max(1, total_files)) * 100.0
                pct_s = (sz / max(1, total_size)) * 100.0
                ext_stats.append(
                    ExtensionStat(
                        extension=ext,
                        file_count=count,
                        total_size=sz,
                        percentage_files=pct_f,
                        percentage_size=pct_s,
                    )
                )
            ext_stats.sort(key=lambda e: (e.total_size, e.file_count), reverse=True)

        wasted_bytes = sum(g.wasted_bytes for g in dupe_list)

        return ScanStats(
            root_path=root_path,
            total_files=total_files,
            total_folders=total_folders,
            total_size_bytes=total_size,
            duration_seconds=duration_seconds,
            scan_speed_files_per_sec=speed,
            max_depth=max_depth,
            empty_files_count=empty_files,
            empty_folders_count=empty_folders,
            largest_files=largest,
            smallest_files=smallest,
            extension_distribution=ext_stats,
            duplicate_groups=dupe_list,
            duplicate_wasted_bytes=wasted_bytes,
            error_count=len(err_list),
        )
