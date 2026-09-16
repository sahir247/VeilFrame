"""
veilframe.folder.metadata.metadata_engine — Filesystem attribute and metadata extraction engine.
"""

from __future__ import annotations

import os
import stat
from typing import Optional

from veilframe.folder.metadata.file_type import detect_file_type
from veilframe.folder.metadata.language_detector import detect_language
from veilframe.folder.models.file_record import FileRecord


class MetadataEngine:
    """Extracts filesystem attributes, detects languages, and identifies file types."""

    def __init__(self, check_binary: bool = True) -> None:
        self.check_binary = check_binary

    def extract_metadata(
        self,
        entry: os.DirEntry,
        root_path: str,
        folder_id: int,
        file_id: int,
        include_created: bool = False,
        include_modified: bool = True,
        include_accessed: bool = False,
        include_permissions: bool = False,
        follow_symlinks: bool = False,
    ) -> FileRecord:
        """Create an initialized FileRecord with extracted metadata from an os.DirEntry."""
        rel_path = os.path.relpath(entry.path, root_path)
        _, ext = os.path.splitext(entry.name)
        ext_lower = ext.lower()

        size = 0
        created = None
        modified = None
        accessed = None
        permissions = None
        err = None
        is_symlink = False

        try:
            is_symlink = entry.is_symlink()
            st = entry.stat(follow_symlinks=follow_symlinks)
            size = st.st_size
            if include_created:
                created = getattr(st, "st_birthtime", None) or st.st_ctime
            if include_modified:
                modified = st.st_mtime
            if include_accessed:
                accessed = st.st_atime
            if include_permissions:
                permissions = oct(stat.S_IMODE(st.st_mode))
        except (PermissionError, OSError) as e:
            err = str(e)

        is_binary = False
        mime_type = None
        language = None

        if not err and self.check_binary and size > 0:
            is_binary, mime_type = detect_file_type(entry.path)
            if not is_binary:
                language = detect_language(entry.path)
        elif not is_binary:
            language = detect_language(entry.path)

        return FileRecord(
            id=file_id,
            folder_id=folder_id,
            name=entry.name,
            path=entry.path,
            relative_path=rel_path,
            extension=ext_lower,
            size=size,
            created=created,
            modified=modified,
            accessed=accessed,
            permissions=permissions,
            is_hidden=entry.name.startswith("."),
            is_symlink=is_symlink,
            is_binary=is_binary,
            mime_type=mime_type,
            language=language,
            error=err,
        )
