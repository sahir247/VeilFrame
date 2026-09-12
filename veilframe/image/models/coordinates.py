"""
veilframe.image.models.coordinates — Normative coordinate model.

The system defines four distinct coordinate spaces.  Every measurement,
mask polygon, and fidelity evaluation is explicitly labelled with its space.
Mixing coordinate spaces without a registered transformation is a hard error.

Spaces:
    SOURCE_DECODED       — Pixel grid of the decoded source raster after
                           EXIF orientation is baked but *before* any resizing
                           or cropping.  Origin: top-left.  Unscaled.
    CANONICAL_REPRESENTATION — Intermediate space used as the normalised basis
                           for mask geometry and fidelity mapping.
    OUTPUT               — Pixel grid of the candidate output raster.
    FIDELITY_EVALUATION  — Registered evaluation space where source snapshot
                           and candidate output are geometrically aligned for
                           pixel-accurate SSIM / PSNR / MAE comparison.

Every transformation between spaces is captured in a TransformationGeometryMap.
The QualityGate verifies that the observed output geometry matches the DAG-
declared expected geometry before any fidelity metric is computed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Tuple, List


class CoordinateSpace(str, Enum):
    """Named coordinate spaces used throughout the pipeline."""
    SOURCE_DECODED = "source_decoded"
    CANONICAL_REPRESENTATION = "canonical_representation"
    OUTPUT = "output"
    FIDELITY_EVALUATION = "fidelity_evaluation"


# ---------------------------------------------------------------------------
# Primitive geometry types
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BoundingBox:
    """Axis-aligned bounding box in a named coordinate space.

    All coordinates are in pixels (float to accommodate sub-pixel precision).
    Origin is top-left.  x increases rightward, y increases downward.

    Invariant: x_min < x_max AND y_min < y_max.
    """
    x_min: float
    y_min: float
    x_max: float
    y_max: float
    space: CoordinateSpace

    def __post_init__(self) -> None:
        if self.x_min >= self.x_max:
            raise ValueError(
                f"BoundingBox: x_min ({self.x_min}) must be < x_max ({self.x_max})"
            )
        if self.y_min >= self.y_max:
            raise ValueError(
                f"BoundingBox: y_min ({self.y_min}) must be < y_max ({self.y_max})"
            )

    @property
    def width(self) -> float:
        return self.x_max - self.x_min

    @property
    def height(self) -> float:
        return self.y_max - self.y_min

    @property
    def area(self) -> float:
        return self.width * self.height

    def clamp(self, canvas_w: int, canvas_h: int) -> "BoundingBox":
        """Return a new BoundingBox clamped to canvas bounds."""
        return BoundingBox(
            x_min=max(0.0, self.x_min),
            y_min=max(0.0, self.y_min),
            x_max=min(float(canvas_w), self.x_max),
            y_max=min(float(canvas_h), self.y_max),
            space=self.space,
        )

    def to_dict(self) -> dict:
        return {
            "x_min": self.x_min,
            "y_min": self.y_min,
            "x_max": self.x_max,
            "y_max": self.y_max,
            "space": self.space.value,
        }


@dataclass(frozen=True)
class Polygon:
    """Closed polygon in a named coordinate space.

    Vertices are ordered (clockwise or counter-clockwise; rasterizer is
    winding-order agnostic).  A minimum of 3 vertices is required.

    The canonical rasterization convention:
        Pixel (x, y) has its center at (x + 0.5, y + 0.5).
        A pixel is included in the mask iff its center is within the closed
        polygon.  Anti-aliasing is STRICTLY PROHIBITED: α ∈ {0, 1}.
    """
    vertices: Tuple[Tuple[float, float], ...]
    space: CoordinateSpace

    def __post_init__(self) -> None:
        if len(self.vertices) < 3:
            raise ValueError(
                f"Polygon requires ≥ 3 vertices; got {len(self.vertices)}"
            )

    @classmethod
    def from_bbox(cls, bbox: BoundingBox) -> "Polygon":
        """Construct a rectangular polygon from a bounding box."""
        x0, y0, x1, y1 = bbox.x_min, bbox.y_min, bbox.x_max, bbox.y_max
        return cls(
            vertices=((x0, y0), (x1, y0), (x1, y1), (x0, y1)),
            space=bbox.space,
        )

    def clip_to_canvas(self, canvas_w: int, canvas_h: int) -> "Polygon":
        """Sutherland–Hodgman clip to [0, canvas_w] × [0, canvas_h].

        Returns a new polygon with clipped vertices.  Raises ValueError if
        the result has fewer than 3 vertices (polygon outside canvas).
        """
        def _clip_edge(poly: List, x0: float, y0: float, x1: float, y1: float) -> List:
            """Clip polygon against a single half-plane defined by (x0,y0)→(x1,y1)."""
            if not poly:
                return []
            output: List[Tuple[float, float]] = []
            dx, dy = x1 - x0, y1 - y0

            def _inside(p: Tuple[float, float]) -> bool:
                return dx * (p[1] - y0) - dy * (p[0] - x0) >= 0

            def _intersect(a: Tuple[float, float], b: Tuple[float, float]) -> Tuple[float, float]:
                dxab, dyab = b[0] - a[0], b[1] - a[1]
                denom = dx * dyab - dy * dxab
                if abs(denom) < 1e-12:
                    return a
                t = ((x0 - a[0]) * dy - (y0 - a[1]) * dx) / denom
                return (a[0] + t * dxab, a[1] + t * dyab)

            for i, curr in enumerate(poly):
                prev = poly[i - 1]
                if _inside(curr):
                    if not _inside(prev):
                        output.append(_intersect(prev, curr))
                    output.append(curr)
                elif _inside(prev):
                    output.append(_intersect(prev, curr))
            return output

        W, H = float(canvas_w), float(canvas_h)
        verts: List[Tuple[float, float]] = list(self.vertices)
        # Clip against four half-planes (left, right, top, bottom)
        verts = _clip_edge(verts, 0, 0, 0, H)       # left edge  x ≥ 0
        verts = _clip_edge(verts, W, 0, W, H)        # right edge x ≤ W (reversed)
        # Re-orient right edge: (W,H)→(W,0) so interior is x ≤ W
        verts_r: List[Tuple[float, float]] = list(self.vertices)
        verts_r = _clip_edge(list(self.vertices), 0, 0, 0, H)
        verts_r = _clip_edge(verts_r, 0, H, W, H)    # bottom x,y ≤ H
        verts_r = _clip_edge(verts_r, W, H, W, 0)    # right
        verts_r = _clip_edge(verts_r, W, 0, 0, 0)    # top
        if len(verts_r) < 3:
            raise ValueError(
                "Polygon.clip_to_canvas: polygon lies entirely outside canvas "
                f"({canvas_w}×{canvas_h})"
            )
        return Polygon(vertices=tuple(verts_r), space=self.space)

    def bounding_box(self) -> "BoundingBox":
        xs = [v[0] for v in self.vertices]
        ys = [v[1] for v in self.vertices]
        return BoundingBox(
            x_min=min(xs), y_min=min(ys),
            x_max=max(xs), y_max=max(ys),
            space=self.space,
        )

    def to_dict(self) -> dict:
        return {
            "vertices": [list(v) for v in self.vertices],
            "space": self.space.value,
        }


# ---------------------------------------------------------------------------
# Coordinate transformation map
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class TransformationGeometryMap:
    """Records the geometric mapping between source and output spaces.

    This is the authoritative record committed into the Transformation DAG.
    The GeometryIntegrityAuditor compares the *observed* output geometry
    against these *expected* values and fails closed if they diverge.

    Fields
    ------
    source_width, source_height : int
        Dimensions of the SOURCE_DECODED raster (post-orientation bake).
    output_width, output_height : int
        Expected dimensions of the OUTPUT raster as declared by the DAG.
    scale_x, scale_y : float
        Isotropic or anisotropic scale factors (source → output).
        Identity transformation: scale_x = scale_y = 1.0.
    translate_x, translate_y : float
        Pixel-space translation (source → output).
    has_rotation : bool
        True only if a rotation (other than 0°/90°/180°/270°) is applied.
    rotation_degrees : float
        Clockwise rotation in degrees; 0.0 for no rotation.
    geometry_tolerance_pixels : float
        Maximum tolerated residual in observed vs. expected dimensions.
        Default 0 (exact match required).
    """
    source_width: int
    source_height: int
    output_width: int
    output_height: int
    scale_x: float = 1.0
    scale_y: float = 1.0
    translate_x: float = 0.0
    translate_y: float = 0.0
    has_rotation: bool = False
    rotation_degrees: float = 0.0
    geometry_tolerance_pixels: float = 0.0

    def __post_init__(self) -> None:
        for dim_name, dim_val in [
            ("source_width", self.source_width),
            ("source_height", self.source_height),
            ("output_width", self.output_width),
            ("output_height", self.output_height),
        ]:
            if dim_val <= 0:
                raise ValueError(
                    f"TransformationGeometryMap: {dim_name} must be > 0, got {dim_val}"
                )
        if self.geometry_tolerance_pixels < 0:
            raise ValueError("geometry_tolerance_pixels must be ≥ 0")

    def map_point(self, x: float, y: float) -> Tuple[float, float]:
        """Apply scale + translate (no rotation) to a SOURCE_DECODED point."""
        return (x * self.scale_x + self.translate_x,
                y * self.scale_y + self.translate_y)

    def map_bbox(self, bbox: BoundingBox) -> BoundingBox:
        """Map a SOURCE_DECODED BoundingBox into OUTPUT space."""
        if bbox.space is not CoordinateSpace.SOURCE_DECODED:
            raise ValueError(
                f"Expected SOURCE_DECODED bounding box; got {bbox.space}"
            )
        x0, y0 = self.map_point(bbox.x_min, bbox.y_min)
        x1, y1 = self.map_point(bbox.x_max, bbox.y_max)
        return BoundingBox(
            x_min=min(x0, x1), y_min=min(y0, y1),
            x_max=max(x0, x1), y_max=max(y0, y1),
            space=CoordinateSpace.OUTPUT,
        )

    def geometry_residual(self, observed_w: int, observed_h: int) -> float:
        """Euclidean pixel residual between observed and expected output dims."""
        dw = observed_w - self.output_width
        dh = observed_h - self.output_height
        return math.sqrt(dw * dw + dh * dh)

    def to_dict(self) -> dict:
        return {
            "source_width": self.source_width,
            "source_height": self.source_height,
            "output_width": self.output_width,
            "output_height": self.output_height,
            "scale_x": self.scale_x,
            "scale_y": self.scale_y,
            "translate_x": self.translate_x,
            "translate_y": self.translate_y,
            "has_rotation": self.has_rotation,
            "rotation_degrees": self.rotation_degrees,
            "geometry_tolerance_pixels": self.geometry_tolerance_pixels,
        }


# ---------------------------------------------------------------------------
# FIDELITY_EVALUATION space registration
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class FidelityEvaluationSpace:
    """Registered coordinate space for pixel-accurate fidelity evaluation.

    Both the source snapshot and the candidate output are resampled / aligned
    into this common space before SSIM / PSNR / MAE are computed.  The space
    is registered by the Policy Compiler and committed into the Verification
    Plan hash so it cannot be altered post-compilation.

    Dimensions are chosen such that no upscaling of either input is required
    (min of source and output dimensions, preserving aspect ratio).

    Fields
    ------
    eval_width, eval_height : int
        Pixel dimensions of the evaluation canvas.
    source_to_eval_scale_x, source_to_eval_scale_y : float
        Scale factors applied to map SOURCE_DECODED pixels into eval space.
    output_to_eval_scale_x, output_to_eval_scale_y : float
        Scale factors applied to map OUTPUT pixels into eval space.
    erosion_radius : int
        Morphological erosion radius applied to the expected unmasked region
        before SSIM window evaluation.  Default 5 (for 11×11 Gaussian window).
    minimum_ssim_windows : int
        Minimum number of valid SSIM evaluation windows.  Default 100.
    minimum_window_fraction_of_canvas : float
        Minimum fraction of total canvas windows required.  Default 0.005.
    """
    eval_width: int
    eval_height: int
    source_to_eval_scale_x: float
    source_to_eval_scale_y: float
    output_to_eval_scale_x: float
    output_to_eval_scale_y: float
    erosion_radius: int = 5
    minimum_ssim_windows: int = 100
    minimum_window_fraction_of_canvas: float = 0.005

    def __post_init__(self) -> None:
        for name, val in [
            ("eval_width", self.eval_width),
            ("eval_height", self.eval_height),
        ]:
            if val <= 0:
                raise ValueError(f"FidelityEvaluationSpace: {name} must be > 0")
        if self.erosion_radius < 0:
            raise ValueError("erosion_radius must be ≥ 0")
        if self.minimum_ssim_windows < 1:
            raise ValueError("minimum_ssim_windows must be ≥ 1")
        if not (0.0 < self.minimum_window_fraction_of_canvas <= 1.0):
            raise ValueError(
                "minimum_window_fraction_of_canvas must be in (0, 1]"
            )

    def required_windows(self, total_canvas_windows: int) -> int:
        """Exact minimum window count per the specification."""
        import math
        return max(
            self.minimum_ssim_windows,
            math.ceil(self.minimum_window_fraction_of_canvas * total_canvas_windows),
        )

    def to_dict(self) -> dict:
        return {
            "eval_width": self.eval_width,
            "eval_height": self.eval_height,
            "source_to_eval_scale_x": self.source_to_eval_scale_x,
            "source_to_eval_scale_y": self.source_to_eval_scale_y,
            "output_to_eval_scale_x": self.output_to_eval_scale_x,
            "output_to_eval_scale_y": self.output_to_eval_scale_y,
            "erosion_radius": self.erosion_radius,
            "minimum_ssim_windows": self.minimum_ssim_windows,
            "minimum_window_fraction_of_canvas": self.minimum_window_fraction_of_canvas,
        }
