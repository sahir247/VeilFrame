"""
veilframe.folder.security.secret_detector — Deep secret detection, scanning, and safe redaction.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from veilframe.folder.security.entropy import has_high_entropy
from veilframe.folder.security.secret_patterns import SECRET_PATTERNS
from veilframe.folder.security.sensitive_files import is_sensitive_filepath


def mask_secret(val: str) -> str:
    """Safely mask a secret for UI display or reporting (e.g. sk-pro****9ab3)."""
    clean = val.strip()
    if len(clean) <= 6:
        return "******"
    prefix = clean[:4]
    suffix = clean[-4:]
    return f"{prefix}****{suffix}"


@dataclass
class SecurityAlert:
    """Incident report of a detected credential or private key."""
    file_path: str
    line_number: Optional[int]
    alert_type: str
    description: str
    masked_snippet: str
    rule_id: str = ""
    severity: str = "HIGH"
    confidence: float = 1.0

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


from veilframe.folder.security.detectors import GENERIC_DETECTOR, resolve_detectors
from veilframe.folder.security.detectors.base import (
    DISALLOWED_VAR_SUFFIXES,
    UUID_REGEX,
    SecretFinding,
    is_candidate_secret,
    mask_secret,
)


class SecretDetector:
    """Scans files and text content for secrets, tokens, and cryptographic keys using modular language-family detectors."""

    def __init__(self, check_entropy: bool = True, max_scan_bytes: int = 250_000) -> None:
        self.check_entropy = check_entropy
        self.max_scan_bytes = max_scan_bytes

    def scan_file(self, file_path_or_record: Any) -> List[SecurityAlert]:
        """Scan a file path or FileRecord object and its contents for security issues."""
        file_rec = None
        if hasattr(file_path_or_record, "path"):
            file_rec = file_path_or_record
            file_path = file_rec.path
        else:
            file_path = str(file_path_or_record)

        alerts: List[SecurityAlert] = []

        # 1. Path-based heuristic check
        is_sens, sens_reason = is_sensitive_filepath(file_path)
        if is_sens:
            alerts.append(
                SecurityAlert(
                    file_path=file_path,
                    line_number=None,
                    alert_type="SENSITIVE_FILE",
                    description=sens_reason,
                    masked_snippet=os.path.basename(file_path),
                    rule_id="sensitive_filename",
                    confidence=0.95,
                )
            )

        # 2. Content scan for text files
        if os.path.exists(file_path) and os.path.isfile(file_path):
            try:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read(self.max_scan_bytes)
                lang = ""
                if file_rec:
                    lang = getattr(file_rec, "language", "") or getattr(file_rec, "detected_language", "") or ""
                alerts.extend(self.scan_content(content, file_path=file_path, language=lang))
            except OSError:
                pass

        if alerts and file_rec:
            file_rec.secret_alerts = [a.description for a in alerts]
            # ONLY mark is_secret = True and category = SECRET if this is a dedicated sensitive credential file.
            # Normal source or documentation files containing inline secrets retain their genuine category
            # and are handled via safe redaction.
            if is_sens:
                file_rec.is_secret = True
                from veilframe.folder.models.classification import AIAction, FileCategory
                if hasattr(file_rec, "classification") and file_rec.classification:
                    file_rec.classification.category = FileCategory.SECRET
                    file_rec.classification.action = AIAction.WARN
                    file_rec.classification.reason = alerts[0].description

        return alerts

    def scan_content(self, content: str, file_path: str = "", language: str = "") -> List[SecurityAlert]:
        """Scan raw string content line by line using language-family and generic detectors."""
        alerts: List[SecurityAlert] = []
        base_name = os.path.basename(file_path).lower() if file_path else ""

        # Skip lockfiles (e.g. uv.lock, package-lock.json) which naturally contain package integrity hashes
        if base_name in ("uv.lock", "package-lock.json", "poetry.lock", "cargo.lock", "yarn.lock", "pnpm-lock.yaml", "composer.lock"):
            return alerts

        detectors = resolve_detectors(language=language, file_path=file_path)
        seen_keys = set()

        for det in detectors:
            if not self.check_entropy and det != GENERIC_DETECTOR:
                continue
            findings = det.detect(content, file_path=file_path)
            for f in findings:
                dedup_key = (f.line_number, f.rule_id or f.masked_snippet)
                if dedup_key in seen_keys:
                    continue
                seen_keys.add(dedup_key)
                alerts.append(
                    SecurityAlert(
                        file_path=file_path,
                        line_number=f.line_number,
                        alert_type=f.alert_type,
                        description=f.description,
                        masked_snippet=f.masked_snippet,
                        rule_id=f.rule_id,
                        severity=f.severity,
                        confidence=f.confidence,
                    )
                )

        return alerts


def redact_inline_secrets(content: str, file_path: str = "", language: str = "") -> Tuple[str, List[SecurityAlert]]:
    """
    Deterministic, conservative inline redaction for source and config files.
    Safely replaces matched secret literals with '[REDACTED]' while strictly preserving code syntax.

    Example:
        API_KEY = "sk-proj-12345678901234567890" -> API_KEY = "[REDACTED]"
    """
    base_name = os.path.basename(file_path).lower() if file_path else ""
    if base_name in ("uv.lock", "package-lock.json", "poetry.lock", "cargo.lock", "yarn.lock", "pnpm-lock.yaml", "composer.lock"):
        return content, []

    detectors = resolve_detectors(language=language, file_path=file_path)
    all_alerts: List[SecurityAlert] = []
    redacted = content
    seen_keys = set()

    for det in detectors:
        redacted, findings = det.redact(redacted, file_path=file_path)
        for f in findings:
            dedup_key = (f.line_number, f.rule_id or f.masked_snippet)
            if dedup_key in seen_keys:
                continue
            seen_keys.add(dedup_key)
            all_alerts.append(
                SecurityAlert(
                    file_path=file_path,
                    line_number=f.line_number,
                    alert_type=f.alert_type,
                    description=f.description,
                    masked_snippet=f.masked_snippet,
                    rule_id=f.rule_id,
                    severity=f.severity,
                    confidence=f.confidence,
                )
            )

    return redacted, all_alerts

