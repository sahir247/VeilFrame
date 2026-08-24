# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Threat Model & Security Architecture

VeilFrame is designed around strict defense-in-depth principles:
1. **Local-Only Execution:** All media processing, encoding, and auditing execute 100% locally on the host machine. No telemetry, metadata, or media streams are transmitted over network sockets.
2. **Read-Only Auditor Trust Boundary:** The transformation engine (`pipeline.py`, `encoder.py`) is decoupled from the independent read-only validator (`validator.py`). The validator cannot alter the processed output, and the transformer cannot falsify validation metrics.
3. **Ephemeral Asymmetric Keys:** Ed25519 signing keys are generated ephemerally in RAM during the export audit phase. Private keys are never serialized to disk, saved in logs, or embedded in manifests.
4. **Pinned Public Key Fingerprinting:** Public keys are fingerprinted via SHA-256 over raw 32-byte Ed25519 public key bytes (`SHA256:...`) to detect key substitution attacks.

---

## Reporting a Vulnerability

If you discover a security vulnerability, cryptographic flaw, or potential metadata leak in VeilFrame:

1. **Do not open a public GitHub issue.**
2. Please send a detailed report to the maintainers via GitHub Private Security Advisory or email.
3. Include:
   - Description of the vulnerability or leakage vector.
   - Steps to reproduce with sample media (please ensure no personal or sensitive data is included in reproduction files).
   - Expected vs. actual behavior.
   - Potential impact on user privacy or audit integrity.

We appreciate responsible disclosure and will respond within 48 hours to validate and address reported issues.
