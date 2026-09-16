"""
veilframe.folder.rules — Extensible rule registry, precedence engine, and built-in rules.
"""

from veilframe.folder.rules.precedence import resolve_highest_precedence
from veilframe.folder.rules.registry import RuleRegistry
from veilframe.folder.rules.rule_loader import (
    load_rule_file,
    load_rules_from_dir,
)
from veilframe.folder.rules.rule_model import Rule

__all__ = [
    "Rule",
    "RuleRegistry",
    "resolve_highest_precedence",
    "load_rule_file",
    "load_rules_from_dir",
]
