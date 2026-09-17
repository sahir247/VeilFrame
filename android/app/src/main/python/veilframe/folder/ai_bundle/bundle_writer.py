"""
veilframe.folder.ai_bundle.bundle_writer — File writing and persistence for AI bundles.
"""

from __future__ import annotations

import os
from typing import Union


def write_bundle_to_disk(content: Union[str, bytes], output_path: str) -> int:
    """
    Write bundle content (string or binary zip) to the specified destination path.
    Returns:
        bytes_written: int
    """
    abs_path = os.path.abspath(output_path)
    os.makedirs(os.path.dirname(abs_path), exist_ok=True)

    if isinstance(content, str):
        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(content)
        return os.path.getsize(abs_path)
    elif isinstance(content, bytes):
        with open(abs_path, "wb") as f:
            f.write(content)
        return len(content)
    else:
        raise TypeError(f"Unsupported content type for bundle writing: {type(content)}")
