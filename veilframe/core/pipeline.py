"""
Two-pass video privacy cleaning and processing pipeline.
"""
import shutil
import tempfile
from pathlib import Path
from typing import Optional, Callable

from .analyzer import analyze_video
from .sanitizer import pre_sanitize, post_sanitize
from .encoder import run_encode_pass
from .verifier import verify_output, VerificationReport
from .validator import evaluate_visual_quality, generate_ed25519_signed_manifest
from ..models.settings import ProcessingSettings
from ..models.video_info import VideoInfo, VisualQualityReport


def run_pipeline(
    src_path: Path,
    dst_path: Path,
    settings: ProcessingSettings,
    progress_callback: Optional[Callable[[float, str], None]] = None,
    cancel_check: Optional[Callable[[], bool]] = None,
) -> VerificationReport:
    """
    Executes the full multi-pass privacy and visual fidelity workflow:
    1. ANALYZE: inspects input streams, codecs, and tags
    2. PRE-SANITIZE: strips pre-existing metadata, attachments, and cover art
    3. PROCESS / ENCODE: applies crop, resize, fps, trim, noise, color, and codec transforms
    4. POST-SANITIZE: scrubs any encoder-injected tags and writes clean container headers
    5. QUALITY GATE: independent visual fidelity audit (SSIM & PSNR distribution vs policy constraints)
    6. VERIFY: performs fresh post-export inspection and produces auditable report
    """
    if cancel_check and cancel_check():
        raise RuntimeError("Cancelled before start.")

    # 1. Analyze
    if progress_callback:
        progress_callback(5.0, "Analyzing input media & inspecting metadata...")
    info: VideoInfo = analyze_video(src_path)

    if cancel_check and cancel_check():
        raise RuntimeError("Cancelled.")

    with tempfile.TemporaryDirectory(prefix="pvc_") as tmp_dir:
        tmp_path = Path(tmp_dir)
        stage1_path = tmp_path / "stage1_presanitized.mp4"
        stage2_path = tmp_path / "stage2_encoded.mp4"
        final_temp_path = tmp_path / "final_clean.mp4"

        # 2. Pre-sanitize (if enabled in privacy settings)
        if settings.privacy.remove_metadata:
            if progress_callback:
                progress_callback(15.0, "Pass 1: Pre-sanitizing container metadata & attachments...")
            pre_sanitize(src_path, stage1_path)
            input_for_encode = stage1_path
        else:
            input_for_encode = src_path

        if cancel_check and cancel_check():
            raise RuntimeError("Cancelled.")

        # 3. Process & Encode
        if progress_callback:
            progress_callback(25.0, "Pass 2: Encoding & applying visual/temporal filters...")

        def encode_progress(pct: float, msg: str):
            # Scale encode progress from 25% to 80%
            mapped = 25.0 + (pct * 0.55)
            if progress_callback:
                progress_callback(mapped, msg)

        run_encode_pass(
            src=input_for_encode,
            dst=stage2_path,
            settings=settings,
            video_info=info,
            progress_callback=encode_progress,
            cancel_check=cancel_check,
        )

        if cancel_check and cancel_check():
            raise RuntimeError("Cancelled.")

        # 4. Post-sanitize
        if settings.privacy.scrub_after_encoding:
            if progress_callback:
                progress_callback(82.0, "Pass 3: Post-sanitizing output container headers...")
            post_sanitize(stage2_path, final_temp_path)
            output_source = final_temp_path
        else:
            output_source = stage2_path

        if cancel_check and cancel_check():
            raise RuntimeError("Cancelled.")

        # Move to destination
        dst_path.parent.mkdir(parents=True, exist_ok=True)
        if dst_path.exists():
            dst_path.unlink()
        shutil.copy2(str(output_source), str(dst_path))

    # 5. Independent Visual Quality & Fidelity Gate
    quality_report: Optional[VisualQualityReport] = None
    q_policy = getattr(settings, "quality_gate", None)
    if q_policy and q_policy.enabled:
        if progress_callback:
            progress_callback(88.0, "Pass 4: Independent Visual Quality & Fidelity Gate (SSIM / PSNR)...")

        # Determine canvas dimensions matching aspect ratio
        can_w = 1280
        can_h = 720
        if info.video and info.video.width > 0 and info.video.height > 0:
            if info.video.height > info.video.width:  # Vertical video
                can_w = 720
                can_h = 1280

        # Pre-create audit directory so provider evidence (e.g. vmaf.json) lands directly in the bundle
        clean_stem = "".join(c for c in dst_path.stem if c.isalnum() or c in ("-", "_", " "))[:40].strip()
        audit_dir = dst_path.parent / (f"{clean_stem}_audit" if clean_stem else "audit_manifest")
        audit_dir.mkdir(parents=True, exist_ok=True)

        quality_report = evaluate_visual_quality(
            ref_path=src_path,
            trans_path=dst_path,
            policy=q_policy,
            canonical_w=can_w,
            canonical_h=can_h,
            evidence_dir=audit_dir,
        )

        # Generate Ed25519 signed audit manifest
        try:
            generate_ed25519_signed_manifest(quality_report, audit_dir, policy=q_policy)
        except Exception as e:
            if q_policy.enforce_strict:
                if dst_path.exists():
                    dst_path.unlink()
                raise RuntimeError(f"Quality gate audit manifest generation failed in strict mode: {e}")
            else:
                import sys
                print(f"[!] WARNING: Audit manifest generation failed: {e}", file=sys.stderr)

        if not quality_report.passed and q_policy.enforce_strict:
            if dst_path.exists():
                dst_path.unlink()
            errs = "; ".join(quality_report.policy_violations)
            raise RuntimeError(f"Visual quality gate REJECTED output (Strict Policy): {errs}")

    if cancel_check and cancel_check():
        raise RuntimeError("Cancelled.")

    # 6. Verify post-export container & metadata
    if progress_callback:
        progress_callback(96.0, "Pass 5: Running verification inspection on output...")

    report = verify_output(dst_path)
    report.quality_report = quality_report

    if progress_callback:
        progress_callback(100.0, "Processing, quality gate, and verification complete.")

    return report
