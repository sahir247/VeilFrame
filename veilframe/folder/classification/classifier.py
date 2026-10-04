"""
veilframe.folder.classification.classifier — Central classification coordinator executing the 9-tier precedence chain.
"""

from __future__ import annotations

import os
from typing import List, Optional, Set

from veilframe.folder.classification.scoring import calculate_file_priority
from veilframe.folder.models.classification import (
    AIAction,
    ClassificationResult,
    FileCategory,
    RulePriority,
)
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.project_context.dependency_analyzer import (
    analyze_vendor_status,
)
from veilframe.folder.project_context.gitignore_parser import (
    GitIgnoreParser,
    GitIgnoreReason,
)
from veilframe.folder.project_context.project_graph import ProjectGraph
from veilframe.folder.rules.precedence import resolve_highest_precedence
from veilframe.folder.rules.registry import RuleRegistry
from veilframe.folder.security.sensitive_files import is_sensitive_filepath


class Classifier:
    """Evaluates files and directories against security, gitignore, ecosystem rules, and priorities."""

    def __init__(
        self,
        registry: Optional[RuleRegistry] = None,
        gitignore_parser: Optional[GitIgnoreParser] = None,
        project_graph: Optional[ProjectGraph] = None,
    ) -> None:
        self.registry = registry or RuleRegistry.get_default()
        self.gitignore_parser = gitignore_parser
        self.project_graph = project_graph

    def classify_file(
        self,
        record: FileRecord,
        active_ecosystems: Set[str],
        depth: int = 0,
    ) -> ClassificationResult:
        """Classify a single FileRecord through the strict 9-tier precedence pipeline."""
        candidates: List[ClassificationResult] = []
        rel_path = record.relative_path or record.path

        # ----------------------------------------------------
        # 1. SECURITY PRECEDENCE (Priority = 100)
        # ----------------------------------------------------
        is_sens, sens_reason = is_sensitive_filepath(rel_path)
        if is_sens:
            record.is_secret = True
            record.secret_alerts.append(sens_reason)
            candidates.append(
                ClassificationResult(
                    category=FileCategory.SECRET,
                    action=AIAction.WARN,
                    confidence=1.0,
                    reason=sens_reason,
                    rule_id="security.path_heuristic",
                    priority=RulePriority.SECURITY,
                )
            )

        # ----------------------------------------------------
        # 2. USER EXPLICIT OVERRIDE (Priority = 90)
        # ----------------------------------------------------
        if record.user_override_action is not None:
            candidates.append(
                ClassificationResult(
                    category=record.classification.category if record.classification else FileCategory.UNKNOWN,
                    action=record.user_override_action,
                    confidence=1.0,
                    reason="User explicit configuration override",
                    rule_id="user.override",
                    priority=RulePriority.USER_OVERRIDE,
                )
            )

        # ----------------------------------------------------
        # 3. PROJECT .gitignore EVALUATION (Priority = 80)
        # ----------------------------------------------------
        if self.gitignore_parser and self.gitignore_parser.is_ignored(rel_path, is_dir=False):
            is_ign, reason_type, act, cat = self.gitignore_parser.evaluate_reason(rel_path, is_dir=False)
            if is_ign:
                candidates.append(
                    ClassificationResult(
                        category=cat,
                        action=act,
                        confidence=0.85,
                        reason=f"Ignored by .gitignore ({reason_type.value if reason_type else 'GENERIC'})",
                        rule_id="project.gitignore",
                        priority=RulePriority.GITIGNORE,
                    )
                )

        # ----------------------------------------------------
        # 4. RULE REGISTRY (Ecosystem, Framework, Tool, Global)
        # ----------------------------------------------------
        rule_result = self.registry.classify(rel_path, is_dir=False, project_ecosystems=active_ecosystems)
        if rule_result.rule_id != "default_fallback":
            candidates.append(rule_result)

        # ----------------------------------------------------
        # 5. VENDOR DIRECTORY ANALYSIS
        # ----------------------------------------------------
        is_ext_vendor, vendor_reason = analyze_vendor_status(rel_path, active_ecosystems)
        if is_ext_vendor:
            candidates.append(
                ClassificationResult(
                    category=FileCategory.VENDOR,
                    action=AIAction.EXCLUDE,
                    confidence=0.9,
                    reason=vendor_reason,
                    rule_id="vendor.analysis",
                    priority=RulePriority.ECOSYSTEM,
                )
            )

        # ----------------------------------------------------
        # 6. FILE TYPE CLASSIFIER
        # ----------------------------------------------------
        if record.is_binary:
            candidates.append(
                ClassificationResult(
                    category=FileCategory.BINARY,
                    action=AIAction.DESCRIBE,
                    confidence=0.8,
                    reason="Binary non-text file",
                    rule_id="filetype.binary",
                    priority=RulePriority.FILE_TYPE,
                )
            )
        else:
            base_name = os.path.basename(rel_path).lower()
            norm_rel_path = rel_path.replace("\\", "/").lower()
            ext = (record.extension or "").lower()
            if not ext and "." in base_name:
                ext = f".{base_name.rsplit('.', 1)[1]}"

            # 6a. JSON formats (.json, .jsonc, .json5)
            if ext in (".json", ".jsonc", ".json5"):
                if base_name in ("package-lock.json", "composer.lock") or base_name.endswith("-lock.json"):
                    cat = FileCategory.DEPENDENCY
                    act = AIAction.EXCLUDE
                    reason = "JSON package lockfile"
                elif base_name in ("package.json", "composer.json", "deno.json", "deno.jsonc", "tsconfig.json") or base_name.startswith("tsconfig."):
                    cat = FileCategory.MANIFEST
                    act = AIAction.INCLUDE
                    reason = "JSON project manifest or configuration"
                else:
                    cat = FileCategory.CONFIG
                    act = AIAction.INCLUDE
                    reason = "JSON structured configuration or data file"
                candidates.append(
                    ClassificationResult(
                        category=cat,
                        action=act,
                        confidence=0.85,
                        reason=reason,
                        rule_id="filetype.json",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

            # 6b. YAML formats (.yaml, .yml)
            elif ext in (".yaml", ".yml"):
                if base_name in ("pnpm-lock.yaml", "pnpm-lock.yml") or base_name.endswith(("-lock.yaml", "-lock.yml")):
                    cat = FileCategory.DEPENDENCY
                    act = AIAction.EXCLUDE
                    reason = "YAML package lockfile"
                elif ".github/workflows" in norm_rel_path or base_name in (".gitlab-ci.yml", ".gitlab-ci.yaml", "azure-pipelines.yml", "azure-pipelines.yaml"):
                    cat = FileCategory.CI_CD
                    act = AIAction.INCLUDE
                    reason = "YAML CI/CD workflow specification"
                elif base_name in ("pubspec.yaml", "pubspec.yml", "pnpm-workspace.yaml", "pnpm-workspace.yml", "environment.yml", "environment.yaml"):
                    cat = FileCategory.MANIFEST
                    act = AIAction.INCLUDE
                    reason = "YAML project manifest"
                else:
                    cat = FileCategory.CONFIG
                    act = AIAction.INCLUDE
                    reason = "YAML structured configuration file"
                candidates.append(
                    ClassificationResult(
                        category=cat,
                        action=act,
                        confidence=0.85,
                        reason=reason,
                        rule_id="filetype.yaml",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

            # 6c. Other structured config files (.toml, .ini, .cfg, .conf, .properties, .env templates)
            elif ext in (".toml", ".ini", ".cfg", ".conf", ".properties") or base_name.startswith((".env.", ".editorconfig")):
                candidates.append(
                    ClassificationResult(
                        category=FileCategory.CONFIG,
                        action=AIAction.INCLUDE,
                        confidence=0.85,
                        reason=f"Structured configuration ({ext or base_name})",
                        rule_id="filetype.config",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

            # 6d. Documentation files (.md, .markdown, .rst, .adoc, .txt)
            elif ext in (".md", ".markdown", ".rst", ".adoc", ".txt"):
                candidates.append(
                    ClassificationResult(
                        category=FileCategory.DOCUMENTATION,
                        action=AIAction.INCLUDE,
                        confidence=0.80,
                        reason=f"Documentation file ({ext})",
                        rule_id="filetype.documentation",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

            # 6e. Shell / Script files (.sh, .bash, .zsh, .fish, .ps1, .bat, .cmd)
            elif ext in (".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd"):
                candidates.append(
                    ClassificationResult(
                        category=FileCategory.SCRIPT,
                        action=AIAction.INCLUDE,
                        confidence=0.80,
                        reason=f"Shell / automation script ({ext})",
                        rule_id="filetype.script",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

            # 6f. Programming language source files
            elif (
                (record.language and record.language.lower() not in ("unknown", "text", "binary"))
                or ext in (
                    ".py", ".pyw", ".pyi", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
                    ".rs", ".go", ".java", ".kt", ".kts", ".scala", ".c", ".h", ".cpp",
                    ".hpp", ".cc", ".cxx", ".cs", ".swift", ".dart", ".rb", ".php",
                    ".lua", ".sql", ".html", ".htm", ".css", ".scss", ".sass", ".vue", ".svelte"
                )
            ):
                candidates.append(
                    ClassificationResult(
                        category=FileCategory.SOURCE,
                        action=AIAction.INCLUDE,
                        confidence=0.80,
                        reason=f"{record.language or 'Source'} code file",
                        rule_id="filetype.source",
                        priority=RulePriority.FILE_TYPE,
                    )
                )

        # Resolve candidate with highest precedence
        final_result = resolve_highest_precedence(candidates)
        record.classification = final_result

        # Calculate Priority Score (0–100)
        centrality = self.project_graph.get_centrality_score(rel_path) if self.project_graph else 0
        record.priority_score = calculate_file_priority(
            rel_path=rel_path,
            category=final_result.category,
            is_entry_point=record.is_entry_point,
            centrality_bonus=centrality,
            size_bytes=record.size,
            depth=depth,
        )

        return final_result
