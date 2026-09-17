"""
veilframe.folder.security.detectors.generic — Format-based credential detector for known token patterns, keys, and URLs.
"""

from __future__ import annotations

import re
from typing import List, Tuple

from veilframe.folder.security.detectors.base import BaseSecretDetector, SecretFinding, mask_secret
from veilframe.folder.security.secret_patterns import SECRET_PATTERNS


class GenericSecretDetector(BaseSecretDetector):
    """Detector for signature-matched credentials (AWS, GitHub, Google, OpenAI, Slack, Stripe, JWT, Private Keys)."""

    languages = {"*"}

    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        findings: List[SecretFinding] = []

        # 1. Private Key blocks
        if "-----BEGIN" in content and "PRIVATE KEY-----" in content:
            for idx, line in enumerate(content.splitlines(), 1):
                if "-----BEGIN" in line and "PRIVATE KEY" in line:
                    findings.append(
                        SecretFinding(
                            file_path=file_path,
                            line_number=idx,
                            alert_type="PRIVATE_KEY",
                            description="Unencrypted Private Key Block",
                            masked_snippet="-----BEGIN PRIVATE KEY-----",
                            rule_id="private_key_block",
                            severity="CRITICAL",
                            confidence=1.0,
                        )
                    )

        # 2. Curated regex patterns
        for idx, line in enumerate(content.splitlines(), 1):
            line_str = line.strip()
            if not line_str or line_str.startswith(("#", "//", "/*", "*", "<!--")):
                pass

            for rule_id, desc, pattern in SECRET_PATTERNS:
                m = pattern.search(line)
                if m:
                    secret_val = m.group(1) if m.groups() else m.group(0)
                    findings.append(
                        SecretFinding(
                            file_path=file_path,
                            line_number=idx,
                            alert_type="PATTERN_MATCH",
                            description=desc,
                            masked_snippet=mask_secret(secret_val),
                            rule_id=rule_id,
                            severity="HIGH",
                            confidence=0.98,
                            raw_token=secret_val,
                        )
                    )

        return findings

    def redact(self, content: str, file_path: str = "") -> Tuple[str, List[SecretFinding]]:
        findings = self.detect(content, file_path=file_path)
        if not findings:
            return content, []

        redacted = content

        # 1. Redact Private Key blocks
        redacted = re.sub(
            r"(-----BEGIN (?:[A-Z0-9_\-]+\s+)?PRIVATE KEY-----)[\s\S]*?(-----END (?:[A-Z0-9_\-]+\s+)?PRIVATE KEY-----)",
            r"\1\n[REDACTED_PRIVATE_KEY]\n\2",
            redacted,
        )

        # 2. Redact Database Connection URLs with Passwords
        redacted = re.sub(
            r"((?:postgres|mysql|mongodb|redis)://[^:\s@]+):[^@\s]+(@)",
            r"\1:[REDACTED]\2",
            redacted,
        )

        # 3. Redact specific curated patterns
        for _, _, pattern in SECRET_PATTERNS:
            def _repl(m):
                val = m.group(1) if m.groups() else m.group(0)
                return m.group(0).replace(val, "[REDACTED]")
            redacted = pattern.sub(_repl, redacted)

        return redacted, findings
