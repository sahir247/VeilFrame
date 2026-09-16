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
