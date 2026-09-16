"""
veilframe.folder.models.classification — Classification taxonomy, AI policy actions, and result models.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set


class FileCategory(str, Enum):
    """Universal classification taxonomy for files and directories across all software ecosystems."""
    SOURCE = "SOURCE"
    CONFIG = "CONFIG"
    MANIFEST = "MANIFEST"
    DOCUMENTATION = "DOCUMENTATION"
    TEST = "TEST"
    SCRIPT = "SCRIPT"
    CI_CD = "CI_CD"
    IDE_CONFIG = "IDE_CONFIG"
    VCS_CONFIG = "VCS_CONFIG"
    DEPENDENCY = "DEPENDENCY"
    VENDOR = "VENDOR"
    CACHE = "CACHE"
    BUILD_OUTPUT = "BUILD_OUTPUT"
    GENERATED = "GENERATED"
    COMPILED = "COMPILED"
    RUNTIME_DATA = "RUNTIME_DATA"
    MEDIA = "MEDIA"
    DATABASE = "DATABASE"
    DATASET = "DATASET"
    MODEL = "MODEL"
    BINARY = "BINARY"
    SECRET = "SECRET"
    CREDENTIAL = "CREDENTIAL"
    PRIVATE_KEY = "PRIVATE_KEY"
    UNKNOWN = "UNKNOWN"


class AIAction(str, Enum):
    """Default AI handling policy applied to each classified entity."""
    INCLUDE = "INCLUDE"
    EXCLUDE = "EXCLUDE"
    WARN = "WARN"
    DESCRIBE = "DESCRIBE"
    ANALYZE = "ANALYZE"
    SELECTIVE_INCLUDE = "SELECTIVE_INCLUDE"
    REDACT = "REDACT"


class RulePriority(int, Enum):
    """Strict precedence levels for rule matching and evaluation."""
    SECURITY = 100
    USER_OVERRIDE = 90
    GITIGNORE = 80
    ECOSYSTEM = 70
    FRAMEWORK = 60
    TOOL_IDE = 50
    GENERATED = 40
    GENERATED_DETECTOR = 40
    FILE_TYPE = 30
    DEFAULT = 10


DEFAULT_CATEGORY_ACTIONS: Dict[FileCategory, AIAction] = {
    FileCategory.SOURCE: AIAction.INCLUDE,
    FileCategory.CONFIG: AIAction.INCLUDE,
    FileCategory.MANIFEST: AIAction.INCLUDE,
    FileCategory.DOCUMENTATION: AIAction.INCLUDE,
    FileCategory.TEST: AIAction.INCLUDE,
    FileCategory.SCRIPT: AIAction.INCLUDE,
    FileCategory.CI_CD: AIAction.INCLUDE,
    FileCategory.VCS_CONFIG: AIAction.INCLUDE,
    FileCategory.IDE_CONFIG: AIAction.SELECTIVE_INCLUDE,
    FileCategory.VENDOR: AIAction.EXCLUDE,
    FileCategory.DEPENDENCY: AIAction.EXCLUDE,
    FileCategory.CACHE: AIAction.EXCLUDE,
    FileCategory.BUILD_OUTPUT: AIAction.EXCLUDE,
    FileCategory.GENERATED: AIAction.EXCLUDE,
    FileCategory.COMPILED: AIAction.EXCLUDE,
    FileCategory.RUNTIME_DATA: AIAction.EXCLUDE,
    FileCategory.MEDIA: AIAction.DESCRIBE,
    FileCategory.DATABASE: AIAction.DESCRIBE,
    FileCategory.DATASET: AIAction.DESCRIBE,
    FileCategory.MODEL: AIAction.DESCRIBE,
    FileCategory.BINARY: AIAction.DESCRIBE,
    FileCategory.SECRET: AIAction.WARN,
    FileCategory.CREDENTIAL: AIAction.WARN,
    FileCategory.PRIVATE_KEY: AIAction.WARN,
    FileCategory.UNKNOWN: AIAction.ANALYZE,
}


def get_default_action(category: FileCategory | str) -> AIAction:
    """Resolve the default AI policy action for a given file category."""
    if isinstance(category, str):
        try:
            category = FileCategory(category.upper())
        except ValueError:
            return AIAction.ANALYZE
    return DEFAULT_CATEGORY_ACTIONS.get(category, AIAction.ANALYZE)


@dataclass
class ClassificationResult:
    """Rich classification result returned by the rule registry and classifier."""
    category: FileCategory = FileCategory.UNKNOWN
    action: AIAction = AIAction.ANALYZE
    confidence: float = 0.5
    reason: str = "Default classification"
    rule_id: str = "default"
    priority: RulePriority = RulePriority.DEFAULT
    tags: Set[str] = field(default_factory=set)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "category": self.category.value,
            "action": self.action.value,
            "confidence": round(self.confidence, 3),
            "reason": self.reason,
            "rule_id": self.rule_id,
            "priority": self.priority.value,
            "tags": sorted(list(self.tags)),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ClassificationResult:
        cat_val = data.get("category", "UNKNOWN")
        try:
            category = FileCategory(cat_val)
        except ValueError:
            category = FileCategory.UNKNOWN

        act_val = data.get("action", "ANALYZE")
        try:
            action = AIAction(act_val)
        except ValueError:
            action = AIAction.ANALYZE

        pri_val = data.get("priority", 10)
        try:
            priority = RulePriority(pri_val)
        except ValueError:
            priority = RulePriority.DEFAULT

        return cls(
            category=category,
            action=action,
            confidence=float(data.get("confidence", 0.5)),
            reason=str(data.get("reason", "")),
            rule_id=str(data.get("rule_id", "default")),
            priority=priority,
            tags=set(data.get("tags", [])),
        )
