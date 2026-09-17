"""
veilframe.image.redteam.engine — Red-team adversarial validation engine.

The Red-Team Engine runs all registered attack probes against the sanitized
output and returns a consolidated PrivacyAttackResult.

Architectural role:
  - The engine runs AFTER all sanitizers have produced output.
  - Each probe independently tests one attack vector.
  - A single probe failure → overall red-team result = FAIL.
  - The engine's result is recorded in the EvidencePreimage as
    execution_record_hash.

The engine is deterministic: given the same input bytes + probe set,
it always produces the same result.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from ..models.status import CheckStatus
from .probes.base import AttackProbe, ProbeResult


@dataclass
class PrivacyAttackResult:
    """Consolidated result from all red-team attack probes.

    Fields
    ------
    overall_status : CheckStatus
        PASS iff every probe returned PASS.  Any FAIL or UNKNOWN → FAIL.
    probe_results : Dict[str, ProbeResult]
        Per-probe results keyed by probe name.
    probes_run : int
        Total number of probes executed.
    probes_failed : int
        Number of probes that returned FAIL or UNKNOWN.
    """
    overall_status: CheckStatus
    probe_results: Dict[str, "ProbeResult"] = field(default_factory=dict)
    probes_run: int = 0
    probes_failed: int = 0

    def to_dict(self) -> dict:
        return {
            "overall_status": self.overall_status.value,
            "probes_run": self.probes_run,
            "probes_failed": self.probes_failed,
            "probe_results": {
                name: r.to_dict() for name, r in self.probe_results.items()
            },
        }


class RedTeamEngine:
    """Runs all registered attack probes against the sanitized output.

    Usage
    -----
    engine = RedTeamEngine()
    engine.register(MetadataProbe())
    engine.register(ThumbnailProbe())
    result = engine.run(original_bytes=..., sanitized_bytes=...)
    """

    def __init__(self) -> None:
        self._probes: List[AttackProbe] = []

    def register(self, probe: "AttackProbe") -> None:
        """Register an attack probe."""
        self._probes.append(probe)

    def run(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> PrivacyAttackResult:
        """Execute all probes and aggregate results."""
        probe_results: Dict[str, ProbeResult] = {}
        probes_failed = 0
        overall = CheckStatus.PASS

        for probe in self._probes:
            try:
                result = probe.attack(
                    original_bytes=original_bytes,
                    sanitized_bytes=sanitized_bytes,
                    original_array=original_array,
                    sanitized_array=sanitized_array,
                )
            except Exception as exc:
                from .probes.base import ProbeResult
                result = ProbeResult(
                    probe_name=probe.name,
                    status=CheckStatus.UNKNOWN,
                    failure_reason=f"Probe exception: {type(exc).__name__}: {exc}",
                )

            probe_results[probe.name] = result
            overall = overall & result.status
            if result.status != CheckStatus.PASS:
                probes_failed += 1

        return PrivacyAttackResult(
            overall_status=overall,
            probe_results=probe_results,
            probes_run=len(self._probes),
            probes_failed=probes_failed,
        )


def build_default_engine() -> RedTeamEngine:
    """Build a RedTeamEngine with all default probes registered."""
    from .probes.metadata_probe import MetadataProbe
    from .probes.thumbnail_probe import ThumbnailProbe
    from .probes.container_probe import ContainerProbe
    from .probes.face_probe import FaceProbe
    from .probes.plate_probe import PlateProbe
    from .probes.code_probe import CodeProbe
    from .probes.text_probe import TextProbe

    engine = RedTeamEngine()
    engine.register(MetadataProbe())
    engine.register(ThumbnailProbe())
    engine.register(ContainerProbe())
    engine.register(FaceProbe())
    engine.register(PlateProbe())
    engine.register(CodeProbe())
    engine.register(TextProbe())
    return engine
