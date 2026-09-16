"""
veilframe.folder.security.entropy — Shannon entropy calculations for detecting high-randomness secrets.
"""

from __future__ import annotations

import math
from collections import Counter


def calculate_entropy(text: str) -> float:
    """
    Compute the Shannon entropy of a string token.
    Higher entropy (> 4.3 for base64 / > 3.0 for hex) indicates high-randomness cryptographic secrets.
    """
    if not text:
        return 0.0

    length = len(text)
    counts = Counter(text)
    entropy = 0.0

    for count in counts.values():
        p = count / length
        entropy -= p * math.log2(p)

    return entropy


def has_high_entropy(token: str, min_len: int = 16, threshold: float = 4.3) -> bool:
    """
    Check if a candidate token meets length and Shannon entropy criteria for an API key or password.
    """
    if len(token) < min_len:
        return False

    # Discard pure whitespace or trivial strings
    stripped = token.strip()
    if len(stripped) < min_len:
        return False

    return calculate_entropy(stripped) >= threshold


def is_high_entropy(token: str, threshold: float = 4.0) -> bool:
    """Check if token exceeds Shannon entropy threshold."""
    if not token or not token.strip():
        return False
    return calculate_entropy(token.strip()) >= threshold
