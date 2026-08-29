"""
Quality provider data models.

Design invariant: These types are the *only* types that QualityGate consumes.
External providers (libvmaf, ffmpeg-native, ffmpeg-quality-metrics) produce
QualityResult objects. VeilFrame owns all verdict logic.

No provider-specific subclasses. VMAF sub-metrics (ADM2, VIF scales) are stored
in the generic `feature_metrics` dict to avoid a type hierarchy.
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
    model_path: Optional[Path] = None       # None = dev mode (FFmpeg default search)
    evidence_dir: Optional[Path] = None     # destination directory for vmaf.json etc.
    audit_mode: bool = False                # if True, model_path must be explicitly set


@dataclass
class PerFrameMetric:
    """Single per-frame measurement from a quality provider."""
    frame_index: int
    timestamp_sec: float
    value: float


@dataclass
class QualityResult:
    """
    Generic result returned by any QualityProvider.

    Per-frame data (per_frame) may be left empty for large videos when
    evidence is written to an external file (evidence_file) and hashed
    (evidence_sha256). The manifest records only the hash.
    """
    provider_name: str          # e.g. "libvmaf", "ffmpeg-native"
    metric_name: str            # e.g. "vmaf", "ssim", "psnr"
    mean: float
    minimum: Optional[float] = None
    p1: Optional[float] = None
    p5: Optional[float] = None
    p95: Optional[float] = None
    per_frame: List[PerFrameMetric] = field(default_factory=list)
    evidence_file: Optional[Path] = None        # path to detailed log (vmaf.json)
    evidence_sha256: Optional[str] = None       # SHA-256 of evidence_file
    model_name: Optional[str] = None
    model_sha256: Optional[str] = None
    # VMAF-specific scalars (ADM2, VIF) — stored here to avoid subclassing
    feature_metrics: Dict[str, float] = field(default_factory=dict)
    raw_output: Optional[Dict[str, Any]] = None
