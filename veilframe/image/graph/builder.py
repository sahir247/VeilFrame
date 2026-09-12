"""
veilframe.image.graph.builder — Privacy Graph construction.

Constructs a PrivacyGraph by traversing all four analysis layers:
  CONTAINER    — Metadata fields (EXIF, GPS, XMP, thumbnails, maker notes).
  REPRESENTATION — ICC profile, orientation state, color-space encoding.
  SEMANTIC     — Detected faces, plates, text regions, barcodes.
  PROVENANCE   — Forensic residuals.
"""

from __future__ import annotations

from typing import Optional

from ..models.coordinates import BoundingBox
from ..models.graph import (
    DetectorEvidence,
    LayerType,
    PrivacyGraph,
    PrivacyNode,
    RiskLevel,
)
from ..models.status import DetectorClass


class PrivacyGraphBuilder:
    """Incrementally constructs a PrivacyGraph across all four layers."""

    def __init__(self, raw_source_hash: str, width: int, height: int) -> None:
        self._raw_source_hash = raw_source_hash
        self._width = width
        self._height = height
        self._nodes: list[PrivacyNode] = []
        self._counter = 0

    def _next_id(self, prefix: str) -> str:
        self._counter += 1
        return f"{prefix}_{self._counter:04d}"

    def add_metadata_node(
        self,
        metadata_field: str,
        risk: RiskLevel = RiskLevel.HIGH,
        layer: LayerType = LayerType.CONTAINER,
        notes: str = "",
    ) -> str:
        node_id = self._next_id(layer.value)
        node = PrivacyNode(
            node_id=node_id,
            layer=layer,
            risk_level=risk,
            metadata_keys=[metadata_field],
            description=f"{layer.value}: {metadata_field}",
        )
        self._nodes.append(node)
        return node_id

    def add_semantic_node(
        self,
        bbox: BoundingBox,
        detector_class: DetectorClass,
        evidence: DetectorEvidence,
        risk: RiskLevel = RiskLevel.HIGH,
        notes: str = "",
    ) -> str:
        node_id = self._next_id("semantic")
        node = PrivacyNode(
            node_id=node_id,
            layer=LayerType.SEMANTIC,
            risk_level=risk,
            evidence=[evidence],
            description=f"Semantic: {detector_class.value}",
        )
        self._nodes.append(node)
        return node_id

    def add_provenance_node(
        self,
        description: str,
        risk: RiskLevel = RiskLevel.LOW,
    ) -> str:
        node_id = self._next_id("provenance")
        node = PrivacyNode(
            node_id=node_id,
            layer=LayerType.PROVENANCE,
            risk_level=risk,
            description=f"Provenance: {description}",
        )
        self._nodes.append(node)
        return node_id

    def build(self) -> PrivacyGraph:
        import hashlib, json
        graph = PrivacyGraph(raw_source_hash=self._raw_source_hash)
        for node in self._nodes:
            graph.add_node(node)
        canonical = json.dumps(graph.to_dict(), sort_keys=True, ensure_ascii=True)
        graph.graph_hash = hashlib.sha256(canonical.encode()).hexdigest()
        return graph
