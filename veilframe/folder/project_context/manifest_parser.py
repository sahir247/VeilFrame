"""
veilframe.folder.project_context.manifest_parser — High-fidelity package manifest and dependency parsers.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

try:
    import tomllib
except ImportError:
    try:
        import tomli as tomllib  # type: ignore
    except ImportError:
        tomllib = None  # type: ignore


@dataclass
class ParsedManifest:
    """Structured dependency and package metadata extracted from a manifest."""
    file_path: str
    manifest_type: str
    project_name: Optional[str] = None
    version: Optional[str] = None
    description: Optional[str] = None
    dependencies: Dict[str, str] = field(default_factory=dict)
    dev_dependencies: Dict[str, str] = field(default_factory=dict)
    scripts: Dict[str, str] = field(default_factory=dict)
    entry_points: List[str] = field(default_factory=list)

    @property
    def name(self) -> Optional[str]:
        """Alias for project_name."""
        return self.project_name

    def to_dict(self) -> Dict[str, Any]:
        return {
            "file_path": self.file_path,
            "manifest_type": self.manifest_type,
            "project_name": self.project_name,
            "version": self.version,
            "description": self.description,
            "dependencies_count": len(self.dependencies),
            "dev_dependencies_count": len(self.dev_dependencies),
            "dependencies": self.dependencies,
            "dev_dependencies": self.dev_dependencies,
            "scripts": self.scripts,
            "entry_points": self.entry_points,
        }


def parse_package_json(path: str) -> Optional[ParsedManifest]:
    """Parse Node.js package.json manifest."""
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            data = json.load(f)
    except Exception:
        return None

    name = data.get("name")
    version = data.get("version")
    description = data.get("description")
    deps = data.get("dependencies", {})
    dev_deps = data.get("devDependencies", {})
    scripts = data.get("scripts", {})

    entry_points: List[str] = []
    main_entry = data.get("main")
    if main_entry:
        entry_points.append(main_entry)

    bin_entry = data.get("bin")
    if isinstance(bin_entry, str):
        entry_points.append(bin_entry)
    elif isinstance(bin_entry, dict):
        entry_points.extend(list(bin_entry.values()))

    return ParsedManifest(
        file_path=path,
        manifest_type="package.json",
        project_name=name,
        version=version,
        description=description,
        dependencies={k: str(v) for k, v in deps.items()} if isinstance(deps, dict) else {},
        dev_dependencies={k: str(v) for k, v in dev_deps.items()} if isinstance(dev_deps, dict) else {},
        scripts={k: str(v) for k, v in scripts.items()} if isinstance(scripts, dict) else {},
        entry_points=entry_points,
    )


def parse_pyproject_toml(path: str) -> Optional[ParsedManifest]:
    """Parse Python pyproject.toml manifest."""
    data: Dict[str, Any] = {}
    if tomllib is not None:
        try:
            with open(path, "rb") as f:
                data = tomllib.load(f)
        except Exception:
            data = {}

    name = None
    version = None
    description = None
    deps: Dict[str, str] = {}
    dev_deps: Dict[str, str] = {}
    scripts: Dict[str, str] = {}
    entry_points: List[str] = []

    # Standard PEP 621 [project]
    proj = data.get("project", {})
    if isinstance(proj, dict):
        name = proj.get("name")
        version = proj.get("version")
        description = proj.get("description")
        req_deps = proj.get("dependencies", [])
        if isinstance(req_deps, list):
            for item in req_deps:
                m = re.match(r"^([a-zA-Z0-9_\-\.]+)(.*)$", item.strip())
                if m:
                    deps[m.group(1)] = m.group(2).strip()
        proj_scripts = proj.get("scripts", {})
        if isinstance(proj_scripts, dict):
            scripts.update({k: str(v) for k, v in proj_scripts.items()})
            entry_points.extend(list(proj_scripts.values()))

    # Fallback to simple regex parsing if tomllib failed or empty
    if not name and os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
                m_name = re.search(r'name\s*=\s*["\']([^"\']+)["\']', content)
                if m_name:
                    name = m_name.group(1)
                m_ver = re.search(r'version\s*=\s*["\']([^"\']+)["\']', content)
                if m_ver:
                    version = m_ver.group(1)
        except OSError:
            pass

    return ParsedManifest(
        file_path=path,
        manifest_type="pyproject.toml",
        project_name=name,
        version=version,
        description=description,
        dependencies=deps,
        dev_dependencies=dev_deps,
        scripts=scripts,
        entry_points=entry_points,
    )


def parse_cargo_toml(path: str) -> Optional[ParsedManifest]:
    """Parse Rust Cargo.toml manifest."""
    name = None
    version = None
    deps: Dict[str, str] = {}

    if tomllib is not None:
        try:
            with open(path, "rb") as f:
                data = tomllib.load(f)
                pkg = data.get("package", {})
                name = pkg.get("name")
                version = pkg.get("version")
                raw_deps = data.get("dependencies", {})
                if isinstance(raw_deps, dict):
                    for k, v in raw_deps.items():
                        deps[k] = str(v) if not isinstance(v, dict) else str(v.get("version", "*"))
        except Exception:
            pass

    if not name and os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
                m_name = re.search(r'name\s*=\s*["\']([^"\']+)["\']', content)
                if m_name:
                    name = m_name.group(1)
                m_ver = re.search(r'version\s*=\s*["\']([^"\']+)["\']', content)
                if m_ver:
                    version = m_ver.group(1)
        except OSError:
            pass

    return ParsedManifest(
        file_path=path,
        manifest_type="Cargo.toml",
        project_name=name,
        version=version,
        dependencies=deps,
        entry_points=["src/main.rs", "src/lib.rs"],
    )


def parse_go_mod(path: str) -> Optional[ParsedManifest]:
    """Parse Go go.mod manifest."""
    if not os.path.exists(path):
        return None

    module_name = None
    go_version = None
    deps: Dict[str, str] = {}

    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line_str = line.strip()
                if line_str.startswith("module "):
                    module_name = line_str.split(None, 1)[1].strip()
                elif line_str.startswith("go "):
                    go_version = line_str.split(None, 1)[1].strip()
                elif line_str and not line_str.startswith("//") and not line_str.startswith("require"):
                    parts = line_str.split()
                    if len(parts) >= 2 and "/" in parts[0]:
                        deps[parts[0]] = parts[1]
    except OSError:
        return None

    return ParsedManifest(
        file_path=path,
        manifest_type="go.mod",
        project_name=module_name,
        version=go_version,
        dependencies=deps,
        entry_points=["main.go"],
    )


def parse_manifest(path: str) -> Optional[ParsedManifest]:
    """Universal dispatcher to parse any recognized manifest by filename."""
    base = os.path.basename(path).lower()
    if base == "package.json":
        return parse_package_json(path)
    elif base == "pyproject.toml":
        return parse_pyproject_toml(path)
    elif base == "cargo.toml":
        return parse_cargo_toml(path)
    elif base == "go.mod":
        return parse_go_mod(path)
    return None
