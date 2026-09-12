"""
veilframe.image.runtime.snapshot — Resource-Bounded Sealed Source Snapshot.

Implements the source snapshot layer from spec Section 4:
  • Resource limits enforced BEFORE any memory allocation.
  • Small files (< 32 MB): immutable in-memory buffer.
  • Large files (≥ 32 MB): sealed content-addressed read-only file backing.
  • Immediate identity: RawSourceHash, DecodedSourceStructure, DecodedRasterFingerprint.
"""

from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Any


class ResourceLimitExceeded(Exception):
    """Raised when source input violates a declared resource limit."""
    def __init__(self, limit_name: str, value: int, maximum: int) -> None:
        super().__init__(
            f"Resource limit exceeded: {limit_name} = {value} > max {maximum}"
        )
        self.limit_name = limit_name
        self.value = value
        self.maximum = maximum


# ---------------------------------------------------------------------------
# Sealed snapshot handle (large-file backing)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SealedSnapshotHandle:
    """Read-only reference to a content-addressed large-file backing store.

    The file at `backing_path` was written atomically and is read-only.
    Its SHA-256 digest == raw_source_hash.

    This handle is passed to the sanitizer in lieu of loading the full
    buffer into memory.
    """
    backing_path: Path
    raw_source_hash: str      # sha256:<hex>
    file_size_bytes: int

    def verify(self) -> bool:
        """Re-read the backing file and confirm digest still matches."""
        h = hashlib.sha256()
        with self.backing_path.open("rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest() == self.raw_source_hash


# ---------------------------------------------------------------------------
# Resource-bounded sealed source snapshot
# ---------------------------------------------------------------------------

@dataclass
class ResourceBoundedSourceSnapshot:
    """Sealed, immutable source snapshot with pre-allocation resource guards.

    Construction always goes through the class method ``from_file`` or
    ``from_bytes``.  The limits are checked before any buffer allocation.

    Attributes
    ----------
    raw_source_hash : str
        hex(SHA-256(exact input bytes)).
    file_size_bytes : int
        Length of the source file in bytes.
    is_large_file : bool
        True iff file_size_bytes >= large_file_threshold_bytes (32 MB).
        Large files use a SealedSnapshotHandle; small files use _buffer.
    handle : Optional[SealedSnapshotHandle]
        Set for large files; None for small files.
    decoded_source_structure_hash : Optional[str]
        SHA-256 of canonical decoded metadata (set after decode).
    decoded_raster_fingerprint : Optional[str]
        Byte-level canonical hash over:
            width (uint32-LE) || height (uint32-LE) || channels=3 ||
            dtype=float32-LE || row_major_linear_sRGB_pixels
        Set after decode.
    """
    raw_source_hash: str
    file_size_bytes: int
    is_large_file: bool
    handle: Optional[SealedSnapshotHandle] = None
    decoded_source_structure_hash: Optional[str] = None
    decoded_raster_fingerprint: Optional[str] = None
    _buffer: Optional[bytes] = field(default=None, repr=False)

    # ------------------------------------------------------------------
    # Factory methods
    # ------------------------------------------------------------------

    @classmethod
    def from_bytes(
        cls,
        data: bytes,
        limits: Optional[Any] = None,
        *,
        max_input_bytes: Optional[int] = None,
        max_metadata_bytes: Optional[int] = None,
        large_file_threshold_bytes: Optional[int] = None,
    ) -> "ResourceBoundedSourceSnapshot":
        """Create a snapshot from raw bytes.

        Raises ResourceLimitExceeded if data exceeds max_input_bytes.
        """
        if max_input_bytes is None:
            max_input_bytes = limits.max_input_bytes if limits and hasattr(limits, "max_input_bytes") else 268_435_456
        if max_metadata_bytes is None:
            max_metadata_bytes = limits.max_metadata_bytes if limits and hasattr(limits, "max_metadata_bytes") else 10_485_760
        if large_file_threshold_bytes is None:
            large_file_threshold_bytes = limits.large_file_threshold_bytes if limits and hasattr(limits, "large_file_threshold_bytes") else 33_554_432

        n = len(data)
        if n > max_input_bytes:
            raise ResourceLimitExceeded("max_input_bytes", n, max_input_bytes)

        digest = hashlib.sha256(data).hexdigest()
        is_large = n >= large_file_threshold_bytes

        if is_large:
            raise NotImplementedError(
                "Large-file sealed backing not yet implemented; use from_file()."
            )

        return cls(
            raw_source_hash=digest,
            file_size_bytes=n,
            is_large_file=False,
            _buffer=data,
        )

    @classmethod
    def from_file(
        cls,
        path: Path,
        limits: Optional[Any] = None,
        *,
        max_input_bytes: Optional[int] = None,
        max_metadata_bytes: Optional[int] = None,
        large_file_threshold_bytes: Optional[int] = None,
        staging_dir: Optional[Path] = None,
    ) -> "ResourceBoundedSourceSnapshot":
        """Create a snapshot from a file path.

        For files < large_file_threshold_bytes: reads into memory buffer.
        For larger files: creates a SealedSnapshotHandle backed by a
        content-addressed read-only copy in staging_dir.

        Raises ResourceLimitExceeded before any allocation if the file
        size exceeds max_input_bytes.
        """
        if max_input_bytes is None:
            max_input_bytes = limits.max_input_bytes if limits and hasattr(limits, "max_input_bytes") else 268_435_456
        if max_metadata_bytes is None:
            max_metadata_bytes = limits.max_metadata_bytes if limits and hasattr(limits, "max_metadata_bytes") else 10_485_760
        if large_file_threshold_bytes is None:
            large_file_threshold_bytes = limits.large_file_threshold_bytes if limits and hasattr(limits, "large_file_threshold_bytes") else 33_554_432

        stat = path.stat()
        n = stat.st_size
        if n > max_input_bytes:
            raise ResourceLimitExceeded("max_input_bytes", n, max_input_bytes)

        is_large = n >= large_file_threshold_bytes

        # Compute digest while reading
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        digest = h.hexdigest()

        if not is_large:
            with path.open("rb") as f:
                buf = f.read()
            return cls(
                raw_source_hash=digest,
                file_size_bytes=n,
                is_large_file=False,
                _buffer=buf,
            )

        # Large file: create sealed backing copy
        if staging_dir is None:
            staging_dir = path.parent / ".veilframe_staging"
        staging_dir.mkdir(parents=True, exist_ok=True)
        backing = staging_dir / f"sha256_{digest}"

        if not backing.exists():
            import shutil
            shutil.copy2(str(path), str(backing))
            backing.chmod(0o444)  # read-only

        handle = SealedSnapshotHandle(
            backing_path=backing,
            raw_source_hash=digest,
            file_size_bytes=n,
        )
        return cls(
            raw_source_hash=digest,
            file_size_bytes=n,
            is_large_file=True,
            handle=handle,
        )

    # ------------------------------------------------------------------
    # Accessors
    # ------------------------------------------------------------------

    @property
    def raw_bytes(self) -> bytes:
        """Return the raw source bytes as a property."""
        return self.get_bytes()

    def get_bytes(self) -> bytes:
        """Return the raw source bytes.

        For large files, reads from the sealed backing handle.
        """
        if not self.is_large_file:
            assert self._buffer is not None
            return self._buffer
        assert self.handle is not None
        with self.handle.backing_path.open("rb") as f:
            return f.read()

    def set_decoded_raster_fingerprint(
        self,
        width: int,
        height: int,
        linear_srgb_f32_row_major: bytes,
    ) -> None:
        """Compute and set the DecodedRasterFingerprint.

        Canonical preimage:
            width (uint32-LE) || height (uint32-LE) || channels=3 (uint32-LE)
            || dtype=float32-LE (uint32-LE literal 0x00000001)
            || row_major_linear_sRGB_pixels (IEEE-754 f32, clamped [0,1])
        """
        header = struct.pack("<IIII", width, height, 3, 1)
        h = hashlib.sha256(header + linear_srgb_f32_row_major).hexdigest()
        object.__setattr__(self, "decoded_raster_fingerprint", h)

    def check_decoded_pixels(
        self,
        n_pixels: int,
        n_channels: int,
        max_decoded_pixels: int = 67_108_864,
        max_channels: int = 4,
    ) -> None:
        """Enforce pixel/channel limits after decode header is known.

        Must be called before allocating the full decoded raster buffer.
        Raises ResourceLimitExceeded on violation.
        """
        if n_pixels > max_decoded_pixels:
            raise ResourceLimitExceeded("max_decoded_pixels", n_pixels, max_decoded_pixels)
        if n_channels > max_channels:
            raise ResourceLimitExceeded("max_channels", n_channels, max_channels)
