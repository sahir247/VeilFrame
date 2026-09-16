"""
veilframe.folder.project_context.gitignore_parser — Semantic .gitignore parser with 'WHY' classification.
"""

from __future__ import annotations

import fnmatch
import os
from enum import Enum
from typing import List, Optional, Tuple

from veilframe.folder.models.classification import AIAction, FileCategory


try:
    from pathspec import PathSpec
    HAVE_PATHSPEC = True
except ImportError:
    HAVE_PATHSPEC = False


class GitIgnoreReason(str, Enum):
    CACHE_OR_BUILD = "CACHE_OR_BUILD"
    SECRET_OR_KEY = "SECRET_OR_KEY"
    GENERATED = "GENERATED"
    LOCAL_CONFIG = "LOCAL_CONFIG"
    GENERIC = "GENERIC"


IgnoreReason = GitIgnoreReason


class GitIgnoreParser:
    """Parses .gitignore patterns and categorizes why ignored files were excluded from git."""

    def __init__(self, root_path: str = "") -> None:
        self.root_path = root_path
        self.rules: List[Tuple[bool, str]] = []  # (is_negation, pattern)
        self.raw_lines: List[str] = []
        self._pathspec: Optional[PathSpec] = None
        if root_path and os.path.exists(os.path.join(root_path, ".gitignore")):
            self._load_gitignore(os.path.join(root_path, ".gitignore"))

    def _rebuild_pathspec(self) -> None:
        if HAVE_PATHSPEC and self.raw_lines:
            try:
                self._pathspec = PathSpec.from_lines("gitignore", self.raw_lines)
            except Exception:
                try:
                    self._pathspec = PathSpec.from_lines("gitwildmatch", self.raw_lines)
                except Exception:
                    self._pathspec = None

    def add_rules_from_text(self, text: str) -> None:
        for line in text.splitlines():
            line_str = line.strip()
            if not line_str or line_str.startswith("#"):
                continue
            self.raw_lines.append(line_str)
            is_neg = False
            if line_str.startswith("!"):
                is_neg = True
                line_str = line_str[1:]
            self.rules.append((is_neg, line_str))
        self._rebuild_pathspec()

    def _load_gitignore(self, gitignore_path: str) -> None:
        if not os.path.exists(gitignore_path):
            return

        try:
            with open(gitignore_path, "r", encoding="utf-8", errors="ignore") as f:
                for line in f:
                    line_str = line.strip()
                    if not line_str or line_str.startswith("#"):
                        continue
                    self.raw_lines.append(line_str)
                    is_neg = False
                    if line_str.startswith("!"):
                        is_neg = True
                        line_str = line_str[1:]

                    self.rules.append((is_neg, line_str))
            self._rebuild_pathspec()
        except OSError:
            pass

    def is_ignored(self, rel_path: str, is_dir: bool = False) -> bool:
        """Test if a path is excluded under gitignore rules."""
        norm = rel_path.replace("\\", "/").strip("/")
        if not norm:
            return False

        # If pathspec is available, use gitwildmatch standard
        if self._pathspec is not None:
            # Check directory path if is_dir or if physically a directory
            if is_dir or (self.root_path and os.path.isdir(os.path.join(self.root_path, norm))):
                if self._pathspec.match_file(norm + "/"):
                    return True
            if self._pathspec.match_file(norm):
                return True
            # Check if any parent folder matches directory pattern
            parts = norm.split("/")
            accum = ""
            for p in parts[:-1]:
                accum = f"{accum}/{p}" if accum else p
                if self._pathspec.match_file(accum + "/"):
                    return True
            return False

        # Fallback to fnmatch if pathspec is unavailable
        ignored = False
        path_segments = norm.split("/")
        basename = path_segments[-1]

        for is_neg, pat in self.rules:
            pat_clean = pat.rstrip("/")
            is_dir_pat = pat.endswith("/")

            if is_dir_pat and not is_dir and pat_clean not in path_segments:
                continue

            matches = False
            if fnmatch.fnmatch(norm, pat_clean) or fnmatch.fnmatch(basename, pat_clean):
                matches = True
            elif any(fnmatch.fnmatch(seg, pat_clean) for seg in path_segments):
                matches = True

            if matches:
                ignored = not is_neg

        return ignored

    def evaluate_reason(
        self,
        rel_path: str,
        is_dir: bool = False,
    ) -> Tuple[bool, Optional[GitIgnoreReason], AIAction, FileCategory]:
        """
        Ask WHY Git ignored this file:
        - Cache or build artifact? -> EXCLUDE
        - Secret / key? -> WARN
        - Generated artifact? -> EXCLUDE
        - Useful local config? -> SELECTIVE_INCLUDE
        """
        if not self.is_ignored(rel_path, is_dir):
            return False, None, AIAction.INCLUDE, FileCategory.UNKNOWN

        lower = rel_path.lower()
        base = os.path.basename(lower)

        # 1. Secret or Key check
        if ".env" in base or any(ext in base for ext in (".key", ".pem", ".p12", ".secret")):
            return True, GitIgnoreReason.SECRET_OR_KEY, AIAction.WARN, FileCategory.SECRET

        # 2. Local config check (.vscode, .idea, local configs)
        if (
            ".local" in base
            or "local_settings" in base
            or "config.local" in base
            or ".vscode" in lower
            or ".idea" in lower
        ):
            return True, GitIgnoreReason.LOCAL_CONFIG, AIAction.SELECTIVE_INCLUDE, FileCategory.CONFIG

        # 3. Cache or build check
        if any(w in lower for w in ("cache", "build", "dist", "target", "node_modules", "venv", "tmp")):
            return True, GitIgnoreReason.CACHE_OR_BUILD, AIAction.EXCLUDE, FileCategory.BUILD_OUTPUT

        # 4. Generated check
        if any(base.endswith(ext) for ext in (".pyc", ".o", ".obj", ".class", ".min.js", ".tsbuildinfo")):
            return True, GitIgnoreReason.GENERATED, AIAction.EXCLUDE, FileCategory.GENERATED

        return True, GitIgnoreReason.GENERIC, AIAction.EXCLUDE, FileCategory.UNKNOWN

    def get_reason(self, rel_path: str, is_dir: bool = False) -> Optional[GitIgnoreReason]:
        """Convenience method returning just the GitIgnoreReason or None."""
        _, reason, _, _ = self.evaluate_reason(rel_path, is_dir=is_dir)
        return reason


def parse_gitignore(content_or_path: str) -> GitIgnoreParser:
    """Parse gitignore rules from either a file path or a string with newline-separated patterns."""
    if os.path.exists(content_or_path) and os.path.isdir(content_or_path):
        return GitIgnoreParser(content_or_path)
    if os.path.exists(content_or_path) and os.path.isfile(content_or_path):
        parser = GitIgnoreParser()
        parser._load_gitignore(content_or_path)
        return parser
    parser = GitIgnoreParser()
    parser.add_rules_from_text(content_or_path)
    return parser
