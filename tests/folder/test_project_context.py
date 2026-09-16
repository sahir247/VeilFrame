"""
Unit tests for veilframe.folder.project_context (ecosystems, gitignore, project graph).
"""

import os
import tempfile
import unittest

from veilframe.folder.project_context.ecosystem_detector import detect_ecosystems
from veilframe.folder.project_context.gitignore_parser import IgnoreReason, parse_gitignore
from veilframe.folder.project_context.project_graph import ProjectGraph


class TestProjectContext(unittest.TestCase):
    def test_multi_ecosystem_detection(self):
        # Multiple ecosystems simultaneously
        marker_files = [
            "pyproject.toml",
            "package.json",
            "Dockerfile",
            "Cargo.toml",
        ]
        ecosystems = detect_ecosystems(marker_files)
        self.assertIn("python", ecosystems)
        self.assertIn("node", ecosystems)
        self.assertIn("docker", ecosystems)
        self.assertIn("rust", ecosystems)

    def test_semantic_gitignore_parser(self):
        gitignore_text = """
        # Build artifacts
        dist/
        target/
        *.pyc
        # Secrets
        .env
        # Local IDE
        .vscode/settings.json
        """
        parsed = parse_gitignore(gitignore_text)
        
        # Test evaluating WHY a file was ignored
        self.assertTrue(parsed.is_ignored("dist/bundle.js"))
        self.assertEqual(parsed.get_reason("dist/bundle.js"), IgnoreReason.CACHE_OR_BUILD)

        self.assertTrue(parsed.is_ignored(".env"))
        self.assertEqual(parsed.get_reason(".env"), IgnoreReason.SECRET_OR_KEY)

        self.assertTrue(parsed.is_ignored(".vscode/settings.json"))
        self.assertEqual(parsed.get_reason(".vscode/settings.json"), IgnoreReason.LOCAL_CONFIG)

        self.assertFalse(parsed.is_ignored("src/main.py"))

    def test_project_graph_and_hubs(self):
        graph = ProjectGraph()
        graph.add_file("src/main.py", is_entry_point=True)
        graph.add_file("src/utils.py")
        graph.add_file("src/helpers.py")

        # main imports utils and helpers
        graph.add_import("src/main.py", "utils")
        graph.add_import("src/main.py", "helpers")
        # helpers imports utils
        graph.add_import("src/helpers.py", "utils")

        # utils should have in_degree of 2 (imported by both main and helpers)
        self.assertEqual(graph.nodes["src/utils.py"].in_degree, 2)
        self.assertGreater(graph.get_centrality_score("src/utils.py"), 0)

        # Cycles
        cycles = graph.detect_circular_dependencies()
        self.assertEqual(len(cycles), 0)


if __name__ == "__main__":
    unittest.main()
