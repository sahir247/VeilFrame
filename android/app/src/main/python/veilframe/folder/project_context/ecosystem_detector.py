"""
veilframe.folder.project_context.ecosystem_detector — Marker-based multi-ecosystem detection engine.
"""

from __future__ import annotations

import os
from typing import Dict, Iterable, List, Optional, Set, Union

# Ecosystem markers: mapping from marker filename (lowercase) to ecosystem identifier(s)
MARKER_ECOSYSTEM_MAP: Dict[str, List[str]] = {
    "pyproject.toml": ["python"],
    "setup.py": ["python"],
    "setup.cfg": ["python"],
    "pipfile": ["python"],
    "requirements.txt": ["python"],
    "uv.lock": ["python"],
    "package.json": ["javascript", "node", "npm"],
    "package-lock.json": ["javascript", "node", "npm"],
    "yarn.lock": ["javascript", "node", "yarn"],
    "pnpm-lock.yaml": ["javascript", "node", "pnpm"],
    "tsconfig.json": ["typescript", "javascript"],
    "cargo.toml": ["rust", "cargo"],
    "cargo.lock": ["rust", "cargo"],
    "go.mod": ["go"],
    "go.sum": ["go"],
    "go.work": ["go"],
    "pom.xml": ["java", "jvm", "maven"],
    "build.gradle": ["java", "jvm", "gradle"],
    "build.gradle.kts": ["kotlin", "java", "jvm", "gradle"],
    "settings.gradle": ["java", "jvm", "gradle"],
    "pubspec.yaml": ["dart", "flutter"],
    "package.swift": ["swift", "xcode", "apple"],
    "composer.json": ["php", "composer"],
    "gemfile": ["ruby", "bundler"],
    "mix.exs": ["elixir", "mix"],
    "rebar.config": ["erlang", "rebar3"],
    "project.clj": ["clojure", "leiningen"],
    "deps.edn": ["clojure"],
    "cmakelists.txt": ["c", "cpp", "cmake"],
    "makefile": ["c", "cpp"],
    "dockerfile": ["docker", "container", "infrastructure"],
    "docker-compose.yml": ["docker", "container", "infrastructure"],
    "compose.yml": ["docker", "container", "infrastructure"],
    "project.godot": ["godot", "game"],
}

# Extension-based ecosystem clues
EXTENSION_ECOSYSTEM_MAP: Dict[str, str] = {
    ".py": "python",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".js": "javascript",
    ".jsx": "javascript",
    ".rs": "rust",
    ".go": "go",
    ".java": "java",
    ".kt": "kotlin",
    ".scala": "scala",
    ".cs": "csharp",
    ".cpp": "cpp",
    ".c": "c",
    ".swift": "swift",
    ".dart": "dart",
    ".php": "php",
    ".rb": "ruby",
    ".ex": "elixir",
    ".erl": "erlang",
    ".clj": "clojure",
    ".jl": "julia",
    ".r": "r",
    ".lua": "lua",
    ".tf": "terraform",
    ".safetensors": "ai_ml",
    ".onnx": "ai_ml",
    ".pt": "ai_ml",
}


def detect_ecosystems(
    root_path: Union[str, Iterable[str]],
    file_rel_paths: Optional[Iterable[str]] = None,
) -> Set[str]:
    """
    Detect all active ecosystems in the project directory simultaneously.
    Can operate on a list of relative file paths or by inspecting the root directory.
    """
    ecosystems: Set[str] = set()

    if isinstance(root_path, (list, set, tuple)):
        file_rel_paths = root_path
        root_path = ""

    # 1. Inspect files directly if passed
    if file_rel_paths:
        for rel in file_rel_paths:
            base_lower = os.path.basename(rel).lower()
            if base_lower in MARKER_ECOSYSTEM_MAP:
                ecosystems.update(MARKER_ECOSYSTEM_MAP[base_lower])

            # Extension heuristics
            _, ext = os.path.splitext(base_lower)
            if ext in EXTENSION_ECOSYSTEM_MAP:
                ecosystems.add(EXTENSION_ECOSYSTEM_MAP[ext])

    # 2. Inspect root directory for top-level markers
    if os.path.isdir(root_path):
        try:
            entries = os.listdir(root_path)
            for entry in entries:
                entry_lower = entry.lower()
                if entry_lower in MARKER_ECOSYSTEM_MAP:
                    ecosystems.update(MARKER_ECOSYSTEM_MAP[entry_lower])
                elif entry_lower.endswith(".sln") or entry_lower.endswith(".csproj"):
                    ecosystems.update(["csharp", "dotnet"])
        except OSError:
            pass

    # Ensure universal fallback if empty
    if not ecosystems:
        ecosystems.add("generic")

    return ecosystems
