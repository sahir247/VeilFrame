"""
Shared cryptographic utilities.

Extracted from validator.py to break the circular import between
veilframe.core.validator and veilframe.quality.adapters.vmaf.

Both modules (and any other code) should import compute_sha256 from here,
not from validator.py. The validator re-exports it for backward compatibility.
"""
import hashlib
from pathlib import Path


def compute_sha256(file_path: Path) -> str:
    """Computes SHA-256 cryptographic hash of the specified file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()
