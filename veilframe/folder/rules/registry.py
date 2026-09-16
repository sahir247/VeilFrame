"""
veilframe.folder.rules.registry — Extensible rule registry and fast matching engine.
"""

from __future__ import annotations

import os
from typing import Dict, List, Optional, Set

from veilframe.folder.models.classification import (
    AIAction,
    ClassificationResult,
    FileCategory,
    RulePriority,
)
from veilframe.folder.rules.precedence import resolve_highest_precedence
from veilframe.folder.rules.rule_loader import load_rules_from_dir
from veilframe.folder.rules.rule_model import Rule


class RuleRegistry:
    """Central repository of extensible classification rules across ecosystems."""

    _instance: Optional[RuleRegistry] = None

    def __init__(self, auto_load_builtin: bool = True) -> None:
        self._rules: List[Rule] = []
        self._rules_by_ecosystem: Dict[str, List[Rule]] = {}
        self._rules_by_id: Dict[str, Rule] = {}

        if auto_load_builtin:
            self.load_builtin_rules()

    @property
    def rules(self) -> List[Rule]:
        return list(self._rules)

    @property
    def ecosystem_index(self) -> Dict[str, List[Rule]]:
        return self._rules_by_ecosystem

    def find_matching_rules(
        self,
        rel_path: str,
        is_dir: bool = False,
        ecosystems: Optional[Set[str]] = None,
    ) -> List[Rule]:
        applicable: Set[Rule] = set()
        applicable.update(self._rules_by_ecosystem.get("*", []))
        for eco in (ecosystems or set()):
            applicable.update(self._rules_by_ecosystem.get(eco.lower(), []))
        return [r for r in applicable if r.matches(rel_path, is_dir, ecosystems)]

    @classmethod
    def get_default(cls) -> RuleRegistry:
        """Singleton accessor for shared default registry."""
        if cls._instance is None:
            cls._instance = RuleRegistry(auto_load_builtin=True)
        return cls._instance

    def register_rule(self, rule: Rule) -> None:
        """Register a new or updated rule into the registry."""
        # Replace existing rule with same ID if present
        if rule.id in self._rules_by_id:
            old_rule = self._rules_by_id[rule.id]
            if old_rule in self._rules:
                self._rules.remove(old_rule)

        self._rules.append(rule)
        self._rules_by_id[rule.id] = rule

        for eco in rule.ecosystems:
            eco_lower = eco.lower()
            if eco_lower not in self._rules_by_ecosystem:
                self._rules_by_ecosystem[eco_lower] = []
            self._rules_by_ecosystem[eco_lower].append(rule)

    def load_builtin_rules(self) -> None:
        """Load all built-in YAML rule definition files bundled with the package."""
        rules_dir = os.path.dirname(__file__)
        builtin_dir = os.path.join(rules_dir, "builtin")
        global_dir = os.path.join(rules_dir, "global")

        if os.path.exists(builtin_dir):
            for rule in load_rules_from_dir(builtin_dir):
                self.register_rule(rule)

        if os.path.exists(global_dir):
            for rule in load_rules_from_dir(global_dir):
                self.register_rule(rule)

    def load_custom_rules_dir(self, directory_path: str) -> int:
        """Load user-defined or project-specific rules from an external directory."""
        if not os.path.exists(directory_path):
            return 0
        loaded = load_rules_from_dir(directory_path)
        for rule in loaded:
            self.register_rule(rule)
        return len(loaded)

    def classify(
        self,
        rel_path: str,
        is_dir: bool,
        project_ecosystems: Set[str],
    ) -> ClassificationResult:
        """
        Match the relative path against all applicable rules (global + active ecosystems)
        and return the single highest-precedence ClassificationResult.
        """
        candidates: List[ClassificationResult] = []

        # Candidate pool: rules for '*' plus rules for active project ecosystems
        applicable_rules: Set[Rule] = set()
        applicable_rules.update(self._rules_by_ecosystem.get("*", []))

        for eco in project_ecosystems:
            applicable_rules.update(self._rules_by_ecosystem.get(eco.lower(), []))

        # Check each rule
        for rule in applicable_rules:
            if rule.matches(rel_path, is_dir, project_ecosystems):
                candidates.append(rule.to_classification_result())

        if candidates:
            return resolve_highest_precedence(candidates)

        # Fallback default
        return ClassificationResult(
            category=FileCategory.UNKNOWN,
            action=AIAction.ANALYZE,
            confidence=0.5,
            reason="No specific rule matched",
            rule_id="default_fallback",
            priority=RulePriority.DEFAULT,
        )
