"""
veilframe.image.models.policy — Declarative policy and normative rule set.

ImagePrivacyPolicy is the caller-facing declaration of what must be sanitised.
NormativePolicyRuleSet is the trust-anchor-signed document that defines the
authoritative rules the compiler enforces; it is independent of any caller
declaration.

Resource limits are embedded in the policy so the sealed snapshot enforcer
can reject oversized inputs before allocating any memory.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

from .status import DetectorClass, RedactionClass, CoverageScope


# ---------------------------------------------------------------------------
# Resource limits
# ---------------------------------------------------------------------------

# Normative defaults per the frozen specification
_MAX_INPUT_BYTES: int = 256 * 1024 * 1024        # 256 MB
_MAX_DECODED_PIXELS: int = 64 * 1024 * 1024       # 64 MP
_MAX_CHANNELS: int = 4
_MAX_METADATA_BYTES: int = 10 * 1024 * 1024       # 10 MB
_LARGE_FILE_THRESHOLD_BYTES: int = 32 * 1024 * 1024  # 32 MB


@dataclass(frozen=True)
class ResourceLimits:
    """Hard resource bounds enforced before any memory allocation.

    Exceeding any limit causes the snapshot to return
    RESOURCE_LIMIT_EXCEEDED -> UNKNOWN -> FAIL before decoding begins.

    Defaults match the frozen specification.
    """
    max_input_bytes: int = _MAX_INPUT_BYTES
    max_decoded_pixels: int = _MAX_DECODED_PIXELS
    max_channels: int = _MAX_CHANNELS
    max_metadata_bytes: int = _MAX_METADATA_BYTES
    large_file_threshold_bytes: int = _LARGE_FILE_THRESHOLD_BYTES

    def __post_init__(self) -> None:
        for name, val in [
            ("max_input_bytes", self.max_input_bytes),
            ("max_decoded_pixels", self.max_decoded_pixels),
            ("max_channels", self.max_channels),
            ("max_metadata_bytes", self.max_metadata_bytes),
            ("large_file_threshold_bytes", self.large_file_threshold_bytes),
        ]:
            if val <= 0:
                raise ValueError(f"ResourceLimits.{name} must be > 0; got {val}")

    def check_input_bytes(self, nbytes: int) -> bool:
        return nbytes <= self.max_input_bytes

    def check_decoded_pixels(self, width: int, height: int) -> bool:
        return width * height <= self.max_decoded_pixels

    def use_file_backing(self, nbytes: int) -> bool:
        """True iff this file size exceeds the large-file threshold."""
        return nbytes >= self.large_file_threshold_bytes

    def to_dict(self) -> dict:
        return {
            "max_input_bytes": self.max_input_bytes,
            "max_decoded_pixels": self.max_decoded_pixels,
            "max_channels": self.max_channels,
            "max_metadata_bytes": self.max_metadata_bytes,
            "large_file_threshold_bytes": self.large_file_threshold_bytes,
        }


# ---------------------------------------------------------------------------
# Threat model configuration
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ThreatModelConfig:
    """Selects which threat models are active for a given policy.

    Model A: Metadata Recipient -- parse and strip container metadata.
    Model B: Visual / Pixel Inspector -- detect and redact sensitive regions.
    Model C: Dataset Linkage Attacker -- measure perceptual linkage.
    Model D: Forensic Analyst -- quantify provenance residuals.

    threat_identity : str
        A stable, version-qualified string committed into the IdentityHash
        preimage.  Changing the threat model configuration must produce a
        different threat_identity.
    """
    threat_identity: str
    model_a_enabled: bool = True   # Metadata stripping
    model_b_enabled: bool = True   # Pixel-level redaction
    model_c_enabled: bool = True   # Linkage resistance measurement
    model_d_enabled: bool = False  # Forensic residual quantification

    def __post_init__(self) -> None:
        if not self.threat_identity:
            raise ValueError("ThreatModelConfig.threat_identity must not be empty")

    def to_dict(self) -> dict:
        return {
            "threat_identity": self.threat_identity,
            "model_a_enabled": self.model_a_enabled,
            "model_b_enabled": self.model_b_enabled,
            "model_c_enabled": self.model_c_enabled,
            "model_d_enabled": self.model_d_enabled,
        }


# ---------------------------------------------------------------------------
# Per-detector coverage requirement
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class DetectorCoverageRequirement:
    """Policy requirement for a single detector class.

    Fields
    ------
    detector_class : DetectorClass
        The detector this requirement applies to.
    required_redaction_class : RedactionClass
        Minimum redaction strength required for findings from this detector.
        For sensitive zones this must be DESTRUCTIVE.
    required_coverage_scope : CoverageScope
        The spatial scope within which 100 % coverage is required.
        Default FULL_CANVAS for face, plate, and text detectors.
    min_confidence_threshold : float
        Findings below this threshold are treated as UNKNOWN (no detection).
    """
    detector_class: DetectorClass
    required_redaction_class: RedactionClass = RedactionClass.DESTRUCTIVE
    required_coverage_scope: CoverageScope = CoverageScope.FULL_CANVAS
    min_confidence_threshold: float = 0.0

    def __post_init__(self) -> None:
        if not (0.0 <= self.min_confidence_threshold <= 1.0):
            raise ValueError(
                "min_confidence_threshold must be in [0, 1]; "
                f"got {self.min_confidence_threshold}"
            )

    def to_dict(self) -> dict:
        return {
            "detector_class": self.detector_class.value,
            "required_redaction_class": self.required_redaction_class.value,
            "required_coverage_scope": self.required_coverage_scope.value,
            "min_confidence_threshold": self.min_confidence_threshold,
        }


# ---------------------------------------------------------------------------
# Normative policy rule set (trust-anchor-signed)
# ---------------------------------------------------------------------------

@dataclass
class NormativePolicyRuleSet:
    """The authoritative, signed rule document governing compiler behaviour.

    This is NOT the caller's policy declaration.  It is the trust-anchor-
    signed normative source of truth that the policy compiler enforces.  Any
    deviation between a caller-declared policy and this rule set causes the
    compiler to reject the policy.

    Fields
    ------
    rule_set_id : str
        Stable identifier for this rule set.
    rule_set_version : str
        Semantic version string (e.g., "2.0.0").
    rule_set_hash : str
        SHA-256 (hex) of the RFC 8785 canonical serialisation of this
        rule set.  Committed into the IdentityHash preimage.
    rule_set_identity : str
        Composite identity string: "{rule_set_id}:{rule_set_version}:{rule_set_hash}".
        This is what is committed into VEILFRAME-IDENTITY-V1.
    signature : Optional[bytes]
        Ed25519 signature over SHA-256(RFC8785(this rule set)), produced by
        a trust anchor in the TrustAnchorRegistry.
    signer_anchor_id : Optional[str]
        The anchor_id of the TrustAnchor that signed this rule set.
    required_detectors : Dict[str, DetectorCoverageRequirement]
        Map of detector_class.value -> coverage requirement.
    allowed_metadata_keys : Set[str]
        Container metadata keys permitted in the output.  Any key not in
        this set must be stripped.  Unknown markers fail closed.
    min_independence_level : int
        Minimum required independence level for red-team probes (default 3).
    schema_version : str
        Schema version of this document (e.g., "2.0.0").
    """
    rule_set_id: str
    rule_set_version: str
    required_detectors: Dict[str, DetectorCoverageRequirement] = field(
        default_factory=dict
    )
    allowed_metadata_keys: Set[str] = field(default_factory=set)
    rule_set_hash: Optional[str] = None
    rule_set_identity: Optional[str] = None
    signature: Optional[bytes] = None
    signer_anchor_id: Optional[str] = None
    schema_version: str = "2.0.0"
    min_independence_level: int = 3  # IndependenceLevel.LEVEL_3

    def __post_init__(self) -> None:
        if not self.rule_set_id:
            raise ValueError("NormativePolicyRuleSet.rule_set_id must not be empty")
        if not self.rule_set_version:
            raise ValueError("NormativePolicyRuleSet.rule_set_version must not be empty")

    def compute_identity(self) -> str:
        """Compose the rule_set_identity string from id:version:hash."""
        h = self.rule_set_hash or "unhashed"
        return f"{self.rule_set_id}:{self.rule_set_version}:{h}"

    def to_dict(self) -> dict:
        return {
            "rule_set_id": self.rule_set_id,
            "rule_set_version": self.rule_set_version,
            "schema_version": self.schema_version,
            "min_independence_level": self.min_independence_level,
            "required_detectors": {
                k: v.to_dict()
                for k, v in sorted(self.required_detectors.items())
            },
            "allowed_metadata_keys": sorted(self.allowed_metadata_keys),
        }


# ---------------------------------------------------------------------------
# Caller-declared image privacy policy
# ---------------------------------------------------------------------------

@dataclass
class ImagePrivacyPolicy:
    """Caller-facing declaration of desired image privacy processing.

    The Policy Compiler validates this against the NormativePolicyRuleSet
    before generating any transformation tasks.  If the declared policy
    is weaker than the normative rules, the compiler rejects it.

    Fields
    ------
    policy_id : str
        Stable policy identifier (e.g., "anonymous_share").
    policy_version : str
        Semantic version (e.g., "1.0.0").
    threat_model : ThreatModelConfig
        Active threat models.
    active_detector_classes : List[DetectorClass]
        Which detector classes to run on this image.
    resource_limits : ResourceLimits
        Per-image resource bounds.
    fill_color_rgb : tuple
        RGB constant fill colour for solid redaction (range [0.0, 1.0]).
    redact_metadata : bool
        Whether Model A metadata stripping is required.
    redact_thumbnails : bool
        Whether embedded thumbnails must be removed.
    expansion_margin_px : int
        Padding added around detected bounding boxes before polygon generation.
    max_mask_expansion_tolerance_px : int
        Maximum allowed mask growth beyond ExpectedMappedMask (anti-inflation).
    """
    policy_id: str
    policy_version: str
    threat_model: ThreatModelConfig
    active_detector_classes: List[DetectorClass] = field(default_factory=list)
    resource_limits: ResourceLimits = field(default_factory=ResourceLimits)
    fill_color_rgb: tuple = (0.0, 0.0, 0.0)  # Black
    redact_metadata: bool = True
    redact_thumbnails: bool = True
    expansion_margin_px: int = 10
    max_mask_expansion_tolerance_px: int = 5

    def __post_init__(self) -> None:
        if not self.policy_id:
            raise ValueError("ImagePrivacyPolicy.policy_id must not be empty")
        if not self.policy_version:
            raise ValueError("ImagePrivacyPolicy.policy_version must not be empty")
        r, g, b = self.fill_color_rgb
        if not all(0.0 <= c <= 1.0 for c in (r, g, b)):
            raise ValueError(
                f"fill_color_rgb values must be in [0, 1]; got {self.fill_color_rgb}"
            )
        if self.expansion_margin_px < 0:
            raise ValueError("expansion_margin_px must be >= 0")
        if self.max_mask_expansion_tolerance_px < 0:
            raise ValueError("max_mask_expansion_tolerance_px must be >= 0")

    @property
    def policy_identity(self) -> str:
        """Composite identity committed into VEILFRAME-IDENTITY-V1.

        Format: "{policy_id}:{policy_version}:{threat_identity}"
        """
        return (
            f"{self.policy_id}:{self.policy_version}:"
            f"{self.threat_model.threat_identity}"
        )

    def to_dict(self) -> dict:
        return {
            "policy_id": self.policy_id,
            "policy_version": self.policy_version,
            "threat_model": self.threat_model.to_dict(),
            "active_detector_classes": [d.value for d in self.active_detector_classes],
            "resource_limits": self.resource_limits.to_dict(),
            "fill_color_rgb": list(self.fill_color_rgb),
            "redact_metadata": self.redact_metadata,
            "redact_thumbnails": self.redact_thumbnails,
            "expansion_margin_px": self.expansion_margin_px,
            "max_mask_expansion_tolerance_px": self.max_mask_expansion_tolerance_px,
        }


def create_default_rule_set() -> NormativePolicyRuleSet:
    """Construct standard NormativePolicyRuleSet for v2 compiler."""
    detectors = {
        DetectorClass.FACE.value: DetectorCoverageRequirement(
            detector_class=DetectorClass.FACE,
            required_redaction_class=RedactionClass.DESTRUCTIVE,
            required_coverage_scope=CoverageScope.FULL_CANVAS,
            min_confidence_threshold=0.5,
        ),
        DetectorClass.LICENSE_PLATE.value: DetectorCoverageRequirement(
            detector_class=DetectorClass.LICENSE_PLATE,
            required_redaction_class=RedactionClass.DESTRUCTIVE,
            required_coverage_scope=CoverageScope.FULL_CANVAS,
            min_confidence_threshold=0.5,
        ),
        DetectorClass.TEXT.value: DetectorCoverageRequirement(
            detector_class=DetectorClass.TEXT,
            required_redaction_class=RedactionClass.DESTRUCTIVE,
            required_coverage_scope=CoverageScope.FULL_CANVAS,
            min_confidence_threshold=0.7,
        ),
        DetectorClass.QR_CODE.value: DetectorCoverageRequirement(
            detector_class=DetectorClass.QR_CODE,
            required_redaction_class=RedactionClass.DESTRUCTIVE,
            required_coverage_scope=CoverageScope.FULL_CANVAS,
            min_confidence_threshold=0.5,
        ),
    }
    rule_set = NormativePolicyRuleSet(
        rule_set_id="veilframe-normative-rules-v2",
        rule_set_version="2.0.0",
        required_detectors=detectors,
        allowed_metadata_keys=set(),
        min_independence_level=1,
    )
    import hashlib, json
    d = json.dumps(rule_set.to_dict(), sort_keys=True, ensure_ascii=True)
    rule_set.rule_set_hash = hashlib.sha256(d.encode()).hexdigest()
    rule_set.rule_set_identity = rule_set.compute_identity()
    return rule_set


def create_default_policy() -> ImagePrivacyPolicy:
    """Construct standard ImagePrivacyPolicy for anonymous sharing."""
    return ImagePrivacyPolicy(
        policy_id="default_privacy",
        policy_version="1.0.0",
        threat_model=ThreatModelConfig(
            threat_identity="threat-model-abcd-v1",
            model_a_enabled=True,
            model_b_enabled=True,
            model_c_enabled=True,
            model_d_enabled=False,
        ),
        active_detector_classes=[
            DetectorClass.FACE,
            DetectorClass.LICENSE_PLATE,
            DetectorClass.TEXT,
            DetectorClass.QR_CODE,
        ],
        fill_color_rgb=(0.0, 0.0, 0.0),
        redact_metadata=True,
        redact_thumbnails=True,
        expansion_margin_px=10,
        max_mask_expansion_tolerance_px=5,
    )

