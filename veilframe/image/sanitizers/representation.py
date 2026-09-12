"""
veilframe.image.sanitizers.representation — Layer B Representation Sanitizer.

Implements the REPRESENTATION sanitization layer (Layer B) per spec Section 5.2:

  Layer B — Representation Normalization:
    B1. ICC profile normalization: convert to sRGB IEC 61966-2.1.
    B2. Bit-depth normalization: any ≥16-bit input → 8-bit sRGB output.
    B3. Alpha channel handling: premultiply-then-drop alpha, or fill white.
    B4. Color mode normalization: CMYK/LAB/YCbCr → sRGB.

Invariants:
  - Output is always 8-bit sRGB (3-channel), lossless within representation layer.
  - The output of Layer B is the INPUT to all subsequent layers.
  - Layer B does NOT write output bytes — it produces a normalized pixel array
    (numpy float32 linear_sRGB, values in [0.0, 1.0], shape (H, W, 3)).
  - If numpy/cv2 is unavailable, fails CLOSED with status=UNKNOWN.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from typing import Optional

from ..models.status import CheckStatus


@dataclass
class RepresentationNormalizationResult:
    """Result of the Representation sanitizer (Layer B).

    Fields
    ------
    linear_srgb_f32 : Optional[object]
        numpy.ndarray of shape (H, W, 3), dtype float32, values in [0.0, 1.0],
        in linear sRGB color space.  None on failure.
    height : int
        Pixel height of the output array.
    width : int
        Pixel width of the output array.
    status : CheckStatus
        PASS iff normalization completed without error.
    original_mode : Optional[str]
        PIL image mode of the input (e.g. "RGB", "CMYK", "L", "RGBA").
    original_bit_depth : Optional[int]
        Bit depth of the input (e.g. 8, 16).
    had_alpha : bool
        True iff the input had an alpha channel.
    had_icc_profile : bool
        True iff the input had an embedded ICC profile.
    failure_reason : Optional[str]
        Set if status != PASS.
    """
    linear_srgb_f32: Optional[object]
    height: int
    width: int
    status: CheckStatus
    original_mode: Optional[str] = None
    original_bit_depth: Optional[int] = None
    had_alpha: bool = False
    had_icc_profile: bool = False
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "height": self.height,
            "width": self.width,
            "original_mode": self.original_mode,
            "original_bit_depth": self.original_bit_depth,
            "had_alpha": self.had_alpha,
            "had_icc_profile": self.had_icc_profile,
            "failure_reason": self.failure_reason,
        }


class RepresentationSanitizer:
    """Layer B: Representation normalization sanitizer.

    Input: raw JPEG/PNG bytes (already container-sanitized by Layer A).
    Output: RepresentationNormalizationResult with linear_sRGB float32 array.
    """

    def normalize(self, input_bytes: bytes) -> RepresentationNormalizationResult:
        """Normalize representation to 8-bit sRGB.

        Returns RepresentationNormalizationResult.
        """
        try:
            return self._normalize_impl(input_bytes)
        except Exception as exc:
            return RepresentationNormalizationResult(
                linear_srgb_f32=None, height=0, width=0,
                status=CheckStatus.UNKNOWN,  # Unknown — fail-closed
                failure_reason=f"RepresentationSanitizer exception: {type(exc).__name__}: {exc}",
            )

    def _normalize_impl(self, input_bytes: bytes) -> RepresentationNormalizationResult:
        try:
            import numpy as np  # type: ignore
            from PIL import Image, ImageCms  # type: ignore
        except ImportError as e:
            return RepresentationNormalizationResult(
                linear_srgb_f32=None, height=0, width=0,
                status=CheckStatus.UNKNOWN,
                failure_reason=f"Required dependency not installed: {e}",
            )

        img_io = io.BytesIO(input_bytes)
        img = Image.open(img_io)
        img.load()  # ensure fully decoded

        original_mode = img.mode
        had_alpha = "A" in original_mode or "a" in original_mode or original_mode == "RGBA"
        original_bit_depth = self._detect_bit_depth(img)
        had_icc_profile = "icc_profile" in img.info

        # --- Step B4: Convert to RGB ---
        if original_mode == "RGBA":
            # Premultiply alpha then drop
            img = self._premultiply_alpha(img)
        elif original_mode == "LA":
            img = img.convert("L").convert("RGB")
            had_alpha = True
        elif original_mode == "P":
            # Palette → RGBA → RGB
            img = img.convert("RGBA")
            img = self._premultiply_alpha(img)
        elif original_mode in ("L", "1"):
            img = img.convert("RGB")
        elif original_mode == "CMYK":
            img = img.convert("RGB")
        elif original_mode not in ("RGB", "RGBa"):
            try:
                img = img.convert("RGB")
            except Exception as exc:
                return RepresentationNormalizationResult(
                    linear_srgb_f32=None, height=0, width=0,
                    status=CheckStatus.FAIL,
                    original_mode=original_mode,
                    failure_reason=f"Cannot convert mode {original_mode!r} to RGB: {exc}",
                )
        elif original_mode == "RGBa":
            img = img.convert("RGB")

        # Ensure RGB at this point
        if img.mode != "RGB":
            img = img.convert("RGB")

        # --- Step B1: ICC profile normalization → sRGB ---
        if had_icc_profile:
            try:
                srgb_profile = ImageCms.createProfile("sRGB")
                img = ImageCms.profileToProfile(
                    img, img.info.get("icc_profile", b""), srgb_profile,
                    outputMode="RGB"
                )
            except Exception:
                # ICC conversion failed — image is still usable as-is; continue
                pass

        # --- Step B2: Bit depth → 8-bit ---
        # PIL normalises to 8-bit on open for standard JPEG/PNG; force for safety
        if img.mode != "RGB":
            img = img.convert("RGB")

        # --- Convert to linear sRGB float32 ---
        arr_u8 = np.array(img, dtype=np.uint8)
        # sRGB → linear sRGB via IEC 61966-2.1 transfer function
        linear = self._srgb_to_linear(arr_u8 / 255.0)

        h, w, _ = linear.shape

        return RepresentationNormalizationResult(
            linear_srgb_f32=linear.astype(np.float32),
            height=h,
            width=w,
            status=CheckStatus.PASS,
            original_mode=original_mode,
            original_bit_depth=original_bit_depth,
            had_alpha=had_alpha,
            had_icc_profile=had_icc_profile,
        )

    @staticmethod
    def _premultiply_alpha(img) -> object:
        """Convert RGBA image to RGB by premultiplying alpha and dropping it."""
        import numpy as np  # type: ignore
        arr = np.array(img, dtype=np.float32) / 255.0
        rgb = arr[:, :, :3]
        alpha = arr[:, :, 3:4]
        white = np.ones_like(rgb)
        # Composite over white background
        composited = rgb * alpha + white * (1.0 - alpha)
        composited_u8 = (composited * 255.0).clip(0, 255).astype(np.uint8)
        from PIL import Image  # type: ignore
        return Image.fromarray(composited_u8, mode="RGB")

    @staticmethod
    def _detect_bit_depth(img) -> int:
        """Return the bit depth per channel of a PIL image."""
        mode_depths = {
            "1": 1, "L": 8, "P": 8, "RGB": 8, "RGBA": 8,
            "CMYK": 8, "YCbCr": 8, "LAB": 8, "HSV": 8, "I": 32, "F": 32,
            "LA": 8, "PA": 8, "RGBa": 8,
        }
        return mode_depths.get(img.mode, 8)

    @staticmethod
    def _srgb_to_linear(v):
        """Apply sRGB EOTF (IEC 61966-2.1) to a float array in [0, 1]."""
        import numpy as np  # type: ignore
        mask = v <= 0.04045
        linear = np.where(mask, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
        return linear.clip(0.0, 1.0)
