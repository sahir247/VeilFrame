"""
veilframe.folder.rules.rule_model — Data schema, pattern matching, and validation for ecosystem rules.
"""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from veilframe.folder.models.classification import (
    AIAction,
    ClassificationResult,
    FileCategory,
    RulePriority,
)


@dataclass
class Rule:
    """Individual data-driven rule for classifying files and directories."""
    id: str
    ecosystems: List[str] = field(default_factory=lambda: ["*"])
    patterns: List[str] = field(default_factory=list)
    classification: FileCategory = FileCategory.UNKNOWN
    action: AIAction = AIAction.ANALYZE
    confidence: float = 0.9
    reason: str = ""
    priority: RulePriority = RulePriority.ECOSYSTEM
    extensions: List[str] = field(default_factory=list)
    tags: Set[str] = field(default_factory=set)

    def __hash__(self) -> int:
        return hash(self.id)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Rule):
            return False
        return self.id == other.id

    def matches(
        self,
        rel_path: str,
        is_dir: bool = False,
        project_ecosystems: Optional[Set[str]] = None,
    ) -> bool:
        """Evaluate whether this rule applies to the given relative path and ecosystem context."""
        # 1. Check ecosystem match: rule must apply to '*' or intersect with active project ecosystems
        if "*" not in self.ecosystems:
            if project_ecosystems is None:
                active_lower = {e.lower() for e in self.ecosystems}
            else:
                active_lower = {e.lower() for e in project_ecosystems}
            rule_ecosystems = {e.lower() for e in self.ecosystems}
            if not rule_ecosystems.intersection(active_lower):
                return False

        if not is_dir and (rel_path.endswith("/") or rel_path.endswith("\\")):
            is_dir = True

        # Normalize path representation to forward slashes
        norm_path = rel_path.replace("\\", "/").strip("/")
        if not norm_path:
            return False

        path_segments = norm_path.split("/")
        basename = path_segments[-1]

        # 2. Check extension filter if present
        if self.extensions:
            _, ext = norm_path.rsplit(".", 1) if "." in basename else ("", "")
            ext_with_dot = f".{ext.lower()}" if ext else ""
            if ext_with_dot not in [e.lower() for e in self.extensions]:
                return False

        # 3. Match patterns (directory names, glob patterns, filenames)
        for pat in self.patterns:
            pat_clean = pat.replace("\\", "/").strip()
            is_dir_pat = pat_clean.endswith("/")
            pat_str = pat_clean.rstrip("/")

            # Directory pattern check: matches if any directory segment equals the pattern
            if is_dir_pat:
                if is_dir and any(segment == pat_str for segment in path_segments):
                    return True
                if not is_dir and any(segment == pat_str for segment in path_segments[:-1]):
                    return True

            # Standard glob match against full path or basename
            if fnmatch.fnmatch(norm_path, pat_str):
                return True
            if fnmatch.fnmatch(basename, pat_str):
                return True
            if any(fnmatch.fnmatch(seg, pat_str) for seg in path_segments):
                return True

        return False

    def to_classification_result(self) -> ClassificationResult:
        return ClassificationResult(
            category=self.classification,
            action=self.action,
            confidence=self.confidence,
            reason=self.reason,
            rule_id=self.id,
            priority=self.priority,
            tags=self.tags,
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "ecosystems": self.ecosystems,
            "patterns": self.patterns,
            "classification": self.classification.value,
            "action": self.action.value,
            "confidence": self.confidence,
            "reason": self.reason,
            "priority": self.priority.name,
            "extensions": self.extensions,
            "tags": sorted(list(self.tags)),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> Rule:
        cat_str = data.get("classification", "UNKNOWN")
        try:
            cat = FileCategory(cat_str.upper())
        except ValueError:
            cat = FileCategory.UNKNOWN

        act_str = data.get("action", "ANALYZE")
        try:
            act = AIAction(act_str.upper())
        except ValueError:
            act = AIAction.ANALYZE

        pri_val = data.get("priority", "ECOSYSTEM")
        if isinstance(pri_val, str):
            try:
                pri = RulePriority[pri_val.upper()]
            except KeyError:
                pri = RulePriority.ECOSYSTEM
        elif isinstance(pri_val, int):
            try:
                pri = RulePriority(pri_val)
            except ValueError:
                pri = RulePriority.ECOSYSTEM
        else:
            pri = RulePriority.ECOSYSTEM

        patterns = data.get("patterns", [])
        if isinstance(patterns, str):
            patterns = [patterns]

        ecosystems = data.get("ecosystems", ["*"])
        if isinstance(ecosystems, str):
            ecosystems = [ecosystems]

        extensions = data.get("extensions", [])
        if isinstance(extensions, str):
            extensions = [extensions]

        tags = set(data.get("tags", []))

        return cls(
            id=str(data.get("id", "unnamed")),
            ecosystems=ecosystems,
            patterns=patterns,
            classification=cat,
            action=act,
            confidence=float(data.get("confidence", 0.9)),
            reason=str(data.get("reason", "")),
            priority=pri,
            extensions=extensions,
            tags=tags,
        )
