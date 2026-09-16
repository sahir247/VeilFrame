"""
Unit tests for veilframe.folder.classification.scoring and ai_bundle file selection.
"""

import unittest

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.file_selector import FileSelector
from veilframe.folder.classification.scoring import calculate_priority_score
from veilframe.folder.models.classification import AIAction, ClassificationResult, FileCategory
from veilframe.folder.models.file_record import FileRecord


class TestAIPriority(unittest.TestCase):
    def test_priority_hierarchy(self):
        # Entry point -> 100
        f_entry = FileRecord(
            name="main.py", relative_path="main.py", is_entry_point=True,
            classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE)
        )
        score_entry = calculate_priority_score(f_entry)
        self.assertEqual(score_entry, 100)

        # README -> 98
        f_readme = FileRecord(
            name="README.md", relative_path="README.md",
            classification=ClassificationResult(category=FileCategory.DOCUMENTATION, action=AIAction.INCLUDE)
        )
        score_readme = calculate_priority_score(f_readme)
        self.assertEqual(score_readme, 98)

        # Manifest -> 95
        f_manifest = FileRecord(
            name="pyproject.toml", relative_path="pyproject.toml",
            classification=ClassificationResult(category=FileCategory.MANIFEST, action=AIAction.INCLUDE)
        )
        score_manifest = calculate_priority_score(f_manifest)
        self.assertEqual(score_manifest, 95)

        # Dependency/Cache -> 0
        f_dep = FileRecord(
            name="bundle.js", relative_path="node_modules/express/bundle.js",
            classification=ClassificationResult(category=FileCategory.DEPENDENCY, action=AIAction.EXCLUDE)
        )
        score_dep = calculate_priority_score(f_dep)
        self.assertEqual(score_dep, 0)

    def test_knapsack_file_selector(self):
        config = BundleConfig(target_tokens=500)
        selector = FileSelector(config)

        f1 = FileRecord(name="main.py", relative_path="main.py", is_entry_point=True, token_count=100,
                        classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE))
        f2 = FileRecord(name="README.md", relative_path="README.md", token_count=100,
                        classification=ClassificationResult(category=FileCategory.DOCUMENTATION, action=AIAction.INCLUDE))
        f3 = FileRecord(name="huge.py", relative_path="huge.py", token_count=600,
                        classification=ClassificationResult(category=FileCategory.SOURCE, action=AIAction.INCLUDE))

        included, excluded, tokens = selector.select_files([f1, f2, f3])
        self.assertIn(f1, included)
        self.assertIn(f2, included)
        self.assertIn(f3, [f for f, _ in excluded])
        self.assertEqual(tokens, 200)


if __name__ == "__main__":
    unittest.main()
