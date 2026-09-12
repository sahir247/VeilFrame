"""
veilframe.image.redteam.probes.base — Abstract AttackProbe protocol.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional

from ...models.status import CheckStatus


@dataclass
class ProbeResult:
    """Result of a single attack probe.

    Fields
    ------
    probe_name : str
        Stable name of the probe (e.g. "metadata", "thumbnail").
    status : CheckStatus
        PASS iff the attack failed to extract private information.
    failure_reason : Optional[str]
        Human-readable description of what leaked.
    findings : List[str]
        Specific finding strings (e.g. GPS coordinates, face bbox).
    """
    probe_name: str
    status: CheckStatus
    failure_reason: Optional[str] = None
    findings: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        d = {
            "probe_name": self.probe_name,
            "status": self.status.value,
            "finding_count": len(self.findings),
        }
        if self.failure_reason:
            d["failure_reason"] = self.failure_reason
        if self.findings:
            d["findings"] = self.findings[:5]  # first 5 findings
        return d


class AttackProbe(ABC):
    """Abstract base class for all VeilFrame red-team attack probes.

    A probe attempts to extract private information from the sanitized output.
    PASS means the probe failed to find anything (privacy holds).
    FAIL means the probe succeeded in finding private data (privacy breach).
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Stable probe name used as dict key in results."""
        ...

    @abstractmethod
    def attack(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> ProbeResult:
        """Run the attack.

        Parameters
        ----------
        original_bytes : bytes
            Input image bytes (pre-sanitization).
        sanitized_bytes : bytes
            Output image bytes (post-sanitization).
        original_array : Optional[numpy.ndarray]
            Pre-sanitization linear sRGB float32 array, if available.
        sanitized_array : Optional[numpy.ndarray]
            Post-sanitization linear sRGB float32 array, if available.

        Returns
        -------
        ProbeResult
            status=PASS → privacy holds (attack failed).
            status=FAIL → privacy breach (attack succeeded).
            status=UNKNOWN → probe could not execute (treated as FAIL at gate).
        """
        ...
