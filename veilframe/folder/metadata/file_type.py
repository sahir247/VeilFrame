"""
veilframe.folder.metadata.file_type — Binary vs text heuristic analysis and MIME detection.
"""

from __future__ import annotations

import mimetypes
import os
from typing import Optional, Tuple

# Pre-seed custom MIME types commonly encountered in software engineering
CUSTOM_MIMETYPES = {
    ".py": "text/x-python",
    ".pyi": "text/x-python",
    ".ts": "text/typescript",
    ".tsx": "text/typescript-jsx",
    ".jsx": "text/javascript-jsx",
    ".rs": "text/rust",
    ".go": "text/x-go",
    ".kt": "text/x-kotlin",
    ".kts": "text/x-kotlin",
    ".swift": "text/x-swift",
    ".dart": "text/x-dart",
    ".vue": "text/x-vue",
    ".svelte": "text/x-svelte",
    ".toml": "text/x-toml",
    ".yaml": "text/yaml",
    ".yml": "text/yaml",
    ".json": "application/json",
    ".sql": "text/x-sql",
    ".sh": "text/x-shellscript",
    ".bash": "text/x-shellscript",
    ".zsh": "text/x-shellscript",
    ".ps1": "text/x-powershell",
    ".md": "text/markdown",
    ".rst": "text/x-rst",
    ".graphql": "text/x-graphql",
    ".proto": "text/x-protobuf",
    ".safetensors": "application/x-safetensors",
    ".onnx": "application/x-onnx",
    ".gguf": "application/x-gguf",
    ".sqlite": "application/x-sqlite3",
    ".sqlite3": "application/x-sqlite3",
    ".db": "application/x-sqlite3",
}

# Magic byte signatures
MAGIC_SIGNATURES = [
    (b"SQLite format 3\x00", "application/x-sqlite3", True),
    (b"%PDF-", "application/pdf", True),
    (b"\x89PNG\r\n\x1a\n", "image/png", True),
    (b"\xff\xd8\xff", "image/jpeg", True),
    (b"GIF87a", "image/gif", True),
    (b"GIF89a", "image/gif", True),
    (b"PK\x03\x04", "application/zip", True),
    (b"\x1f\x8b", "application/gzip", True),
    (b"\x7fELF", "application/x-executable", True),
    (b"MZ", "application/x-msdownload", True),
    (b"\xca\xfe\xba\xbe", "application/java-vm", True),
    (b"\x00asm", "application/wasm", True),
]


def detect_file_type(path: str, max_read_bytes: int = 8192) -> Tuple[bool, Optional[str]]:
    """
    Determine whether a file is binary or text, and return its detected MIME type.
    
    Returns:
        (is_binary: bool, mime_type: Optional[str])
    """
    _, ext = os.path.splitext(path)
    ext_lower = ext.lower()
    inferred_mime = CUSTOM_MIMETYPES.get(ext_lower) or mimetypes.guess_type(path)[0]

    # Quick check for non-existent or empty files
    try:
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            return False, inferred_mime or "text/plain"
    except OSError:
        return False, inferred_mime

    try:
        with open(path, "rb") as f:
            chunk = f.read(max_read_bytes)
    except (PermissionError, OSError):
        return False, inferred_mime

    if not chunk:
        return False, inferred_mime or "text/plain"

    # 1. Match against known binary magic bytes
    for magic, mime, is_bin in MAGIC_SIGNATURES:
        if chunk.startswith(magic):
            return is_bin, mime

    # 2. Check for UTF-16 text with or without BOM
    if chunk.startswith(b"\xff\xfe") or chunk.startswith(b"\xfe\xff"):
        try:
            d = chunk.decode("utf-16")
            if d.count("\x00") <= len(d) * 0.05:
                return False, inferred_mime or "text/plain"
        except UnicodeDecodeError:
            pass

    # 3. Check for null bytes (canonical binary indicator)
    if b"\x00" in chunk:
        # Check if decodeable as UTF-16 even without BOM
        if len(chunk) % 2 == 0:
            try:
                d = chunk.decode("utf-16")
                if d.count("\x00") <= len(d) * 0.05:
                    return False, inferred_mime or "text/plain"
            except UnicodeDecodeError:
                pass
        return True, inferred_mime or "application/octet-stream"

    # 4. Check decodability as UTF-8
    try:
        chunk.decode("utf-8")
    except UnicodeDecodeError:
        # Check if ASCII with very high ratio of control chars
        control_chars = sum(1 for byte in chunk if byte < 32 and byte not in (9, 10, 13))
        if control_chars / len(chunk) > 0.15:
            return True, inferred_mime or "application/octet-stream"

    return False, inferred_mime or "text/plain"
