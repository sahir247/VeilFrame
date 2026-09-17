"""
veilframe.folder.database.folder_db — SQLite persistence for folder scans, file records, and AI classifications.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Dict, Generator, List, Optional, Tuple

from veilframe.folder.models.classification import (
    AIAction,
    ClassificationResult,
    FileCategory,
)
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.folder_record import FolderRecord
from veilframe.folder.models.scan_result import (
    DuplicateGroup,
    ExtensionStat,
    ScanError,
)


class FolderDatabase:
    """SQLite database abstraction for persisting and querying folder scans and classifications."""

    def __init__(self, db_path: str = ":memory:") -> None:
        self.db_path = db_path
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        if self.db_path != ":memory:":
            self._conn.execute("PRAGMA journal_mode = WAL;")
            self._conn.execute("PRAGMA synchronous = NORMAL;")
        self._init_db()

    @contextmanager
    def _connect(self) -> Generator[sqlite3.Connection, None, None]:
        with self._lock:
            try:
                yield self._conn
                self._conn.commit()
            except Exception:
                self._conn.rollback()
                raise

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.close()
            except Exception:
                pass

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS scan_info (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    root_path TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    started_at REAL NOT NULL,
                    finished_at REAL,
                    status TEXT NOT NULL,
                    total_files INTEGER DEFAULT 0,
                    total_folders INTEGER DEFAULT 0,
                    total_size INTEGER DEFAULT 0,
                    detected_ecosystems_json TEXT
                );

                CREATE TABLE IF NOT EXISTS folders (
                    id INTEGER PRIMARY KEY,
                    parent_id INTEGER,
                    name TEXT NOT NULL,
                    path TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    depth INTEGER NOT NULL,
                    direct_files INTEGER DEFAULT 0,
                    direct_folders INTEGER DEFAULT 0,
                    total_files INTEGER DEFAULT 0,
                    total_folders INTEGER DEFAULT 0,
                    total_size INTEGER DEFAULT 0,
                    error TEXT,
                    FOREIGN KEY(parent_id) REFERENCES folders(id)
                );

                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY,
                    folder_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    path TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    extension TEXT,
                    size INTEGER,
                    created REAL,
                    modified REAL,
                    accessed REAL,
                    permissions TEXT,
                    hash_value TEXT,
                    hash_algorithm TEXT,
                    quick_fingerprint TEXT,
                    is_hidden INTEGER DEFAULT 0,
                    is_symlink INTEGER DEFAULT 0,
                    error TEXT,
                    category TEXT DEFAULT 'UNKNOWN',
                    action TEXT DEFAULT 'ANALYZE',
                    confidence REAL DEFAULT 0.5,
                    rule_id TEXT DEFAULT 'default',
                    language TEXT,
                    priority_score INTEGER DEFAULT 50,
                    token_count INTEGER DEFAULT 0,
                    is_entry_point INTEGER DEFAULT 0,
                    is_secret INTEGER DEFAULT 0,
                    FOREIGN KEY(folder_id) REFERENCES folders(id)
                );

                CREATE TABLE IF NOT EXISTS scan_errors (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    error_type TEXT NOT NULL,
                    message TEXT NOT NULL,
                    timestamp REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_files_folder ON files(folder_id);
                CREATE INDEX IF NOT EXISTS idx_files_name ON files(name);
                CREATE INDEX IF NOT EXISTS idx_files_ext ON files(extension);
                CREATE INDEX IF NOT EXISTS idx_files_size ON files(size);
                CREATE INDEX IF NOT EXISTS idx_files_hash ON files(hash_value);
                CREATE INDEX IF NOT EXISTS idx_files_fingerprint ON files(quick_fingerprint);
                CREATE INDEX IF NOT EXISTS idx_files_category ON files(category);
                CREATE INDEX IF NOT EXISTS idx_files_action ON files(action);
                CREATE INDEX IF NOT EXISTS idx_files_priority ON files(priority_score);
                CREATE INDEX IF NOT EXISTS idx_folders_parent ON folders(parent_id);
                CREATE INDEX IF NOT EXISTS idx_folders_path ON folders(path);
            """)

    def insert_folders(self, folders: List[FolderRecord]) -> None:
        if not folders:
            return
        query = """
            INSERT OR REPLACE INTO folders (
                id, parent_id, name, path, relative_path, depth,
                direct_files, direct_folders, total_files, total_folders, total_size, error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        rows = [
            (
                f.id, f.parent_id, f.name, f.path, f.relative_path, f.depth,
                f.direct_files, f.direct_folders, f.total_files, f.total_folders, f.total_size, f.error
            )
            for f in folders
        ]
        with self._connect() as conn:
            conn.executemany(query, rows)

    def insert_files(self, files: List[FileRecord]) -> None:
        if not files:
            return
        query = """
            INSERT OR REPLACE INTO files (
                id, folder_id, name, path, relative_path, extension, size,
                created, modified, accessed, permissions, hash_value, hash_algorithm,
                quick_fingerprint, is_hidden, is_symlink, error,
                category, action, confidence, rule_id, language,
                priority_score, token_count, is_entry_point, is_secret
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        rows = [
            (
                f.id, f.folder_id, f.name, f.path, f.relative_path, f.extension, f.size,
                f.created, f.modified, f.accessed, f.permissions, f.hash_value, f.hash_algorithm,
                f.quick_fingerprint, 1 if f.is_hidden else 0, 1 if f.is_symlink else 0, f.error,
                f.effective_category.value, f.effective_action.value,
                f.classification.confidence, f.classification.rule_id, f.language,
                f.priority_score, f.token_count, 1 if f.is_entry_point else 0, 1 if f.is_secret else 0
            )
            for f in files
        ]
        with self._connect() as conn:
            conn.executemany(query, rows)

    def update_file_hashes(self, file_updates: List[Tuple[int, Optional[str], Optional[str], Optional[str]]]) -> None:
        if not file_updates:
            return
        query = """
            UPDATE files
            SET hash_value = ?, hash_algorithm = ?, error = COALESCE(?, error)
            WHERE id = ?
        """
        params = [(h, algo, err, fid) for fid, h, algo, err in file_updates]
        with self._connect() as conn:
            conn.executemany(query, params)

    def update_folder_aggregates(self, folder_updates: List[FolderRecord]) -> None:
        if not folder_updates:
            return
        query = """
            UPDATE folders
            SET total_files = ?, total_folders = ?, total_size = ?
            WHERE id = ?
        """
        params = [(f.total_files, f.total_folders, f.total_size, f.id) for f in folder_updates]
        with self._connect() as conn:
            conn.executemany(query, params)

    def insert_errors(self, errors: List[ScanError]) -> None:
        if not errors:
            return
        query = "INSERT INTO scan_errors (path, error_type, message, timestamp) VALUES (?, ?, ?, ?)"
        rows = [(e.path, e.error_type, e.message, e.timestamp) for e in errors]
        with self._connect() as conn:
            conn.executemany(query, rows)

    def search_files(
        self,
        name_query: Optional[str] = None,
        extension: Optional[str] = None,
        min_size: Optional[int] = None,
        max_size: Optional[int] = None,
        has_hash: Optional[bool] = None,
        limit: int = 1000,
        offset: int = 0,
    ) -> List[FileRecord]:
        conditions = []
        params: List[Any] = []

        if name_query:
            conditions.append("(name LIKE ? OR relative_path LIKE ?)")
            pattern = f"%{name_query}%"
            params.extend([pattern, pattern])

        if extension:
            ext = extension if extension.startswith(".") else f".{extension}"
            conditions.append("extension = ?")
            params.append(ext.lower())

        if min_size is not None:
            conditions.append("size >= ?")
            params.append(min_size)

        if max_size is not None:
            conditions.append("size <= ?")
            params.append(max_size)

        if has_hash is True:
            conditions.append("hash_value IS NOT NULL AND hash_value != ''")
        elif has_hash is False:
            conditions.append("(hash_value IS NULL OR hash_value = '')")

        where_clause = " WHERE " + " AND ".join(conditions) if conditions else ""
        sql = f"SELECT * FROM files{where_clause} ORDER BY size DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])

        with self._connect() as conn:
            cursor = conn.execute(sql, params)
            return [self._row_to_file(r) for r in cursor.fetchall()]

    def get_extension_stats(self) -> List[ExtensionStat]:
        sql = """
            SELECT
                COALESCE(NULLIF(extension, ''), '[No Extension]') AS ext,
                COUNT(*) AS file_count,
                SUM(COALESCE(size, 0)) AS total_size
            FROM files
            GROUP BY ext
            ORDER BY total_size DESC, file_count DESC
        """
        with self._connect() as conn:
            rows = conn.execute(sql).fetchall()
            total_files = sum(r["file_count"] for r in rows) or 1
            total_bytes = sum(r["total_size"] for r in rows) or 1

            stats: List[ExtensionStat] = []
            for r in rows:
                ext = r["ext"]
                cnt = r["file_count"]
                sz = r["total_size"] or 0
                stats.append(
                    ExtensionStat(
                        extension=ext,
                        file_count=cnt,
                        total_size=sz,
                        percentage_files=(cnt / total_files) * 100.0,
                        percentage_size=(sz / total_bytes) * 100.0,
                    )
                )
            return stats

    def get_duplicate_groups(self) -> List[DuplicateGroup]:
        sql = """
            SELECT hash_value, size, COUNT(*) as file_count
            FROM files
            WHERE hash_value IS NOT NULL AND hash_value != '' AND size > 0
            GROUP BY hash_value, size
            HAVING file_count > 1
            ORDER BY (file_count - 1) * size DESC
        """
        groups: List[DuplicateGroup] = []
        with self._connect() as conn:
            rows = conn.execute(sql).fetchall()
            for r in rows:
                h_val = r["hash_value"]
                sz = r["size"]
                f_cursor = conn.execute("SELECT * FROM files WHERE hash_value = ? ORDER BY path ASC", (h_val,))
                files = [self._row_to_file(fr) for fr in f_cursor.fetchall()]
                groups.append(
                    DuplicateGroup(
                        size=sz,
                        hash_value=h_val,
                        files=files,
                    )
                )
        return groups

    def get_all_folders(self) -> List[FolderRecord]:
        with self._connect() as conn:
            cursor = conn.execute("SELECT * FROM folders ORDER BY depth ASC, path ASC")
            return [self._row_to_folder(r) for r in cursor.fetchall()]

    def get_all_files(self) -> List[FileRecord]:
        with self._connect() as conn:
            cursor = conn.execute("SELECT * FROM files ORDER BY path ASC")
            return [self._row_to_file(r) for r in cursor.fetchall()]

    def get_all_errors(self) -> List[ScanError]:
        with self._connect() as conn:
            cursor = conn.execute("SELECT * FROM scan_errors ORDER BY id ASC")
            return [
                ScanError(
                    id=r["id"],
                    path=r["path"],
                    error_type=r["error_type"],
                    message=r["message"],
                    timestamp=r["timestamp"],
                )
                for r in cursor.fetchall()
            ]

    def _row_to_file(self, row: sqlite3.Row) -> FileRecord:
        cat_str = row["category"] if "category" in row.keys() else "UNKNOWN"
        try:
            cat = FileCategory(cat_str)
        except ValueError:
            cat = FileCategory.UNKNOWN

        act_str = row["action"] if "action" in row.keys() else "ANALYZE"
        try:
            act = AIAction(act_str)
        except ValueError:
            act = AIAction.ANALYZE

        return FileRecord(
            id=row["id"],
            folder_id=row["folder_id"],
            name=row["name"],
            path=row["path"],
            relative_path=row["relative_path"],
            extension=row["extension"] or "",
            size=row["size"] or 0,
            created=row["created"],
            modified=row["modified"],
            accessed=row["accessed"],
            permissions=row["permissions"],
            hash_value=row["hash_value"],
            hash_algorithm=row["hash_algorithm"],
            quick_fingerprint=row["quick_fingerprint"],
            is_hidden=bool(row["is_hidden"]),
            is_symlink=bool(row["is_symlink"]),
            error=row["error"],
            classification=ClassificationResult(category=cat, action=act),
            language=row["language"] if "language" in row.keys() else None,
            priority_score=row["priority_score"] if "priority_score" in row.keys() else 50,
            token_count=row["token_count"] if "token_count" in row.keys() else 0,
            is_entry_point=bool(row["is_entry_point"]) if "is_entry_point" in row.keys() else False,
            is_secret=bool(row["is_secret"]) if "is_secret" in row.keys() else False,
        )

    def _row_to_folder(self, row: sqlite3.Row) -> FolderRecord:
        return FolderRecord(
            id=row["id"],
            parent_id=row["parent_id"],
            name=row["name"],
            path=row["path"],
            relative_path=row["relative_path"],
            depth=row["depth"],
            direct_files=row["direct_files"] or 0,
            direct_folders=row["direct_folders"] or 0,
            total_files=row["total_files"] or 0,
            total_folders=row["total_folders"] or 0,
            total_size=row["total_size"] or 0,
            error=row["error"],
        )
