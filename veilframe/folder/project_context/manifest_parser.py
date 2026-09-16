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


def parse_pubspec_yaml(path: str) -> Optional[ParsedManifest]:
    """Parse Flutter/Dart pubspec.yaml manifest."""
    if not os.path.exists(path):
        return None

    name = None
    version = None
    desc = None
    deps: Dict[str, str] = {}
    dev_deps: Dict[str, str] = {}
    section = None

    try:
        from veilframe.folder.ai_bundle.context_compressor import read_text_file_safe
        content, _ = read_text_file_safe(path)
    except Exception:
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except OSError:
            return None

    for line in content.splitlines():
        line_str = line.rstrip()
        if not line_str or line_str.startswith("#"):
            continue
        if not line_str.startswith(" "):
            if line_str.startswith("name:"):
                name = line_str.split(":", 1)[1].strip().strip('"\'')
            elif line_str.startswith("version:"):
                version = line_str.split(":", 1)[1].strip().strip('"\'')
            elif line_str.startswith("description:"):
                desc = line_str.split(":", 1)[1].strip().strip('"\'')
            elif line_str.startswith("dependencies:"):
                section = "deps"
            elif line_str.startswith("dev_dependencies:"):
                section = "dev_deps"
            else:
                section = None
        elif section and line_str.startswith("  ") and not line_str.startswith("    "):
            parts = line_str.strip().split(":", 1)
            k = parts[0].strip()
            v = parts[1].strip() if len(parts) > 1 else "*"
            if section == "deps":
                deps[k] = v
            elif section == "dev_deps":
                dev_deps[k] = v

    return ParsedManifest(
        file_path=path,
        manifest_type="pubspec.yaml",
        project_name=name,
        version=version,
        description=desc,
        dependencies=deps,
        dev_dependencies=dev_deps,
        entry_points=["lib/main.dart"],
    )


def parse_composer_json(path: str) -> Optional[ParsedManifest]:
    """Parse PHP composer.json manifest."""
    if not os.path.exists(path):
        return None
    try:
        from veilframe.folder.ai_bundle.context_compressor import read_text_file_safe
        content, _ = read_text_file_safe(path)
        data = json.loads(content)
    except Exception:
        return None

    name = data.get("name")
    version = data.get("version")
    desc = data.get("description")
    deps = data.get("require", {})
    dev_deps = data.get("require-dev", {})

    return ParsedManifest(
        file_path=path,
        manifest_type="composer.json",
        project_name=name,
        version=version,
        description=desc,
        dependencies={k: str(v) for k, v in deps.items()} if isinstance(deps, dict) else {},
        dev_dependencies={k: str(v) for k, v in dev_deps.items()} if isinstance(dev_deps, dict) else {},
    )


def parse_gradle(path: str) -> Optional[ParsedManifest]:
    """Parse Android/Kotlin Gradle build files (build.gradle, build.gradle.kts)."""
    if not os.path.exists(path):
        return None

    deps: Dict[str, str] = {}
    try:
        from veilframe.folder.ai_bundle.context_compressor import read_text_file_safe
        content, _ = read_text_file_safe(path)
    except Exception:
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except OSError:
            return None

    for line in content.splitlines():
        m = re.search(r"""(?:implementation|api|compileOnly|testImplementation)\s*\(?['"]([^'"]+)['"]\)?""", line)
        if m:
            val = m.group(1)
            parts = val.split(":")
            if len(parts) >= 2:
                deps[f"{parts[0]}:{parts[1]}"] = parts[2] if len(parts) > 2 else "*"

    base = os.path.basename(path)
    return ParsedManifest(
        file_path=path,
        manifest_type=base,
        project_name=base,
        dependencies=deps,
    )


def parse_pom_xml(path: str) -> Optional[ParsedManifest]:
    """Parse Java Maven pom.xml manifest."""
    if not os.path.exists(path):
        return None
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(path)
        root = tree.getroot()
        ns = ""
        if root.tag.startswith("{"):
            ns = root.tag.split("}")[0] + "}"

        artifact_id = root.findtext(f"{ns}artifactId")
        version = root.findtext(f"{ns}version")
        deps: Dict[str, str] = {}
        for dep in root.findall(f".//{ns}dependency"):
            g = dep.findtext(f"{ns}groupId") or ""
            a = dep.findtext(f"{ns}artifactId") or ""
            v = dep.findtext(f"{ns}version") or "*"
            if g and a:
                deps[f"{g}:{a}"] = v

        return ParsedManifest(
            file_path=path,
            manifest_type="pom.xml",
            project_name=artifact_id,
            version=version,
            dependencies=deps,
        )
    except Exception:
        return None


def parse_csproj(path: str) -> Optional[ParsedManifest]:
    """Parse C# .NET *.csproj manifest."""
    if not os.path.exists(path):
        return None
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(path)
        root = tree.getroot()
        deps: Dict[str, str] = {}
        for pkg in root.findall(".//PackageReference"):
            inc = pkg.get("Include")
            ver = pkg.get("Version", "*")
            if inc:
                deps[inc] = ver

        base = os.path.basename(path)
        proj_name = base.rsplit(".", 1)[0]
        return ParsedManifest(
            file_path=path,
            manifest_type="csproj",
            project_name=proj_name,
            dependencies=deps,
        )
    except Exception:
        return None


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
    elif base == "pubspec.yaml":
        return parse_pubspec_yaml(path)
    elif base == "composer.json":
        return parse_composer_json(path)
    elif base in ("build.gradle", "build.gradle.kts"):
        return parse_gradle(path)
    elif base == "pom.xml":
        return parse_pom_xml(path)
    elif base.endswith(".csproj"):
        return parse_csproj(path)
    return None
