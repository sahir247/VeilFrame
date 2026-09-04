"""
Configuration and settings dataclasses for processing operations and presets.
"""
from dataclasses import dataclass, field
from typing import Optional, Dict, Any, List


@dataclass
class CropSettings:
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    asymmetric: bool = True  # Asymmetric crop to disrupt pHash & edge grids
    left: int = 0
    right: int = 0
    top: int = 0
    bottom: int = 0
    # or direct bounding box
    x: int = 0
    y: int = 0
    width: int = 0
    height: int = 0


@dataclass
class ResizeSettings:
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    width: int = 1920
    height: int = 1080
    maintain_aspect: bool = True


@dataclass
class FpsSettings:
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    fps: float = 30.0


@dataclass
class TrimSettings:
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    start: float = 0.0
    end: Optional[float] = None
    duration: Optional[float] = None


@dataclass
class NoiseSettings:
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    strength: int = 1  # 0 to 100 (0=disabled, 1=minimum subtle, 8=5% budget, 16=10% budget)
    prnu_mode: str = "gaussian"  # "gaussian" or "cfa_mosaic"
    cfa_pattern: str = "RGGB"  # "RGGB", "BGGR", "GRBG", "GBRG"
    cfa_gamma: float = 0.6  # saturation clamping exponent
    hash_perturbation_enabled: bool = False
    hash_perturbation_budget: float = 0.02


@dataclass
class ColorSettings:
    """Low-frequency color and luminance drift (~1% perturbation budget)."""
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    contrast: float = 1.015   # 1.015 (~1.5% contrast tilt)
    brightness: float = 0.005 # 0.005 (~0.5% luma shift)
    gamma: float = 0.985      # 0.985 (~1.5% non-linear curve shift)
    saturation: float = 1.02  # 1.02 (~2% chrominance drift)


@dataclass
class AudioPrivacySettings:
    """Audio domain privacy & Electrical Network Frequency (ENF) mains notch filtering."""
    enabled: bool = False
    mode: str = "auto"  # "auto" or "manual"
    enf_notch: bool = True     # 50Hz, 60Hz, 100Hz, 120Hz mains hum notch
    enf_frequencies: List[int] = field(default_factory=lambda: [50, 60, 100, 120])
    micro_pitch: bool = True   # ~0.99x micro-pitch shift
    pitch_ratio: float = 0.99
    noise_floor_dither: bool = False


@dataclass
class QuantizationSettings:
    """Deterministic quantization, fixed GOP, and timestamp normalization."""
    forced_gop: bool = True
    gop_size: int = 48
    scene_change_threshold: int = 0  # 0 = fixed cadence, no adaptive scene keyframes
    normalize_timestamps: bool = True
    epoch_zero: bool = True          # creation_time="1970-01-01T00:00:00Z"
    bitexact: bool = True


@dataclass
class CodecSettings:
    mode: str = "auto"  # "auto" or "manual"
    codec: str = "h264"  # "h264", "hevc", "av1"


@dataclass
class QualitySettings:
    mode: str = "auto"  # "auto", "crf", "bitrate"
    crf: int = 18
    bitrate_kbps: int = 12000


@dataclass
class PrivacySettings:
    remove_metadata: bool = True
    remove_comments: bool = True
    remove_chapters: bool = True
    remove_attachments: bool = True
    scrub_after_encoding: bool = True
    verify_output: bool = True


@dataclass
class VisualBudgetPolicy:
    """Multi-dimensional visual quality gate and fidelity constraint policy."""
    enabled: bool = True
    enforce_strict: bool = False  # If True, rejects/fails export when constraints are violated
    policy_budget: float = 0.05   # Target 5% bounded perturbation budget (5.0%)

    # Calibration weights
    frequency_weight: float = 2.0
    luma_weight: float = 100.0
    chroma_weight: float = 100.0

    # Policy component ceilings (%)
    spatial_ceiling_pct: float = 2.0
    temporal_ceiling_pct: float = 1.0
    luma_ceiling_pct: float = 1.0
    chroma_ceiling_pct: float = 1.0
    frequency_ceiling_pct: float = 1.0
    aggregate_ceiling_pct: float = 5.0

    # Structural similarity constraints
    ssim_mean_min: float = 0.95
    ssim_p5_min: float = 0.90
    ssim_worst_min: float = 0.85

    # Pixel-level fidelity constraints
    psnr_mean_min_db: float = 30.0
    psnr_worst_min_db: float = 25.0

    # VMAF perceptual quality gate (Tier 2b).
    # NOTE: Uncalibrated exploratory defaults. These values (85 / 75 / 70) are
    # baseline placeholders and must NOT be represented as an empirically validated
    # production threshold. Real-corpus calibration with strict research constraints
    # (FAR < 2.0%, FRR < 5.0%) yielded NO_FEASIBLE_THRESHOLD for a single global scalar.
    # The production gate MUST remain disabled (vmaf_gate_enabled=False) until an
    # operating point achieves verified held-out validation.
    # "Providers measure. VeilFrame decides." — QualityGate owns verdict logic.
    vmaf_gate_enabled: bool = False
    vmaf_mean_min: float = 85.0   # Uncalibrated baseline mean placeholder (gate disabled)
    vmaf_p5_min: float = 75.0     # Uncalibrated baseline P5 tail placeholder (gate disabled)
    vmaf_worst_min: float = 70.0  # Uncalibrated baseline worst-frame placeholder (gate disabled)

    # VMAF model provenance and audit controls
    vmaf_model_path: Optional[str] = None
    vmaf_audit_mode: bool = False

    # Sampling controls
    sample_count: int = 15
    sample_range_start: float = 0.02
    sample_range_end: float = 0.98
    max_eval_frames: int = 800

    # Cryptographic signing controls
    signing_mode: str = "ephemeral"  # "ephemeral" or "persistent"
    signing_key_path: Optional[str] = None
    key_id: Optional[str] = None


@dataclass
class ProcessingSettings:
    preset_name: str = "Custom"
    crop: CropSettings = field(default_factory=CropSettings)
    resize: ResizeSettings = field(default_factory=ResizeSettings)
    fps: FpsSettings = field(default_factory=FpsSettings)
    trim: TrimSettings = field(default_factory=TrimSettings)
    noise: NoiseSettings = field(default_factory=NoiseSettings)
    color: ColorSettings = field(default_factory=ColorSettings)
    audio_privacy: AudioPrivacySettings = field(default_factory=AudioPrivacySettings)
    quantization: QuantizationSettings = field(default_factory=QuantizationSettings)
    codec: CodecSettings = field(default_factory=CodecSettings)
    quality: QualitySettings = field(default_factory=QualitySettings)
    privacy: PrivacySettings = field(default_factory=PrivacySettings)
    quality_gate: VisualBudgetPolicy = field(default_factory=VisualBudgetPolicy)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "preset_name": self.preset_name,
            "crop": vars(self.crop),
            "resize": vars(self.resize),
            "fps": vars(self.fps),
            "trim": vars(self.trim),
            "noise": vars(self.noise),
            "color": vars(self.color),
            "audio_privacy": vars(self.audio_privacy),
            "quantization": vars(self.quantization),
            "codec": vars(self.codec),
            "quality": vars(self.quality),
            "privacy": vars(self.privacy),
            "quality_gate": vars(self.quality_gate),
        }
