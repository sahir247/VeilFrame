"""
veilframe.folder.project_context.import_analyzer — Multi-language import statement extraction.
"""

from __future__ import annotations

import ast
import re
from typing import List, Set

# Regex patterns for fast extraction without heavy compilers
JS_IMPORT_REGEX = re.compile(r"""(?:import\s+.*?from\s+['"]([^'"]+)['"]|require\s*\(\s*['"]([^'"]+)['"]\s*\))""")
GO_IMPORT_REGEX = re.compile(r"""import\s+(?:\(\s*([^)]+)\s*\)|["']([^"']+)["'])""", re.MULTILINE)
RUST_USE_REGEX = re.compile(r"""(?:use\s+([a-zA-Z0-9_:]+)|mod\s+([a-zA-Z0-9_]+))""")
CPP_INCLUDE_REGEX = re.compile(r"""#include\s+["<]([^">]+)[">]""")
JAVA_IMPORT_REGEX = re.compile(r"""import\s+(?:static\s+)?([a-zA-Z0-9_.]+);""")


def extract_python_imports(content: str) -> Set[str]:
    """Extract imported module names from Python source code using AST or regex fallback."""
    imports: Set[str] = set()
    try:
        tree = ast.parse(content)
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    imports.add(alias.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    imports.add(node.module.split(".")[0])
    except SyntaxError:
        # Regex fallback
        for line in content.splitlines():
            line_str = line.strip()
            if line_str.startswith("import ") or line_str.startswith("from "):
                parts = line_str.split()
                if len(parts) >= 2:
                    mod = parts[1].split(".")[0]
                    imports.add(mod)
    return imports


def extract_js_imports(content: str) -> Set[str]:
    """Extract module and relative file imports from JavaScript or TypeScript code."""
    imports: Set[str] = set()
    for m in JS_IMPORT_REGEX.finditer(content):
        val = m.group(1) or m.group(2)
        if val:
            imports.add(val)
    return imports


def extract_cpp_includes(content: str) -> Set[str]:
    """Extract header files included in C/C++ source code."""
    includes: Set[str] = set()
    for m in CPP_INCLUDE_REGEX.finditer(content):
        val = m.group(1)
        if val:
            includes.add(val)
    return includes


def extract_imports(path: str, content: str) -> Set[str]:
    """Universal dispatcher extracting imports based on file extension."""
    ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
    if ext in ("py", "pyi"):
        return extract_python_imports(content)
    elif ext in ("js", "jsx", "ts", "tsx", "mjs", "cjs"):
        return extract_js_imports(content)
    elif ext in ("c", "cpp", "cc", "cxx", "h", "hpp", "hxx"):
        return extract_cpp_includes(content)
    elif ext == "go":
        imports: Set[str] = set()
        for m in GO_IMPORT_REGEX.finditer(content):
            val = m.group(2) or m.group(1)
            if val:
                for line in val.splitlines():
                    clean = line.strip().strip('"\'')
                    if clean:
                        imports.add(clean)
        return imports
    elif ext == "rs":
        imports: Set[str] = set()
        for m in RUST_USE_REGEX.finditer(content):
            val = m.group(1) or m.group(2)
            if val:
                imports.add(val.split("::")[0])
        return imports
    elif ext in ("java", "kt", "scala"):
        imports: Set[str] = set()
        for m in JAVA_IMPORT_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val.split(".")[0])
        return imports
    return set()
