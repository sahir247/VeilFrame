"""
veilframe.folder.ai_bundle.tree_renderer — Formatted Unicode directory tree renderer for project bundles.
"""

from __future__ import annotations

import os
from typing import Dict, List, Optional, Set

from veilframe.folder.models.file_record import FileRecord


class TreeNode:
    """Represents a directory or file node in the hierarchical tree."""

    def __init__(
        self,
        name: str,
        is_dir: bool = False,
        file_rec: Optional[FileRecord] = None,
        is_collapsed_dir: bool = False,
        exclusion_reason: Optional[str] = None,
    ):
        self.name = name
        self.is_dir = is_dir
        self.file_rec = file_rec
        self.is_collapsed_dir = is_collapsed_dir
        self.exclusion_reason = exclusion_reason
        self.children: Dict[str, TreeNode] = {}

    def add_child(
        self,
        path_parts: List[str],
        file_rec: Optional[FileRecord] = None,
        is_collapsed_dir: bool = False,
        exclusion_reason: Optional[str] = None,
    ) -> None:
        if not path_parts:
            return
        part = path_parts[0]
        if len(path_parts) == 1:
            if is_collapsed_dir:
                if part not in self.children:
                    self.children[part] = TreeNode(
                        part, is_dir=True, is_collapsed_dir=True, exclusion_reason=exclusion_reason
                    )
            else:
                # Leaf node (file)
                if part not in self.children:
                    self.children[part] = TreeNode(
                        part, is_dir=False, file_rec=file_rec, exclusion_reason=exclusion_reason
                    )
        else:
            # Directory node
            if part not in self.children:
                self.children[part] = TreeNode(part, is_dir=True)
            self.children[part].add_child(
                path_parts[1:],
                file_rec=file_rec,
                is_collapsed_dir=is_collapsed_dir,
                exclusion_reason=exclusion_reason,
            )


def build_project_tree(
    files: List[FileRecord],
    excluded_files: Optional[List[Tuple[FileRecord, str]]] = None,
    max_depth: int = 6,
    max_files: int = 500,
    include_badges: bool = True,
) -> str:
    """
    Render a clean Unicode hierarchical directory tree with collapsed excluded directories.
    Args:
        files: List of FileRecord objects to include in the tree.
        excluded_files: Optional list of (FileRecord, reason) excluded from context.
        max_depth: Maximum directory nesting depth to render.
        max_files: Max total files to render before collapsing.
        include_badges: Whether to append category badges like [SOURCE].
    """
    root = TreeNode(".", is_dir=True)

    # 1. Add all included files
    included_dir_prefixes: Set[str] = set()
    for f in files:
        parts = f.relative_path.replace("\\", "/").strip("/").split("/")
        accum = ""
        for p in parts[:-1]:
            accum = f"{accum}/{p}" if accum else p
            included_dir_prefixes.add(accum)
        root.add_child(parts, f)

    # 2. Add collapsed excluded directories and loose excluded files
    if excluded_files:
        excluded_dirs: Dict[str, str] = {}
        loose_excluded: List[Tuple[FileRecord, str]] = []

        for f, reason in excluded_files:
            parts = f.relative_path.replace("\\", "/").strip("/").split("/")
            if len(parts) > 1:
                top_part = parts[0]
                if top_part not in included_dir_prefixes:
                    if top_part not in excluded_dirs:
                        excluded_dirs[top_part] = reason
                else:
                    accum = parts[0]
                    found = False
                    for p in parts[1:-1]:
                        accum = f"{accum}/{p}"
                        if accum not in included_dir_prefixes:
                            if accum not in excluded_dirs:
                                excluded_dirs[accum] = reason
                            found = True
                            break
                    if not found and len(loose_excluded) < 20:
                        loose_excluded.append((f, reason))
            else:
                if len(loose_excluded) < 20:
                    loose_excluded.append((f, reason))

        # Add collapsed directory nodes
        for dir_path, reason in excluded_dirs.items():
            parts = dir_path.split("/")
            root.add_child(parts, is_collapsed_dir=True, exclusion_reason=reason)

        # Add loose excluded files (e.g. .env)
        for f, reason in loose_excluded:
            parts = f.relative_path.replace("\\", "/").strip("/").split("/")
            root.add_child(parts, file_rec=f, exclusion_reason=reason)

    rendered_lines: List[str] = ["."]
    rendered_count = [0]

    def _walk(node: TreeNode, prefix: str, depth: int) -> None:
        if depth > max_depth or rendered_count[0] >= max_files:
            return

        sorted_children = sorted(
            node.children.values(),
            key=lambda c: (not c.is_dir, c.name.lower())
        )
        total = len(sorted_children)
        for i, child in enumerate(sorted_children):
            if rendered_count[0] >= max_files:
                rendered_lines.append(f"{prefix}... (remaining tree collapsed)")
                break

            is_last = (i == total - 1)
            connector = "└── " if is_last else "├── "
            child_prefix = prefix + ("    " if is_last else "│   ")

            if child.is_collapsed_dir:
                reason_str = child.exclusion_reason or "excluded"
                rendered_lines.append(f"{prefix}{connector}{child.name}/  [EXCLUDED: {reason_str}]")
                rendered_count[0] += 1
            elif child.is_dir:
                rendered_lines.append(f"{prefix}{connector}{child.name}/")
                _walk(child, child_prefix, depth + 1)
            else:
                badge = ""
                if child.exclusion_reason:
                    badge = f"  [EXCLUDED: {child.exclusion_reason}]"
                elif include_badges and child.file_rec:
                    cat = child.file_rec.effective_category.value
                    if child.file_rec.is_entry_point:
                        badge = "  [ENTRY]"
                    else:
                        badge = f"  [{cat}]"
                rendered_lines.append(f"{prefix}{connector}{child.name}{badge}")
                rendered_count[0] += 1

    _walk(root, "", 1)
    return "\n".join(rendered_lines)
