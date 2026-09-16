"""
veilframe.folder.security.detectors.rust — Rust language credential detector.
"""

from __future__ import annotations

import re
from typing import List, Tuple

from veilframe.folder.security.detectors.base import (
    BaseSecretDetector,
    SecretFinding,
    is_candidate_secret,
    mask_secret,
)

RUST_ASSIGNMENT_REGEX = re.compile(
    r"""(?im)^(?P<prefix>[ \t]*(?:pub(?:\([^)]+\))?\s+)?(?:const|let|static)\s+(?:mut\s+)?(?P<var>[a-zA-Z_][a-zA-Z0-9_]*)(?:\s*:\s*&?(?:str|String|[a-zA-Z0-9_]+))?\s*=\s*)(?P<quote>["'])(?P<token>[^"'\r\n]{20,})(?P=quote)(?P<semi>;?)"""
)


class RustSecretDetector(BaseSecretDetector):
    """Detects high-entropy credential assignments in Rust source code."""

    languages = {"rust", "rs"}

    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        findings: List[SecretFinding] = []
        for idx, line in enumerate(content.splitlines(), 1):
            line_str = line.strip()
            if not line_str or line_str.startswith("//"):
                continue
            m = RUST_ASSIGNMENT_REGEX.match(line)
            if m:
                var_name = m.group("var")
                token = m.group("token")
                if any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret", "access")):
                    if is_candidate_secret(var_name, token):
                        findings.append(
                            SecretFinding(
                                file_path=file_path,
                                line_number=idx,
                                alert_type="HIGH_ENTROPY",
                                description="High-entropy credential assigned in Rust code",
                                masked_snippet=mask_secret(token),
                                rule_id="rust_assignment_secret",
                                var_name=var_name,
                                raw_token=token,
                            )
                        )
        return findings

    def redact(self, content: str, file_path: str = "") -> Tuple[str, List[SecretFinding]]:
        findings = self.detect(content, file_path=file_path)
        if not findings:
            return content, []

        def _repl(m: re.Match) -> str:
            var_name = m.group("var")
            token = m.group("token")
            if not any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret", "access")):
                return m.group(0)
            if not is_candidate_secret(var_name, token):
                return m.group(0)
            prefix = m.group("prefix")
            quote = m.group("quote")
            semi = m.group("semi") or ""
            return f"{prefix}{quote}[REDACTED]{quote}{semi}"

        redacted = RUST_ASSIGNMENT_REGEX.sub(_repl, content)
        return redacted, findings
