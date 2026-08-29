"""
QualityProvider Protocol.

Architectural invariant:
  Providers measure. VeilFrame decides.

Every external quality tool (FFmpeg lavfi, libvmaf, ffmpeg-quality-metrics)
implements this protocol. QualityGate has no knowledge of which backend
produced the QualityResult objects it receives.

Key design rules:
  - is_available() must NEVER raise. Unavailability is a first-class state.
  - runtime_info() returns Dict[str, Any] so nullable fields (libvmaf_version)
    are represented as None, not as empty strings or manufactured values.
  - evaluate() may raise if is_available() returned True but runtime fails.
  - No provider may set or enforce pass/fail thresholds.
"""
from typing import Protocol, runtime_checkable, List, Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .models import QualityConfig, QualityResult


@runtime_checkable
class QualityProvider(Protocol):
    """
    Protocol that every quality measurement adapter must satisfy.

    runtime_info() key schema (all providers):
      adapter_version:          str       VeilFrame-controlled adapter semver
      runtime_version:          str|None  Backend binary version (ffmpeg -version)
      libvmaf_version:          str|None  libvmaf version if detectable; None otherwise
      libvmaf_version_source:   str       "ffmpeg-version-output" | "unavailable"
      model_identity:           dict|None { name, sha256, source } or None
      capabilities:             List[str] metric names this provider can produce
    """
    name: str
    version: str            # adapter semver (VeilFrame-controlled)
    capabilities: List[str]

    def is_available(self) -> bool:
        """
        Returns True only if all required binaries, libraries, and (in audit mode)
        model files are present and functional. Must never raise.
        """
        ...

    def runtime_info(self) -> Dict[str, Any]:
        """
        Returns structured metadata for inclusion in the signed manifest.
        libvmaf_version must be None when not reliably detectable —
        never manufacture version strings.
        """
        ...

    def evaluate(
        self,
        config: "QualityConfig",
    ) -> List["QualityResult"]:
        """
        Runs measurement. Returns one QualityResult per metric produced.
        Must not enforce pass/fail logic. May raise RuntimeError on
        execution failure if is_available() previously returned True.
        """
        ...
