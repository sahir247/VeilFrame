"""
veilframe.image.verification.completeness — Independent CompletenessAuditor.

The CompletenessAuditor (Execution Completeness Axis) independently verifies
that every task declared in the VerificationPlan was executed, every
CONSTANT_FILL task in the DAG produced a valid redaction record, and no
extra un-planned tasks were executed.

Uses the real TransformationDAG and VerificationPlan APIs (sealed objects
produced by TransformationPlanner.compile()).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, FrozenSet, List, Optional, Set

from ..models.status import CheckStatus
from ..models.transform_plan import (
    TransformationDAG,
    TransformOperation,
    VerificationPlan,
)
from ..models.report import RedactionAuditRecord


@dataclass
class CompletenessAuditResult:
    """Result of the execution completeness audit."""
    status: CheckStatus
    dag_hash_verified: bool
    plan_hash_verified: bool
    executed_task_ids: FrozenSet[str]
    required_task_ids: FrozenSet[str]
    missing_task_ids: FrozenSet[str]
    unexpected_task_ids: FrozenSet[str]
    redaction_records_valid: bool
    failure_reasons: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "dag_hash_verified": self.dag_hash_verified,
            "plan_hash_verified": self.plan_hash_verified,
            "executed_task_ids": sorted(self.executed_task_ids),
            "required_task_ids": sorted(self.required_task_ids),
            "missing_task_ids": sorted(self.missing_task_ids),
            "unexpected_task_ids": sorted(self.unexpected_task_ids),
            "redaction_records_valid": self.redaction_records_valid,
            "failure_reasons": self.failure_reasons,
        }


class CompletenessAuditor:
    """Independently verifies execution completeness against the sealed plan.

    Instantiated with the compiled (sealed) DAG + VerificationPlan.
    """

    def __init__(
        self,
        dag: TransformationDAG,
        plan: VerificationPlan,
    ) -> None:
        self._dag = dag
        self._plan = plan
        # All verification task IDs from the plan (all are considered required)
        self._required_task_ids: FrozenSet[str] = frozenset(
            t.task_id for t in plan.tasks
        )
        self._all_dag_task_ids: FrozenSet[str] = frozenset(
            t.task_id for t in dag.tasks
        )
        self._fill_task_ids: FrozenSet[str] = frozenset(
            t.task_id for t in dag.tasks
            if t.operation == TransformOperation.CONSTANT_FILL
        )

    def audit(
        self,
        executed_verification_task_ids: Set[str],
        redaction_records: List[RedactionAuditRecord],
        claimed_dag_hash: str,
        claimed_plan_hash: str,
    ) -> CompletenessAuditResult:
        """Audit execution completeness."""
        failure_reasons: List[str] = []

        # 1. Verify hash claims
        dag_hash_verified = (claimed_dag_hash == self._dag.dag_hash)
        plan_hash_verified = (claimed_plan_hash == self._plan.verification_plan_hash)
        if not dag_hash_verified:
            failure_reasons.append(
                f"DAG hash mismatch: claimed={claimed_dag_hash!r} "
                f"expected={self._dag.dag_hash!r}"
            )
        if not plan_hash_verified:
            failure_reasons.append(
                f"VerificationPlan hash mismatch: claimed={claimed_plan_hash!r} "
                f"expected={self._plan.verification_plan_hash!r}"
            )

        # 2. Check required verification tasks
        executed_set = frozenset(executed_verification_task_ids)
        missing = self._required_task_ids - executed_set
        unexpected = executed_set - frozenset(t.task_id for t in self._plan.tasks)
        if missing:
            failure_reasons.append(f"Missing required verification tasks: {sorted(missing)}")
        if unexpected:
            failure_reasons.append(f"Unexpected verification tasks: {sorted(unexpected)}")

        # 3. Check redaction records for CONSTANT_FILL tasks
        redaction_records_valid = True
        executed_fill_ids = {r.task_id for r in redaction_records}
        missing_fills = self._fill_task_ids - executed_fill_ids
        if missing_fills:
            failure_reasons.append(
                f"Missing redaction records for fill tasks: {sorted(missing_fills)}"
            )
            redaction_records_valid = False

        for rec in redaction_records:
            if rec.execution_status != CheckStatus.PASS:
                failure_reasons.append(
                    f"Redaction record task {rec.task_id!r}: status="
                    f"{rec.execution_status.value}"
                )
                redaction_records_valid = False
            if rec.mask_coverage_pct < 100.0:
                failure_reasons.append(
                    f"Task {rec.task_id!r}: mask_coverage_pct="
                    f"{rec.mask_coverage_pct:.2f}% < 100%"
                )
                redaction_records_valid = False
            if rec.replacement_coverage_pct < 100.0:
                failure_reasons.append(
                    f"Task {rec.task_id!r}: replacement_coverage_pct="
                    f"{rec.replacement_coverage_pct:.2f}% < 100%"
                )
                redaction_records_valid = False
            if rec.anti_aliasing_present:
                failure_reasons.append(
                    f"Task {rec.task_id!r}: anti-aliasing detected (α ∉ {{0,1}})"
                )
                redaction_records_valid = False

        overall = CheckStatus.PASS if not failure_reasons else CheckStatus.FAIL

        return CompletenessAuditResult(
            status=overall,
            dag_hash_verified=dag_hash_verified,
            plan_hash_verified=plan_hash_verified,
            executed_task_ids=executed_set,
            required_task_ids=self._required_task_ids,
            missing_task_ids=missing,
            unexpected_task_ids=unexpected,
            redaction_records_valid=redaction_records_valid,
            failure_reasons=failure_reasons,
        )
