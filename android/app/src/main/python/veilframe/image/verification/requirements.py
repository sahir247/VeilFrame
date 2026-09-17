"""
veilframe.image.verification.requirements — Normative verification requirements.

Derives, from a compiled TransformationDAG + VerificationPlan + policy, the
complete set of verification obligations that must be satisfied for a PASS.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import FrozenSet, List, Set

from ..models.status import DetectorClass, CheckStatus
from ..models.transform_plan import TransformationDAG, VerificationPlan, TransformOperation


@dataclass(frozen=True)
class NormativeVerificationRequirements:
    """Derived verification requirements for a specific compiled plan.

    Fields
    ------
    required_check_ids : FrozenSet[str]
        task_ids of required VerificationTasks (required=True).
    required_redteam_probe_classes : FrozenSet[DetectorClass]
        Detector classes that must have an adversarial red-team probe.
    minimum_redteam_independence_level : int
        Minimum independence level for every red-team probe.
    constant_fill_task_ids : FrozenSet[str]
        task_ids from the DAG that are CONSTANT_FILL operations.
    dag_hash : str
        The DAG hash this requirements object was derived from.
    verification_plan_hash : str
        The VerificationPlan hash this was derived from.
    """
    required_check_ids: FrozenSet[str]
    required_redteam_probe_classes: FrozenSet[DetectorClass]
    minimum_redteam_independence_level: int
    constant_fill_task_ids: FrozenSet[str]
    dag_hash: str
    verification_plan_hash: str

    @classmethod
    def from_plan(
        cls,
        dag: TransformationDAG,
        plan: VerificationPlan,
        minimum_independence_level: int = 1,
    ) -> "NormativeVerificationRequirements":
        """Derive requirements from a compiled DAG + VerificationPlan."""
        required_ids: Set[str] = {
            t.task_id for t in plan.tasks if t.required
        }
        probe_classes: Set[DetectorClass] = {
            t.probe_class for t in plan.tasks
            if t.probe_class is not None and t.required
        }
        fill_ids: Set[str] = {
            t.task_id for t in dag.tasks
            if t.operation == TransformOperation.CONSTANT_FILL
        }
        return cls(
            required_check_ids=frozenset(required_ids),
            required_redteam_probe_classes=frozenset(probe_classes),
            minimum_redteam_independence_level=minimum_independence_level,
            constant_fill_task_ids=frozenset(fill_ids),
            dag_hash=dag.dag_hash,
            verification_plan_hash=plan.verification_plan_hash,
        )
