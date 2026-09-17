"""
Quality provider data models.

Design invariant: These types are the *only* types that QualityGate consumes.
External providers produce QualityResult objects. VeilFrame owns all verdict logic.
"""
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Dict, Any, Optional


@dataclass
class QualityConfig:
    """Configuration passed to every QualityProvider.evaluate() call."""
    reference: Path
    distorted: Path
    canonical_w: int
    canonical_h: int
    sample_count: int = 15
    evidence_dir: Optional[Path] = None     # destination directory for detailed logs


@dataclass
class PerFrameMetric:
    """Single per-frame measurement from a quality provider."""
    frame_index: int
    value: float
    timestamp_sec: Optional[float] = None


@dataclass
class QualityResult:
    """
    Generic result returned by any QualityProvider.

    Per-frame data (per_frame) may be left empty for large videos when
    evidence is written to an external file (evidence_file) and hashed
    (evidence_sha256). The manifest records only the hash.
    """
    provider_name: str                      # e.g. "ffmpeg-native"
    metric_name: str                        # e.g. "ssim", "psnr"
    mean: Optional[float] = None
    minimum: Optional[float] = None
    p1: Optional[float] = None
    p5: Optional[float] = None
    p95: Optional[float] = None
    status: str = "success"                 # "success", "missing", "error"
    error_message: Optional[str] = None
    per_frame: List[PerFrameMetric] = field(default_factory=list)
    evidence_file: Optional[Path] = None
    evidence_sha256: Optional[str] = None
    model_name: Optional[str] = None
    model_sha256: Optional[str] = None
    feature_metrics: Dict[str, float] = field(default_factory=dict)
    raw_output: Optional[Dict[str, Any]] = None

