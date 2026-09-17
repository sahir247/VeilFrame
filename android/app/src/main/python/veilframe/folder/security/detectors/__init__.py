"""
veilframe.folder.security.detectors — Language-family secret detectors registry.
"""

from __future__ import annotations

import os
from typing import List, Optional

from veilframe.folder.security.detectors.base import BaseSecretDetector, SecretFinding
from veilframe.folder.security.detectors.c_family import CFamilySecretDetector
from veilframe.folder.security.detectors.config import ConfigSecretDetector
from veilframe.folder.security.detectors.generic import GenericSecretDetector
from veilframe.folder.security.detectors.go import GoSecretDetector
from veilframe.folder.security.detectors.javascript import JavaScriptSecretDetector
from veilframe.folder.security.detectors.jvm import JVMSecretDetector
from veilframe.folder.security.detectors.php import PHPSecretDetector
from veilframe.folder.security.detectors.python import PythonSecretDetector
from veilframe.folder.security.detectors.rust import RustSecretDetector
from veilframe.folder.security.detectors.shell import ShellSecretDetector

ALL_LANGUAGE_DETECTORS: List[BaseSecretDetector] = [
    PythonSecretDetector(),
    JavaScriptSecretDetector(),
    GoSecretDetector(),
    RustSecretDetector(),
    JVMSecretDetector(),
    CFamilySecretDetector(),
    PHPSecretDetector(),
    ShellSecretDetector(),
    ConfigSecretDetector(),
]

GENERIC_DETECTOR = GenericSecretDetector()


def resolve_detectors(language: Optional[str] = None, file_path: str = "") -> List[BaseSecretDetector]:
    """
    Resolve active detectors for a given file.
    Always includes the Generic detector, plus the matched language-family detector.
    If language is unknown, evaluates against all language detectors.
    """
    active: List[BaseSecretDetector] = [GENERIC_DETECTOR]

    # Normalize language and extension
    lang = (language or "").lower()
    ext = os.path.splitext(file_path)[1].lower().lstrip(".") if file_path else ""

    matched = False
    for det in ALL_LANGUAGE_DETECTORS:
        if lang in det.languages or ext in det.languages:
            active.append(det)
            matched = True

    # If no specific language matched, include all language detectors as fallback
    if not matched:
        active.extend(ALL_LANGUAGE_DETECTORS)

    return active
