"""
Unit tests for veilframe.folder.rules (RuleRegistry, precedence, and rule models).
"""

import unittest

from veilframe.folder.models.classification import AIAction, FileCategory, RulePriority
from veilframe.folder.rules.precedence import PRECEDENCE_ORDER, resolve_highest_precedence
from veilframe.folder.rules.registry import RuleRegistry
from veilframe.folder.rules.rule_model import Rule


class TestRules(unittest.TestCase):
    def setUp(self):
        self.registry = RuleRegistry()
        self.registry.load_builtin_rules()

    def test_builtin_rules_loaded(self):
        # We loaded over 100 rules across ecosystems and global definitions
        self.assertGreater(len(self.registry.rules), 50)
        self.assertIn("python", self.registry.ecosystem_index)
        self.assertIn("node", self.registry.ecosystem_index)
        self.assertIn("rust", self.registry.ecosystem_index)

    def test_precedence_hierarchy(self):
        # Verify 9-tier precedence hierarchy order
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.SECURITY],
            PRECEDENCE_ORDER[RulePriority.USER_OVERRIDE],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.USER_OVERRIDE],
            PRECEDENCE_ORDER[RulePriority.GITIGNORE],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.GITIGNORE],
            PRECEDENCE_ORDER[RulePriority.ECOSYSTEM],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.ECOSYSTEM],
            PRECEDENCE_ORDER[RulePriority.FRAMEWORK],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.FRAMEWORK],
            PRECEDENCE_ORDER[RulePriority.TOOL_IDE],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.TOOL_IDE],
            PRECEDENCE_ORDER[RulePriority.GENERATED_DETECTOR],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.GENERATED_DETECTOR],
            PRECEDENCE_ORDER[RulePriority.FILE_TYPE],
        )
        self.assertLess(
            PRECEDENCE_ORDER[RulePriority.FILE_TYPE],
            PRECEDENCE_ORDER[RulePriority.DEFAULT],
        )

    def test_rule_matching(self):
        r = Rule(
            id="test.pyc",
            ecosystems={"python"},
            patterns=["*.pyc", "__pycache__/"],
            classification=FileCategory.CACHE,
            action=AIAction.EXCLUDE,
            priority=RulePriority.ECOSYSTEM,
        )
        self.assertTrue(r.matches("__pycache__/foo.pyc"))
        self.assertTrue(r.matches("build/__pycache__/"))
        self.assertFalse(r.matches("src/main.py"))

    def test_find_matching_rules(self):
        matches = self.registry.find_matching_rules("node_modules/express/index.js", ecosystems={"node"})
        self.assertTrue(any(m.action == AIAction.EXCLUDE for m in matches))

    def test_resolve_highest_precedence(self):
        r_sec = Rule(
            id="sec.env",
            patterns=[".env"],
            classification=FileCategory.SECRET,
            action=AIAction.WARN,
            priority=RulePriority.SECURITY,
        )
        r_eco = Rule(
            id="eco.config",
            patterns=[".env"],
            classification=FileCategory.CONFIG,
            action=AIAction.INCLUDE,
            priority=RulePriority.ECOSYSTEM,
        )
        best = resolve_highest_precedence([r_eco, r_sec])
        self.assertEqual(best, r_sec)


if __name__ == "__main__":
    unittest.main()
