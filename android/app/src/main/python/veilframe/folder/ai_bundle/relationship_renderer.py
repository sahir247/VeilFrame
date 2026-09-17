"""
veilframe.folder.ai_bundle.relationship_renderer — Formats dependency graph and module relationships.
"""

from __future__ import annotations

from typing import Dict, List, Optional

from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.project_context.project_graph import ProjectGraph


def render_project_relationships(
    files: List[FileRecord],
    graph: Optional[ProjectGraph] = None,
    max_hubs: int = 10,
    max_links: int = 40,
) -> str:
    """Render module relationships, hub dependencies, and architectural links."""
    if not graph:
        # Build graph on the fly if not supplied
        graph = ProjectGraph()
        graph.build_from_files(files)

    if not graph.nodes:
        return "No intra-project import relationships detected."

    lines: List[str] = []

    # 1. Hub modules (most imported)
    hub_scores = graph.get_hub_scores()
    top_hubs = [
        (path, score) for path, score in sorted(hub_scores.items(), key=lambda x: -x[1])
        if score > 0
    ][:max_hubs]

    if top_hubs:
        lines.append("CORE HUB MODULES (most imported across project):")
        for path, score in top_hubs:
            dependents = graph.nodes[path].imported_by
            lines.append(f"  • {path} (imported by {len(dependents)} modules)")
        lines.append("")

    # 2. Key internal dependency links
    lines.append("INTERNAL IMPORT LINKS:")
    link_count = 0
    for path, node in sorted(graph.nodes.items()):
        if node.imports:
            internal_imports = [imp for imp in sorted(node.imports) if imp in graph.nodes]
            if internal_imports:
                lines.append(f"  {path}")
                for idx, imp in enumerate(internal_imports[:6]):
                    is_last = (idx == len(internal_imports[:6]) - 1)
                    branch = "└──" if is_last else "├──"
                    lines.append(f"   {branch} {imp} [INTERNAL]")
                link_count += 1
                if link_count >= max_links:
                    lines.append(f"  ... ({len(graph.nodes) - link_count} additional module links omitted)")
                    break

    # 3. Circular dependencies
    cycles = graph.detect_circular_dependencies()
    if cycles:
        lines.append("\nCIRCULAR DEPENDENCIES DETECTED:")
        for cyc in cycles[:5]:
            lines.append(f"  ⚠️  {' -> '.join(cyc)}")

    return "\n".join(lines) if lines else "No internal module imports discovered."
