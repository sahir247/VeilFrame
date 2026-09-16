"""
veilframe.folder.duplicates.hasher — Streaming cryptographic hash engine and quick fingerprinting.
"""

from __future__ import annotations

import hashlib
import os
import threading
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from typing import Callable, Dict, List, Optional, Tuple

from veilframe.folder.models.file_record import FileRecord


def get_hash_object(algorithm: str):
    """Obtain standard hashlib constructor for algorithm name."""
    algo = algorithm.lower().strip()
    if algo in ("sha256", "sha-256"):
        return hashlib.sha256()
    elif algo in ("sha1", "sha-1"):
        return hashlib.sha1()
    elif algo in ("md5",):
        return hashlib.md5()
    elif algo in ("sha512", "sha-512"):
        return hashlib.sha512()
    else:
        raise ValueError(f"Unsupported hash algorithm: {algorithm}")


def compute_file_hash(
    path: str,
    algorithm: str = "sha256",
    chunk_size: int = 1024 * 1024,
    cancel_event: Optional[threading.Event] = None,
) -> str:
    """Compute the full cryptographic hash of a file using streaming chunks."""
    hasher = get_hash_object(algorithm)
    with open(path, "rb") as f:
        while True:
            if cancel_event and cancel_event.is_set():
                raise InterruptedError("Hash calculation cancelled by user.")
            chunk = f.read(chunk_size)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest()


def compute_quick_fingerprint(
    path: str,
    file_size: int,
    sample_size: int = 8192,
) -> str:
    """
    Compute a fast staged fingerprint consisting of:
    [Size in bytes] + [MD5(first 8KB + last 8KB)].
    """
    if file_size <= 0:
        return "empty_0_bytes"

    hasher = hashlib.md5()
    with open(path, "rb") as f:
        head = f.read(sample_size)
        hasher.update(head)

        if file_size > sample_size * 2:
            try:
                f.seek(file_size - sample_size)
                tail = f.read(sample_size)
                hasher.update(tail)
            except (OSError, ValueError):
                pass

    return f"{file_size}_{hasher.hexdigest()}"


class ParallelHashEngine:
    """Bounded thread pool executor for high-throughput batch hashing."""

    def __init__(
        self,
        algorithm: str = "sha256",
        max_workers: int = 4,
        workers: Optional[int] = None,
        chunk_size: int = 1024 * 1024,
    ) -> None:
        self.algorithm = algorithm
        self.max_workers = workers if workers is not None else max_workers
        self.chunk_size = chunk_size
        self._cancel_event = threading.Event()

    def cancel(self) -> None:
        self._cancel_event.set()

    def hash_files(
        self,
        files: List[FileRecord],
        on_progress: Optional[Callable[[int, int, str], None]] = None,
    ) -> List[Tuple[FileRecord, Optional[str], Optional[str]]]:
        results: List[Tuple[FileRecord, Optional[str], Optional[str]]] = []
        total = len(files)
        completed = 0

        def _hash_worker(f_rec: FileRecord) -> Tuple[FileRecord, Optional[str], Optional[str]]:
            if self._cancel_event.is_set():
                return f_rec, None, "Cancelled"
            try:
                h = compute_file_hash(
                    path=f_rec.path,
                    algorithm=self.algorithm,
                    chunk_size=self.chunk_size,
                    cancel_event=self._cancel_event,
                )
                return f_rec, h, None
            except Exception as e:
                return f_rec, None, str(e)

        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            future_map: Dict[Future, FileRecord] = {
                executor.submit(_hash_worker, f): f for f in files
            }

            for future in as_completed(future_map):
                if self._cancel_event.is_set():
                    break
                rec, hash_val, err = future.result()
                if hash_val:
                    rec.hash_value = hash_val
                    rec.hash_algorithm = self.algorithm
                if err and not rec.error:
                    rec.error = err

                results.append((rec, hash_val, err))
                completed += 1
                if on_progress:
                    on_progress(completed, total, rec.path)

        return results
