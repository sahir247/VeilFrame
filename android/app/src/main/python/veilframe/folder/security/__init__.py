"""
veilframe.folder.security — Security scanning, entropy analysis, and credential redaction.
"""

from veilframe.folder.security.entropy import calculate_entropy, has_high_entropy
from veilframe.folder.security.secret_detector import (
    SecretDetector,
    SecurityAlert,
    mask_secret,
)
from veilframe.folder.security.secret_patterns import SECRET_PATTERNS
from veilframe.folder.security.sensitive_files import is_sensitive_filepath

__all__ = [
    "SecretDetector",
    "SecurityAlert",
    "mask_secret",
    "calculate_entropy",
    "has_high_entropy",
    "SECRET_PATTERNS",
    "is_sensitive_filepath",
]
