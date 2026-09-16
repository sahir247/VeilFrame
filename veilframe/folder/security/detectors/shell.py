"""
veilframe.folder.security.detectors.shell — Shell and Bash script credential detector.
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

SHELL_ASSIGNMENT_REGEX = re.compile(
    r"""(?im)^(?P<prefix>[ \t]*(?:export\s+)?(?P<var>[a-zA-Z_][a-zA-Z0-9_]*)=)(?P<quote>["']?)(?P<token>[^"'\s\r\n]{20,})(?P=quote)"""
)


class ShellSecretDetector(BaseSecretDetector):
    """Detects credentials declared or exported in Shell and Bash scripts."""

    languages = {"shell", "bash", "sh", "zsh", "fish"}

    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        findings: List[SecretFinding] = []
        for idx, line in enumerate(content.splitlines(), 1):
            line_str = line.strip()
            if not line_str or line_str.startswith("#"):
                continue
            m = SHELL_ASSIGNMENT_REGEX.match(line)
            if m:
                var_name = m.group("var")
                token = m.group("token")
                if any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret", "key")):
                    if is_candidate_secret(var_name, token):
                        findings.append(
                            SecretFinding(
                                file_path=file_path,
                                line_number=idx,
                                alert_type="HIGH_ENTROPY",
                                description="High-entropy credential exported in Shell script",
                                masked_snippet=mask_secret(token),
                                rule_id="shell_assignment_secret",
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
            if not any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret", "key")):
                return m.group(0)
            if not is_candidate_secret(var_name, token):
                return m.group(0)
            prefix = m.group("prefix")
            quote = m.group("quote") or '"'
            return f"{prefix}{quote}[REDACTED]{quote}"

        redacted = SHELL_ASSIGNMENT_REGEX.sub(_repl, content)
        return redacted, findings
