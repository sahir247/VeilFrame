"""
veilframe.image.verification.normalizer — Universal provider status normalizer.

All external providers (detectors, red-team probes, sanitizers) may return
domain-specific result types.  This normalizer converts them to the canonical
CheckStatus tri-state used by the QualityGate.

The fail-closed invariant:
    Any unrecognised status, None, or exception → UNKNOWN → FAIL at gate.
"""

from __future__ import annotations

from typing import Any, Optional

from ..models.status import CheckStatus


def normalize_status(raw_status: Any) -> CheckStatus:
    """Convert any provider-specific status to CheckStatus.

    Rules (applied in order):
      1. Already a CheckStatus → return as-is.
      2. None → UNKNOWN.
      3. bool True → PASS; False → FAIL.
      4. str (case-insensitive): "pass" → PASS, "fail" → FAIL, anything else → UNKNOWN.
      5. int: 0 → FAIL, 1 → PASS, anything else → UNKNOWN.
      6. Any other type → UNKNOWN.
    """
    if isinstance(raw_status, CheckStatus):
        return raw_status
    if raw_status is None:
        return CheckStatus.UNKNOWN
    if isinstance(raw_status, bool):
        return CheckStatus.PASS if raw_status else CheckStatus.FAIL
    if isinstance(raw_status, str):
        lowered = raw_status.strip().lower()
        if lowered == "pass":
            return CheckStatus.PASS
        if lowered == "fail":
            return CheckStatus.FAIL
        return CheckStatus.UNKNOWN
    if isinstance(raw_status, int):
        if raw_status == 1:
            return CheckStatus.PASS
        if raw_status == 0:
            return CheckStatus.FAIL
        return CheckStatus.UNKNOWN
    return CheckStatus.UNKNOWN


def safe_normalize(raw_status: Any, *, on_exception: CheckStatus = CheckStatus.UNKNOWN) -> CheckStatus:
    """Like normalize_status but catches any exception and returns on_exception."""
    try:
        return normalize_status(raw_status)
    except Exception:
        return on_exception
