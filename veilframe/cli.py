#!/usr/bin/env python3
"""
VeilFrame — Modern Developer CLI.

Provides unified command-line access to:
  1. sanitize   - Multi-pass privacy sanitization & bounded forensic disruption
  2. inspect    - Forensic metadata, container atom, and elementary stream inspection
  3. audit      - Independent read-only 3-tier QualityGate visual fidelity audit
  4. verify     - Cryptographic verification of Ed25519 signed audit manifests
  5. presets    - Explore and inspect built-in transformation profiles
  6. doctor     - Diagnostic health check of local environment & encoders
  7. benchmark  - Run research attribution benchmark detectors
  8. gui        - Launch PySide6 desktop GUI
"""
import sys
import os
import json
import argparse
import subprocess
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple

# Ensure UTF-8 output streams
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from veilframe.cli_ui import (
    Style,
    badge_pass,
    badge_fail,
    badge_warn,
    badge_info,
    badge_secure,
    print_banner,
    print_card,
    print_section_header,
    print_table,
    print_tree,
    ProgressBar,
    prompt_choice,
    prompt_text,
    prompt_confirm,
)


# --------------------------------------------------------------------------- #
# Command: GUI                                                                #
# --------------------------------------------------------------------------- #
def cmd_gui(args):
    """Launch VeilFrame desktop GUI."""
    print(f"{Style.BRIGHT_CYAN}Launching VeilFrame Desktop GUI...{Style.RESET}")
    try:
        from veilframe.app import main as gui_main
        gui_main()
    except ImportError as e:
        print(f"{badge_fail()} Failed to launch GUI: PySide6 not installed or headless display: {e}")
        sys.exit(1)


# --------------------------------------------------------------------------- #
# Command: Sanitize                                                           #
# --------------------------------------------------------------------------- #
def cmd_sanitize(args):
    """Run VeilFrame multi-pass sanitization pipeline."""
    from veilframe.core.pipeline import run_pipeline
    from veilframe.core.analyzer import analyze_video
    from veilframe.presets.manager import PresetManager
    from veilframe.models.settings import ProcessingSettings

    src = Path(args.input)
    if not src.exists():
        print(f"{badge_fail()} Input video file not found: {src}")
        sys.exit(1)

    dst = Path(args.output) if args.output else src.parent / f"{src.stem}_veilframe{src.suffix}"

    pm = PresetManager()
    preset = pm.get_preset(args.preset)
    if not preset:
        print(f"{badge_warn()} Preset '{args.preset}' not found. Falling back to default.")
        settings = ProcessingSettings()
    else:
        settings = pm.to_processing_settings(preset)

    if args.strict:
        settings.quality_gate.enabled = True
        settings.quality_gate.enforce_strict = True

    if getattr(args, "budget", None) == "10":
        p10 = pm.get_preset("10% Bounded Forensic Disruption")
        if p10:
            settings = pm.to_processing_settings(p10)

    if not args.json:
        print_banner(subtitle="Sanitizing Media with Bounded Disruption & Signed Quality Audit")

        # Probe input file details
        try:
            info = analyze_video(src)
            vid_dim = f"{info.video.width}x{info.video.height} @ {info.video.fps:.2f} fps" if info.video else "N/A"
            vid_codec = info.video.codec.upper() if info.video else "N/A"
            dur_str = f"{info.duration:.2f}s" if info.duration else "N/A"
            has_gps = f"YES ({info.metadata.gps})" if (info.metadata and info.metadata.gps) else "NONE"

            print_card(
                "Source Media",
                [
                    ("Input Path", str(src.resolve())),
                    ("Output Path", str(dst.resolve())),
                    ("Dimensions", vid_dim),
                    ("Codec", vid_codec),
                    ("Duration", dur_str),
                    ("GPS Tag", has_gps),
                    ("Preset Profile", args.preset),
                    ("Strict QualityGate", "ENFORCED (Strict Reject on violation)" if args.strict else "ENABLED (Advisory)"),
                ],
            )
        except Exception:
            pass

    prog = ProgressBar(total_steps=100, title="Sanitizing")

    def on_progress(pct: float, msg: str):
        if not args.json:
            prog.update(pct, msg)

    try:
        report = run_pipeline(
            src_path=src,
            dst_path=dst,
            settings=settings,
            progress_callback=on_progress,
        )

        if not args.json:
            prog.finish(success=True, final_msg="Sanitization & Quality Audit Complete")

            # Post-export Summary
            q_rep = report.quality_report
            verdict_badge = badge_pass("PASS") if (q_rep and q_rep.passed) else badge_fail("REJECT")

            tree_nodes = [
                ("Output Video", str(dst.resolve())),
                ("Sanitization", badge_pass("Metadata Stripped & Re-muxed")),
            ]

            if q_rep:
                tree_nodes.extend([
                    ("Quality Verdict", f"{verdict_badge} ({q_rep.three_tier_verdict.overall_verdict})"),
                    ("Mean SSIM", f"{q_rep.ssim.mean:.4f} (Constraint >= {q_rep.policy.ssim_mean_min:.4f})"),
                    ("Mean PSNR", f"{q_rep.psnr.mean:.2f} dB (Constraint >= {q_rep.policy.psnr_mean_min:.1f} dB)"),
                    ("Policy Score", f"{q_rep.policy_score.aggregate_policy_score_pct:.2f}% (Ceiling <= {q_rep.policy_score.policy_ceiling_pct:.1f}%)"),
                    ("Temporalmonotone", badge_pass("No frame drops or PTS jitter")),
                ])
                if q_rep.manifest_path:
                    tree_nodes.append(("Audit Manifest", f"{badge_secure('SIGNED')} {Path(q_rep.manifest_path).resolve()}"))
                if q_rep.public_key_fingerprint:
                    tree_nodes.append(("Signer Fingerprint", f"{Style.DIM}{q_rep.public_key_fingerprint}{Style.RESET}"))

            print_tree("Sanitization Results", tree_nodes)
        else:
            # JSON Mode
            res_dict: Dict[str, Any] = {
                "status": "success",
                "input": str(src.resolve()),
                "output": str(dst.resolve()),
                "passed": report.all_passed,
            }
            if report.quality_report:
                qr = report.quality_report
                res_dict["quality_gate"] = {
                    "verdict": qr.three_tier_verdict.overall_verdict,
                    "passed": qr.passed,
                    "policy_score_pct": qr.policy_score.aggregate_policy_score_pct,
                    "ssim_mean": qr.ssim.mean,
                    "psnr_mean_db": qr.psnr.mean,
                    "manifest_path": qr.manifest_path,
                    "public_key_fingerprint": qr.public_key_fingerprint,
                    "violations": qr.policy_violations,
                }
            print(json.dumps(res_dict, indent=2))

    except Exception as e:
        if not args.json:
            prog.finish(success=False, final_msg="Failed")
            print(f"\n{badge_fail('ERROR')} Sanitization failed: {Style.BRIGHT_RED}{e}{Style.RESET}")
        else:
            print(json.dumps({"status": "error", "error": str(e)}, indent=2))
        sys.exit(1)


# --------------------------------------------------------------------------- #
# Command: Inspect                                                            #
# --------------------------------------------------------------------------- #
def cmd_inspect(args):
    """Deep forensic and container inspection of a media file."""
    from veilframe.core.analyzer import analyze_video

    path = Path(args.video)
    if not path.exists():
        print(f"{badge_fail()} File not found: {path}")
        sys.exit(1)

    info = analyze_video(path)

    if args.json:
        out = {
            "path": str(path.resolve()),
            "format": info.format_name,
            "duration": info.duration,
            "size_bytes": info.size_bytes,
            "bitrate": info.overall_bitrate,
            "video": {
                "codec": info.video.codec if info.video else None,
                "width": info.video.width if info.video else None,
                "height": info.video.height if info.video else None,
                "fps": info.video.fps if info.video else None,
                "pix_fmt": info.video.pixel_format if info.video else None,
                "color_space": info.video.color_space if info.video else None,
            } if info.video else None,
            "audio": {
                "codec": info.audio.codec if info.audio else None,
                "channels": info.audio.channels if info.audio else None,
                "sample_rate": info.audio.sample_rate if info.audio else None,
            } if info.audio else None,
            "metadata_tags": info.metadata.raw_tags if info.metadata else {},
            "gps": info.metadata.gps if (info.metadata and info.metadata.gps) else None,
        }
        print(json.dumps(out, indent=2))
        return

    print_banner(subtitle="Forensic Container & Elementary Stream Inspector")

    meta_items: List[Tuple[str, str]] = [
        ("File Path", str(path.resolve())),
        ("Container Format", info.format_name),
        ("Duration", f"{info.duration:.3f} sec" if info.duration else "N/A"),
        ("File Size", f"{info.size_bytes / (1024*1024):.2f} MB ({info.size_bytes:,} bytes)"),
        ("Overall Bitrate", f"{info.overall_bitrate // 1000:,} kbps" if info.overall_bitrate else "N/A"),
    ]

    if info.video:
        v = info.video
        meta_items.extend([
            ("Video Codec", f"{v.codec.upper()} ({v.codec_long_name})"),
            ("Resolution", f"{v.width} x {v.height} (DAR {v.aspect_ratio})"),
            ("Frame Rate", f"{v.fps:.3f} fps"),
            ("Pixel Format", f"{v.pixel_format} (Color Space: {v.color_space or 'default'})"),
        ])
    else:
        meta_items.append(("Video Stream", badge_warn("No video stream found")))

    if info.audio:
        a = info.audio
        meta_items.extend([
            ("Audio Codec", f"{a.codec.upper()} ({a.channels} ch, {a.sample_rate} Hz)"),
        ])
    else:
        meta_items.append(("Audio Stream", "None"))

    # Metadata & Tracking Tags
    raw_tags = info.metadata.raw_tags if info.metadata else {}
    if raw_tags:
        meta_items.append(("Tracking Tags", badge_warn(f"{len(raw_tags)} tags present in container")))
    else:
        meta_items.append(("Tracking Tags", badge_pass("Clean (0 metadata tags)")))

    if info.metadata and info.metadata.gps:
        meta_items.append(("GPS Coordinates", badge_fail(str(info.metadata.gps))))
    else:
        meta_items.append(("GPS Coordinates", badge_pass("None detected")))

    print_card("Media Characteristics", meta_items)

    if raw_tags:
        print_section_header("Extracted Metadata Tags", icon="🏷️")
        tag_rows = [[k, str(v)] for k, v in list(raw_tags.items())[:20]]
        print_table(["Tag Key", "Value"], tag_rows)


# --------------------------------------------------------------------------- #
# Command: Audit                                                              #
# --------------------------------------------------------------------------- #
def cmd_audit(args):
    """Run independent read-only 3-tier QualityGate visual fidelity audit."""
    from veilframe.core.validator import evaluate_visual_quality, generate_ed25519_signed_manifest
    from veilframe.models.settings import VisualBudgetPolicy
    from veilframe.presets.manager import PresetManager

    ref = Path(args.reference)
    trans = Path(args.transformed)

    if not ref.exists():
        print(f"{badge_fail()} Reference video not found: {ref}")
        sys.exit(1)
    if not trans.exists():
        print(f"{badge_fail()} Transformed video not found: {trans}")
        sys.exit(1)

    policy = VisualBudgetPolicy()
    if getattr(args, "preset", None):
        pm = PresetManager()
        p = pm.get_preset(args.preset)
        if p:
            sett = pm.to_processing_settings(p)
            policy = sett.quality_gate

    report = evaluate_visual_quality(
        ref_path=ref,
        trans_path=trans,
        policy=policy,
    )

    if getattr(args, "export_manifest", None):
        out_manifest_dir = Path(args.export_manifest)
        generate_ed25519_signed_manifest(report, out_manifest_dir, policy=policy)

    if args.json:
        data = {
            "verdict": report.three_tier_verdict.overall_verdict,
            "passed": report.passed,
            "tier1_policy_passed": report.three_tier_verdict.tier1_policy_passed,
            "tier2_fidelity_passed": report.three_tier_verdict.tier2_fidelity_passed,
            "tier3_temporal_passed": report.three_tier_verdict.tier3_temporal_passed,
            "policy_score_pct": report.policy_score.aggregate_policy_score_pct,
            "ssim": {
                "mean": report.ssim.mean,
                "p5": report.ssim.p5,
                "worst": report.ssim.min_val,
            },
            "psnr": {
                "mean": report.psnr.mean,
                "worst": report.psnr.min_val,
            },
            "temporal": {
                "missing_frames": report.temporal_metrics.missing_frames,
                "duplicate_frames": report.temporal_metrics.duplicate_frames,
                "reordered_frames": report.temporal_metrics.reordered_frames,
                "cadence_deviation_pct": report.temporal_metrics.cadence_deviation_pct,
            },
            "violations": report.policy_violations,
            "public_key_fingerprint": report.public_key_fingerprint,
        }
        print(json.dumps(data, indent=2))
    else:
        print_banner(subtitle="Independent 3-Tier QualityGate Fidelity Audit")
        
        v_badge = badge_pass("PASS") if report.passed else badge_fail("REJECT")
        print_card(
            f"Audit Verdict: {report.three_tier_verdict.overall_verdict}",
            [
                ("Reference Video", str(ref.resolve())),
                ("Transformed Video", str(trans.resolve())),
                ("Overall Verdict", v_badge),
                ("Tier 1 (Policy Budget)", badge_pass("PASS") if report.three_tier_verdict.tier1_policy_passed else badge_fail("FAIL")),
                ("Tier 2 (Rendered Fidelity)", badge_pass("PASS") if report.three_tier_verdict.tier2_fidelity_passed else badge_fail("FAIL")),
                ("Tier 3 (Temporal Cadence)", badge_pass("PASS") if report.three_tier_verdict.tier3_temporal_passed else badge_fail("FAIL")),
            ],
        )

        print_section_header("Transformation Policy Budget (Ceiling <= 5.0%)", icon="📊")
        pol_rows = [
            ["Spatial Distortion (ΔW, ΔH)", f"{report.native_metrics.spatial_delta_pct:.2f}%", f"<= {policy.spatial_ceiling_pct:.1f}%", badge_pass() if report.native_metrics.spatial_delta_pct <= policy.spatial_ceiling_pct else badge_fail()],
            ["Temporal Speed / FPS Delta", f"{report.native_metrics.temporal_delta_pct:.2f}%", f"<= {policy.temporal_ceiling_pct:.1f}%", badge_pass() if report.native_metrics.temporal_delta_pct <= policy.temporal_ceiling_pct else badge_fail()],
            ["Luminance Mean Shift", f"{report.energy_metrics.mean_luma_delta * 100:.2f}%", f"<= {policy.luma_ceiling_pct:.1f}%", badge_pass() if report.energy_metrics.mean_luma_delta * 100 <= policy.luma_ceiling_pct else badge_fail()],
            ["Chroma Composite Shift", f"{report.energy_metrics.chroma_delta_composite * 100:.2f}%", f"<= {policy.chroma_ceiling_pct:.1f}%", badge_pass() if report.energy_metrics.chroma_delta_composite * 100 <= policy.chroma_ceiling_pct else badge_fail()],
            ["Aggregate Policy Score", f"{report.policy_score.aggregate_policy_score_pct:.2f}%", f"<= {report.policy_score.policy_ceiling_pct:.1f}%", badge_pass() if report.policy_score.passed else badge_fail()],
        ]
        print_table(["Metric Dimension", "Measured", "Ceiling", "Status"], pol_rows)

        print_section_header("Rendered Fidelity (SSIM & PSNR Distributions)", icon="👁️")
        fid_rows = [
            ["Mean SSIM", f"{report.ssim.mean:.4f}", f">= {policy.ssim_mean_min:.4f}", badge_pass() if report.ssim.mean >= policy.ssim_mean_min else badge_fail()],
            ["5th Percentile SSIM (Tail)", f"{report.ssim.p5:.4f}", f">= {policy.ssim_p5_min:.4f}", badge_pass() if report.ssim.p5 >= policy.ssim_p5_min else badge_fail()],
            ["Worst-Frame SSIM", f"{report.ssim.min_val:.4f}", f">= {policy.ssim_worst_min:.4f}", badge_pass() if report.ssim.min_val >= policy.ssim_worst_min else badge_fail()],
            ["Mean PSNR", f"{report.psnr.mean:.2f} dB", f">= {policy.psnr_mean_min_db:.1f} dB", badge_pass() if report.psnr.mean >= policy.psnr_mean_min_db else badge_fail()],
            ["Worst-Frame PSNR", f"{report.psnr.min_val:.2f} dB", f">= {policy.psnr_worst_min_db:.1f} dB", badge_pass() if report.psnr.min_val >= policy.psnr_worst_min_db else badge_fail()],
            ["Luma Histogram TV (D_TV)", f"{report.energy_metrics.luma_hist_divergence_tv:.4f}", "N/A", badge_info("DIVERGENCE")],
        ]
        print_table(["Fidelity Metric", "Measured Value", "Constraint", "Status"], fid_rows)

        print_section_header("Temporal Stream Integrity (PTS Monotonicity)", icon="⏱️")
        temp_rows = [
            ["Missing Frames", str(report.temporal_metrics.missing_frames), "0", badge_pass() if report.temporal_metrics.missing_frames == 0 else badge_fail()],
            ["Duplicate Frames", str(report.temporal_metrics.duplicate_frames), "0", badge_pass() if report.temporal_metrics.duplicate_frames == 0 else badge_fail()],
            ["Reordered Packets", str(report.temporal_metrics.reordered_frames), "0", badge_pass() if report.temporal_metrics.reordered_frames == 0 else badge_fail()],
            ["Max Timestamp Drift", f"{report.temporal_metrics.timestamp_drift_max_sec:.4f}s", "<= 0.100s", badge_pass() if report.temporal_metrics.timestamp_drift_max_sec <= 0.1 else badge_fail()],
            ["Cadence Deviation", f"{report.temporal_metrics.cadence_deviation_pct:.2f}%", "<= 1.0%", badge_pass() if report.temporal_metrics.cadence_deviation_pct <= 1.0 else badge_fail()],
        ]
        print_table(["Temporal Check", "Observed", "Threshold", "Status"], temp_rows)

        if report.policy_violations:
            print(f"\n{badge_fail('VIOLATIONS DETECTED')}")
            for v in report.policy_violations:
                print(f"  {Style.BRIGHT_RED}• {v}{Style.RESET}")
            print()

    if not report.passed:
        sys.exit(2)


# --------------------------------------------------------------------------- #
# Command: Verify Manifest                                                    #
# --------------------------------------------------------------------------- #
def cmd_verify(args):
    """Cryptographically verify an Ed25519 signed audit manifest."""
    from examples.verify_manifest import main as vm_main

    manifest_p = Path(args.manifest)
    if not manifest_p.exists():
        print(f"{badge_fail()} Manifest file not found: {manifest_p}")
        sys.exit(1)

    sig_p = Path(args.signature) if args.signature else manifest_p.parent / "manifest.sig"
    pub_p = Path(args.public_key) if args.public_key else manifest_p.parent / "public_key.pem"

    if not sig_p.exists():
        print(f"{badge_fail()} Signature file not found: {sig_p}")
        sys.exit(1)
    if not pub_p.exists():
        print(f"{badge_fail()} Public key file not found: {pub_p}")
        sys.exit(1)

    sys_argv = [
        "verify_manifest",
        str(manifest_p),
        str(sig_p),
        str(pub_p),
    ]
    if args.expected_fingerprint:
        sys_argv.extend(["--expected-fingerprint", args.expected_fingerprint])
    if args.video_file:
        sys_argv.extend(["--video-file", str(args.video_file)])

    old_argv = sys.argv
    sys.argv = sys_argv
    try:
        vm_main()
    finally:
        sys.argv = old_argv


# --------------------------------------------------------------------------- #
# Command: Presets                                                            #
# --------------------------------------------------------------------------- #
def cmd_presets(args):
    """List or inspect built-in transformation presets."""
    from veilframe.presets.manager import PresetManager

    pm = PresetManager()
    preset_names = pm.get_preset_names()

    presets_data = []
    for name in preset_names:
        raw_dict = pm.get_preset(name)
        if raw_dict:
            sett = pm.to_processing_settings(raw_dict)
            q = sett.quality_gate
            presets_data.append({
                "name": name,
                "description": pm.get_preset_description(name),
                "budget_pct": q.policy_budget * 100.0,
                "spatial_budget_pct": q.spatial_ceiling_pct,
                "temporal_budget_pct": q.temporal_ceiling_pct,
                "ssim_min": q.ssim_mean_min,
                "psnr_min": q.psnr_mean_min_db,
            })

    if args.json:
        print(json.dumps(presets_data, indent=2))
        return

    print_banner(subtitle="Built-in Transformation Presets & Budget Ceilings")

    headers = ["Preset Name", "Budget", "Spatial", "Temporal", "SSIM (Min)", "PSNR (Min)"]
    rows = []
    for p in presets_data:
        b_pct = f"{p['budget_pct']:.1f}%"
        sp_pct = f"{p['spatial_budget_pct']:.1f}%"
        tm_pct = f"{p['temporal_budget_pct']:.1f}%"
        ssim_min = f"{p['ssim_min']:.2f}"
        psnr_min = f"{p['psnr_min']:.1f} dB"
        rows.append([p['name'], b_pct, sp_pct, tm_pct, ssim_min, psnr_min])

    print_table(headers, rows)


# --------------------------------------------------------------------------- #
# Command: Doctor (Diagnostics)                                               #
# --------------------------------------------------------------------------- #
def cmd_doctor(args):
    """Run full diagnostic environment health check."""
    import platform
    import numpy as np
    from veilframe.core.resources import get_ffmpeg_path, get_ffprobe_path
    from veilframe.quality.adapters.vmaf import LibvmafFFmpegProvider

    ffmpeg_p = get_ffmpeg_path()
    ffprobe_p = get_ffprobe_path()
    ffmpeg_ok = ffmpeg_p.exists()
    ffprobe_ok = ffprobe_p.exists()

    # Probe FFmpeg version
    ffmpeg_ver = "Unknown"
    if ffmpeg_ok:
        try:
            res = subprocess.run([str(ffmpeg_p), "-version"], capture_output=True, text=True)
            first_line = res.stdout.splitlines()[0] if res.stdout else ""
            ffmpeg_ver = first_line.split("version")[1].split()[0] if "version" in first_line else "Available"
        except Exception:
            ffmpeg_ver = "Present"

    # Libvmaf check
    vmaf_provider = LibvmafFFmpegProvider()
    vmaf_ok = vmaf_provider.is_available()

    # PySide6 GUI check
    pyside_ok = True
    pyside_ver = "Unknown"
    try:
        import PySide6
        pyside_ver = PySide6.__version__
    except ImportError:
        pyside_ok = False

    # Cryptography check
    crypto_ok = True
    crypto_ver = "Unknown"
    try:
        import cryptography
        crypto_ver = cryptography.__version__
    except ImportError:
        crypto_ok = False

    # Hardware acceleration check
    hw_encoders = []
    if ffmpeg_ok:
        try:
            res = subprocess.run([str(ffmpeg_p), "-encoders"], capture_output=True, text=True)
            txt = res.stdout or ""
            if "nvenc" in txt:
                hw_encoders.append("NVIDIA NVENC")
            if "qsv" in txt:
                hw_encoders.append("Intel QSV")
            if "videotoolbox" in txt:
                hw_encoders.append("Apple VideoToolbox")
            if "amf" in txt:
                hw_encoders.append("AMD AMF")
        except Exception:
            pass

    diag_data = {
        "os": platform.platform(),
        "python": sys.version.split()[0],
        "ffmpeg": {"available": ffmpeg_ok, "path": str(ffmpeg_p), "version": ffmpeg_ver},
        "ffprobe": {"available": ffprobe_ok, "path": str(ffprobe_p)},
        "libvmaf": {"available": vmaf_ok},
        "pyside6": {"available": pyside_ok, "version": pyside_ver},
        "cryptography": {"available": crypto_ok, "version": crypto_ver},
        "numpy": {"version": np.__version__},
        "hardware_encoders": hw_encoders,
    }

    if args.json:
        print(json.dumps(diag_data, indent=2))
        return

    print_banner(subtitle="System Environment Diagnostic & Health Check")

    diag_rows = [
        ["Operating System", platform.platform(), badge_pass("OK")],
        ["Python Runtime", sys.version.split()[0], badge_pass(">= 3.10")],
        ["FFmpeg Binary", f"{ffmpeg_ver} ({ffmpeg_p.name})", badge_pass("FOUND") if ffmpeg_ok else badge_fail("MISSING")],
        ["FFprobe Binary", str(ffprobe_p.name), badge_pass("FOUND") if ffprobe_ok else badge_fail("MISSING")],
        ["libvmaf Filter", "FFmpeg libvmaf filter", badge_pass("ENABLED") if vmaf_ok else badge_warn("UNAVAILABLE (Skipped in local)")],
        ["Cryptography (Ed25519)", f"v{crypto_ver}", badge_pass("ACCELERATED") if crypto_ok else badge_fail("MISSING")],
        ["NumPy Engine", f"v{np.__version__}", badge_pass("ACCELERATED")],
        ["PySide6 (Qt GUI)", f"v{pyside_ver}", badge_pass("READY") if pyside_ok else badge_warn("HEADLESS ONLY")],
        ["Hardware Codecs", ", ".join(hw_encoders) if hw_encoders else "Software (CPU fallback)", badge_info("DETECTED")],
    ]

    print_table(["Component / Subsystem", "Details", "Status"], diag_rows)

    if ffmpeg_ok and crypto_ok:
        print(f"{badge_pass('HEALTHY')} VeilFrame is fully operational and ready to process media.\n")
    else:
        print(f"{badge_fail('ATTENTION')} Critical prerequisites are missing. Review table above.\n")


# --------------------------------------------------------------------------- #
# Command: Benchmark                                                          #
# --------------------------------------------------------------------------- #
def cmd_benchmark(args):
    """Run research attribution benchmark detectors."""
    from tools.run_attribution_benchmarks import main as bench_main

    sys_argv = ["run_attribution_benchmarks"]
    if args.ref:
        sys_argv.extend(["--ref", str(args.ref)])
    if args.trans:
        sys_argv.extend(["--trans", str(args.trans)])
    if args.synthetic:
        sys_argv.append("--synthetic")
    if args.output_json:
        sys_argv.extend(["--output-json", str(args.output_json)])

    old_argv = sys.argv
    sys.argv = sys_argv
    try:
        bench_main()
    finally:
        sys.argv = old_argv


# --------------------------------------------------------------------------- #
# Interactive TUI Wizard                                                      #
# --------------------------------------------------------------------------- #
def run_interactive_wizard():
    """Interactive developer console dashboard."""
    print_banner(subtitle="Interactive Media Privacy & Quality Console")

    choices = [
        "Sanitize a Video (Strip metadata, apply bounded disruption, sign audit)",
        "Inspect Video Metadata & Streams (Forensic container & stream probe)",
        "Audit Fidelity (Run independent 3-tier QualityGate on reference vs transformed)",
        "Verify Signed Audit Manifest (Ed25519 & SHA-256 cryptographic proof)",
        "Explore Presets & Budget Ceilings",
        "Run System Health Check (Doctor)",
        "Launch Desktop GUI (PySide6)",
        "Exit",
    ]

    idx = prompt_choice("What would you like to do?", choices, default_idx=0)

    if idx == 0:
        # Sanitize
        in_path = prompt_text("Enter input video file path")
        if not in_path or not Path(in_path).exists():
            print(f"{badge_fail()} File does not exist: {in_path}")
            return
        preset_idx = prompt_choice(
            "Select transformation preset profile",
            ["5% Bounded Forensic Disruption (Standard)", "10% Bounded Forensic Disruption (Deep)", "Privacy Clean (Lossless container scrub)"],
            default_idx=0,
        )
        p_name = "5% Bounded Forensic Disruption" if preset_idx == 0 else ("10% Bounded Forensic Disruption" if preset_idx == 1 else "Privacy Clean")
        strict = prompt_confirm("Enforce Strict QualityGate (Reject & delete output if fidelity fails)?", default=True)

        class Args:
            input = in_path
            output = None
            preset = p_name
            strict = strict
            json = False
            budget = None

        cmd_sanitize(Args())

    elif idx == 1:
        # Inspect
        in_path = prompt_text("Enter video file path to inspect")
        if not in_path or not Path(in_path).exists():
            print(f"{badge_fail()} File does not exist: {in_path}")
            return

        class Args:
            video = in_path
            json = False

        cmd_inspect(Args())

    elif idx == 2:
        # Audit
        ref_path = prompt_text("Enter ground-truth reference video path")
        trans_path = prompt_text("Enter sanitized/transformed video path")
        if not Path(ref_path).exists() or not Path(trans_path).exists():
            print(f"{badge_fail()} One or both files not found.")
            return

        class Args:
            reference = ref_path
            transformed = trans_path
            preset = None
            export_manifest = None
            json = False

        cmd_audit(Args())

    elif idx == 3:
        # Verify
        m_path = prompt_text("Enter manifest.json file path")
        if not Path(m_path).exists():
            print(f"{badge_fail()} Manifest not found.")
            return

        class Args:
            manifest = m_path
            signature = None
            public_key = None
            expected_fingerprint = None
            video_file = None

        cmd_verify(Args())

    elif idx == 4:
        class Args:
            json = False
        cmd_presets(Args())

    elif idx == 5:
        class Args:
            json = False
        cmd_doctor(Args())

    elif idx == 6:
        class Args:
            pass
        cmd_gui(Args())

    elif idx == 7:
        print(f"\n{Style.BRIGHT_CYAN}Goodbye!{Style.RESET}")
        sys.exit(0)


# --------------------------------------------------------------------------- #
# Main Entry Point                                                            #
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(
        prog="veilframe",
        description="◈ VeilFrame — Media sanitization, bounded forensic disruption, and cryptographically signed audit manifests.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  veilframe sanitize input.mp4 -o output.mp4 --preset "5% Bounded Forensic Disruption" --strict
  veilframe inspect video.mp4
  veilframe audit original.mp4 sanitized.mp4
  veilframe verify manifest.json
  veilframe doctor
  veilframe gui
        """,
    )
    parser.add_argument(
        "--version",
        action="version",
        version="VeilFrame 1.1.0 (Quality Gate v4.0, Policy 5pct-v1.0)",
    )

    subparsers = parser.add_subparsers(dest="command", help="Available subcommands")

    # 1. Sanitize
    p_san = subparsers.add_parser("sanitize", help="Sanitize a video file and generate signed audit manifest")
    p_san.add_argument("input", help="Path to input video file")
    p_san.add_argument("-o", "--output", help="Path to output video file")
    p_san.add_argument(
        "-p", "--preset",
        default="5% Bounded Forensic Disruption",
        help="Preset profile (default: '5% Bounded Forensic Disruption')",
    )
    p_san.add_argument(
        "--strict",
        action="store_true",
        help="Enforce strict quality gate (delete output if fidelity fails)",
    )
    p_san.add_argument(
        "--budget",
        choices=["5", "10"],
        help="Quick-select 5%% or 10%% modification budget",
    )
    p_san.add_argument("--json", action="store_true", help="Output results in JSON format")
    p_san.set_defaults(func=cmd_sanitize)

    # 2. Inspect
    p_ins = subparsers.add_parser("inspect", help="Inspect container atoms, metadata, and stream properties")
    p_ins.add_argument("video", help="Path to video file to inspect")
    p_ins.add_argument("--json", action="store_true", help="Output inspection data in JSON format")
    p_ins.set_defaults(func=cmd_inspect)

    # 3. Audit
    p_aud = subparsers.add_parser("audit", help="Run independent 3-tier QualityGate visual fidelity audit")
    p_aud.add_argument("reference", help="Ground-truth reference video")
    p_aud.add_argument("transformed", help="Sanitized/transformed video")
    p_aud.add_argument("-p", "--preset", help="Optional preset name to load policy thresholds from")
    p_aud.add_argument("--export-manifest", help="Optional output directory to write signed manifest bundle")
    p_aud.add_argument("--json", action="store_true", help="Output audit results in JSON format")
    p_aud.set_defaults(func=cmd_audit)

    # 4. Verify Manifest
    p_ver = subparsers.add_parser("verify", help="Cryptographically verify an Ed25519 signed audit manifest")
    p_ver.add_argument("manifest", help="Path to manifest.json")
    p_ver.add_argument("-s", "--signature", help="Path to manifest.sig (default: alongside manifest)")
    p_ver.add_argument("-k", "--public-key", help="Path to public_key.pem (default: alongside manifest)")
    p_ver.add_argument("--expected-fingerprint", help="Optional pinned SHA-256 public key fingerprint")
    p_ver.add_argument("--video-file", help="Optional path to output video file to match hash")
    p_ver.set_defaults(func=cmd_verify)

    # 5. Presets
    p_pre = subparsers.add_parser("presets", help="List and inspect built-in transformation presets")
    p_pre.add_argument("--json", action="store_true", help="Output presets in JSON format")
    p_pre.set_defaults(func=cmd_presets)

    # 6. Doctor
    p_doc = subparsers.add_parser("doctor", help="Run diagnostic health check of local environment")
    p_doc.add_argument("--json", action="store_true", help="Output diagnostics in JSON format")
    p_doc.set_defaults(func=cmd_doctor)

    # 7. Benchmark
    p_bnc = subparsers.add_parser("benchmark", help="Run research attribution benchmark detectors")
    p_bnc.add_argument("--ref", help="Reference video")
    p_bnc.add_argument("--trans", help="Transformed video")
    p_bnc.add_argument("--synthetic", action="store_true", help="Run on synthetic corpus")
    p_bnc.add_argument("--output-json", help="Path to export benchmark results JSON")
    p_bnc.set_defaults(func=cmd_benchmark)

    # 8. GUI
    p_gui = subparsers.add_parser("gui", help="Launch VeilFrame desktop GUI")
    p_gui.set_defaults(func=cmd_gui)

    # 9. Interactive
    p_itr = subparsers.add_parser("interactive", help="Launch interactive terminal console")
    p_itr.set_defaults(func=lambda args: run_interactive_wizard())

    args = parser.parse_args()

    if hasattr(args, "func"):
        args.func(args)
    else:
        # If running interactively in a TTY with no arguments, launch interactive wizard;
        # otherwise launch GUI
        if sys.stdin.isatty() and sys.stdout.isatty():
            run_interactive_wizard()
        else:
            cmd_gui(args)


if __name__ == "__main__":
    main()
