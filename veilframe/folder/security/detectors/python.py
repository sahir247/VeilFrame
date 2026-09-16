"""
veilframe.folder.security.detectors.python — Python-specific assignment secret detector.
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

PYTHON_ASSIGNMENT_REGEX = re.compile(
    r"""(?im)^(?P<indent>[ \t]*)(?P<var>(?:[a-z_][a-z0-9_]*_)?(?:secret|token|password|passwd|pwd|private_key|api_key|apikey|auth_token|access_token|secret_key|client_secret|api_secret|app_secret|signing_key)[a-z0-9_]*)(?P<type>\s*:\s*[a-zA-Z0-9_.\[\]]+)?\s*=\s*(?P<quote>["'])(?P<token>[^"'\r\n]{20,})(?P=quote)"""
)


class PythonSecretDetector(BaseSecretDetector):
    """Detects high-entropy credential assignments in Python source code."""

    languages = {"python", "py", "pyw", "pyi"}

    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        findings: List[SecretFinding] = []
        for idx, line in enumerate(content.splitlines(), 1):
            line_str = line.strip()
            if not line_str or line_str.startswith("#"):
                continue
            m = PYTHON_ASSIGNMENT_REGEX.match(line)
            if m:
                var_name = m.group("var")
                token = m.group("token")
                if is_candidate_secret(var_name, token):
                    findings.append(
                        SecretFinding(
                            file_path=file_path,
                            line_number=idx,
                            alert_type="HIGH_ENTROPY",
                            description="High-entropy credential assigned in Python code",
                            masked_snippet=mask_secret(token),
                            rule_id="python_assignment_secret",
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
            if not is_candidate_secret(var_name, token):
                return m.group(0)
            indent = m.group("indent")
            type_ann = m.group("type") or ""
            quote = m.group("quote")
            return f"{indent}{var_name}{type_ann} = {quote}[REDACTED]{quote}"

        redacted = PYTHON_ASSIGNMENT_REGEX.sub(_repl, content)
        return redacted, findings
