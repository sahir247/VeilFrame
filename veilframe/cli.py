#!/usr/bin/env python3
"""
VeilFrame — Unified Command Line Interface.

Provides CLI access to:
  1. VeilFrame Sanitizer (multi-pass media sanitization)
  2. VeilFrame Quality Gate (independent read-only fidelity audit)
  3. VeilFrame Audit Engine & Manifest Verifier (cryptographic provenance checking)
  4. VeilFrame GUI (desktop application)
"""
import sys
import os
import argparse
from pathlib import Path

# Safe encoding
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def cmd_gui(args):
    """Launch VeilFrame PySide6 desktop GUI."""
    from veilframe.app import main as gui_main
    gui_main()


def cmd_sanitize(args):
    """Run VeilFrame multi-pass sanitization pipeline."""
    from veilframe.core.pipeline import run_pipeline
    from veilframe.presets.manager import PresetManager
    from veilframe.models.settings import ProcessingSettings

    src = Path(args.input)
    if not src.exists():
        print(f"Error: Input video not found: {src}")
        sys.exit(1)

    dst = Path(args.output) if args.output else src.parent / f"{src.stem}_veilframe{src.suffix}"

    pm = PresetManager()
    preset = pm.get_preset(args.preset)
    if not preset:
        print(f"Warning: Preset '{args.preset}' not found. Falling back to default.")
        settings = ProcessingSettings()
    else:
        settings = pm.to_processing_settings(preset)

    if args.strict:
        settings.quality_gate.enabled = True
        settings.quality_gate.enforce_strict = True

    print(f"[*] Sanitizing: {src} -> {dst}")
    print(f"[*] Applying Preset: {args.preset} (Strict Quality Gate: {'ON' if args.strict else 'OFF'})")

    try:
        def on_prog(pct, msg):
            print(f"  [{pct:3d}%] {msg}")

        report = run_pipeline(
            src_path=src,
            dst_path=dst,
            settings=settings,
            progress_callback=on_prog,
        )
        print("\n[+] Sanitization Complete!")
        print(f"    Output Video: {dst}")
        if report.quality_report:
            print(f"    Quality Verdict: {report.quality_report.three_tier_verdict.overall_verdict}")
            print(f"    Public Key Fingerprint: {report.quality_report.public_key_fingerprint}")
    except Exception as e:
        print(f"\n[-] Sanitization failed: {e}")
        sys.exit(1)


def cmd_audit(args):
    """Run independent read-only visual fidelity audit comparing reference vs transformed."""
    from veilframe.core.validator import evaluate_visual_quality
    from veilframe.core.verifier import format_terminal_quality_report
    from veilframe.models.settings import VisualBudgetPolicy

    ref = Path(args.reference)
    trans = Path(args.transformed)

    if not ref.exists():
        print(f"Error: Reference video not found: {ref}")
        sys.exit(1)
    if not trans.exists():
        print(f"Error: Transformed video not found: {trans}")
        sys.exit(1)

    print(f"[*] Running VeilFrame Quality Gate Audit...")
    print(f"    Reference:   {ref}")
    print(f"    Transformed: {trans}\n")

    report = evaluate_visual_quality(
        ref_path=ref,
        trans_path=trans,
        policy=VisualBudgetPolicy(),
    )

    print(format_terminal_quality_report(report))
    if report.three_tier_verdict.overall_verdict != "PASS":
        sys.exit(2)


def cmd_verify_manifest(args):
    """Run independent Ed25519 cryptographic audit manifest verification."""
    from examples.verify_manifest import main as vm_main
    # Pass arguments through
    sys.argv = [
        "verify_manifest",
        str(args.manifest),
        str(args.signature),
        str(args.public_key),
    ]
    if args.expected_fingerprint:
        sys.argv.extend(["--expected-fingerprint", args.expected_fingerprint])
    if args.video_file:
        sys.argv.extend(["--video-file", str(args.video_file)])
    vm_main()


def main():
    parser = argparse.ArgumentParser(
        prog="veilframe",
        description="VeilFrame — Privacy-focused media sanitization with independent visual-fidelity verification and cryptographically signed audit manifests.",
    )
    parser.add_argument(
        "--version",
        action="version",
        version="VeilFrame 1.0.0 (Quality Gate v2.1, Policy 5pct-v1.0)",
    )

    subparsers = parser.add_subparsers(dest="command", help="VeilFrame commands")

    # GUI Command
    p_gui = subparsers.add_parser("gui", help="Launch VeilFrame desktop GUI")
    p_gui.set_defaults(func=cmd_gui)

    # Sanitize Command
    p_san = subparsers.add_parser("sanitize", help="Sanitize a video file")
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
        help="Enforce strict quality gate (delete and reject if constraints fail)",
    )
    p_san.set_defaults(func=cmd_sanitize)

    # Audit Command
    p_aud = subparsers.add_parser("audit", help="Run independent read-only quality gate audit")
    p_aud.add_argument("reference", help="Ground-truth reference video")
    p_aud.add_argument("transformed", help="Sanitized/transformed video")
    p_aud.set_defaults(func=cmd_audit)

    # Verify Manifest Command
    p_vm = subparsers.add_parser("verify-manifest", help="Verify Ed25519 signed audit manifest")
    p_vm.add_argument("manifest", help="Path to manifest.json")
    p_vm.add_argument("signature", help="Path to manifest.sig")
    p_vm.add_argument("public_key", help="Path to public_key.pem")
    p_vm.add_argument("--expected-fingerprint", help="Optional pinned SHA-256 public key fingerprint")
    p_vm.add_argument("--video-file", help="Optional path to output video file to match hash")
    p_vm.set_defaults(func=cmd_verify_manifest)

    args = parser.parse_args()
    if hasattr(args, "func"):
        args.func(args)
    else:
        # Default to launching GUI if no subcommand given
        cmd_gui(args)


if __name__ == "__main__":
    main()
