"""
veilframe.folder.security.sensitive_files — Path and basename heuristics for sensitive credentials and keys.
"""

from __future__ import annotations

import os
from typing import Tuple

SENSITIVE_EXTENSIONS = {
    ".pem", ".key", ".pkcs12", ".p12", ".pfx", ".jks", ".keystore",
    ".secret", ".kdbx", ".tfstate",
}

SENSITIVE_BASENAMES = {
    ".env", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
    "credentials.json", "credentials.yaml", "credentials.yml",
    "secrets.json", "secrets.yaml", "secrets.yml",
    "authorized_keys", "known_hosts", ".netrc",
}

SAFE_EXAMPLE_SUFFIXES = {
    ".example", ".sample", ".template", ".dist", ".default"
}


def is_sensitive_filepath(path: str) -> Tuple[bool, str]:
    """
    Evaluate if a filename or path strongly indicates credentials or cryptographic keys.
    
    Returns:
        (is_sensitive: bool, reason: str)
    """
    basename = os.path.basename(path)
    base_lower = basename.lower()

    # 1. Allow templates like .env.example, config.sample.json
    for safe_sfx in SAFE_EXAMPLE_SUFFIXES:
        if base_lower.endswith(safe_sfx):
            return False, "Safe template or sample file"

    # 2. Check exact sensitive filenames
    if base_lower in SENSITIVE_BASENAMES:
        return True, f"Known sensitive filename: {basename}"

    # 3. Check environment file patterns
    if base_lower.startswith(".env.") or base_lower == ".env":
        return True, f"Environment credential file: {basename}"

    # 4. Check sensitive extensions
    _, ext = os.path.splitext(basename)
    ext_lower = ext.lower()
    if ext_lower in SENSITIVE_EXTENSIONS:
        return True, f"Sensitive cryptographic or credential extension: {ext}"

    # 5. Check Terraform state backups
    if "tfstate" in base_lower:
        return True, "Terraform infrastructure state file"

    return False, ""


def is_sensitive_filename(path: str) -> bool:
    """Convenience boolean helper checking if a filepath is sensitive."""
    is_sens, _ = is_sensitive_filepath(path)
    return is_sens
