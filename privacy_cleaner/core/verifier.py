"""
Post-processing verification engine inspecting output containers and producing formal Privacy & Forensic Reports.
"""
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Any, Optional

from .analyzer import analyze_video
from ..models.video_info import VideoInfo, VisualQualityReport


@dataclass
class VerificationReport:
    file_path: str = ""
    # Metadata checks
    gps: str = "NONE"
    camera: str = "NONE"
    device: str = "NONE"
    creation_date: str = "NONE"
    mod_date: str = "NONE"
    comment: str = "NONE"
    software: str = "NONE"
    encoder: str = "NONE"
    chapters: str = "NONE"
    attachments: str = "NONE"

    # Media parameters
    codec: str = "Unknown"
    resolution: str = "Unknown"
    fps: str = "Unknown"
    duration: str = "0.00 sec"
    audio: str = "None"

    # Inspection statuses
    metadata_passed: bool = True
    container_passed: bool = True
    stream_passed: bool = True
    all_passed: bool = True

    leaked_tags: Dict[str, Any] = field(default_factory=dict)
    summary_statement: str = "Embedded metadata successfully sanitized."
    video_info: Optional[VideoInfo] = None
    quality_report: Optional[VisualQualityReport] = None

    def format_text(self) -> str:
        """Returns the formatted ASCII Privacy & Fidelity Audit Report."""
        def mark(passed: bool) -> str:
            return "✓" if passed else "✗ LEAK"

        # Check if creation date is either NONE or Epoch 0 (1970-01-01)
        creation_clean = self.creation_date in ("NONE", "1970-01-01T00:00:00.000000Z", "1970-01-01T00:00:00Z")

        lines = [
            "════════ PRIVACY & FIDELITY REPORT ════════",
            "",
            "Metadata",
            f"  GPS                 {self.gps:<10} {mark(self.gps == 'NONE')}",
            f"  Camera              {self.camera:<10} {mark(self.camera == 'NONE')}",
            f"  Device              {self.device:<10} {mark(self.device == 'NONE')}",
            f"  Creation date       {self.creation_date:<10} {mark(creation_clean)}",
            f"  Modification date   {self.mod_date:<10} {mark(self.mod_date == 'NONE')}",
            f"  Comment             {self.comment:<10} {mark(self.comment == 'NONE')}",
            f"  Software            {self.software:<10} {mark(self.software == 'NONE')}",
            f"  Encoder             {self.encoder:<10} {mark(self.encoder == 'NONE')}",
            f"  Chapters            {self.chapters:<10} {mark(self.chapters == 'NONE')}",
            f"  Attachments         {self.attachments:<10} {mark(self.attachments == 'NONE')}",
            "",
            "Media",
            f"  Codec               {self.codec}",
            f"  Resolution          {self.resolution}",
            f"  FPS                 {self.fps}",
            f"  Duration            {self.duration}",
            f"  Audio               {self.audio}",
            "",
            "Sanitization Verification",
            f"  Metadata sanitization     {'PASSED' if self.metadata_passed else 'FAILED'}",
            f"  Container inspection     {'PASSED' if self.container_passed else 'FAILED'}",
            f"  Stream inspection        {'PASSED' if self.stream_passed else 'FAILED'}",
        ]

        if self.quality_report:
            q = self.quality_report
            lines.extend([
                "",
                "QUALITY GATE (Independent Audit)",
                "────────────────────────────────",
                "Transformation Policy",
                f"  Spatial policy:       {'PASS' if q.native_metrics.spatial_delta_pct <= 2.0 else 'FAIL'} ({q.native_metrics.spatial_delta_pct:.2f}%)",
                f"  Temporal policy:      {'PASS' if q.native_metrics.temporal_delta_pct <= 1.0 else 'FAIL'} ({q.native_metrics.temporal_delta_pct:.2f}%)",
                f"  Luminance policy:     {'PASS' if q.energy_metrics.mean_luma_delta * 100 <= 1.0 else 'FAIL'} ({q.energy_metrics.mean_luma_delta * 100:.2f}%)",
                f"  Chroma policy:        {'PASS' if q.energy_metrics.chroma_delta_composite * 100 <= 1.0 else 'FAIL'} ({q.energy_metrics.chroma_delta_composite * 100:.2f}%)",
                f"  Frequency policy:     {'PASS' if q.policy_score.frequency_score_pct <= 1.0 else 'FAIL'} ({q.policy_score.frequency_score_pct:.2f}%)",
                f"  Aggregate policy:     {'PASS' if q.policy_score.passed else 'FAIL'} (Score: {q.policy_score.aggregate_policy_score_pct:.2f}% / Max {q.policy_score.policy_ceiling_pct:.1f}%)",
                f"  HF Spectral Energy:   Ref={q.energy_metrics.hf_energy_ref:.1f}, Out={q.energy_metrics.hf_energy_trans:.1f} (Δabs={q.energy_metrics.abs_delta_hf:.1f}, Δrel={q.energy_metrics.rel_delta_hf:.4f})",
                "",
                "Rendered Fidelity",
                f"  SSIM mean:            {q.ssim.mean:.4f}  {'PASS' if q.ssim.mean >= 0.95 else 'FAIL'}",
                f"  SSIM P5:              {q.ssim.p5:.4f}  {'PASS' if q.ssim.p5 >= 0.90 else 'FAIL'}",
                f"  SSIM worst:           {q.ssim.min_val:.4f}  {'PASS' if q.ssim.min_val >= 0.85 else 'FAIL'}",
                f"  PSNR mean:            {q.psnr.mean:.2f} dB  {'PASS' if q.psnr.mean >= 30.0 else 'FAIL'}",
                f"  PSNR worst:           {q.psnr.min_val:.2f} dB  {'PASS' if q.psnr.min_val >= 25.0 else 'FAIL'}",
                f"  Luma Dist Drift(D_TV):{q.energy_metrics.luma_hist_divergence_tv:.4f}",
                "",
                "Temporal Integrity",
                f"  Missing frames:       {q.temporal_metrics.missing_frames}  {'PASS' if q.temporal_metrics.missing_frames == 0 else 'FAIL'}",
                f"  Duplicate frames:     {q.temporal_metrics.duplicate_frames}  {'PASS' if q.temporal_metrics.duplicate_frames == 0 else 'FAIL'}",
                f"  Reordered frames:     {q.temporal_metrics.reordered_frames}  {'PASS' if q.temporal_metrics.reordered_frames == 0 else 'FAIL'}",
                f"  Duration delta:       {q.native_metrics.duration_delta_sec:+.3f}s ({q.native_metrics.duration_delta_pct:.2f}%)  {'PASS' if q.native_metrics.duration_delta_pct <= 1.0 else 'FAIL'}",
                f"  Max timestamp drift:  {q.temporal_metrics.timestamp_drift_max_sec:.4f}s  {'PASS' if q.temporal_metrics.timestamp_drift_max_sec <= 0.1 else 'FAIL'}",
                f"  Cadence deviation:    {q.temporal_metrics.cadence_deviation_pct:.2f}%  {'PASS' if q.temporal_metrics.cadence_deviation_pct <= 1.0 else 'FAIL'}",
                "",
                f"FINAL VERDICT:          {q.three_tier_verdict.overall_verdict}",
                f"Input SHA-256:          {q.input_sha256[:16]}...{q.input_sha256[-8:]}" if q.input_sha256 else "",
                f"Output SHA-256:         {q.output_sha256[:16]}...{q.output_sha256[-8:]}" if q.output_sha256 else "",
                f"Ed25519 Signature:      {q.manifest_signature[:16]}...{q.manifest_signature[-8:]}" if q.manifest_signature else "",
                f"Public Key Fingerprint: {q.public_key_fingerprint}" if q.public_key_fingerprint else "",
            ])
            if q.policy_violations:
                lines.append("")
                lines.append("  Policy Constraint Violations:")
                for v in q.policy_violations:
                    lines.append(f"    - {v}")

        lines.extend([
            "",
            f"> {self.summary_statement}",
        ])
        return "\n".join(lines)


def verify_output(file_path: Path) -> VerificationReport:
    """
    Performs fresh ffprobe inspection of the processed media file and generates a VerificationReport.
    """
    info = analyze_video(file_path)
    meta = info.metadata
    v = info.video
    a = info.audio

    report = VerificationReport(
        file_path=str(file_path),
        video_info=info,
    )

    # Check for metadata traces
    leaks = {}
    if meta.gps:
        report.gps = "PRESENT"
        leaks["gps"] = meta.gps
    if meta.camera_make or meta.camera_model:
        report.camera = "PRESENT"
        leaks["camera"] = f"{meta.camera_make or ''} {meta.camera_model or ''}".strip()
    if meta.device_info:
        report.device = "PRESENT"
        leaks["device"] = meta.device_info

    # Handle creation time: None or normalized Epoch 0 are both clean
    if meta.creation_date:
        if meta.creation_date in ("1970-01-01T00:00:00.000000Z", "1970-01-01T00:00:00Z", "1970-01-01 00:00:00"):
            report.creation_date = "EPOCH 0"
        else:
            report.creation_date = "PRESENT"
            leaks["creation_date"] = meta.creation_date

    if meta.modification_date:
        report.mod_date = "PRESENT"
        leaks["modification_date"] = meta.modification_date
    if meta.comment:
        report.comment = "PRESENT"
        leaks["comment"] = meta.comment
    if meta.software:
        report.software = "PRESENT"
        leaks["software"] = meta.software
    if meta.encoder:
        report.encoder = "PRESENT"
        leaks["encoder"] = meta.encoder
    if meta.chapters_count > 0:
        report.chapters = f"{meta.chapters_count} FOUND"
        leaks["chapters"] = meta.chapters_count
    if meta.attachments_count > 0:
        report.attachments = f"{meta.attachments_count} FOUND"
        leaks["attachments"] = meta.attachments_count

    # Check for suspicious container tags (excluding innocent standard MP4 brands)
    allowed_standard_tags = {"major_brand", "minor_version", "compatible_brands", "creation_time"}
    suspicious_container = {
        k: val for k, val in meta.container_tags.items()
        if k.lower() not in allowed_standard_tags
    }
    if suspicious_container:
        leaks["suspicious_container_tags"] = suspicious_container

    # Check streams: Ensure only video/audio, no leftover data or subtitle tracks
    raw_streams = info.raw_probe.get("streams", [])
    suspicious_streams = [
        s for s in raw_streams
        if s.get("codec_type") not in ("video", "audio")
    ]

    report.metadata_passed = len(leaks) == 0
    report.container_passed = len(suspicious_container) == 0
    report.stream_passed = len(suspicious_streams) == 0
    report.all_passed = report.metadata_passed and report.container_passed and report.stream_passed
    report.leaked_tags = leaks

    if not report.all_passed:
        report.summary_statement = "Warning: Some metadata or stream artifacts were detected."
    else:
        report.summary_statement = "Embedded metadata successfully sanitized."

    # Media parameters
    if v:
        report.codec = (v.codec_long_name or v.codec).upper()
        report.resolution = v.resolution_str
        report.fps = f"{int(v.fps)}" if abs(v.fps - round(v.fps)) < 0.001 else f"{v.fps:.2f}"
    if a:
        report.audio = (a.codec_long_name or a.codec).upper()
    else:
        report.audio = "None"

    report.duration = f"{info.duration:.2f} sec"

    return report
