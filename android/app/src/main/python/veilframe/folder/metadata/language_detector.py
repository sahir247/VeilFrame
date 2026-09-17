"""
veilframe.folder.metadata.language_detector — Multi-ecosystem language recognition engine.
"""

from __future__ import annotations

import os
import re
from typing import Dict, Optional, Set

# Comprehensive extension to canonical language mapping
EXTENSION_LANGUAGE_MAP: Dict[str, str] = {
    # Core Languages
    ".py": "Python",
    ".pyw": "Python",
    ".pyi": "Python",
    ".pyx": "Cython",
    ".js": "JavaScript",
    ".mjs": "JavaScript",
    ".cjs": "JavaScript",
    ".jsx": "JavaScript",
    ".ts": "TypeScript",
    ".mts": "TypeScript",
    ".cts": "TypeScript",
    ".tsx": "TypeScript",
    ".java": "Java",
    ".kt": "Kotlin",
    ".kts": "Kotlin",
    ".scala": "Scala",
    ".sc": "Scala",
    ".c": "C",
    ".h": "C",
    ".cpp": "C++",
    ".cxx": "C++",
    ".cc": "C++",
    ".hpp": "C++",
    ".hxx": "C++",
    ".hh": "C++",
    ".cs": "C#",
    ".csx": "C#",
    ".go": "Go",
    ".rs": "Rust",
    ".php": "PHP",
    ".phtml": "PHP",
    ".rb": "Ruby",
    ".rake": "Ruby",
    ".gemspec": "Ruby",
    ".swift": "Swift",
    ".dart": "Dart",
    ".r": "R",
    ".rmd": "R",
    ".pl": "Perl",
    ".pm": "Perl",
    ".t": "Perl",
    ".lua": "Lua",
    ".hs": "Haskell",
    ".lhs": "Haskell",
    ".ex": "Elixir",
    ".exs": "Elixir",
    ".erl": "Erlang",
    ".hrl": "Erlang",
    ".clj": "Clojure",
    ".cljs": "Clojure",
    ".cljc": "Clojure",
    ".edn": "Clojure",
    ".jl": "Julia",
    ".mat": "MATLAB",
    ".f": "Fortran",
    ".f90": "Fortran",
    ".f95": "Fortran",
    ".f03": "Fortran",
    ".f08": "Fortran",
    ".cob": "COBOL",
    ".cbl": "COBOL",
    ".cpy": "COBOL",
    ".pas": "Pascal",
    ".dpr": "Pascal",
    ".dfm": "Pascal",
    ".pp": "Pascal",
    ".zig": "Zig",
    ".zon": "Zig",
    ".nim": "Nim",
    ".nims": "Nim",
    ".cr": "Crystal",
    ".d": "D",
    ".v": "V",
    ".sh": "Shell",
    ".bash": "Shell",
    ".zsh": "Shell",
    ".fish": "Shell",
    ".ps1": "PowerShell",
    ".psm1": "PowerShell",
    ".psd1": "PowerShell",
    ".sql": "SQL",
    ".cql": "SQL",
    ".pgsql": "SQL",
    ".html": "HTML",
    ".htm": "HTML",
    ".css": "CSS",
    ".scss": "SCSS",
    ".sass": "Sass",
    ".less": "Less",
    ".vue": "Vue",
    ".svelte": "Svelte",
    ".graphql": "GraphQL",
    ".proto": "Protocol Buffers",
    ".tf": "HCL",
    ".tfvars": "HCL",
    ".json": "JSON",
    ".yaml": "YAML",
    ".yml": "YAML",
    ".toml": "TOML",
    ".xml": "XML",
    ".md": "Markdown",
    ".markdown": "Markdown",
    ".rst": "reStructuredText",
}

# Exact filename to language mapping
FILENAME_LANGUAGE_MAP: Dict[str, str] = {
    "dockerfile": "Dockerfile",
    "containerfile": "Dockerfile",
    "makefile": "Makefile",
    "gnumakefile": "Makefile",
    "cmakelists.txt": "CMake",
    "vagrantfile": "Ruby",
    "rakefile": "Ruby",
    "gemfile": "Ruby",
    "brewfile": "Ruby",
    "jenkinsfile": "Groovy",
    "build.gradle": "Gradle",
    "build.gradle.kts": "Kotlin",
    "settings.gradle": "Gradle",
    "settings.gradle.kts": "Kotlin",
    "pom.xml": "Maven POM",
    "cargo.toml": "TOML",
    "pyproject.toml": "TOML",
    "package.json": "JSON",
    "go.mod": "Go Module",
    "go.work": "Go Work",
    "go.sum": "Go Checksum",
}

SHEBANG_REGEX = re.compile(r"^#!\s*(?:/usr/bin/env\s+)?([a-zA-Z0-9_\-\.]+)")

SHEBANG_LANGUAGE_MAP: Dict[str, str] = {
    "python": "Python",
    "python3": "Python",
    "python2": "Python",
    "node": "JavaScript",
    "nodejs": "JavaScript",
    "bash": "Shell",
    "sh": "Shell",
    "zsh": "Shell",
    "ruby": "Ruby",
    "perl": "Perl",
    "php": "PHP",
}


def detect_language(path: str, content_sample: Optional[str] = None) -> Optional[str]:
    """
    Detect programming language from file extension, filename, or shebang header.
    """
    basename = os.path.basename(path)
    base_lower = basename.lower()

    # 1. Exact filename match
    if base_lower in FILENAME_LANGUAGE_MAP:
        return FILENAME_LANGUAGE_MAP[base_lower]

    _, ext = os.path.splitext(basename)
    ext_lower = ext.lower()

    # 2. Ambiguity resolution for .m (Objective-C vs MATLAB)
    if ext_lower == ".m":
        if content_sample:
            if "#import" in content_sample or "@interface" in content_sample or "@implementation" in content_sample:
                return "Objective-C"
            if "function" in content_sample or "end" in content_sample:
                return "MATLAB"
        return "Objective-C"

    # 3. Extension lookup
    if ext_lower in EXTENSION_LANGUAGE_MAP:
        return EXTENSION_LANGUAGE_MAP[ext_lower]

    # 4. Shebang inspection if content or file available
    sample = content_sample
    if sample is None and os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                sample = f.readline(200)
        except OSError:
            sample = None

    if sample and sample.startswith("#!"):
        m = SHEBANG_REGEX.match(sample)
        if m:
            interpreter = os.path.basename(m.group(1)).lower()
            return SHEBANG_LANGUAGE_MAP.get(interpreter)

    return None
