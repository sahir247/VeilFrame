"""
veilframe.folder.hasher — Streaming cryptographic hash engine and quick fingerprinting.

Follows the staged optimization principles:
- Chunked streaming I/O (1 MiB default) to prevent memory ballooning on large files.
- Staged fingerprinting (Size + Head + Tail) for fast duplicate candidate pruning.
- Bounded thread pool executor with graceful cancellation support.
"""

from __future__ import annotations

import hashlib
import os
import threading
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from typing import Callable, Dict, List, Optional, Tuple

from veilframe.folder.models import FileRecord


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
    Compute a fast staged fingerprint for candidate duplicate pruning.
    Hashes: [file_size_bytes] + [head 8KB] + [tail 8KB].
    Reads at most 16KB regardless of file size (e.g. 50 GB video).
    """
    if file_size == 0:
        return "empty:0"
    
    hasher = hashlib.md5()
    hasher.update(str(file_size).encode("utf-8"))

    try:
        with open(path, "rb") as f:
            # Head sample
            head = f.read(sample_size)
            hasher.update(head)

            # Tail sample (if file is larger than head sample)
            if file_size > sample_size:
                offset = max(0, file_size - sample_size)
                f.seek(offset)
                tail = f.read(sample_size)
                hasher.update(tail)
        return hasher.hexdigest()
    except (OSError, IOError) as e:
        return f"error:{e}"


class ParallelHashEngine:
    """
    Thread-pool orchestrator for batch file hashing with bounded concurrency.
    """

    def __init__(
        self,
        workers: int = 4,
        algorithm: str = "sha256",
        chunk_size: int = 1024 * 1024,
    ) -> None:
        self.workers = max(1, workers)
        self.algorithm = algorithm
        self.chunk_size = chunk_size
        self._cancel_event = threading.Event()

    def cancel(self) -> None:
        """Signal all workers to stop processing further chunks/files."""
        self._cancel_event.set()

    def is_cancelled(self) -> bool:
        return self._cancel_event.is_set()

    def hash_files(
        self,
        files: List[FileRecord],
        progress_callback: Optional[Callable[[int, int, FileRecord], None]] = None,
    ) -> List[Tuple[FileRecord, Optional[str], Optional[str]]]:
        """
        Hash a list of FileRecord items in parallel.
        Returns list of (file_record, hash_hex, error_str).
        """
        total = len(files)
        results: List[Tuple[FileRecord, Optional[str], Optional[str]]] = []
        if total == 0:
            return results

        completed_count = 0
        lock = threading.Lock()

        def _worker_task(file_rec: FileRecord) -> Tuple[FileRecord, Optional[str], Optional[str]]:
            if self._cancel_event.is_set():
                return file_rec, None, "Cancelled"
            try:
                h = compute_file_hash(
                    file_rec.path,
                    algorithm=self.algorithm,
                    chunk_size=self.chunk_size,
                    cancel_event=self._cancel_event,
                )
                return file_rec, h, None
            except Exception as ex:
                return file_rec, None, str(ex)

        with ThreadPoolExecutor(max_workers=self.workers) as executor:
            future_to_file: Dict[Future, FileRecord] = {
                executor.submit(_worker_task, f): f for f in files
            }

            for future in as_completed(future_to_file):
                rec, h_val, err = future.result()
                if h_val:
                    rec.hash_value = h_val
                    rec.hash_algorithm = self.algorithm
                elif err and not rec.error:
                    rec.error = err

                results.append((rec, h_val, err))

                with lock:
                    completed_count += 1
                    if progress_callback:
                        progress_callback(completed_count, total, rec)

        return results
