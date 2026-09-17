"""
veilframe.folder.project_context.project_detector — Project root discovery and monorepo workspace recognition.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import List, Optional, Set


@dataclass
class SubProject:
    """Represents a discovered sub-project or package in a monorepo."""
    name: str
    relative_path: str
    ecosystems: Set[str] = field(default_factory=set)
    manifest_path: Optional[str] = None


class ProjectDetector:
    """Identifies root project metadata and discovers monorepo subprojects."""

    ROOT_INDICATORS = {
        ".git", "pyproject.toml", "package.json", "cargo.toml", "go.mod", "pom.xml"
    }

    MONOREPO_DIRS = {
        "packages", "apps", "modules", "libs", "services", "crates"
    }

    def detect_subprojects(self, root_path: str) -> List[SubProject]:
        """Scan one level into common monorepo directories to find member packages."""
        subprojects: List[SubProject] = []
        if not os.path.isdir(root_path):
            return subprojects

        for member_dir in self.MONOREPO_DIRS:
            parent_dir = os.path.join(root_path, member_dir)
            if not os.path.isdir(parent_dir):
                continue

            try:
                for entry in os.scandir(parent_dir):
                    if entry.is_dir():
                        rel = os.path.relpath(entry.path, root_path)
                        # Check if this subdirectory has its own manifest
                        sub_manifest = None
                        sub_ecosystems: Set[str] = set()

                        for manifest_candidate in ("package.json", "pyproject.toml", "Cargo.toml", "go.mod", "pom.xml"):
                            candidate_path = os.path.join(entry.path, manifest_candidate)
                            if os.path.exists(candidate_path):
                                sub_manifest = os.path.relpath(candidate_path, root_path)
                                if "json" in manifest_candidate:
                                    sub_ecosystems.add("javascript")
                                elif "toml" in manifest_candidate and "cargo" in manifest_candidate.lower():
                                    sub_ecosystems.add("rust")
                                elif "pyproject" in manifest_candidate:
                                    sub_ecosystems.add("python")
                                elif "go" in manifest_candidate:
                                    sub_ecosystems.add("go")
                                elif "pom" in manifest_candidate:
                                    sub_ecosystems.add("java")
                                break

                        subprojects.append(
                            SubProject(
                                name=entry.name,
                                relative_path=rel,
                                ecosystems=sub_ecosystems,
                                manifest_path=sub_manifest,
                            )
                        )
            except OSError:
                continue

        return subprojects
