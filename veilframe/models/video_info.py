"""
Data models for video, audio, and metadata information parsed from media containers.
"""
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional, Tuple


@dataclass
class VideoStreamInfo:
    width: int = 0
    height: int = 0
    fps: float = 0.0
    avg_fps: float = 0.0
    duration: float = 0.0
    codec: str = ""
    codec_long_name: str = ""
    profile: str = ""
    pixel_format: str = ""
    color_space: str = ""
    color_range: str = ""
    bitrate: int = 0  # in bps
    frame_count: int = 0
    rotation: int = 0
    aspect_ratio: str = ""
    gop_b_frames: int = 0
    tags: Dict[str, Any] = field(default_factory=dict)

    @property
    def resolution_str(self) -> str:
        if self.width and self.height:
            return f"{self.width} × {self.height}"
        return "Unknown"

    @property
    def fps_str(self) -> str:
        if self.fps:
            return f"{self.fps:.2f} fps" if abs(self.fps - round(self.fps)) > 0.001 else f"{int(self.fps)} fps"
        return "Unknown"

    @property
    def bitrate_str(self) -> str:
        if self.bitrate > 0:
            if self.bitrate >= 1_000_000:
                return f"{self.bitrate / 1_000_000:.2f} Mbps"
            return f"{self.bitrate / 1_000:.1f} kbps"
        return "Unknown"


@dataclass
class AudioStreamInfo:
    codec: str = ""
    codec_long_name: str = ""
    sample_rate: int = 0
    channels: int = 0
    channel_layout: str = ""
    bitrate: int = 0
    duration: float = 0.0
    language: str = ""
    stream_count: int = 1
    tags: Dict[str, Any] = field(default_factory=dict)

    @property
    def channels_str(self) -> str:
        if self.channels == 1:
            return "Mono (1 ch)"
        elif self.channels == 2:
            return "Stereo (2 ch)"
        elif self.channels > 2:
            return f"{self.channels} channels ({self.channel_layout or 'surround'})"
        return "None / Unknown"

    @property
    def sample_rate_str(self) -> str:
        if self.sample_rate:
            return f"{self.sample_rate / 1000:.1f} kHz"
        return "Unknown"


@dataclass
class MetadataInfo:
    creation_date: Optional[str] = None
    modification_date: Optional[str] = None
    comment: Optional[str] = None
    encoder: Optional[str] = None
    software: Optional[str] = None
    gps: Optional[str] = None
    camera_make: Optional[str] = None
    camera_model: Optional[str] = None
    device_info: Optional[str] = None
    title: Optional[str] = None
    artist: Optional[str] = None
    chapters_count: int = 0
    attachments_count: int = 0
    container_tags: Dict[str, Any] = field(default_factory=dict)
    stream_tags: Dict[str, Any] = field(default_factory=dict)
    raw_tags: Dict[str, Any] = field(default_factory=dict)

    @property
    def has_privacy_leaks(self) -> bool:
        return bool(
            self.gps or self.camera_make or self.camera_model or self.device_info
            or self.creation_date or self.comment or self.software or self.encoder
            or self.chapters_count > 0 or self.attachments_count > 0
        )


@dataclass
class VideoInfo:
    file_path: str = ""
    format_name: str = ""
    format_long_name: str = ""
    duration: float = 0.0
    size_bytes: int = 0
    overall_bitrate: int = 0
    video: Optional[VideoStreamInfo] = None
    audio: Optional[AudioStreamInfo] = None
    metadata: MetadataInfo = field(default_factory=MetadataInfo)
    raw_probe: Dict[str, Any] = field(default_factory=dict)

    @property
    def duration_str(self) -> str:
        if self.duration <= 0:
            return "0.00s"
        m, s = divmod(self.duration, 60)
        h, m = divmod(m, 60)
        if h > 0:
            return f"{int(h):02d}:{int(m):02d}:{s:05.2f}"
        return f"{int(m):02d}:{s:05.2f} ({self.duration:.2f}s)"

    @property
    def size_str(self) -> str:
        if self.size_bytes >= 1024 * 1024 * 1024:
            return f"{self.size_bytes / (1024**3):.2f} GB"
        elif self.size_bytes >= 1024 * 1024:
            return f"{self.size_bytes / (1024**2):.2f} MB"
        elif self.size_bytes >= 1024:
            return f"{self.size_bytes / 1024:.1f} KB"
        return f"{self.size_bytes} bytes"


@dataclass
class QualityMetricStats:
    """Statistical distribution of a quality metric across evaluated frames."""
    mean: float = 0.0
    median: float = 0.0
    p1: float = 0.0
    p5: float = 0.0
    p95: float = 0.0
    min_val: float = 0.0  # Worst-case frame score
    max_val: float = 0.0
    std_dev: float = 0.0


@dataclass
class NativeDomainMetrics:
    """Exact stream and container measurements without spatial/temporal normalization."""
    resolution_ref: str = ""
    resolution_trans: str = ""
    spatial_delta_pct: float = 0.0
    fps_ref: float = 0.0
    fps_trans: float = 0.0
    fps_delta_pct: float = 0.0
    duration_ref: float = 0.0
    duration_trans: float = 0.0
    duration_delta_sec: float = 0.0
    duration_delta_pct: float = 0.0
    aspect_ratio_ref: str = ""
    aspect_ratio_trans: str = ""
    pix_fmt_ref: str = ""
    pix_fmt_trans: str = ""
    colorspace_ref: str = ""
    colorspace_trans: str = ""


@dataclass
class DecodedEnergyMetrics:
    """Direct pixel and spectral measurements from decoded YUV planes."""
    mean_luma_delta: float = 0.0        # Delta Mean Y / 255
    rms_luma_delta: float = 0.0         # RMS Delta Y / 255
    luma_hist_divergence_tv: float = 0.0  # Total Variation normalized histogram distance (0 to 1)
    chroma_delta_u: float = 0.0         # Delta Mean U / 255
    chroma_delta_v: float = 0.0         # Delta Mean V / 255
    chroma_delta_composite: float = 0.0 # sqrt(dU^2 + dV^2)
    hf_energy_ref: float = 0.0          # Laplacian variance of reference frames
    hf_energy_trans: float = 0.0        # Laplacian variance of transformed frames
    abs_delta_hf: float = 0.0           # |Et - Er|
    rel_delta_hf: float = 0.0           # |Et - Er| / (Er + 1.0)
    # Sampling metadata
    sampling_strategy: str = "uniform_timeline"
    sampling_range: Tuple[float, float] = (0.02, 0.98)
    sampled_indices_ref: List[int] = field(default_factory=list)
    sampled_timestamps_ref: List[float] = field(default_factory=list)
    sampled_indices_trans: List[int] = field(default_factory=list)
    sampled_timestamps_trans: List[float] = field(default_factory=list)


@dataclass
class TemporalIntegrityMetrics:
    """Pre-resampling temporal correspondence and frame integrity audit."""
    frame_count_ref: int = 0
    frame_count_trans: int = 0
    frame_count_diff: int = 0
    missing_frames: int = 0
    duplicate_frames: int = 0            # Frame-level visual/timestamp duplication
    duplicate_timestamps: int = 0        # Container packet timestamp duplication
    duplicate_decoded_frames: int = 0    # Identical decoded luma matrix duplication
    reordered_frames: int = 0            # Non-monotonic PTS sequences
    timestamp_drift_max_sec: float = 0.0
    timestamp_drift_mean_sec: float = 0.0
    cadence_deviation_pct: float = 0.0
    passed: bool = True
    violations: List[str] = field(default_factory=list)


@dataclass
class TransformationPolicyScore:
    """
    Application-defined engineering policy budget score.
    NOTE: This is a declared policy ceiling score, not a literal percentage of changed pixels.
    """
    spatial_score_pct: float = 0.0
    temporal_score_pct: float = 0.0
    luminance_score_pct: float = 0.0
    chroma_score_pct: float = 0.0
    frequency_score_pct: float = 0.0
    aggregate_policy_score_pct: float = 0.0
    policy_ceiling_pct: float = 5.0
    passed: bool = True
    violations: List[str] = field(default_factory=list)


@dataclass
class ThreeTierQualityVerdict:
    """Three-tier independent quality gate verdict."""
    tier1_policy_passed: bool = True
    tier1_violations: List[str] = field(default_factory=list)
    tier2_fidelity_passed: bool = True
    tier2_violations: List[str] = field(default_factory=list)
    tier3_temporal_passed: bool = True
    tier3_violations: List[str] = field(default_factory=list)
    overall_verdict: str = "PASS"  # "PASS" or "REJECT"
    all_passed: bool = True


@dataclass
class VisualQualityReport:
    """Independent audit report evaluating rendered output against visual fidelity constraints."""
    evaluated_frames: int = 0
    input_sha256: str = ""
    output_sha256: str = ""
    native_metrics: NativeDomainMetrics = field(default_factory=NativeDomainMetrics)
    energy_metrics: DecodedEnergyMetrics = field(default_factory=DecodedEnergyMetrics)
    temporal_metrics: TemporalIntegrityMetrics = field(default_factory=TemporalIntegrityMetrics)
    policy_score: TransformationPolicyScore = field(default_factory=TransformationPolicyScore)
    ssim: QualityMetricStats = field(default_factory=QualityMetricStats)
    psnr: QualityMetricStats = field(default_factory=QualityMetricStats)
    three_tier_verdict: ThreeTierQualityVerdict = field(default_factory=ThreeTierQualityVerdict)
    passed: bool = True
    policy_violations: List[str] = field(default_factory=list)
    signing_mode: str = "ephemeral"
    signing_key_id: Optional[str] = None
    manifest_signature: str = ""
    public_key_pem: str = ""
    public_key_fingerprint: str = ""
    public_key_fingerprint_pem: str = ""
    raw_details: Dict[str, Any] = field(default_factory=dict)

    @property
    def summary_text(self) -> str:
        status = "PASSED" if self.passed else "FAILED / REJECTED"
        lines = [
            f"QUALITY GATE: {status}",
            "────────────────────────────────",
            "Transformation Policy (Score Budget)",
            f"  Spatial policy:       {'PASS' if self.native_metrics.spatial_delta_pct <= 2.0 else 'FAIL'} ({self.native_metrics.spatial_delta_pct:.2f}%)",
            f"  Temporal policy:      {'PASS' if self.native_metrics.temporal_delta_pct <= 1.0 else 'FAIL'} ({self.native_metrics.temporal_delta_pct:.2f}%)",
            f"  Luminance policy:     {'PASS' if self.energy_metrics.mean_luma_delta * 100 <= 1.0 else 'FAIL'} ({self.energy_metrics.mean_luma_delta * 100:.2f}%)",
            f"  Chroma policy:        {'PASS' if self.energy_metrics.chroma_delta_composite * 100 <= 1.0 else 'FAIL'} ({self.energy_metrics.chroma_delta_composite * 100:.2f}%)",
            f"  Frequency policy:     {'PASS' if self.policy_score.frequency_score_pct <= 1.0 else 'FAIL'} ({self.policy_score.frequency_score_pct:.2f}%)",
            f"  Aggregate policy:     {'PASS' if self.policy_score.passed else 'FAIL'} (Score: {self.policy_score.aggregate_policy_score_pct:.2f}% / Max {self.policy_score.policy_ceiling_pct:.1f}%)",
            f"  HF Spectral Energy:   Ref={self.energy_metrics.hf_energy_ref:.1f}, Out={self.energy_metrics.hf_energy_trans:.1f} (Δabs={self.energy_metrics.abs_delta_hf:.1f}, Δrel={self.energy_metrics.rel_delta_hf:.4f})",
            "",
            "Rendered Fidelity",
            f"  SSIM mean:            {self.ssim.mean:.4f}  {'PASS' if self.ssim.mean >= 0.95 else 'FAIL'}",
            f"  SSIM P5:              {self.ssim.p5:.4f}  {'PASS' if self.ssim.p5 >= 0.90 else 'FAIL'}",
            f"  SSIM worst:           {self.ssim.min_val:.4f}  {'PASS' if self.ssim.min_val >= 0.85 else 'FAIL'}",
            f"  PSNR mean:            {self.psnr.mean:.2f} dB  {'PASS' if self.psnr.mean >= 30.0 else 'FAIL'}",
            f"  PSNR worst:           {self.psnr.min_val:.2f} dB  {'PASS' if self.psnr.min_val >= 25.0 else 'FAIL'}",
            f"  Luma Dist Drift(D_TV):{self.energy_metrics.luma_hist_divergence_tv:.4f}",
            "",
            "Temporal Integrity",
            f"  Missing frames:       {self.temporal_metrics.missing_frames}  {'PASS' if self.temporal_metrics.missing_frames == 0 else 'FAIL'}",
            f"  Duplicate frames:     {self.temporal_metrics.duplicate_frames}  {'PASS' if self.temporal_metrics.duplicate_frames == 0 else 'FAIL'}",
            f"  Reordered frames:     {self.temporal_metrics.reordered_frames}  {'PASS' if self.temporal_metrics.reordered_frames == 0 else 'FAIL'}",
            f"  Duration delta:       {self.native_metrics.duration_delta_sec:+.3f}s ({self.native_metrics.duration_delta_pct:.2f}%)  {'PASS' if self.native_metrics.duration_delta_pct <= 1.0 else 'FAIL'}",
            f"  Max timestamp drift:  {self.temporal_metrics.timestamp_drift_max_sec:.4f}s  {'PASS' if self.temporal_metrics.timestamp_drift_max_sec <= 0.1 else 'FAIL'}",
            f"  Cadence deviation:    {self.temporal_metrics.cadence_deviation_pct:.2f}%  {'PASS' if self.temporal_metrics.cadence_deviation_pct <= 1.0 else 'FAIL'}",
            "",
            f"FINAL VERDICT:          {self.three_tier_verdict.overall_verdict}",
        ]
        if self.public_key_fingerprint:
            lines.append(f"Public Key Fingerprint: {self.public_key_fingerprint}")
        if self.policy_violations:
            lines.append("")
            lines.append("Violations:")
            for v in self.policy_violations:
                lines.append(f"  - {v}")
        return "\n".join(lines)
