"""
veilframe.folder.security.secret_patterns — Curated high-precision regex signatures for API keys, tokens, and credentials.
"""

from __future__ import annotations

import re
from typing import List, Pattern, Tuple

# Tuples of: (Rule ID, Description, Compiled Regex Pattern)
SECRET_PATTERNS: List[Tuple[str, str, Pattern[str]]] = [
    (
        "sec.aws_access_key",
        "AWS Access Key ID",
        re.compile(r"\b((?:AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16})\b"),
    ),
    (
        "sec.aws_secret_key",
        "AWS Secret Access Key",
        re.compile(r"(?i)\b(?:aws_secret_access_key|aws_secret_key|secret_key)\s*[:=]\s*['\"]?([0-9a-zA-Z/+]{40})['\"]?"),
    ),
    (
        "sec.github_pat",
        "GitHub Personal Access Token",
        re.compile(r"\b(ghp_[0-9a-zA-Z]{36}|github_pat_[0-9a-zA-Z_]{82})\b"),
    ),
    (
        "sec.google_api_key",
        "Google Cloud API Key",
        re.compile(r"\b(AIza[0-9A-Za-z\-_]{35})\b"),
    ),
    (
        "sec.openai_key",
        "OpenAI API Key",
        re.compile(r"\b(sk-[a-zA-Z0-9]{20,}|sk-proj-[a-zA-Z0-9_\-]{40,})\b"),
    ),
    (
        "sec.stripe_key",
        "Stripe Secret / Restricted Key",
        re.compile(r"\b([rs]k_(?:live|test)_[0-9a-zA-Z]{24,})\b"),
    ),
    (
        "sec.slack_token",
        "Slack Bot or User Token",
        re.compile(r"\b(xox[baprs]-[0-9a-zA-Z\-]{10,48})\b"),
    ),
    (
        "sec.private_key_header",
        "Cryptographic Private Key Header",
        re.compile(r"-----BEGIN (?:[A-Z0-9_\-]+\s+)?PRIVATE KEY-----"),
    ),
    (
        "sec.jwt_token",
        "JSON Web Token (JWT)",
        re.compile(r"\b(eyJ[A-Za-z0-9\-_=]+\.eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_.+/=]+)\b"),
    ),
    (
        "sec.db_connection_url",
        "Database Connection URL with Password",
        re.compile(r"(?:postgres|mysql|mongodb|redis)://[^:\s]+:([^@\s]+)@"),
    ),
    (
        "sec.generic_api_secret",
        "Generic API Secret Assignment",
        re.compile(r"(?i)\b(?:api_key|apikey|secret_key|client_secret|auth_token|access_token)\s*[:=]\s*['\"]([0-9a-zA-Z_\-\.]{16,})['\"]"),
    ),
]
