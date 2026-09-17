"""
veilframe.folder.project_context — Project intelligence, ecosystem discovery, and dependency graphs.
"""

from veilframe.folder.project_context.dependency_analyzer import (
    analyze_vendor_status,
)
from veilframe.folder.project_context.ecosystem_detector import (
    detect_ecosystems,
)
from veilframe.folder.project_context.gitignore_parser import (
    GitIgnoreParser,
    GitIgnoreReason,
)
from veilframe.folder.project_context.import_analyzer import extract_imports
from veilframe.folder.project_context.manifest_parser import (
    ParsedManifest,
    parse_manifest,
)
from veilframe.folder.project_context.project_detector import (
    ProjectDetector,
    SubProject,
)
from veilframe.folder.project_context.project_graph import (
    GraphNode,
    ProjectGraph,
)

__all__ = [
    "detect_ecosystems",
    "ProjectDetector",
    "SubProject",
    "ParsedManifest",
    "parse_manifest",
    "GitIgnoreParser",
    "GitIgnoreReason",
    "extract_imports",
    "analyze_vendor_status",
    "ProjectGraph",
    "GraphNode",
]
