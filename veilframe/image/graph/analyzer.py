"""
veilframe.image.graph.analyzer — Risk evaluation & Privacy Residual Profile computation.

Analyzes a PrivacyGraph to:
  1. Determine the aggregate risk profile per layer.
  2. Identify which semantic nodes require CONSTANT_FILL redaction.
  3. Compute a graph_hash for the IdentityPreimage.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..models.graph import LayerType, PrivacyGraph, PrivacyNode, RiskLevel
from ..models.status import DetectorClass


@dataclass
class LayerRiskSummary:
    """Aggregated risk for a single graph layer."""
    layer: LayerType
    node_count: int
    max_risk: RiskLevel
    high_or_critical_count: int

    def to_dict(self) -> dict:
        return {
            "layer": self.layer.value,
            "node_count": self.node_count,
            "max_risk": self.max_risk.value,
            "high_or_critical_count": self.high_or_critical_count,
        }


@dataclass
class GraphAnalysis:
    """Results of analyzing a PrivacyGraph."""
    graph_hash: str
    layer_summaries: Dict[str, LayerRiskSummary] = field(default_factory=dict)
    redaction_required_node_ids: List[str] = field(default_factory=list)
    container_purge_required: bool = False
    has_semantic_detections: bool = False
    has_metadata_risk: bool = False

    def to_dict(self) -> dict:
        return {
            "graph_hash": self.graph_hash,
            "layer_summaries": {k: v.to_dict() for k, v in self.layer_summaries.items()},
            "redaction_required_node_ids": self.redaction_required_node_ids,
            "container_purge_required": self.container_purge_required,
            "has_semantic_detections": self.has_semantic_detections,
            "has_metadata_risk": self.has_metadata_risk,
        }


class GraphAnalyzer:
    """Analyzes a PrivacyGraph and produces a GraphAnalysis."""

    _REDACTION_RISK_THRESHOLD = RiskLevel.MEDIUM

    def analyze(self, graph: PrivacyGraph) -> GraphAnalysis:
        """Analyze the graph and produce risk summaries + redaction targets."""
        graph_hash = self._compute_graph_hash(graph)

        layer_summaries: Dict[str, LayerRiskSummary] = {}
        redaction_node_ids: List[str] = []
        container_risk = False
        semantic_detections = False

        for layer in LayerType:
            layer_nodes = [n for n in graph.nodes.values() if n.layer == layer]
            if not layer_nodes:
                layer_summaries[layer.value] = LayerRiskSummary(
                    layer=layer, node_count=0,
                    max_risk=RiskLevel.NONE, high_or_critical_count=0,
                )
                continue

            risk_order = ["none", "low", "medium", "high", "critical"]
            max_risk = max(layer_nodes, key=lambda n: risk_order.index(n.risk_level.value)).risk_level
            high_count = sum(
                1 for n in layer_nodes
                if n.risk_level in (RiskLevel.HIGH, RiskLevel.CRITICAL)
            )
            layer_summaries[layer.value] = LayerRiskSummary(
                layer=layer,
                node_count=len(layer_nodes),
                max_risk=max_risk,
                high_or_critical_count=high_count,
            )

            if layer in (LayerType.CONTAINER, LayerType.REPRESENTATION) and high_count > 0:
                container_risk = True

            if layer == LayerType.SEMANTIC:
                semantic_detections = len(layer_nodes) > 0
                risk_order_map = {r: i for i, r in enumerate(risk_order)}
                threshold_idx = risk_order_map[self._REDACTION_RISK_THRESHOLD.value]
                for node in layer_nodes:
                    if risk_order_map[node.risk_level.value] >= threshold_idx:
                        redaction_node_ids.append(node.node_id)

        return GraphAnalysis(
            graph_hash=graph_hash,
            layer_summaries=layer_summaries,
            redaction_required_node_ids=redaction_node_ids,
            container_purge_required=container_risk,
            has_semantic_detections=semantic_detections,
            has_metadata_risk=container_risk,
        )

    @staticmethod
    def _compute_graph_hash(graph: PrivacyGraph) -> str:
        """SHA-256 of the canonical graph JSON for the IdentityPreimage."""
        d = graph.to_dict()
        canonical = json.dumps(d, sort_keys=True, ensure_ascii=True)
        return hashlib.sha256(canonical.encode()).hexdigest()
