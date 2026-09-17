"""
veilframe.image.models.trust — Trust anchor model and cryptographic validation.

Trust Hierarchy
---------------
    VeilFrameRootKeys (pinned, compiled-in)
        └── TrustAnchorRegistry (signed by a root key)
              └── TrustAnchor (per signing key; versioned)
                    ├── NormativePolicyRuleSet signature
                    └── CapabilityRegistry signature

All signed artifacts embed a SignerIdentity block which cryptographically binds
the signer's key to the EvidenceHash preimage (VEILFRAME-EVIDENCE-V1).  This
prevents key substitution attacks where an attacker swaps the key_id field
without the rest of the manifest being invalidated.

Historical Signature Validity
------------------------------
    SignatureValidAt(T_sig) =
        verify(pubkey, data, sig)
        AND valid_from ≤ T_sig ≤ valid_until
        AND NOT (revoked
                 AND (reason == COMPROMISED OR revoked_at ≤ T_sig))

For SUPERSEDED keys: historical signatures with T_sig < revoked_at remain valid.
For COMPROMISED keys: ALL signatures are invalidated regardless of timestamp.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional

from .status import TrustAnchorStatus, ArtifactTrustStatus, RevocationReason


# ---------------------------------------------------------------------------
# Signer identity — embedded in every signed artifact's preimage
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SignerIdentity:
    """Cryptographic binding of a signer key to a signed artifact.

    Embedded verbatim in the VEILFRAME-EVIDENCE-V1 preimage so that the
    signer identity is part of the data being signed, preventing substitution.

    Fields
    ------
    trust_anchor_id : str
        Stable identifier of the TrustAnchor entry in the registry.
    key_id : str
        Version-qualified key identifier (e.g., "veilframe-signing-v1").
    public_key_hash : str
        SHA-256(DER-encoded public key bytes) as lowercase hex.
        Provides an additional commitment to the exact public key material.
    """
    trust_anchor_id: str
    key_id: str
    public_key_hash: str  # SHA-256(public_key_der) as lowercase hex

    def __post_init__(self) -> None:
        if not self.trust_anchor_id:
            raise ValueError("trust_anchor_id must not be empty")
        if not self.key_id:
            raise ValueError("key_id must not be empty")
        if len(self.public_key_hash) != 64:
            raise ValueError(
                f"public_key_hash must be 64 hex chars; got {len(self.public_key_hash)}"
            )

    def to_dict(self) -> dict:
        """RFC 8785–ready dict (keys must be sorted by caller)."""
        return {
            "key_id": self.key_id,
            "public_key_hash": self.public_key_hash,
            "trust_anchor_id": self.trust_anchor_id,
        }

    @classmethod
    def from_public_key_der(
        cls,
        trust_anchor_id: str,
        key_id: str,
        public_key_der: bytes,
    ) -> "SignerIdentity":
        """Construct a SignerIdentity, computing public_key_hash from DER bytes."""
        return cls(
            trust_anchor_id=trust_anchor_id,
            key_id=key_id,
            public_key_hash=hashlib.sha256(public_key_der).hexdigest(),
        )


# ---------------------------------------------------------------------------
# Trust anchor
# ---------------------------------------------------------------------------

@dataclass
class TrustAnchor:
    """A single trusted signing key within the VeilFrame trust hierarchy.

    The public key is stored as DER-encoded bytes.  The trust anchor is
    validated by a root key (PinnedRoots) before being added to the registry.

    Fields
    ------
    anchor_id : str
        Stable, unique identifier (e.g., "veilframe-signing-v1").
    key_id : str
        Version-qualified human-readable label.
    public_key_der : bytes
        Ed25519 public key in DER encoding.
    status : TrustAnchorStatus
        Lifecycle state: ACTIVE, REVOKED, EXPIRED.
    valid_from : datetime
        UTC datetime from which this key is authoritative.
    valid_until : datetime
        UTC datetime after which this key expires.
    revocation_reason : Optional[RevocationReason]
        Set when status == REVOKED; governs historical signature validity.
    revoked_at : Optional[datetime]
        UTC datetime of revocation; required when revocation_reason is set.
    metadata : dict
        Arbitrary key-value metadata (purpose, algorithm label, etc.).
    """
    anchor_id: str
    key_id: str
    public_key_der: bytes
    status: TrustAnchorStatus
    valid_from: datetime
    valid_until: datetime
    revocation_reason: Optional[RevocationReason] = None
    revoked_at: Optional[datetime] = None
    metadata: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.anchor_id:
            raise ValueError("anchor_id must not be empty")
        if not self.public_key_der:
            raise ValueError("public_key_der must not be empty")
        if self.valid_from >= self.valid_until:
            raise ValueError("valid_from must be before valid_until")
        if self.status == TrustAnchorStatus.REVOKED and self.revocation_reason is None:
            raise ValueError(
                "TrustAnchor with REVOKED status must supply revocation_reason"
            )
        if self.revocation_reason is not None and self.revoked_at is None:
            raise ValueError(
                "revoked_at must be provided when revocation_reason is set"
            )

    @property
    def public_key_hash(self) -> str:
        """SHA-256 of DER public key bytes as lowercase hex."""
        return hashlib.sha256(self.public_key_der).hexdigest()

    @property
    def signer_identity(self) -> SignerIdentity:
        return SignerIdentity(
            trust_anchor_id=self.anchor_id,
            key_id=self.key_id,
            public_key_hash=self.public_key_hash,
        )

    def is_valid_at(self, timestamp: datetime) -> bool:
        """True iff the key is within its validity window at *timestamp*."""
        ts = _ensure_utc(timestamp)
        return self.valid_from <= ts <= self.valid_until

    def signature_valid_at(self, signature_timestamp: datetime) -> bool:
        """Evaluate historical signature validity per the specification.

        A signature made at *signature_timestamp* is valid only if:
          1. The key is within its validity window at that timestamp.
          2. The key is not revoked, OR if revoked:
               - Reason is SUPERSEDED AND revoked_at > signature_timestamp.
               - (COMPROMISED always invalidates historical signatures.)
        """
        ts = _ensure_utc(signature_timestamp)

        if not self.is_valid_at(ts):
            return False

        if self.status == TrustAnchorStatus.REVOKED:
            if self.revocation_reason == RevocationReason.COMPROMISED:
                return False  # All historical signatures invalidated
            # SUPERSEDED / ADMINISTRATIVE / TEST: only signatures after revoked_at
            # are invalidated.
            if self.revoked_at is not None and _ensure_utc(self.revoked_at) <= ts:
                return False

        return True

    def to_dict(self) -> dict:
        return {
            "anchor_id": self.anchor_id,
            "key_id": self.key_id,
            "public_key_hash": self.public_key_hash,
            "status": self.status.value,
            "valid_from": self.valid_from.isoformat(),
            "valid_until": self.valid_until.isoformat(),
            "revocation_reason": (
                self.revocation_reason.value
                if self.revocation_reason is not None else None
            ),
            "revoked_at": (
                self.revoked_at.isoformat() if self.revoked_at is not None else None
            ),
        }


# ---------------------------------------------------------------------------
# Trust anchor registry
# ---------------------------------------------------------------------------

@dataclass
class TrustAnchorRegistry:
    """Registry of all known trust anchors validated by the pinned root keys.

    The registry is itself signed by a root key so any tampering can be
    detected.  At runtime the registry is loaded from a signed bundle; the
    signature is verified against PinnedRoots before any anchor is trusted.

    Methods for signature verification are provided but do NOT depend on the
    ``cryptography`` library at import time — they accept the raw Ed25519
    public key from the TrustAnchor.public_key_der field.
    """
    anchors: Dict[str, TrustAnchor] = field(default_factory=dict)
    registry_version: str = "1.0.0"

    def add_anchor(self, anchor: TrustAnchor) -> None:
        if anchor.anchor_id in self.anchors:
            raise ValueError(
                f"Anchor '{anchor.anchor_id}' already registered; "
                "use update_anchor to replace."
            )
        self.anchors[anchor.anchor_id] = anchor

    def get_anchor(self, anchor_id: str) -> Optional[TrustAnchor]:
        return self.anchors.get(anchor_id)

    def validate_signer_identity(
        self,
        signer: SignerIdentity,
        signature_timestamp: datetime,
    ) -> ArtifactTrustStatus:
        """Validate a SignerIdentity against this registry.

        Returns the appropriate ArtifactTrustStatus.  Does NOT verify the
        cryptographic signature bytes — callers must do that separately.
        """
        anchor = self.get_anchor(signer.trust_anchor_id)
        if anchor is None:
            return ArtifactTrustStatus.UNTRUSTED_SIGNER

        # Verify key material commitment
        if anchor.public_key_hash != signer.public_key_hash:
            return ArtifactTrustStatus.UNTRUSTED_SIGNER

        if anchor.status == TrustAnchorStatus.EXPIRED:
            return ArtifactTrustStatus.EXPIRED_KEY

        if anchor.status == TrustAnchorStatus.REVOKED:
            if not anchor.signature_valid_at(signature_timestamp):
                return ArtifactTrustStatus.REVOKED_SIGNER

        if not anchor.signature_valid_at(signature_timestamp):
            return ArtifactTrustStatus.EXPIRED_KEY

        return ArtifactTrustStatus.TRUSTED_VALID

    def verify_ed25519_signature(
        self,
        anchor_id: str,
        data: bytes,
        signature: bytes,
    ) -> ArtifactTrustStatus:
        """Verify an Ed25519 signature using the named anchor's public key.

        Returns ArtifactTrustStatus.TRUSTED_VALID on success, or an
        appropriate failure status.  Requires the ``cryptography`` package.
        """
        try:
            from cryptography.hazmat.primitives.asymmetric.ed25519 import (
                Ed25519PublicKey,
            )
            from cryptography.hazmat.primitives.serialization import (
                Encoding, PublicFormat,
            )
            from cryptography.exceptions import InvalidSignature
        except ImportError as exc:
            raise RuntimeError(
                "cryptography package is required for Ed25519 verification"
            ) from exc

        anchor = self.get_anchor(anchor_id)
        if anchor is None:
            return ArtifactTrustStatus.UNTRUSTED_SIGNER

        try:
            pubkey = Ed25519PublicKey.from_public_bytes(
                # DER → raw 32-byte key extraction
                # Ed25519 DER keys have a 12-byte prefix; raw key is the last 32 bytes
                anchor.public_key_der[-32:]
                if len(anchor.public_key_der) > 32
                else anchor.public_key_der
            )
            pubkey.verify(signature, data)
            return ArtifactTrustStatus.TRUSTED_VALID
        except InvalidSignature:
            return ArtifactTrustStatus.INVALID_SIGNATURE


# ---------------------------------------------------------------------------
# Pinned root keys (compiled-in; never loaded from disk at runtime)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class PinnedRoots:
    """Immutable set of root public keys compiled into the veilframe binary.

    These keys are used to validate the TrustAnchorRegistry bundle itself.
    They must NEVER be sourced from user-provided configuration or network
    resources at runtime — they are constants baked in at build time.

    In this implementation the set is empty (development mode).  Production
    builds replace this class with a version containing hard-coded key bytes.
    """
    # Tuple of DER-encoded Ed25519 root public key bytes
    root_public_keys: tuple = ()

    @property
    def is_development_mode(self) -> bool:
        """True when no root keys are pinned (development / test builds)."""
        return len(self.root_public_keys) == 0

    def validate_registry_bundle(self, registry: TrustAnchorRegistry) -> bool:
        """Verify the registry was signed by a pinned root key.

        In development mode (no pinned keys) this always returns True.
        Production builds must override with actual signature verification.
        """
        if self.is_development_mode:
            return True  # Permissive in dev; strict in production
        raise NotImplementedError(
            "Production root key validation is not implemented in this build."
        )


# ---------------------------------------------------------------------------
# Signature validation result (returned by the pipeline verifier)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SignatureValidation:
    """Result of verifying a cryptographic signature on an artifact.

    Fields
    ------
    artifact_trust_status : ArtifactTrustStatus
        Overall trust verdict.
    anchor_id : Optional[str]
        Anchor used for verification (None if signer is untrusted).
    signature_timestamp : Optional[datetime]
        Trusted timestamp from the signed artifact.
    error_detail : Optional[str]
        Human-readable explanation for non-TRUSTED_VALID results.
    """
    artifact_trust_status: ArtifactTrustStatus
    anchor_id: Optional[str] = None
    signature_timestamp: Optional[datetime] = None
    error_detail: Optional[str] = None

    @property
    def is_trusted(self) -> bool:
        return self.artifact_trust_status == ArtifactTrustStatus.TRUSTED_VALID

    def to_dict(self) -> dict:
        return {
            "artifact_trust_status": self.artifact_trust_status.value,
            "anchor_id": self.anchor_id,
            "signature_timestamp": (
                self.signature_timestamp.isoformat()
                if self.signature_timestamp is not None else None
            ),
            "error_detail": self.error_detail,
        }


# ---------------------------------------------------------------------------
# Utility
# ---------------------------------------------------------------------------

def _ensure_utc(dt: datetime) -> datetime:
    """Return *dt* in UTC, attaching UTC tzinfo if naive."""
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)
