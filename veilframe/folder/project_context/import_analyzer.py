"""
veilframe.folder.project_context.import_analyzer — Multi-language import statement extraction.
"""

from __future__ import annotations

import ast
import os
import re
from typing import List, Optional, Set

# Regex patterns for fast extraction without heavy compilers
JS_IMPORT_REGEX = re.compile(r"""(?:import\s+.*?from\s+['"]([^'"]+)['"]|require\s*\(\s*['"]([^'"]+)['"]\s*\))""")
GO_IMPORT_REGEX = re.compile(r"""import\s+(?:\(\s*([^)]+)\s*\)|["']([^"']+)["'])""", re.MULTILINE)
RUST_USE_REGEX = re.compile(r"""(?:use\s+([a-zA-Z0-9_:]+)|mod\s+([a-zA-Z0-9_]+))""")
CPP_INCLUDE_REGEX = re.compile(r"""#include\s+["<]([^">]+)[">]""")
JAVA_IMPORT_REGEX = re.compile(r"""import\s+(?:static\s+)?([a-zA-Z0-9_.]+);""")
KOTLIN_IMPORT_REGEX = re.compile(r"""import\s+([a-zA-Z0-9_.]+)(?:\s*;|\s*$)""", re.MULTILINE)
DART_IMPORT_REGEX = re.compile(r"""import\s+['"](?:package:)?([^/'"]+)""")
SWIFT_IMPORT_REGEX = re.compile(r"""import\s+([a-zA-Z0-9_]+)""")
CSHARP_USING_REGEX = re.compile(r"""using\s+(?:static\s+)?([a-zA-Z0-9_.]+);""")
PHP_USE_REGEX = re.compile(r"""use\s+([a-zA-Z0-9_\\]+);""")
RUBY_REQUIRE_REGEX = re.compile(r"""require(?:_relative)?\s+['"]([^'"]+)['"]""")


def extract_python_imports(content: str) -> Set[str]:
    """Extract imported module names from Python source code using AST or regex fallback."""
    if not content or not isinstance(content, str):
        return set()
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


def extract_imports(path: str, content: Optional[str] = None) -> Set[str]:
    """Universal dispatcher extracting imports across Python, JS/TS, C++, Go, Rust, Java, Kotlin, Dart, Swift, C#, PHP, Ruby."""
    if not isinstance(content, str) or not content.strip():
        if os.path.isfile(path):
            try:
                from veilframe.folder.ai_bundle.context_compressor import read_text_file_safe
                content, _ = read_text_file_safe(path, max_bytes=250_000)
            except Exception:
                try:
                    with open(path, "r", encoding="utf-8", errors="ignore") as f:
                        content = f.read(250_000)
                except OSError:
                    return set()
        else:
            return set()

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
    elif ext in ("java", "scala"):
        imports: Set[str] = set()
        for m in JAVA_IMPORT_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val.split(".")[0])
        return imports
    elif ext in ("kt", "kts"):
        imports: Set[str] = set()
        for m in KOTLIN_IMPORT_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val.split(".")[0])
        return imports
    elif ext == "dart":
        imports: Set[str] = set()
        for m in DART_IMPORT_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val)
        return imports
    elif ext == "swift":
        imports: Set[str] = set()
        for m in SWIFT_IMPORT_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val)
        return imports
    elif ext in ("cs", "csx"):
        imports: Set[str] = set()
        for m in CSHARP_USING_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val.split(".")[0])
        return imports
    elif ext in ("php", "phtml"):
        imports: Set[str] = set()
        for m in PHP_USE_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val.split("\\")[0])
        return imports
    elif ext in ("rb", "rake", "gemspec"):
        imports: Set[str] = set()
        for m in RUBY_REQUIRE_REGEX.finditer(content):
            val = m.group(1)
            if val:
                imports.add(val)
        return imports
    return set()


def classify_imports(
    project_root: str,
    file_path: str,
    imports: Set[str],
    known_project_modules: Optional[Set[str]] = None,
) -> Tuple[Set[str], Set[str]]:
    """
    Distinguish between [INTERNAL] and [EXTERNAL] imports for a given source file.
    Returns:
        (internal_imports: Set[str], external_imports: Set[str])
    """
    internal: Set[str] = set()
    external: Set[str] = set()

    file_dir = os.path.dirname(os.path.abspath(file_path)) if file_path else ""
    proj_dir = os.path.abspath(project_root) if project_root else ""

    known_mods = {m.lower() for m in known_project_modules} if known_project_modules else set()

    for imp in imports:
        imp_clean = imp.strip().strip("'\"")
        if not imp_clean:
            continue

        # 1. Obvious relative imports
        if imp_clean.startswith((".", "./", "../")) or imp_clean.startswith(("crate::", "super::", "self::")):
            internal.add(imp_clean)
            continue

        # 2. Known modules registered in project
        first_segment = imp_clean.split(".")[0].split("::")[0].split("/")[0].lower()
        if first_segment in known_mods:
            internal.add(imp_clean)
            continue

        # 3. Local filesystem resolution under project root
        is_local = False
        if proj_dir:
            candidate_dir = os.path.join(proj_dir, first_segment)
            candidate_file_py = os.path.join(proj_dir, f"{first_segment}.py")
            candidate_file_js = os.path.join(proj_dir, f"{first_segment}.js")
            candidate_file_ts = os.path.join(proj_dir, f"{first_segment}.ts")
            candidate_src_dir = os.path.join(proj_dir, "src", first_segment)
            if (
                os.path.isdir(candidate_dir)
                or os.path.isfile(candidate_file_py)
                or os.path.isfile(candidate_file_js)
                or os.path.isfile(candidate_file_ts)
                or os.path.isdir(candidate_src_dir)
            ):
                is_local = True

        if not is_local and file_dir:
            candidate_file_local = os.path.join(file_dir, f"{first_segment}.py")
            candidate_dir_local = os.path.join(file_dir, first_segment)
            if os.path.isfile(candidate_file_local) or os.path.isdir(candidate_dir_local):
                is_local = True

        if is_local:
            internal.add(imp_clean)
        else:
            external.add(imp_clean)

    return internal, external


