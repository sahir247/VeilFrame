"""
veilframe.folder.rules.rule_loader — Loading and parsing of YAML and JSON rule definitions.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List

from veilframe.folder.rules.rule_model import Rule

try:
    import yaml
    HAVE_YAML = True
except ImportError:
    HAVE_YAML = False


def _parse_simple_yaml(text: str) -> List[Dict[str, Any]]:
    """
    Fallback parser for standard VeilFrame rule YAML format when PyYAML is unavailable.
    Supports top-level rule list or 'rules:' list key.
    """
    lines = [line.rstrip() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]
    rules_data: List[Dict[str, Any]] = []
    curr_rule: Optional[Dict[str, Any]] = None
    curr_list_key: Optional[str] = None

    for line in lines:
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if stripped.startswith("- ") and indent <= 2 and ("id:" in stripped or not stripped[2:].startswith(" ")):
            # Start of a new rule in list
            if curr_rule:
                rules_data.append(curr_rule)
            curr_rule = {}
            curr_list_key = None
            item_text = stripped[2:].strip()
            if ":" in item_text:
                k, v = item_text.split(":", 1)
                curr_rule[k.strip()] = v.strip().strip("\"'")
        elif curr_rule is not None:
            if stripped.startswith("- ") and curr_list_key:
                val = stripped[2:].strip().strip("\"'")
                curr_rule[curr_list_key].append(val)
            elif ":" in stripped:
                k, v = stripped.split(":", 1)
                k_clean = k.strip()
                v_clean = v.strip().strip("\"'")
                if not v_clean:
                    curr_rule[k_clean] = []
                    curr_list_key = k_clean
                else:
                    curr_list_key = None
                    curr_rule[k_clean] = v_clean

    if curr_rule:
        rules_data.append(curr_rule)

    return rules_data


def load_rule_file(file_path: str) -> List[Rule]:
    """Load a list of Rule objects from a YAML or JSON file."""
    if not os.path.exists(file_path):
        return []

    ext = os.path.splitext(file_path)[1].lower()
    raw_content = ""
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            raw_content = f.read()
    except OSError:
        return []

    parsed: Any = None
    if ext == ".json":
        try:
            parsed = json.loads(raw_content)
        except json.JSONDecodeError:
            return []
    elif ext in (".yaml", ".yml"):
        if HAVE_YAML:
            try:
                parsed = yaml.safe_load(raw_content)
            except Exception:
                parsed = _parse_simple_yaml(raw_content)
        else:
            parsed = _parse_simple_yaml(raw_content)

    if not parsed:
        return []

    # Handle dictionary with 'rules' key or direct list
    items: List[Dict[str, Any]] = []
    if isinstance(parsed, dict):
        items = parsed.get("rules", [])
    elif isinstance(parsed, list):
        items = parsed

    rules: List[Rule] = []
    for item in items:
        if isinstance(item, dict):
            try:
                rules.append(Rule.from_dict(item))
            except Exception:
                continue

    return rules


def load_rules_from_dir(dir_path: str) -> List[Rule]:
    """Scan a directory recursively and load all .yaml, .yml, and .json rule files."""
    if not os.path.exists(dir_path) or not os.path.isdir(dir_path):
        return []

    rules: List[Rule] = []
    for root, _, files in os.walk(dir_path):
        for f in files:
            if f.lower().endswith((".yaml", ".yml", ".json")):
                rules.extend(load_rule_file(os.path.join(root, f)))
    return rules
