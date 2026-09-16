"""
veilframe.folder.scanner.walker — Stack-based directory walker with bottom-up rollup calculations.
"""

from __future__ import annotations

import os
from typing import Callable, Dict, List, Optional, Tuple

from veilframe.folder.metadata.metadata_engine import MetadataEngine
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.folder_record import FolderRecord
from veilframe.folder.models.scan_result import ScanError
from veilframe.folder.scanner.cancellation import CancellationToken
from veilframe.folder.scanner.scan_config import ScanConfig


class DirectoryWalker:
    """Non-recursive filesystem traverser tracking folder hierarchies and rollups."""

    def __init__(
        self,
        config: ScanConfig,
        cancel_token: Optional[CancellationToken] = None,
        metadata_engine: Optional[MetadataEngine] = None,
    ) -> None:
        self.config = config
        self.cancel_token = cancel_token or CancellationToken()
        self.metadata_engine = metadata_engine or MetadataEngine(check_binary=config.enable_intelligence)

    def walk(
        self,
        root_path: str,
        on_progress: Optional[Callable[[int, int, str], None]] = None,
        on_file_found: Optional[Callable[[FileRecord], None]] = None,
        on_folder_found: Optional[Callable[[FolderRecord], None]] = None,
        on_error: Optional[Callable[[ScanError], None]] = None,
    ) -> Tuple[List[FolderRecord], List[FileRecord], List[ScanError]]:
        """Traverse the directory tree and return discovered folders, files, and errors."""
        root_abs = os.path.abspath(root_path)
        folders: List[FolderRecord] = []
        files: List[FileRecord] = []
        errors: List[ScanError] = []

        folder_id_seq = 1
        file_id_seq = 1

        folder_map: Dict[int, FolderRecord] = {}
        folder_children: Dict[int, List[int]] = {}
        folder_files: Dict[int, List[FileRecord]] = {}

        # 1. Root folder
        root_folder = FolderRecord(
            id=folder_id_seq,
            parent_id=None,
            name=os.path.basename(root_abs) or root_abs,
            path=root_abs,
            relative_path=".",
            depth=0,
        )
        folders.append(root_folder)
        folder_map[root_folder.id] = root_folder
        folder_children[root_folder.id] = []
        folder_files[root_folder.id] = []
        folder_id_seq += 1

        if on_folder_found:
            on_folder_found(root_folder)

        stack: List[Tuple[str, int, int]] = [(root_abs, 0, root_folder.id)]
        scanned_files_count = 0
        scanned_folders_count = 1

        # 2. Directory traversal via explicit stack
        while stack and not self.cancel_token.is_cancelled:
            current_dir, current_depth, current_fid = stack.pop()

            if self.config.max_depth is not None and current_depth > self.config.max_depth:
                continue

            try:
                entries = list(os.scandir(current_dir))
            except (PermissionError, OSError) as e:
                err = ScanError(path=current_dir, error_type=type(e).__name__, message=str(e))
                errors.append(err)
                if current_fid in folder_map:
                    folder_map[current_fid].error = str(e)
                if on_error:
                    on_error(err)
                continue

            for entry in entries:
                if self.cancel_token.is_cancelled:
                    break

                is_hidden = entry.name.startswith(".")
                if is_hidden and not self.config.include_hidden:
                    continue

                if entry.name in self.config.excluded_dirs:
                    continue

                try:
                    is_dir = entry.is_dir(follow_symlinks=self.config.follow_symlinks)
                except (PermissionError, OSError) as e:
                    err = ScanError(path=entry.path, error_type=type(e).__name__, message=str(e))
                    errors.append(err)
                    if on_error:
                        on_error(err)
                    continue

                if is_dir:
                    if not self.config.recursive and current_depth >= 1:
                        continue

                    rel_p = os.path.relpath(entry.path, root_abs)
                    new_folder = FolderRecord(
                        id=folder_id_seq,
                        parent_id=current_fid,
                        name=entry.name,
                        path=entry.path,
                        relative_path=rel_p,
                        depth=current_depth + 1,
                    )
                    folder_id_seq += 1
                    folders.append(new_folder)
                    folder_map[new_folder.id] = new_folder
                    folder_children[new_folder.id] = []
                    folder_files[new_folder.id] = []

                    folder_children[current_fid].append(new_folder.id)
                    folder_map[current_fid].direct_folders += 1

                    scanned_folders_count += 1
                    stack.append((entry.path, current_depth + 1, new_folder.id))

                    if on_folder_found:
                        on_folder_found(new_folder)

                    if on_progress and scanned_folders_count % 25 == 0:
                        on_progress(scanned_files_count, scanned_folders_count, entry.path)

                elif entry.is_file(follow_symlinks=self.config.follow_symlinks):
                    _, ext = os.path.splitext(entry.name)
                    ext_lower = ext.lower()

                    if self.config.excluded_extensions and ext_lower in self.config.excluded_extensions:
                        continue

                    file_rec = self.metadata_engine.extract_metadata(
                        entry=entry,
                        root_path=root_abs,
                        folder_id=current_fid,
                        file_id=file_id_seq,
                        include_created=self.config.include_created,
                        include_modified=self.config.include_modified,
                        include_accessed=self.config.include_accessed,
                        include_permissions=self.config.include_permissions,
                        follow_symlinks=self.config.follow_symlinks,
                    )
                    file_id_seq += 1
                    files.append(file_rec)
                    folder_files[current_fid].append(file_rec)
                    folder_map[current_fid].direct_files += 1

                    scanned_files_count += 1
                    if on_file_found:
                        on_file_found(file_rec)

                    if on_progress and scanned_files_count % 50 == 0:
                        on_progress(scanned_files_count, scanned_folders_count, entry.path)

        # 3. Bottom-up post-order aggregate rollup
        for fld in sorted(folders, key=lambda f: f.depth, reverse=True):
            fld_total_size = sum(f.size for f in folder_files[fld.id])
            fld_total_files = len(folder_files[fld.id])
            fld_total_folders = len(folder_children[fld.id])

            for child_fid in folder_children[fld.id]:
                child_fld = folder_map[child_fid]
                fld_total_size += child_fld.total_size
                fld_total_files += child_fld.total_files
                fld_total_folders += child_fld.total_folders

            fld.total_size = fld_total_size
            fld.total_files = fld_total_files
            fld.total_folders = fld_total_folders

        return folders, files, errors
