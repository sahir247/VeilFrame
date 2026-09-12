"""
veilframe.image.models — Normative type-safe dataclasses and schemas.

Import order must satisfy internal dependency chain:
  status → coordinates → trust → graph → policy → transform_plan → report → audit
"""

from .status import (
    CheckStatus,
    CoverageScope,
    IndependenceLevel,
    DetectorClass,
    CapabilityProfile,
    RedactionClass,
    TrustAnchorStatus,
    ArtifactTrustStatus,
    RevocationReason,
    PublicationState,
)
from .coordinates import (
    CoordinateSpace,
    BoundingBox,
    Polygon,
    TransformationGeometryMap,
    FidelityEvaluationSpace,
)
from .trust import (
    SignerIdentity,
    TrustAnchor,
    TrustAnchorRegistry,
    PinnedRoots,
    SignatureValidation,
)
from .graph import (
    LayerType,
    RiskLevel,
    ProviderFingerprint,
    DetectorEvidence,
    PrivacyNode,
    PrivacyGraph,
)
from .policy import (
    ResourceLimits,
    ThreatModelConfig,
    NormativePolicyRuleSet,
    ImagePrivacyPolicy,
)
from .transform_plan import (
    TransformOperation,
    ConstantFillParams,
    TransformTask,
    TransformationDAG,
    VerificationTask,
    VerificationPlan,
)
from .report import (
    RedactionAuditRecord,
    PrivacyResidualProfile,
    LinkageResistanceReport,
)
from .audit import (
    IdentityPreimage,
    EvidencePreimage,
    ImageAuditManifest,
)

__all__ = [
    # status
    "CheckStatus",
    "CoverageScope",
    "IndependenceLevel",
    "DetectorClass",
    "CapabilityProfile",
    "RedactionClass",
    "TrustAnchorStatus",
    "ArtifactTrustStatus",
    "RevocationReason",
    "PublicationState",
    # coordinates
    "CoordinateSpace",
    "BoundingBox",
    "Polygon",
    "TransformationGeometryMap",
    "FidelityEvaluationSpace",
    # trust
    "SignerIdentity",
    "TrustAnchor",
    "TrustAnchorRegistry",
    "PinnedRoots",
    "SignatureValidation",
    # graph
    "LayerType",
    "RiskLevel",
    "ProviderFingerprint",
    "DetectorEvidence",
    "PrivacyNode",
    "PrivacyGraph",
    # policy
    "ResourceLimits",
    "ThreatModelConfig",
    "NormativePolicyRuleSet",
    "ImagePrivacyPolicy",
    # transform_plan
    "TransformOperation",
    "ConstantFillParams",
    "TransformTask",
    "TransformationDAG",
    "VerificationTask",
    "VerificationPlan",
    # report
    "RedactionAuditRecord",
    "PrivacyResidualProfile",
    "LinkageResistanceReport",
    # audit
    "IdentityPreimage",
    "EvidencePreimage",
    "ImageAuditManifest",
]
