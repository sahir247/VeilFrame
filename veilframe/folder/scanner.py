"""
veilframe.folder.scanner — Fast, configurable directory walker and analyzer engine.

Key architectural properties:
1. "Scan only what the user asks for, and only read file contents when requested."
2. Explicit stack queue with os.scandir() — no deep Python call-stack recursion.
3. Bottom-up aggregate rollups for directory file counts and byte sizes.
4. Bounded multi-threaded hashing and staged duplicate detection.
5. SQLite result persistence and real-time progress callbacks.
"""

from __future__ import annotations

import os
import stat
import threading
import time
from typing import Callable, Dict, List, Optional

from veilframe.folder.config import ScanConfig
from veilframe.folder.database import FolderDatabase
from veilframe.folder.duplicate import DuplicateFinder
from veilframe.folder.hasher import ParallelHashEngine
from veilframe.folder.models import (
    DuplicateGroup,
    FileRecord,
    FolderRecord,
    ScanError,
    ScanResult,
)
from veilframe.folder.statistics import StatisticsEngine


class FolderScanner:
    """High-performance directory scanner and metadata extractor."""

    def __init__(self, config: Optional[ScanConfig] = None) -> None:
        self.config = config or ScanConfig()
        self._cancel_event = threading.Event()
        self._is_running = False

    def cancel(self) -> None:
        """Signal the scanner to stop discovering files and abort gracefully."""
        self._cancel_event.set()

    @property
    def is_cancelled(self) -> bool:
        return self._cancel_event.is_set()

    def scan(
        self,
        root_path: str,
        db_path: str = ":memory:",
        on_progress: Optional[Callable[[int, int, str], None]] = None,
        on_file_found: Optional[Callable[[FileRecord], None]] = None,
        on_folder_found: Optional[Callable[[FolderRecord], None]] = None,
        on_error: Optional[Callable[[ScanError], None]] = None,
    ) -> ScanResult:
        """Execute the directory scan according to the current configuration."""
        self._is_running = True
        start_time = time.time()

        root_abs = os.path.abspath(root_path)
        if not os.path.exists(root_abs):
            raise FileNotFoundError(f"Root path does not exist: {root_abs}")
        if not os.path.isdir(root_abs):
            raise NotADirectoryError(f"Root path is not a directory: {root_abs}")

        db = FolderDatabase(db_path=db_path)

        folders: List[FolderRecord] = []
        files: List[FileRecord] = []
        errors: List[ScanError] = []

        folder_id_seq = 1
        file_id_seq = 1

        # Map folder_id -> FolderRecord for hierarchy rollup
        folder_map: Dict[int, FolderRecord] = {}
        # Map folder_id -> list of child folder_ids
        folder_children: Dict[int, List[int]] = {}
        # Map folder_id -> list of file_ids
        folder_files: Dict[int, List[FileRecord]] = {}

        # ----------------------------------------------------
        # 1. ROOT FOLDER RECORD
        # ----------------------------------------------------
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

        # Explicit stack: (directory_abs_path, depth, current_folder_id)
        stack: List[tuple[str, int, int]] = [(root_abs, 0, root_folder.id)]

        scanned_files_count = 0
        scanned_folders_count = 1

        # ----------------------------------------------------
        # 2. DIRECTORY TRAVERSAL (Explicit Stack + os.scandir)
        # ----------------------------------------------------
        try:
            while stack and not self._cancel_event.is_set():
                current_dir, current_depth, current_fid = stack.pop()

                if self.config.max_depth is not None and current_depth > self.config.max_depth:
                    continue

                try:
                    entries = list(os.scandir(current_dir))
                except (PermissionError, OSError) as e:
                    err = ScanError(
                        path=current_dir,
                        error_type=type(e).__name__,
                        message=str(e),
                    )
                    errors.append(err)
                    if current_fid in folder_map:
                        folder_map[current_fid].error = str(e)
                    if on_error:
                        on_error(err)
                    continue

                # Separate subdirectories and files
                for entry in entries:
                    if self._cancel_event.is_set():
                        break

                    # Check hidden status
                    is_hidden = entry.name.startswith(".")
                    if is_hidden and not self.config.include_hidden:
                        continue

                    # Check excluded dir names
                    if entry.name in self.config.excluded_dirs:
                        continue

                    try:
                        is_dir = entry.is_dir(follow_symlinks=self.config.follow_symlinks)
                        is_symlink = entry.is_symlink()
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
                        # File Processing
                        _, ext = os.path.splitext(entry.name)
                        ext_lower = ext.lower()

                        if self.config.excluded_extensions and ext_lower in self.config.excluded_extensions:
                            continue

                        file_size = 0
                        created_t = None
                        modified_t = None
                        accessed_t = None
                        perm_str = None
                        file_err = None

                        if self.config.requires_stat:
                            try:
                                st = entry.stat(follow_symlinks=self.config.follow_symlinks)
                                file_size = st.st_size
                                if self.config.include_created:
                                    created_t = getattr(st, "st_birthtime", None) or st.st_ctime
                                if self.config.include_modified:
                                    modified_t = st.st_mtime
                                if self.config.include_accessed:
                                    accessed_t = st.st_atime
                                if self.config.include_permissions:
                                    perm_str = oct(stat.S_IMODE(st.st_mode))
                            except (PermissionError, OSError) as e:
                                file_err = str(e)
                                err = ScanError(path=entry.path, error_type=type(e).__name__, message=str(e))
                                errors.append(err)
                                if on_error:
                                    on_error(err)

                        rel_file_p = os.path.relpath(entry.path, root_abs)
                        file_rec = FileRecord(
                            id=file_id_seq,
                            folder_id=current_fid,
                            name=entry.name if self.config.include_name else "",
                            path=entry.path,
                            relative_path=rel_file_p,
                            extension=ext_lower if self.config.include_extension else "",
                            size=file_size if self.config.include_size else 0,
                            created=created_t,
                            modified=modified_t,
                            accessed=accessed_t,
                            permissions=perm_str,
                            is_hidden=is_hidden,
                            is_symlink=is_symlink,
                            error=file_err,
                        )
                        file_id_seq += 1
                        files.append(file_rec)
                        folder_files[current_fid].append(file_rec)
                        folder_map[current_fid].direct_files += 1

                        scanned_files_count += 1
                        if on_file_found:
                            on_file_found(file_rec)

                        if on_progress and scanned_files_count % 100 == 0:
                            on_progress(scanned_files_count, scanned_folders_count, entry.path)

        except Exception as ex:
            err = ScanError(path=root_abs, error_type=type(ex).__name__, message=f"Scan loop error: {ex}")
            errors.append(err)
            if on_error:
                on_error(err)

        # ----------------------------------------------------
        # 3. DIRECTORY AGGREGATE ROLLUP (Bottom-Up)
        # ----------------------------------------------------
        # Sort folders by depth descending to propagate totals up to root
        sorted_folders = sorted(folders, key=lambda f: f.depth, reverse=True)
        for fld in sorted_folders:
            direct_sz = sum(f.size for f in folder_files.get(fld.id, []))
            direct_cnt = len(folder_files.get(fld.id, []))

            child_sz = sum(folder_map[cid].total_size for cid in folder_children.get(fld.id, []))
            child_file_cnt = sum(folder_map[cid].total_files for cid in folder_children.get(fld.id, []))
            child_fld_cnt = sum(1 + folder_map[cid].total_folders for cid in folder_children.get(fld.id, []))

            fld.total_size = direct_sz + child_sz
            fld.total_files = direct_cnt + child_file_cnt
            fld.total_folders = child_fld_cnt

        # ----------------------------------------------------
        # 4. OPTIONAL HASHING PIPELINE
        # ----------------------------------------------------
        if self.config.include_hash and not self._cancel_event.is_set():
            if on_progress:
                on_progress(scanned_files_count, scanned_folders_count, "Hashing files...")
            
            hash_engine = ParallelHashEngine(
                workers=self.config.effective_hash_workers,
                algorithm=self.config.hash_algorithm,
                chunk_size=self.config.chunk_size_bytes,
            )
            
            def _hash_cb(cur: int, tot: int, rec: FileRecord):
                if on_progress and cur % 20 == 0:
                    on_progress(cur, tot, f"Hashed {rec.name} ({cur}/{tot})")

            hash_engine.hash_files(files, progress_callback=_hash_cb)

        # ----------------------------------------------------
        # 5. OPTIONAL DUPLICATE DETECTION PIPELINE
        # ----------------------------------------------------
        duplicate_groups: List[DuplicateGroup] = []
        if self.config.detect_duplicates and not self._cancel_event.is_set():
            finder = DuplicateFinder(
                hash_algorithm=self.config.hash_algorithm if self.config.include_hash else "sha256",
                workers=self.config.effective_hash_workers,
                chunk_size=self.config.chunk_size_bytes,
            )
            
            def _dupe_cb(msg: str, cur: int, tot: int):
                if on_progress:
                    on_progress(cur, tot, msg)

            duplicate_groups = finder.find_duplicates(
                files=files,
                staged=self.config.duplicate_staged_hash,
                progress_callback=_dupe_cb,
            )

        # ----------------------------------------------------
        # 6. INGEST INTO SQLITE DATABASE
        # ----------------------------------------------------
        db.insert_folders(folders)
        db.insert_files(files)
        db.insert_errors(errors)

        duration = time.time() - start_time
        stats = StatisticsEngine.calculate(
            root_path=root_abs,
            files=files,
            folders=folders,
            errors=errors,
            duplicate_groups=duplicate_groups,
            duration_seconds=duration,
            db=db,
        )

        if on_progress:
            on_progress(scanned_files_count, scanned_folders_count, "Scan completed.")

        self._is_running = False

        return ScanResult(
            config=self.config,
            root_path=root_abs,
            files=files,
            folders=folders,
            errors=errors,
            stats=stats,
            db_path=db_path,
            is_cancelled=self._cancel_event.is_set(),
        )

