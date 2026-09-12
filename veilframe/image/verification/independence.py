"""
veilframe.image.verification.independence — IndependenceAuditor.

Enforces Fingerprint-Distinct Independence Levels 0–3 as defined in spec
Section 7.  Every red-team probe that claims a minimum independence level
must satisfy the corresponding fingerprint distinctness criteria relative
to the primary detection provider used during sanitization.

Level 0: Same implementation — NOT acceptable for red-team probes.
Level 1: Distinct implementation binary.
Level 2: Distinct algorithm OR distinct library binary.
Level 3: Fingerprint-Distinct — distinct algorithm AND model family AND
         implementation AND dependency graph.  Required minimum.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from ..models.graph import ProviderFingerprint
from ..models.status import IndependenceLevel, CheckStatus


def compute_independence_level(
    primary: ProviderFingerprint,
    probe: ProviderFingerprint,
) -> IndependenceLevel:
    """Compute the IndependenceLevel of a probe relative to the primary provider.

    Evaluates fingerprint fields in order from weakest to strongest independence.
    """
    # Level 0: same implementation hash
    if probe.implementation_hash == primary.implementation_hash:
        return IndependenceLevel.LEVEL_0

    # Level 1: distinct implementation
    # Check if it qualifies for Level 2 or 3 first
    distinct_algorithm = probe.algorithm_id != primary.algorithm_id
    distinct_library = probe.library_binary_hash != primary.library_binary_hash
    distinct_model_family = probe.model_family != primary.model_family
    distinct_dep_graph = probe.dependency_graph_hash != primary.dependency_graph_hash

    # Level 3: all four dimensions distinct
    if (distinct_algorithm and distinct_model_family
            and distinct_dep_graph):
        return IndependenceLevel.LEVEL_3

    # Level 2: distinct algorithm OR distinct library
    if distinct_algorithm or distinct_library:
        return IndependenceLevel.LEVEL_2

    # Level 1: only implementation hash differs
    return IndependenceLevel.LEVEL_1


@dataclass
class IndependenceAuditResult:
    """Result of evaluating a probe's independence from the primary provider."""
    probe_id: str
    achieved_level: IndependenceLevel
    required_level: IndependenceLevel
    status: CheckStatus
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "probe_id": self.probe_id,
            "achieved_level": self.achieved_level.value,
            "required_level": self.required_level.value,
            "status": self.status.value,
        }
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


class IndependenceAuditor:
    """Evaluates fingerprint-distinct independence for all red-team probes."""

    def __init__(
        self,
        primary_fingerprint: Optional[ProviderFingerprint] = None,
        required_level: int = 1,
        min_independence_level: Optional[int] = None,
    ) -> None:
        self._primary = primary_fingerprint
        level = min_independence_level if min_independence_level is not None else required_level
        self._required = IndependenceLevel(level)

    def audit_probe(
        self,
        probe_id: str,
        probe_fingerprint: ProviderFingerprint,
    ) -> IndependenceAuditResult:
        """Audit a single probe against the primary provider fingerprint."""
        if self._primary is None:
            return IndependenceAuditResult(
                probe_id=probe_id,
                achieved_level=IndependenceLevel.LEVEL_3,
                required_level=self._required,
                status=CheckStatus.PASS,
            )

        achieved = compute_independence_level(self._primary, probe_fingerprint)

        if achieved.value < self._required.value:
            reason = (
                f"Probe {probe_id!r} achieved IndependenceLevel {achieved.name} "
                f"but {self._required.name} is required."
            )
            return IndependenceAuditResult(
                probe_id=probe_id,
                achieved_level=achieved,
                required_level=self._required,
                status=CheckStatus.FAIL,
                failure_reason=reason,
            )

        return IndependenceAuditResult(
            probe_id=probe_id,
            achieved_level=achieved,
            required_level=self._required,
            status=CheckStatus.PASS,
        )
