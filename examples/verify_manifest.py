#!/usr/bin/env python3
"""
VeilFrame — Standalone Application-Independent Audit Manifest Verifier.

A standalone, read-only verification utility for VeilFrame audit manifests.
Requires only standard Python and 'cryptography' (independent of Qt/GUI and VeilFrame engine).

Usage:
    python verify_manifest.py <manifest.json> <manifest.sig> <public_key.pem> [--expected-fingerprint SHA256:...] [--expected-key-id KEY_ID] [--video-file output.mp4]
"""
import os
import sys
import json
import hashlib
import argparse
from pathlib import Path

# Safe terminal encoding
os.environ["PYTHONIOENCODING"] = "utf-8"
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from typing import Any

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519
    from cryptography.hazmat.primitives import serialization
except ImportError:
    print("Error: 'cryptography' package is required. Install via: pip install cryptography")
    sys.exit(1)


def canonicalize_rfc8785(data: Any) -> bytes:
    """
    Standalone RFC 8785 (JSON Canonicalization Scheme - JCS) serializer.
    """
    import math

    def _serialize_string(s: str) -> str:
        out = ['"']
        for char in s:
            code = ord(char)
            if char == '"':
                out.append('\\"')
            elif char == '\\':
                out.append('\\\\')
            elif code < 0x20:
                out.append(f"\\u{code:04x}")
            else:
                out.append(char)
        out.append('"')
        return "".join(out)

    def _serialize_number(n: float | int) -> str:
        if isinstance(n, int):
            return str(n)
        if math.isnan(n) or math.isinf(n):
            raise ValueError("NaN and Infinity forbidden in RFC 8785")
        if n == 0.0:
            return "0"
        if n.is_integer() and -9007199254740991 <= n <= 9007199254740991:
            return str(int(n))
        s = repr(n)
        if "e" in s or "E" in s:
            s = s.lower()
            parts = s.split("e")
            s = f"{parts[0]}e{int(parts[1])}"
        return s

    def _encode(val: Any) -> str:
        if val is None:
            return "null"
        elif isinstance(val, bool):
            return "true" if val else "false"
        elif isinstance(val, (int, float)):
            return _serialize_number(val)
        elif isinstance(val, str):
            return _serialize_string(val)
        elif isinstance(val, (list, tuple)):
            return "[" + ",".join(_encode(item) for item in val) + "]"
        elif isinstance(val, dict):
            sorted_keys = sorted(val.keys(), key=lambda k: k.encode("utf-16be"))
            return "{" + ",".join(
                f"{_serialize_string(k)}:{_encode(val[k])}" for k in sorted_keys
            ) + "}"
        else:
            raise TypeError(f"Unsupported type {type(val)}")

    return _encode(data).encode("utf-8")


def compute_sha256(file_path: Path) -> str:
    """Computes SHA-256 cryptographic hash of the specified file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(
        description="Independently verify an Ed25519 digitally signed VeilFrame Audit Manifest."
    )
    parser.add_argument("manifest", type=Path, help="Path to manifest.json")
    parser.add_argument("signature", type=Path, help="Path to manifest.sig")
    parser.add_argument("public_key", type=Path, help="Path to public_key.pem")
    parser.add_argument(
        "--expected-fingerprint",
        type=str,
        default=None,
        help="Optional pinned SHA-256 public key fingerprint (e.g. SHA256:...)",
    )
    parser.add_argument(
        "--expected-key-id",
        type=str,
        default=None,
        help="Optional expected persistent signer key identity (e.g. veilframe-production-01)",
    )
    parser.add_argument(
        "--video-file",
        type=Path,
        default=None,
        help="Optional path to output video file to verify cryptographic hash against manifest",
    )

    args = parser.parse_args()

    # 1. File existence validation
    for p, name in [(args.manifest, "Manifest JSON"), (args.signature, "Signature file"), (args.public_key, "Public key")]:
        if not p.exists():
            print(f"[-] Error: {name} not found at: {p}")
            sys.exit(1)

    print("============================================================")
    print("      VEILFRAME STANDALONE AUDIT MANIFEST VERIFIER          ")
    print("============================================================\n")

    manifest_bytes = args.manifest.read_bytes()
    sig_bytes = args.signature.read_bytes()
    pub_pem_bytes = args.public_key.read_bytes()

    # 2. Parse canonical manifest JSON
    try:
        manifest_data = json.loads(manifest_bytes.decode("utf-8"))
        canonical_bytes = canonicalize_rfc8785(manifest_data)
    except Exception as e:
        print(f"[-] FAILED: Manifest is not valid JSON: {e}")
        sys.exit(1)

    manifest_sha256 = hashlib.sha256(canonical_bytes).hexdigest()
    print(f"[*] Manifest Canonical SHA-256: {manifest_sha256}")

    # 3. Load and inspect Ed25519 Public Key
    try:
        public_key = serialization.load_pem_public_key(pub_pem_bytes)
        if not isinstance(public_key, ed25519.Ed25519PublicKey):
            print("[-] FAILED: Public key is not a valid Ed25519 key.")
            sys.exit(1)
    except Exception as e:
        print(f"[-] FAILED: Could not load public key PEM: {e}")
        sys.exit(1)

    raw_pub_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    actual_fingerprint_raw = f"SHA256:{hashlib.sha256(raw_pub_bytes).hexdigest()}"
    actual_fingerprint_pem = f"SHA256:{hashlib.sha256(pub_pem_bytes).hexdigest()}"
    print(f"[*] Raw Public Key Fingerprint:  {actual_fingerprint_raw}")

    # Inspect signing metadata
    signing_info = manifest_data.get("signing", {})
    sign_mode = signing_info.get("mode", "ephemeral")
    sign_key_id = signing_info.get("key_id")
    print(f"[*] Signing Mode:               {sign_mode} ({'Key ID: ' + sign_key_id if sign_key_id else 'No persistent ID'})")

    # 4. Check against expected key ID if specified
    if args.expected_key_id:
        if sign_key_id != args.expected_key_id:
            print(f"\n[!] SECURITY VIOLATION: Signer Key ID mismatch!")
            print(f"    Expected: {args.expected_key_id}")
            print(f"    Recorded: {sign_key_id}")
            sys.exit(1)
        else:
            print(f"[+] Signer Key ID:               MATCHED ({sign_key_id})")

    # 5. Check against pinned fingerprint if specified
    if args.expected_fingerprint:
        if args.expected_fingerprint not in (actual_fingerprint_raw, actual_fingerprint_pem):
            print(f"\n[!] SECURITY VIOLATION: Public key fingerprint mismatch!")
            print(f"    Expected: {args.expected_fingerprint}")
            print(f"    Actual:   {actual_fingerprint_raw}")
            sys.exit(1)
        else:
            print("[+] Pinned Public Key Fingerprint: MATCHED (Valid Root)")

    # 6. Verify Ed25519 Digital Signature
    try:
        public_key.verify(sig_bytes, canonical_bytes)
        print("[+] Ed25519 Digital Signature:     VALID (Manifest is authentic & untampered)")
    except Exception as e:
        print(f"[-] SIGNATURE VERIFICATION FAILED: Manifest has been tampered with or signature invalid!")
        sys.exit(1)

    # 7. Verify target video hash if provided
    if args.video_file:
        if not args.video_file.exists():
            print(f"[-] Video file not found: {args.video_file}")
            sys.exit(1)
        actual_video_sha256 = compute_sha256(args.video_file)
        recorded_output_sha256 = manifest_data.get("output_sha256", "")
        print(f"\n[*] Output Video SHA-256:          {actual_video_sha256}")
        print(f"[*] Manifest Recorded SHA-256:      {recorded_output_sha256}")
        if actual_video_sha256.lower() == recorded_output_sha256.lower():
            print("[+] Video File Integrity:          MATCHED (Bitstream matches manifest exactly)")
        else:
            print("[-] VIDEO INTEGRITY FAILED: Video bitstream does not match recorded manifest hash!")
            sys.exit(1)

    # 8. Print recorded audit findings
    print("\n------------------------------------------------------------")
    print("               RECORDED AUDIT FINDINGS                      ")
    print("------------------------------------------------------------")
    val_info = manifest_data.get("validator", {})
    print(f"Validator Version:       {val_info.get('version', 'unknown')} ({val_info.get('algorithm_version', '')})")
    print(f"Policy Version:          {val_info.get('policy_version', 'unknown')}")

    verdict_info = manifest_data.get("verdict", {})
    print(f"Overall Gate Verdict:    {verdict_info.get('overall_verdict', 'UNKNOWN')}")
    print(f"  Tier 1 Policy:         {'PASS' if verdict_info.get('tier1_policy_passed') else 'FAIL'}")
    print(f"  Tier 2 Fidelity:       {'PASS' if verdict_info.get('tier2_fidelity_passed') else 'FAIL'}")
    print(f"  Tier 3 Temporal:       {'PASS' if verdict_info.get('tier3_temporal_passed') else 'FAIL'}")

    fid = manifest_data.get("rendered_fidelity", {})
    print(f"\nRendered Fidelity Constraints:")
    print(f"  SSIM (Structural):     Mean={fid.get('ssim_mean', 0.0):.4f}, P5={fid.get('ssim_p5', 0.0):.4f}, Worst={fid.get('ssim_worst', 0.0):.4f}")
    print(f"  PSNR (Pixel Fidelity): Mean={fid.get('psnr_mean_db', 0.0):.2f} dB, Worst={fid.get('psnr_worst_db', 0.0):.2f} dB")

    native = manifest_data.get("native_metrics", {})
    print(f"\nNative Stream Modifications:")
    print(f"  Resolution:            {native.get('resolution_ref', '')} -> {native.get('resolution_trans', '')} (Delta={native.get('spatial_delta_pct', 0.0):.2f}%)")
    print(f"  FPS:                   {native.get('fps_ref', 0.0):.2f} -> {native.get('fps_trans', 0.0):.2f}")
    print(f"  Duration:              {native.get('duration_ref', 0.0):.2f}s -> {native.get('duration_trans', 0.0):.2f}s (Delta={native.get('duration_delta_sec', 0.0):+.3f}s)")

    samp = manifest_data.get("sampling", {})
    if samp:
        print(f"\nTimeline Sampling:")
        print(f"  Strategy:              {samp.get('strategy', '')} (Evaluated samples: {samp.get('count', 0)})")
        print(f"  Range:                 {samp.get('range', [])}")

    print("\n============================================================")
    print("             AUDIT VERIFICATION RESULT: PASSED              ")
    print("============================================================")


if __name__ == "__main__":
    main()
