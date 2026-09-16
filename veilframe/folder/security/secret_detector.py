"""
veilframe.folder.security.secret_detector — Deep secret detection, scanning, and safe redaction.
"""

from __future__ import annotations

import os
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


class SecretDetector:
    """Scans files and text content for secrets, tokens, and cryptographic keys."""

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
                alerts.extend(self.scan_content(content, file_path=file_path))
            except OSError:
                pass

        if alerts and file_rec:
            file_rec.is_secret = True
            from veilframe.folder.models.classification import AIAction, FileCategory
            if hasattr(file_rec, "classification") and file_rec.classification:
                file_rec.classification.category = FileCategory.SECRET
                file_rec.classification.action = AIAction.WARN
                file_rec.classification.reason = alerts[0].description

        return alerts

    def scan_content(self, content: str, file_path: str = "") -> List[SecurityAlert]:
        """Scan raw string content line by line for secrets."""
        alerts: List[SecurityAlert] = []
        lines = content.splitlines()

        for idx, line in enumerate(lines, 1):
            line_str = line.strip()
            if not line_str or line_str.startswith(("#", "//", "/*", "*")):
                # Light skip for pure comments unless containing key patterns
                pass

            # A. Match Regex Patterns
            for rule_id, desc, pattern in SECRET_PATTERNS:
                m = pattern.search(line)
                if m:
                    # Extract matched secret (group 1 if present, else full match)
                    secret_val = m.group(1) if m.groups() else m.group(0)
                    alerts.append(
                        SecurityAlert(
                            file_path=file_path,
                            line_number=idx,
                            alert_type="PATTERN_MATCH",
                            description=desc,
                            masked_snippet=mask_secret(secret_val),
                            rule_id=rule_id,
                            confidence=0.98,
                        )
                    )

            # B. Entropy check for assignments: key = "high-entropy-string"
            if self.check_entropy and ("=" in line_str or ":" in line_str):
                tokens = line_str.replace("=", " ").replace(":", " ").replace('"', " ").replace("'", " ").split()
                for token in tokens:
                    if len(token) >= 24 and has_high_entropy(token):
                        alerts.append(
                            SecurityAlert(
                                file_path=file_path,
                                line_number=idx,
                                alert_type="HIGH_ENTROPY",
                                description="High-entropy string token (potential API key/password)",
                                masked_snippet=mask_secret(token),
                                rule_id="high_entropy_token",
                                confidence=0.85,
                            )
                        )
                        break

        return alerts
