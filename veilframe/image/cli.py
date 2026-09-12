"""
veilframe.image.cli — Command-line interface for the VeilFrame Image Privacy Compiler.

Provides subcommands:
  veilframe image sanitize <input> -o <output>
  veilframe image verify <image> [--manifest <manifest>]
  veilframe image inspect <image>
  veilframe image doctor
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Optional, List

from .models.status import CheckStatus, DetectorClass, ArtifactTrustStatus
from .models.policy import (
    ImagePrivacyPolicy,
    create_default_policy,
    create_default_rule_set,
)
from .pipeline import ImagePrivacyPipeline, ImageSanitizationResult
from .runtime.capabilities import CapabilityRegistry
from .sanitizers.container import ContainerSanitizer
from .sanitizers.representation import RepresentationSanitizer
from .detectors.face import PrimaryFaceDetector
from .detectors.plate import PrimaryPlateDetector
from .detectors.text import MSERTextDetector
from .detectors.code import PrimaryQRCodeDetector

# ANSI color constants
CLR_GREEN = "\033[92m"
CLR_RED = "\033[91m"
CLR_YELLOW = "\033[93m"
CLR_CYAN = "\033[1;36m"
CLR_BOLD = "\033[1m"
CLR_RESET = "\033[0m"


def _style_badge(status: CheckStatus) -> str:
    if status == CheckStatus.PASS:
        return f"{CLR_GREEN}[PASS]{CLR_RESET}"
    elif status == CheckStatus.FAIL:
        return f"{CLR_RED}[FAIL]{CLR_RESET}"
    return f"{CLR_YELLOW}[UNKNOWN]{CLR_RESET}"


def cmd_image_sanitize(args) -> int:
    """Execute image privacy compilation."""
    input_path = Path(args.input).resolve()
    if not input_path.exists():
        if args.json:
            print(json.dumps({"status": "FAIL", "error": f"Input file not found: {input_path}"}))
        else:
            print(f"{CLR_RED}[ERROR]{CLR_RESET} Input file not found: {input_path}", file=sys.stderr)
        return 1

    if args.output:
        output_path = Path(args.output).resolve()
    else:
        ext = input_path.suffix or ".jpg"
        output_path = input_path.parent / f"{input_path.stem}_sanitized{ext}"

    policy = create_default_policy()

    # Parse detector classes
    if args.detectors:
        active_classes: List[DetectorClass] = []
        det_map = {
            "face": DetectorClass.FACE,
            "plate": DetectorClass.LICENSE_PLATE,
            "text": DetectorClass.TEXT,
            "code": DetectorClass.QR_CODE,
            "qr": DetectorClass.QR_CODE,
            "barcode": DetectorClass.BARCODE,
        }
        for d in args.detectors.split(","):
            d_clean = d.strip().lower()
            if d_clean in det_map:
                active_classes.append(det_map[d_clean])
        if active_classes:
            policy.active_detector_classes = active_classes

    if args.margin is not None:
        policy.expansion_margin_px = int(args.margin)

    pipeline = ImagePrivacyPipeline(
        policy=policy,
        signing_key_pem=args.signing_key if hasattr(args, "signing_key") else None,
        key_id=args.key_id if hasattr(args, "key_id") else None,
    )

    try:
        result = pipeline.run(
            input_source=input_path,
            output_path=output_path,
            audit_dir=Path(args.audit_dir).resolve() if getattr(args, "audit_dir", None) else None,
            target_format=getattr(args, "format", None),
        )

        if args.json:
            out_data = result.to_dict()
            out_data["output_file"] = str(output_path)
            out_data["manifest_file"] = str(result.publication_result.manifest_path) if result.publication_result else None
            print(json.dumps(out_data, indent=2))
            return 0 if result.is_success else 2

        if not args.quiet:
            print(f"\n{CLR_CYAN}VeilFrame Image Privacy Compiler v2.0.0{CLR_RESET}")
            print(f"Source:     {input_path}")
            print(f"Output:     {output_path}")
            print(f"Redactions: {result.nodes_redacted} regions")
            print(f"Status:     {_style_badge(result.status)}")
            print(f"\n{CLR_BOLD}Contract Verifications:{CLR_RESET}")
            for c_name, c_val in result.verdict.contract_results.items():
                print(f"  • {c_name.capitalize():<15} {c_val}")

            if result.is_success:
                print(f"\n{CLR_GREEN}[PASS] Cryptographic manifest signed and verified.{CLR_RESET}")
                if result.publication_result and result.publication_result.manifest_path:
                    print(f"  Manifest: {result.publication_result.manifest_path}")
            else:
                print(f"\n{CLR_RED}[FAIL] Sanitization failed QualityGate verdict.{CLR_RESET}", file=sys.stderr)
                for r in result.failure_reasons:
                    print(f"  - {r}", file=sys.stderr)

        return 0 if result.is_success else 2

    except Exception as exc:
        if args.json:
            print(json.dumps({"status": "FAIL", "error": f"{type(exc).__name__}: {exc}"}))
        else:
            print(f"{CLR_RED}[ERROR]{CLR_RESET} Pipeline execution error: {exc}", file=sys.stderr)
        return 1


def cmd_image_verify(args) -> int:
    """Verify cryptographic provenance of a sanitized image."""
    img_path = Path(args.image).resolve()
    if not img_path.exists():
        print(f"{CLR_RED}[ERROR]{CLR_RESET} Image not found: {img_path}", file=sys.stderr)
        return 1

    manifest_path = (
        Path(args.manifest).resolve()
        if args.manifest
        else img_path.parent / f"{img_path.name}.manifest.json"
    )

    if not manifest_path.exists():
        print(f"{CLR_RED}[ERROR]{CLR_RESET} Audit manifest not found: {manifest_path}", file=sys.stderr)
        return 1

    try:
        manifest_data = json.loads(manifest_path.read_text(encoding="utf-8"))
        img_bytes = img_path.read_bytes()
        actual_img_hash = hashlib.sha256(img_bytes).hexdigest()

        evidence_preimage = manifest_data.get("evidence_preimage", {})
        final_artifact_hash = evidence_preimage.get("final_artifact_hash")
        evidence_hash = manifest_data.get("evidence_hash")
        sig_hex = manifest_data.get("ed25519_signature_hex")
        pub_pem = manifest_data.get("public_key_pem")

        # Check hash match
        hash_match = actual_img_hash == final_artifact_hash

        # Check Ed25519 signature
        from cryptography.hazmat.primitives.asymmetric import ed25519
        from cryptography.hazmat.primitives import serialization

        sig_valid = False
        if pub_pem and sig_hex and evidence_hash:
            try:
                pub_key = serialization.load_pem_public_key(pub_pem.encode("utf-8"))
                pub_key.verify(bytes.fromhex(sig_hex), evidence_hash.encode("utf-8"))
                sig_valid = True
            except Exception:
                sig_valid = False

        overall_valid = hash_match and sig_valid

        if args.json:
            print(
                json.dumps(
                    {
                        "image_path": str(img_path),
                        "manifest_path": str(manifest_path),
                        "hash_match": hash_match,
                        "signature_valid": sig_valid,
                        "verified": overall_valid,
                        "schema_version": manifest_data.get("schema_version"),
                        "overall_status": manifest_data.get("overall_status"),
                    },
                    indent=2,
                )
            )
            return 0 if overall_valid else 1

        match_str = f"{CLR_GREEN}MATCH{CLR_RESET}" if hash_match else f"{CLR_RED}FAIL{CLR_RESET}"
        sig_str = f"{CLR_GREEN}VALID{CLR_RESET}" if sig_valid else f"{CLR_RED}INVALID{CLR_RESET}"

        print(f"\n{CLR_CYAN}VeilFrame Cryptographic Provenance Verifier{CLR_RESET}")
        print(f"Target Image:  {img_path}")
        print(f"Manifest:      {manifest_path}")
        print(f"Image SHA-256: {actual_img_hash}")
        print(f"Artifact Hash: {match_str}")
        print(f"Signature:     {sig_str}")

        if overall_valid:
            print(f"\n{CLR_GREEN}[PASS] Verification SUCCESSFUL: Provenance and integrity confirmed.{CLR_RESET}")
            return 0
        else:
            print(f"\n{CLR_RED}[FAIL] Verification FAILED: Hash mismatch or invalid signature.{CLR_RESET}", file=sys.stderr)
            return 1

    except Exception as exc:
        print(f"{CLR_RED}[ERROR]{CLR_RESET} Verification exception: {exc}", file=sys.stderr)
        return 1


def cmd_image_inspect(args) -> int:
    """Inspect image structure, metadata, and detected regions."""
    img_path = Path(args.image).resolve()
    if not img_path.exists():
        print(f"{CLR_RED}[ERROR]{CLR_RESET} Image not found: {img_path}", file=sys.stderr)
        return 1

    try:
        raw_bytes = img_path.read_bytes()
        c_res = ContainerSanitizer().sanitize(raw_bytes)
        rep_res = RepresentationSanitizer().normalize(raw_bytes)

        detections = []
        if rep_res.linear_srgb_f32 is not None:
            face_det = PrimaryFaceDetector()
            faces = face_det.detect(rep_res.linear_srgb_f32)
            detections.extend([f.to_evidence().to_dict() for f in faces])

            plate_det = PrimaryPlateDetector()
            plates = plate_det.detect(rep_res.linear_srgb_f32)
            detections.extend([p.to_evidence().to_dict() for p in plates])

        info = {
            "path": str(img_path),
            "size_bytes": len(raw_bytes),
            "sha256": hashlib.sha256(raw_bytes).hexdigest(),
            "width": rep_res.width,
            "height": rep_res.height,
            "mode": rep_res.original_mode,
            "bit_depth": rep_res.original_bit_depth,
            "had_alpha": rep_res.had_alpha,
            "had_icc_profile": rep_res.had_icc_profile,
            "metadata_fields": c_res.exif_fields_found,
            "has_thumbnail": c_res.thumbnail_found,
            "has_gps": c_res.gps_found,
            "has_maker_note": c_res.maker_note_found,
            "has_xmp": c_res.xmp_found,
            "detections": detections,
        }

        if args.json:
            print(json.dumps(info, indent=2))
            return 0

        print(f"\n{CLR_CYAN}VeilFrame Image Inspector{CLR_RESET}")
        print(f"File:         {img_path.name} ({len(raw_bytes):,} bytes)")
        print(f"Dimensions:   {rep_res.width}x{rep_res.height} ({rep_res.original_mode})")
        print(f"Color:        Bit-depth: {rep_res.original_bit_depth}, Alpha: {rep_res.had_alpha}, ICC: {rep_res.had_icc_profile}")
        print(f"Metadata:     GPS: {c_res.gps_found}, Thumbnail: {c_res.thumbnail_found}, XMP: {c_res.xmp_found}, EXIF fields: {len(c_res.exif_fields_found)}")
        print(f"Detections:   {len(detections)} sensitive regions detected")
        for d in detections:
            print(f"  • {d['detector_class']} (confidence: {d['confidence']:.2f}) bbox: {d.get('bbox')}")

        return 0
    except Exception as exc:
        print(f"{CLR_RED}[ERROR]{CLR_RESET} Inspection failed: {exc}", file=sys.stderr)
        return 1


def cmd_image_doctor(args) -> int:
    """Run diagnostic checks on image processing capabilities."""
    report = CapabilityRegistry.detect().to_dict()

    if args.json:
        print(json.dumps(report, indent=2))
        return 0

    print(f"\n{CLR_CYAN}VeilFrame Image Subsystem Diagnostic{CLR_RESET}")
    print(f"Profile:      {CLR_BOLD}{report.get('profile', 'UNKNOWN').upper()}{CLR_RESET}")
    cv_str = f"{CLR_GREEN}AVAILABLE ({report.get('opencv_version', '')}){CLR_RESET}" if report.get("opencv_available") else f"{CLR_RED}MISSING{CLR_RESET}"
    dnn_str = f"{CLR_GREEN}AVAILABLE{CLR_RESET}" if report.get("opencv_dnn_available") else f"{CLR_YELLOW}UNAVAILABLE{CLR_RESET}"
    ocr_str = f"{CLR_GREEN}AVAILABLE{CLR_RESET}" if report.get("ocr_available") else f"{CLR_YELLOW}NOT INSTALLED (optional){CLR_RESET}"
    gpu_str = f"{CLR_GREEN}AVAILABLE{CLR_RESET}" if report.get("gpu_available") else f"{CLR_YELLOW}NOT DETECTED (CPU fallback){CLR_RESET}"
    print(f"OpenCV:       {cv_str}")
    print(f"OpenCV DNN:   {dnn_str}")
    print(f"OCR:          {ocr_str}")
    print(f"CUDA / GPU:   {gpu_str}")
    return 0


def add_image_subparsers(subparsers) -> None:
    """Register 'image' subcommands to an argparse subparsers instance."""
    image_parser = subparsers.add_parser(
        "image",
        help="Auditable Image Privacy Compiler & Red-Team Verification",
        description="Compile, sanitize, and cryptographically verify still images.",
    )
    img_sub = image_parser.add_subparsers(dest="image_subcommand", required=True)

    # sanitize
    p_san = img_sub.add_parser("sanitize", help="Sanitize an image (purge metadata, redact faces/plates/text/codes)")
    p_san.add_argument("input", help="Input image file path")
    p_san.add_argument("-o", "--output", help="Output sanitized image path")
    p_san.add_argument("-f", "--format", choices=["JPEG", "PNG", "WEBP"], help="Target format")
    p_san.add_argument("-d", "--detectors", help="Comma-separated detectors: face,plate,text,code")
    p_san.add_argument("-m", "--margin", type=int, default=10, help="Bounding box margin in pixels")
    p_san.add_argument("--audit-dir", help="Directory for cryptographic audit sidecars")
    p_san.add_argument("--signing-key", help="PEM-encoded Ed25519 private key")
    p_san.add_argument("--key-id", help="Identifier for signing key")
    p_san.add_argument("--json", action="store_true", help="Output in machine-readable JSON")
    p_san.add_argument("-q", "--quiet", action="store_true", help="Suppress non-error output")
    p_san.set_defaults(func=cmd_image_sanitize)

    # verify
    p_ver = img_sub.add_parser("verify", help="Cryptographically verify an image against its manifest")
    p_ver.add_argument("image", help="Sanitized image file path")
    p_ver.add_argument("-m", "--manifest", help="Path to .manifest.json sidecar")
    p_ver.add_argument("--json", action="store_true", help="Output in machine-readable JSON")
    p_ver.set_defaults(func=cmd_image_verify)

    # inspect
    p_insp = img_sub.add_parser("inspect", help="Inspect image metadata and sensitive regions")
    p_insp.add_argument("image", help="Image file path to inspect")
    p_insp.add_argument("--json", action="store_true", help="Output in machine-readable JSON")
    p_insp.set_defaults(func=cmd_image_inspect)

    # doctor
    p_doc = img_sub.add_parser("doctor", help="Check image processing runtime dependencies")
    p_doc.add_argument("--json", action="store_true", help="Output in machine-readable JSON")
    p_doc.set_defaults(func=cmd_image_doctor)
