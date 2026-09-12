"""
veilframe.image.compiler.planner — Dependency resolution, topological sort, DAG & Plan hashing.

Uses the existing TransformationDAG.add_task() / seal() and
VerificationPlan.add_task() / seal() APIs to produce correctly sealed objects.
"""

from __future__ import annotations

import hashlib
import json
from collections import defaultdict, deque
from typing import Dict, List, Optional, Tuple

from ..models.coordinates import FidelityEvaluationSpace, TransformationGeometryMap
from ..models.transform_plan import (
    TransformationDAG,
    TransformTask,
    VerificationPlan,
    VerificationTask,
)


class CyclicDependencyError(Exception):
    """Raised when the task graph contains a dependency cycle."""


class UnknownDependencyError(Exception):
    """Raised when a task references an unknown dependency task_id."""


def topological_sort(tasks: List[TransformTask]) -> List[TransformTask]:
    """Return a topologically sorted list of TransformTasks using Kahn's algorithm.

    Raises
    ------
    UnknownDependencyError
        If a task depends_on a task_id not in the provided list.
    CyclicDependencyError
        If the dependency graph contains a cycle.
    """
    id_to_task: Dict[str, TransformTask] = {t.task_id: t for t in tasks}

    for task in tasks:
        for dep_id in task.depends_on:
            if dep_id not in id_to_task:
                raise UnknownDependencyError(
                    f"Task {task.task_id!r} depends on unknown task {dep_id!r}"
                )

    in_degree: Dict[str, int] = {t.task_id: 0 for t in tasks}
    successors: Dict[str, List[str]] = defaultdict(list)

    for task in tasks:
        for dep_id in task.depends_on:
            in_degree[task.task_id] += 1
            successors[dep_id].append(task.task_id)

    queue: deque = deque(tid for tid, deg in in_degree.items() if deg == 0)
    sorted_ids: List[str] = []

    while queue:
        tid = queue.popleft()
        sorted_ids.append(tid)
        for succ in successors[tid]:
            in_degree[succ] -= 1
            if in_degree[succ] == 0:
                queue.append(succ)

    if len(sorted_ids) != len(tasks):
        cyclic = {tid for tid, deg in in_degree.items() if deg > 0}
        raise CyclicDependencyError(
            f"Dependency cycle detected among tasks: {sorted(cyclic)}"
        )

    return [id_to_task[tid] for tid in sorted_ids]


class TransformationPlanner:
    """Plans and freezes the TransformationDAG and VerificationPlan.

    Usage
    -----
    planner = TransformationPlanner(geometry_map, fidelity_space)
    planner.add_transform_task(task)
    planner.add_verification_task(vtask)
    dag, plan = planner.compile()
    """

    def __init__(
        self,
        geometry_map: TransformationGeometryMap,
        fidelity_space: FidelityEvaluationSpace,
        schema_version: str = "2.0.0",
    ) -> None:
        self._geometry_map = geometry_map
        self._fidelity_space = fidelity_space
        self._schema_version = schema_version
        self._transform_tasks: List[TransformTask] = []
        self._verification_tasks: List[VerificationTask] = []

    def add_transform_task(self, task: TransformTask) -> None:
        self._transform_tasks.append(task)

    def add_verification_task(self, task: VerificationTask) -> None:
        self._verification_tasks.append(task)

    def compile(self) -> Tuple[TransformationDAG, VerificationPlan]:
        """Sort tasks, seal DAG + Plan, and return both.

        Raises
        ------
        UnknownDependencyError, CyclicDependencyError
        """
        # 1. Build & seal DAG
        sorted_tasks = topological_sort(self._transform_tasks)
        dag = TransformationDAG(geometry_map=self._geometry_map)
        for task in sorted_tasks:
            dag.add_task(task)
        dag_dict = dag.to_dict()
        dag_canonical = json.dumps(dag_dict, sort_keys=True, ensure_ascii=True)
        dag_hash = hashlib.sha256(dag_canonical.encode()).hexdigest()
        dag.seal(dag_hash)

        # 2. Build & seal VerificationPlan
        plan = VerificationPlan(fidelity_evaluation_space=self._fidelity_space)
        for vtask in self._verification_tasks:
            plan.add_task(vtask)
        plan_dict = {**plan.to_dict(), "paired_dag_hash": dag_hash}
        plan_canonical = json.dumps(plan_dict, sort_keys=True, ensure_ascii=True)
        plan_hash = hashlib.sha256(plan_canonical.encode()).hexdigest()
        plan.seal(plan_hash)

        return dag, plan
