"""
veilframe.folder.security.detectors.base — Base classes and shared utilities for language-family secret detectors.
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set, Tuple

from veilframe.folder.security.entropy import has_high_entropy

UUID_REGEX = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
DISALLOWED_VAR_SUFFIXES = (
    "_count", "_length", "_len", "_index", "_idx", "_size", "_num", "_number",
    "_type", "_name", "_id", "_limit", "_total", "_status", "_state", "_url", "_path"
)


def mask_secret(val: str) -> str:
    """Safely mask a secret for UI display or reporting (e.g. sk-pro****9ab3)."""
    clean = val.strip()
    if len(clean) <= 6:
        return "******"
    prefix = clean[:4]
    suffix = clean[-4:]
    return f"{prefix}****{suffix}"


def is_candidate_secret(var_name: str, token: str) -> bool:
    """Universal gate evaluating whether an assignment value constitutes a genuine high-entropy secret."""
    var_lower = var_name.lower().strip("$'\"")
    if any(var_lower.endswith(sfx) for sfx in DISALLOWED_VAR_SUFFIXES):
        return False

    tok = token.strip()
    if len(tok) < 20:
        return False
    if UUID_REGEX.match(tok):
        return False
    if "://" in tok or "/" in tok or "\\" in tok:
        return False
    if tok.lower().startswith(("your_", "my_", "test_", "placeholder", "example", "xxx", "000", "dummy", "default")):
        return False

    # High entropy check
    if has_high_entropy(tok, min_len=20, threshold=3.6):
        return True
    if len(set(tok)) >= 12 and has_high_entropy(tok, min_len=20, threshold=3.2):
        return True

    return False


@dataclass
class SecretFinding:
    """Incident report of a detected credential, token, or private key."""
    file_path: str
    line_number: Optional[int]
    alert_type: str
    description: str
    masked_snippet: str
    rule_id: str = ""
    severity: str = "HIGH"
    confidence: float = 1.0
    var_name: Optional[str] = None
    raw_token: Optional[str] = None

    @property
    def relative_path(self) -> str:
        return self.file_path

    def to_dict(self) -> Dict[str, Any]:
        return {
            "file_path": self.file_path,
            "line_number": self.line_number,
            "alert_type": self.alert_type,
            "description": self.description,
            "masked_snippet": self.masked_snippet,
            "rule_id": self.rule_id,
            "severity": self.severity,
            "confidence": round(self.confidence, 2),
        }


class BaseSecretDetector(ABC):
    """Abstract interface for all language-family secret detectors."""

    languages: Set[str] = set()

    @abstractmethod
    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        """Scan content line-by-line and return detected security findings."""
        pass

    @abstractmethod
    def redact(self, content: str, file_path: str = "") -> Tuple[str, List[SecretFinding]]:
        """Redact secrets inline while preserving 100% of language syntax."""
        pass
