"""
veilframe.image.models.status — Normative status enumerations.

All status enumerations used across the audit pipeline.  The fundamental
invariant throughout the system is:

    UNKNOWN == FAIL at every gate boundary.

CheckStatus is the canonical tri-state used by every component.  A component
that cannot produce a definitive PASS or FAIL MUST return UNKNOWN.  The
QualityGate treats UNKNOWN identically to FAIL — no exceptions.
"""

from __future__ import annotations

from enum import Enum


# ---------------------------------------------------------------------------
# Core tri-state check result
# ---------------------------------------------------------------------------

class CheckStatus(str, Enum):
    """Canonical tri-state for every pipeline check.

    Semantics:
        PASS    — Evidence satisfies the declared contract.
        FAIL    — Evidence violates the declared contract.
        UNKNOWN — Evidence is insufficient to determine compliance.
                  Treated identically to FAIL at the QualityGate.

    IMPORTANT: No code path may silently convert an exception into a PASS.
               Uncaught errors must propagate as UNKNOWN, never PASS.
    """
    PASS = "pass"
    FAIL = "fail"
    UNKNOWN = "unknown"

    @property
    def is_pass(self) -> bool:
        return self is CheckStatus.PASS

    @property
    def is_fail(self) -> bool:
        """True for FAIL *or* UNKNOWN (fail-closed)."""
        return self is not CheckStatus.PASS

    def __and__(self, other: "CheckStatus") -> "CheckStatus":
        """Logical AND: both must PASS; UNKNOWN is contagious."""
        if self is CheckStatus.FAIL or other is CheckStatus.FAIL:
            return CheckStatus.FAIL
        if self is CheckStatus.UNKNOWN or other is CheckStatus.UNKNOWN:
            return CheckStatus.UNKNOWN
        return CheckStatus.PASS

    def __or__(self, other: "CheckStatus") -> "CheckStatus":
        """Logical OR: either PASS is sufficient; UNKNOWN taints."""
        if self is CheckStatus.PASS or other is CheckStatus.PASS:
            return CheckStatus.PASS
        if self is CheckStatus.UNKNOWN or other is CheckStatus.UNKNOWN:
            return CheckStatus.UNKNOWN
        return CheckStatus.FAIL


# ---------------------------------------------------------------------------
# Detector / coverage scope
# ---------------------------------------------------------------------------

class CoverageScope(str, Enum):
    """Spatial scope that a detector's coverage metric is relative to.

    The policy compiler specifies which scope is required for each detector.
    Detectors operating on compiler-generated ROIs report ROI coverage, not
    FULL_CANVAS coverage.  Coverage below 100 % within the declared scope
    evaluates to UNKNOWN → FAIL.
    """
    FULL_CANVAS = "full_canvas"
    ROI = "roi"
    MASK = "mask"
    UNKNOWN = "unknown"


class IndependenceLevel(int, Enum):
    """Fingerprint-distinct independence level for red-team probes.

    Level 0: Same implementation (no independence).
    Level 1: Distinct implementation binary.
    Level 2: Distinct algorithm OR distinct library binary.
    Level 3: Fingerprint-Distinct — distinct algorithm AND model family
             AND implementation AND dependency graph.  Required for the
             primary adversarial verification axis.
    """
    LEVEL_0 = 0  # Same implementation — NOT acceptable for red-team
    LEVEL_1 = 1  # Distinct implementation
    LEVEL_2 = 2  # Distinct algorithm or library
    LEVEL_3 = 3  # Fully fingerprint-distinct (required minimum)


class DetectorClass(str, Enum):
    """Semantic category of a detection provider."""
    FACE = "face"
    LICENSE_PLATE = "license_plate"
    TEXT = "text"
    QR_CODE = "qr_code"
    BARCODE = "barcode"
    DATA_MATRIX = "data_matrix"
    CUSTOM = "custom"


class CapabilityProfile(str, Enum):
    """Runtime capability tier available on the host machine.

    BASELINE: CPU-only, no GPU, no DNN model weights.
    STANDARD: CPU with optional DNN support via OpenCV DNN module.
    FULL:     GPU-accelerated, full model weight suite available.
    """
    BASELINE = "baseline"
    STANDARD = "standard"
    FULL = "full"


# ---------------------------------------------------------------------------
# Redaction / transformation classes
# ---------------------------------------------------------------------------

class RedactionClass(str, Enum):
    """Strength class of a pixel-level redaction operation.

    DESTRUCTIVE: Source-independent constant fill.  The only class that
                 satisfies source_dependence = ABSENT.  Required by policy
                 for Model B (Visual Pixel Inspector) threat mitigation.

    SOURCE_DERIVED: Blur or pixelation.  Retains statistical correlation
                    with source pixels.  REJECTED by QualityGate when
                    policy requires DESTRUCTIVE.
    """
    DESTRUCTIVE = "destructive"      # solid_mask / ConstantFill — ABSENT dependence
    SOURCE_DERIVED = "source_derived"  # blur / pixelate — rejected for sensitive zones


class SourceDependence(str, Enum):
    """Source-pixel dependence of a transformation output.

    ABSENT:  Output pixels are purely constant; no source pixel was read.
             This is the only value compatible with RedactionClass.DESTRUCTIVE.
    PRESENT: Output pixels depend on ≥ 1 source pixel (blur, pixelate, etc.).
    """
    ABSENT = "absent"
    PRESENT = "present"


# ---------------------------------------------------------------------------
# Trust / key status
# ---------------------------------------------------------------------------

class TrustAnchorStatus(str, Enum):
    """Lifecycle status of a trust anchor key."""
    ACTIVE = "active"
    REVOKED = "revoked"
    EXPIRED = "expired"


class ArtifactTrustStatus(str, Enum):
    """Result of verifying a signed artifact against the trust anchor registry.

    TRUSTED_VALID:     Signature verifies; key is active and within validity window.
    UNTRUSTED_SIGNER:  Signer key not present in the registry.
    REVOKED_SIGNER:    Signer key is revoked (semantics depend on RevocationReason).
    EXPIRED_KEY:       Signer key validity window has elapsed.
    INVALID_SIGNATURE: Ed25519 signature verification fails cryptographically.
    """
    TRUSTED_VALID = "trusted_valid"
    UNTRUSTED_SIGNER = "untrusted_signer"
    REVOKED_SIGNER = "revoked_signer"
    EXPIRED_KEY = "expired_key"
    INVALID_SIGNATURE = "invalid_signature"


class RevocationReason(str, Enum):
    """Reason a trust anchor was revoked; governs historical signature validity.

    COMPROMISED:    Private key was exposed.  Invalidates ALL historical
                    signatures — even those timestamped before the revocation.
    SUPERSEDED:     Normal key rotation.  Historical signatures remain valid
                    if they predate the revocation event.
    ADMINISTRATIVE: Organisational de-registration.
    TEST:           Test key deactivated after use.

    Historical validity rule:
        SignatureValidAt(T_sig) =
            verify(pubkey, data, sig)
            AND valid_from ≤ T_sig ≤ valid_until
            AND NOT (revoked AND (reason == COMPROMISED OR revoked_at ≤ T_sig))
    """
    COMPROMISED = "compromised"
    SUPERSEDED = "superseded"
    ADMINISTRATIVE = "administrative"
    TEST = "test"


# ---------------------------------------------------------------------------
# Publication state machine
# ---------------------------------------------------------------------------

class PublicationState(str, Enum):
    """State machine for the backend-defined atomic publication protocol.

    Valid transitions (forward):
        STAGED → VERIFIED → COMMITTING → COMMITTED

    Failure at any forward state rolls back to QUARANTINED.
    REVOKED is a post-publication state set by the manifest authority.
    """
    STAGED = "staged"
    VERIFIED = "verified"
    COMMITTING = "committing"
    COMMITTED = "committed"
    QUARANTINED = "quarantined"
    REVOKED = "revoked"
