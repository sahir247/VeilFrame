"""
veilframe.image.models.graph — Privacy Graph types.

The Privacy Graph models an image as a set of privacy-relevant nodes across
four analysis layers.  Each node represents a detected sensitive element with
its evidence provenance and associated risk.

Layers:
    CONTAINER    — File-format metadata: EXIF, XMP, IPTC, GPS, thumbnails.
    REPRESENTATION — Encoded representation: ICC profile, orientation, color space.
    SEMANTIC     — Pixel-level: faces, licence plates, text, barcodes.
    PROVENANCE   — Forensic residuals: PRNU, compression history, perceptual fingerprint.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Optional, Tuple

from .coordinates import BoundingBox, CoordinateSpace
from .status import DetectorClass, IndependenceLevel


# ---------------------------------------------------------------------------
# Graph layer & risk enumerations
# ---------------------------------------------------------------------------

class LayerType(str, Enum):
    """Layer within the Privacy Graph that a node belongs to."""
    CONTAINER = "container"
    REPRESENTATION = "representation"
    SEMANTIC = "semantic"
    PROVENANCE = "provenance"


class RiskLevel(str, Enum):
    """Normalised risk level assigned to a privacy node.

    Risk levels follow a monotonic ordering:
        NONE < LOW < MEDIUM < HIGH < CRITICAL
    """
    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"

    def __ge__(self, other: "RiskLevel") -> bool:  # type: ignore[override]
        _order = ["none", "low", "medium", "high", "critical"]
        return _order.index(self.value) >= _order.index(other.value)

    def __gt__(self, other: "RiskLevel") -> bool:  # type: ignore[override]
        _order = ["none", "low", "medium", "high", "critical"]
        return _order.index(self.value) > _order.index(other.value)


# ---------------------------------------------------------------------------
# Provider fingerprint (enables IndependenceLevel evaluation)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ProviderFingerprint:
    """Cryptographic fingerprint of a detection or transformation provider.

    The IndependenceAuditor uses this struct to evaluate the independence
    level of a red-team probe relative to the primary detection provider.

    All hash fields are lowercase hex SHA-256 strings.

    Independence Levels:
        0 — Same implementation (implementation_hash matches).
        1 — Distinct implementation binary.
        2 — Distinct algorithm OR distinct library binary.
        3 — Fingerprint-Distinct: distinct algorithm AND model_family
            AND implementation AND dependency_graph.
    """
    provider_id: str
    implementation_id: str
    algorithm_id: str
    library_id: str
    model_family: str
    version: str
    source_hash: str           # SHA-256 of implementation source text
    implementation_hash: str   # Bytecode / AST hash of execution routine
    dependency_graph_hash: str # Hash of resolved runtime dependency graph
    library_binary_hash: str   # SHA-256 of compiled shared library or package dist
    model_hash: Optional[str] = None  # SHA-256 of model weights/cascade file

    def __post_init__(self) -> None:
        for name, val in [
            ("source_hash", self.source_hash),
            ("implementation_hash", self.implementation_hash),
            ("dependency_graph_hash", self.dependency_graph_hash),
            ("library_binary_hash", self.library_binary_hash),
        ]:
            if val and len(val) != 64:
                raise ValueError(
                    f"ProviderFingerprint.{name} must be 64 hex chars; got {len(val)}"
                )

    def independence_level(self, other: "ProviderFingerprint") -> IndependenceLevel:
        """Return independence level of *other* relative to *self* (primary)."""
        if self.implementation_hash == other.implementation_hash:
            return IndependenceLevel.LEVEL_0

        # Level 1: distinct implementation
        is_level_1 = True

        # Level 2: distinct algorithm OR distinct library binary
        is_level_2 = (
            self.algorithm_id != other.algorithm_id
            or self.library_binary_hash != other.library_binary_hash
        )

        # Level 3: distinct algo AND model_family AND implementation AND dep_graph
        is_level_3 = (
            self.algorithm_id != other.algorithm_id
            and self.model_family != other.model_family
            and self.implementation_hash != other.implementation_hash
            and self.dependency_graph_hash != other.dependency_graph_hash
        )

        if is_level_3:
            return IndependenceLevel.LEVEL_3
        if is_level_2:
            return IndependenceLevel.LEVEL_2
        return IndependenceLevel.LEVEL_1

    def to_dict(self) -> dict:
        return {
            "provider_id": self.provider_id,
            "implementation_id": self.implementation_id,
            "algorithm_id": self.algorithm_id,
            "library_id": self.library_id,
            "model_family": self.model_family,
            "version": self.version,
            "source_hash": self.source_hash,
            "implementation_hash": self.implementation_hash,
            "dependency_graph_hash": self.dependency_graph_hash,
            "library_binary_hash": self.library_binary_hash,
            "model_hash": self.model_hash,
        }


# ---------------------------------------------------------------------------
# Detector evidence
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class DetectorEvidence:
    """Evidence produced by a detection provider for a single finding.

    Fields
    ------
    detector_class : DetectorClass
        Semantic category of the detection.
    confidence : float
        Provider-reported confidence in [0.0, 1.0].  May be 1.0 if the
        provider does not produce confidence scores.
    bbox : Optional[BoundingBox]
        Bounding box of the detected region in SOURCE_DECODED space.
        None for container-layer or metadata findings (no pixel region).
    fingerprint : ProviderFingerprint
        Cryptographic fingerprint of the provider that produced this evidence.
    raw_metadata : dict
        Arbitrary provider-specific metadata (model output scores, etc.).
    """
    detector_class: DetectorClass
    confidence: float
    fingerprint: ProviderFingerprint
    bbox: Optional[BoundingBox] = None
    raw_metadata: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not (0.0 <= self.confidence <= 1.0):
            raise ValueError(
                f"DetectorEvidence.confidence must be in [0, 1]; got {self.confidence}"
            )
        if self.bbox is not None:
            if self.bbox.space is not CoordinateSpace.SOURCE_DECODED:
                raise ValueError(
                    "DetectorEvidence.bbox must be in SOURCE_DECODED space; "
                    f"got {self.bbox.space}"
                )

    def to_dict(self) -> dict:
        return {
            "detector_class": self.detector_class.value,
            "confidence": self.confidence,
            "bbox": self.bbox.to_dict() if self.bbox is not None else None,
            "fingerprint": self.fingerprint.to_dict(),
        }


# ---------------------------------------------------------------------------
# Privacy graph nodes
# ---------------------------------------------------------------------------

@dataclass
class PrivacyNode:
    """A single privacy-relevant finding in the Privacy Graph.

    Each node is associated with exactly one LayerType and carries all
    evidence used by the Policy Compiler to generate transformation tasks.
    """
    node_id: str
    layer: LayerType
    risk_level: RiskLevel
    evidence: List[DetectorEvidence] = field(default_factory=list)
    metadata_keys: List[str] = field(default_factory=list)  # For CONTAINER layer
    description: str = ""

    def __post_init__(self) -> None:
        if not self.node_id:
            raise ValueError("PrivacyNode.node_id must not be empty")

    def add_evidence(self, ev: DetectorEvidence) -> None:
        self.evidence.append(ev)

    @property
    def has_spatial_extent(self) -> bool:
        """True iff any evidence item has a bounding box."""
        return any(e.bbox is not None for e in self.evidence)

    @property
    def bounding_boxes(self) -> List[BoundingBox]:
        """All bounding boxes from evidence items."""
        return [e.bbox for e in self.evidence if e.bbox is not None]

    def to_dict(self) -> dict:
        return {
            "node_id": self.node_id,
            "layer": self.layer.value,
            "risk_level": self.risk_level.value,
            "evidence": [e.to_dict() for e in self.evidence],
            "metadata_keys": self.metadata_keys,
            "description": self.description,
        }


# ---------------------------------------------------------------------------
# Privacy graph
# ---------------------------------------------------------------------------

@dataclass
class PrivacyGraph:
    """Complete multi-layer Privacy Graph for a single source image.

    The graph is the intermediate representation consumed by the Policy
    Compiler to generate the Transformation DAG and Verification Plan.
    Once constructed and hashed, the graph is immutable.

    Fields
    ------
    raw_source_hash : str
        SHA-256 of the source file bytes (hex) — seals the input.
    nodes : Dict[str, PrivacyNode]
        Node map keyed by node_id.
    graph_hash : Optional[str]
        SHA-256 of the RFC 8785 canonical serialisation of this graph.
        Set by the compiler after graph construction is complete.
    """
    raw_source_hash: str
    nodes: Dict[str, PrivacyNode] = field(default_factory=dict)
    graph_hash: Optional[str] = None

    def add_node(self, node: PrivacyNode) -> None:
        if node.node_id in self.nodes:
            raise ValueError(
                f"PrivacyGraph: duplicate node_id '{node.node_id}'"
            )
        self.nodes[node.node_id] = node

    def nodes_by_layer(self, layer: LayerType) -> List[PrivacyNode]:
        return [n for n in self.nodes.values() if n.layer == layer]

    def nodes_at_or_above_risk(self, level: RiskLevel) -> List[PrivacyNode]:
        return [n for n in self.nodes.values() if n.risk_level >= level]

    def to_dict(self) -> dict:
        """RFC 8785–ready representation (caller must canonicalise)."""
        return {
            "raw_source_hash": self.raw_source_hash,
            "nodes": {
                nid: node.to_dict()
                for nid, node in sorted(self.nodes.items())
            },
        }
