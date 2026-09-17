"""
veilframe.image.models.transform_plan — Immutable Transformation DAG and Verification Plan.

The Policy Compiler produces two co-dependent artifacts:

    TransformationDAG     -- ordered sequence of TransformTasks to execute.
    VerificationPlan      -- ordered sequence of VerificationTasks to run
                            against the candidate output.

Both are hashed via RFC 8785 canonical JSON and committed into the
IdentityHash preimage before any execution begins.  Post-hashing mutation
raises ImmutableDAGError.

ConstantFill Primitive Isolation
---------------------------------
The CONSTANT_FILL operation is the ONLY primitive permitted for
DESTRUCTIVE-class redactions.  Its signature is:

    apply_constant_fill(
        width: int, height: int,
        mask_polygon: Polygon,
        fill_constant: Tuple[float, ...]
    ) -> RasterPatch

It receives NO canvas memory and NO source pixel buffers.  The returned
RasterPatch is composed onto the output canvas after the call returns.
This structural isolation guarantees source_dependence = ABSENT.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

from .coordinates import Polygon, TransformationGeometryMap, FidelityEvaluationSpace
from .status import RedactionClass, SourceDependence


# ---------------------------------------------------------------------------
# Immutability guard
# ---------------------------------------------------------------------------

class ImmutableDAGError(RuntimeError):
    """Raised when a caller attempts to mutate a sealed/hashed DAG or Plan."""


# ---------------------------------------------------------------------------
# Transform operations
# ---------------------------------------------------------------------------

class TransformOperation(str, Enum):
    """Enumeration of all permitted transformation operations.

    CONSTANT_FILL is the ONLY operation with source_dependence = ABSENT.
    All others are SOURCE_DERIVED and are rejected by the gate for sensitive
    zones unless policy explicitly permits lower strength.

    METADATA_STRIP and THUMBNAIL_REMOVE operate on the container layer and
    do not touch pixel data.
    """
    CONSTANT_FILL = "constant_fill"       # Isolated primitive; ABSENT dependence
    METADATA_STRIP = "metadata_strip"     # Container layer: EXIF/XMP/IPTC removal
    THUMBNAIL_REMOVE = "thumbnail_remove" # Container layer: embedded preview removal
    ORIENTATION_BAKE = "orientation_bake" # Representation layer: EXIF orient -> pixels
    ICC_NORMALIZE = "icc_normalize"       # Representation layer: canonical color profile


@dataclass(frozen=True)
class ConstantFillParams:
    """Typed parameters for the CONSTANT_FILL primitive.

    These are the ONLY values passed to apply_constant_fill().
    No canvas pointer, no source buffer reference.

    Fields
    ------
    canvas_width : int
        Integer width of the output canvas in pixels.
    canvas_height : int
        Integer height of the output canvas in pixels.
    mask_polygon : Polygon
        Polygon in CANONICAL_REPRESENTATION space defining the region to fill.
        Will be clipped to canvas bounds before rasterization.
    fill_constant : Tuple[float, ...]
        Per-channel constant fill values in linear_sRGB, range [0.0, 1.0].
    """
    canvas_width: int
    canvas_height: int
    mask_polygon: Polygon
    fill_constant: Tuple[float, ...]

    def __post_init__(self) -> None:
        if self.canvas_width <= 0 or self.canvas_height <= 0:
            raise ValueError(
                f"ConstantFillParams: canvas dimensions must be > 0; "
                f"got {self.canvas_width}x{self.canvas_height}"
            )
        if not self.fill_constant:
            raise ValueError("fill_constant must have at least one channel")
        if not all(0.0 <= c <= 1.0 for c in self.fill_constant):
            raise ValueError(
                f"fill_constant values must be in [0.0, 1.0]; got {self.fill_constant}"
            )

    def to_dict(self) -> dict:
        return {
            "canvas_width": self.canvas_width,
            "canvas_height": self.canvas_height,
            "mask_polygon": self.mask_polygon.to_dict(),
            "fill_constant": list(self.fill_constant),
        }


# ---------------------------------------------------------------------------
# Transform task (single node in the DAG)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class TransformTask:
    """A single node in the Transformation DAG.

    Fields
    ------
    task_id : str
        Unique, stable identifier within the DAG (e.g., "t-face-0").
    operation : TransformOperation
        The operation to execute.
    redaction_class : RedactionClass
        Strength class; must match what the operation can deliver.
        CONSTANT_FILL => DESTRUCTIVE.  Others => SOURCE_DERIVED.
    source_dependence : SourceDependence
        ABSENT for CONSTANT_FILL; PRESENT for all other operations.
    depends_on : Tuple[str, ...]
        task_ids of tasks that must complete before this task executes.
    params : Optional[ConstantFillParams]
        Typed parameters for CONSTANT_FILL; None for other operations.
    op_params : Dict[str, Any]
        Generic parameters for non-CONSTANT_FILL operations.
    node_id : Optional[str]
        The PrivacyGraph node_id this task addresses (if applicable).
    """
    task_id: str
    operation: TransformOperation
    redaction_class: RedactionClass
    source_dependence: SourceDependence
    depends_on: Tuple[str, ...] = ()
    params: Optional[ConstantFillParams] = None
    op_params: Dict[str, Any] = field(default_factory=dict)
    node_id: Optional[str] = None

    def __post_init__(self) -> None:
        if not self.task_id:
            raise ValueError("TransformTask.task_id must not be empty")
        # Enforce the ConstantFill contract
        if self.operation == TransformOperation.CONSTANT_FILL:
            if self.redaction_class is not RedactionClass.DESTRUCTIVE:
                raise ValueError(
                    "CONSTANT_FILL must have redaction_class=DESTRUCTIVE; "
                    f"got {self.redaction_class}"
                )
            if self.source_dependence is not SourceDependence.ABSENT:
                raise ValueError(
                    "CONSTANT_FILL must have source_dependence=ABSENT; "
                    f"got {self.source_dependence}"
                )
            if self.params is None:
                raise ValueError(
                    "CONSTANT_FILL requires a ConstantFillParams instance"
                )

    def to_dict(self) -> dict:
        d: dict = {
            "task_id": self.task_id,
            "operation": self.operation.value,
            "redaction_class": self.redaction_class.value,
            "source_dependence": self.source_dependence.value,
            "depends_on": list(self.depends_on),
            "node_id": self.node_id,
        }
        if self.params is not None:
            d["params"] = self.params.to_dict()
        if self.op_params:
            d["op_params"] = self.op_params
        return d


# ---------------------------------------------------------------------------
# Transformation DAG
# ---------------------------------------------------------------------------

@dataclass
class TransformationDAG:
    """Immutable, deterministically hashed directed acyclic graph of transform tasks.

    The DAG is sealed by calling seal().  After sealing, tasks cannot be added
    and dag_hash is set.  Attempting to add tasks after sealing raises
    ImmutableDAGError.

    dag_hash : Optional[str]
        SHA-256(RFC8785(self.to_dict())) computed by the compiler's planner.
        Set during seal(); None before sealing.
    """
    tasks: List[TransformTask] = field(default_factory=list)
    geometry_map: Optional[TransformationGeometryMap] = None
    dag_hash: Optional[str] = None
    _sealed: bool = field(default=False, init=False, repr=False, compare=False)

    def add_task(self, task: TransformTask) -> None:
        if self._sealed:
            raise ImmutableDAGError(
                "TransformationDAG is sealed; no further tasks may be added."
            )
        existing_ids = {t.task_id for t in self.tasks}
        if task.task_id in existing_ids:
            raise ValueError(
                f"TransformationDAG: duplicate task_id '{task.task_id}'"
            )
        self.tasks.append(task)

    def topological_order(self) -> List[TransformTask]:
        """Return tasks in topological (dependency-first) order.

        Raises ValueError if a cycle is detected.
        """
        task_map = {t.task_id: t for t in self.tasks}
        visited: set = set()
        order: List[TransformTask] = []

        def _visit(tid: str, stack: set) -> None:
            if tid in stack:
                raise ValueError(
                    f"TransformationDAG: cycle detected involving task '{tid}'"
                )
            if tid in visited:
                return
            stack.add(tid)
            task = task_map.get(tid)
            if task is None:
                raise ValueError(
                    f"TransformationDAG: unknown dependency task_id '{tid}'"
                )
            for dep in task.depends_on:
                _visit(dep, stack)
            stack.discard(tid)
            visited.add(tid)
            order.append(task)

        for t in self.tasks:
            _visit(t.task_id, set())
        return order

    def seal(self, dag_hash: str) -> None:
        """Seal the DAG and record its canonical hash.

        After this call the DAG is immutable.  The hash must be computed by
        the compiler's planner (compiler/planner.py) over RFC 8785 canonical
        JSON of self.to_dict().
        """
        if self._sealed:
            raise ImmutableDAGError("DAG is already sealed.")
        object.__setattr__(self, "_sealed", True)
        object.__setattr__(self, "dag_hash", dag_hash)

    @property
    def is_sealed(self) -> bool:
        return self._sealed

    def to_dict(self) -> dict:
        return {
            "tasks": [t.to_dict() for t in self.topological_order()],
            "geometry_map": (
                self.geometry_map.to_dict()
                if self.geometry_map is not None else None
            ),
        }


# ---------------------------------------------------------------------------
# Verification task
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class VerificationTask:
    """A single verification check in the Verification Plan.

    Fields
    ------
    task_id : str
        Stable identifier.
    check_type : str
        Logical name of the check (e.g., "FACE_REDTEAM", "SSIM_FIDELITY").
    required_detector_class : Optional[str]
        If this check requires a specific detector class.
    required_independence_level : int
        Minimum IndependenceLevel for probes used in this check.
    coverage_scope : str
        CoverageScope.value for the spatial extent of this check.
    depends_on : Tuple[str, ...]
        Verification task_ids that must complete before this one.
    """
    task_id: str
    check_type: str
    required_detector_class: Optional[str] = None
    required_independence_level: int = 3
    coverage_scope: str = "full_canvas"
    depends_on: Tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not self.task_id:
            raise ValueError("VerificationTask.task_id must not be empty")
        if not self.check_type:
            raise ValueError("VerificationTask.check_type must not be empty")

    def to_dict(self) -> dict:
        return {
            "task_id": self.task_id,
            "check_type": self.check_type,
            "required_detector_class": self.required_detector_class,
            "required_independence_level": self.required_independence_level,
            "coverage_scope": self.coverage_scope,
            "depends_on": list(self.depends_on),
        }


# ---------------------------------------------------------------------------
# Verification Plan
# ---------------------------------------------------------------------------

@dataclass
class VerificationPlan:
    """Immutable, hashed ordered sequence of verification tasks.

    Produced by the compiler alongside the TransformationDAG and committed
    into the IdentityHash preimage.

    verification_plan_hash : Optional[str]
        SHA-256(RFC8785(self.to_dict())) set by the compiler's planner.
    fidelity_evaluation_space : Optional[FidelityEvaluationSpace]
        Registered evaluation space for SSIM/PSNR/MAE computation.
    """
    tasks: List[VerificationTask] = field(default_factory=list)
    fidelity_evaluation_space: Optional[FidelityEvaluationSpace] = None
    verification_plan_hash: Optional[str] = None
    _sealed: bool = field(default=False, init=False, repr=False, compare=False)

    def add_task(self, task: VerificationTask) -> None:
        if self._sealed:
            raise ImmutableDAGError(
                "VerificationPlan is sealed; no further tasks may be added."
            )
        existing_ids = {t.task_id for t in self.tasks}
        if task.task_id in existing_ids:
            raise ValueError(
                f"VerificationPlan: duplicate task_id '{task.task_id}'"
            )
        self.tasks.append(task)

    def seal(self, plan_hash: str) -> None:
        """Seal the plan and record its canonical hash."""
        if self._sealed:
            raise ImmutableDAGError("VerificationPlan is already sealed.")
        object.__setattr__(self, "_sealed", True)
        object.__setattr__(self, "verification_plan_hash", plan_hash)

    @property
    def is_sealed(self) -> bool:
        return self._sealed

    def to_dict(self) -> dict:
        return {
            "tasks": [t.to_dict() for t in self.tasks],
            "fidelity_evaluation_space": (
                self.fidelity_evaluation_space.to_dict()
                if self.fidelity_evaluation_space is not None else None
            ),
        }
