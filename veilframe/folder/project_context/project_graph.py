"""
veilframe.folder.project_context.project_graph — Directed dependency and import graph with centrality metrics.
"""

from __future__ import annotations

import os
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set


@dataclass
class GraphNode:
    """Node in the project dependency graph representing a file."""
    rel_path: str
    in_degree: int = 0
    out_degree: int = 0
    is_entry_point: bool = False
    imports: Set[str] = field(default_factory=set)
    imported_by: Set[str] = field(default_factory=set)


class ProjectGraph:
    """Directed graph capturing file linkages, module imports, and topological centrality."""

    def __init__(self) -> None:
        self.nodes: Dict[str, GraphNode] = {}
        # Mapping from module name / stem to full relative path
        self._module_to_path: Dict[str, str] = {}

    def add_file(self, rel_path: str, is_entry_point: bool = False) -> GraphNode:
        norm = rel_path.replace("\\", "/").strip("/")
        if norm not in self.nodes:
            self.nodes[norm] = GraphNode(rel_path=norm, is_entry_point=is_entry_point)

            # Map file stem (e.g. "scanner" or "veilframe.folder.scanner")
            stem, _ = os.path.splitext(norm)
            self._module_to_path[os.path.basename(stem).lower()] = norm
            self._module_to_path[stem.replace("/", ".").lower()] = norm

        if is_entry_point:
            self.nodes[norm].is_entry_point = True

        return self.nodes[norm]

    def add_import(self, source_rel: str, imported_target: str) -> None:
        """Add an import reference from source_rel to an imported target."""
        source_norm = source_rel.replace("\\", "/").strip("/")
        src_node = self.add_file(source_norm)

        # Resolve imported target to a known project node if possible
        target_clean = imported_target.replace("\\", "/").strip("./'\"").lower()
        resolved_norm = self._module_to_path.get(target_clean) or self._module_to_path.get(os.path.basename(target_clean))

        if resolved_norm and resolved_norm in self.nodes and resolved_norm != source_norm:
            src_node.imports.add(resolved_norm)
            src_node.out_degree = len(src_node.imports)

            tgt_node = self.nodes[resolved_norm]
            tgt_node.imported_by.add(source_norm)
            tgt_node.in_degree = len(tgt_node.imported_by)

    def get_centrality_score(self, rel_path: str) -> int:
        """
        Calculate an importance multiplier (0 - 15 points) based on graph in-degree.
        Files imported by many other files are critical project hubs.
        """
        norm = rel_path.replace("\\", "/").strip("/")
        node = self.nodes.get(norm)
        if not node:
            return 0
        # Logarithmic or linear saturation: 1 import = +3, 3 imports = +6, 5+ = +10, 10+ = +15
        if node.in_degree >= 10:
            return 15
        elif node.in_degree >= 5:
            return 10
        elif node.in_degree >= 3:
            return 6
        elif node.in_degree >= 1:
            return 3
        return 0

    def build_from_files(self, files: List[Any]) -> None:
        """Build graph nodes and import edges from a list of FileRecord instances."""
        from veilframe.folder.project_context.import_analyzer import extract_imports

        for f in files:
            self.add_file(f.relative_path, is_entry_point=getattr(f, "is_entry_point", False))

        for f in files:
            imports = getattr(f, "imports", None)
            if imports is None and os.path.isfile(f.path):
                imports = extract_imports(f.path, getattr(f, "language", ""))
            if imports:
                for imp in imports:
                    self.add_import(f.relative_path, imp)

    def get_hub_scores(self) -> Dict[str, int]:
        """Return dict of {rel_path: in_degree}."""
        return {n.rel_path: n.in_degree for n in self.nodes.values()}

    def detect_circular_dependencies(self) -> List[List[str]]:
        """Detect circular import cycles using DFS."""
        cycles: List[List[str]] = []
        visited: Set[str] = set()
        rec_stack: List[str] = []

        def _dfs(curr: str) -> None:
            visited.add(curr)
            rec_stack.append(curr)
            node = self.nodes.get(curr)
            if node:
                for nxt in sorted(node.imports):
                    if nxt not in visited:
                        _dfs(nxt)
                    elif nxt in rec_stack:
                        idx = rec_stack.index(nxt)
                        cycle = rec_stack[idx:] + [nxt]
                        if cycle not in cycles:
                            cycles.append(cycle)
            rec_stack.pop()

        for path in sorted(self.nodes.keys()):
            if path not in visited:
                _dfs(path)

        return cycles

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_nodes": len(self.nodes),
            "entry_points": [n.rel_path for n in self.nodes.values() if n.is_entry_point],
            "hub_modules": [
                {"path": n.rel_path, "in_degree": n.in_degree}
                for n in sorted(self.nodes.values(), key=lambda x: x.in_degree, reverse=True)[:10]
                if n.in_degree > 0
            ],
        }
