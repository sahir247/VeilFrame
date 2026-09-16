"""
veilframe.folder.ai_bundle.token_estimator — Fast calibrated token estimation per language.
"""

from __future__ import annotations

from typing import Optional

# Calibrated character-to-token ratios against cl100k_base / o200k_base
CHARS_PER_TOKEN_MAP = {
    "python": 3.6,
    "javascript": 3.7,
    "typescript": 3.7,
    "c": 3.5,
    "cpp": 3.5,
    "csharp": 3.6,
    "go": 3.4,
    "rust": 3.4,
    "java": 3.5,
    "kotlin": 3.5,
    "html": 3.2,
    "css": 3.3,
    "json": 3.1,
    "yaml": 3.3,
    "toml": 3.4,
    "markdown": 4.1,
    "text": 4.2,
}


def count_tokens_exact(content: str, encoding_name: str = "cl100k_base") -> Optional[int]:
    """Calculate exact BPE token count using tiktoken if available; returns None otherwise."""
    if not content:
        return 0
    try:
        import tiktoken
        enc = tiktoken.get_encoding(encoding_name)
        return len(enc.encode(content, disallowed_special=()))
    except Exception:
        return None


def estimate_tokens(content: str, language: Optional[str] = None, use_exact_if_available: bool = False) -> int:
    """
    Fast, reliable token estimation for a text string based on character count
    and language characteristics.
    """
    if not content:
        return 0

    if use_exact_if_available:
        exact = count_tokens_exact(content)
        if exact is not None:
            return exact

    char_len = len(content)
    lang_key = language.lower() if language else "text"
    ratio = CHARS_PER_TOKEN_MAP.get(lang_key, 3.8)

    return max(1, int(char_len / ratio))
