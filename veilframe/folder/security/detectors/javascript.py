"""
veilframe.folder.security.detectors.javascript — JavaScript & TypeScript credential detector.
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

JS_ASSIGNMENT_REGEX = re.compile(
    r"""(?im)^(?P<prefix>(?P<indent>[ \t]*)(?:(?:export\s+)?(?:const|let|var)\s+)?(?P<var>[a-zA-Z_$][a-zA-Z0-9_$]*)(?P<type>\s*:\s*[^=\r\n]+)?\s*=\s*)(?P<quote>["'`])(?P<token>[^"'`\r\n]{20,})(?P=quote)(?P<semi>;?)"""
)


class JavaScriptSecretDetector(BaseSecretDetector):
    """Detects high-entropy credential declarations in JavaScript and TypeScript."""

    languages = {"javascript", "typescript", "js", "jsx", "ts", "tsx", "mjs", "cjs"}

    def detect(self, content: str, file_path: str = "") -> List[SecretFinding]:
        findings: List[SecretFinding] = []
        for idx, line in enumerate(content.splitlines(), 1):
            line_str = line.strip()
            if not line_str or line_str.startswith(("//", "/*", "*")):
                continue
            m = JS_ASSIGNMENT_REGEX.match(line)
            if m:
                var_name = m.group("var")
                token = m.group("token")
                # Check if var name implies credential
                if any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret")):
                    if is_candidate_secret(var_name, token):
                        findings.append(
                            SecretFinding(
                                file_path=file_path,
                                line_number=idx,
                                alert_type="HIGH_ENTROPY",
                                description="High-entropy credential declared in JavaScript/TypeScript",
                                masked_snippet=mask_secret(token),
                                rule_id="js_assignment_secret",
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
            if not any(w in var_name.lower() for w in ("secret", "token", "password", "passwd", "pwd", "api_key", "apikey", "auth", "private_key", "client_secret")):
                return m.group(0)
            if not is_candidate_secret(var_name, token):
                return m.group(0)
            prefix = m.group("prefix")
            quote = m.group("quote")
            semi = m.group("semi") or ""
            return f"{prefix}{quote}[REDACTED]{quote}{semi}"

        redacted = JS_ASSIGNMENT_REGEX.sub(_repl, content)
        return redacted, findings
