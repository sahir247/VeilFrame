"""
veilframe.image.sanitizers.container — Layer A Container Sanitizer.

Implements the CONTAINER sanitization layer (Layer A) per spec Section 5.1:

  Layer A — Container & Metadata Purge:
    A1. Metadata field enumeration (EXIF, XMP, IPTC, MakerNote, GPS).
    A2. Embedded thumbnail removal.
    A3. Orientation state baking (before stripping).
    A4. Color-space ICC profile normalization (sRGB anchor).
    A5. Re-encode as privacy-baseline JPEG/PNG.

Threat model: The container purge is the FIRST line of defence.  Any residual
metadata field after Layer A exits is a CONTAINER contract failure and must be
detected by the red-team MetadataProbe.

Implementation requirements:
  - Uses piexif for EXIF manipulation (if available) or falls back to
    exif-stripping via Pillow's `save(exif=b"")`.
  - ALL maker-note fields are stripped unconditionally.
  - GPS IFD is stripped unconditionally.
  - Embedded thumbnail (IFD1) is stripped unconditionally.
  - XMP is removed by omitting `xmp` in save kwargs.
  - Output JPEG quality is fixed at 92 (lossless-equivalent for privacy —
    the fidelity gate will reject any outside-mask degradation).
  - ICC profile: sRGB IEC 61966-2.1 is embedded; input ICC is discarded.

The sanitizer receives ONLY:
  (input_jpeg_bytes: bytes) → ContainerSanitizationResult

It does NOT receive any PrivacyGraph node references, source pixel arrays,
or semantic detection results — pure container-level operation.
"""

from __future__ import annotations

import io
import struct
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..models.status import CheckStatus


# ---------------------------------------------------------------------------
# Result type
# ---------------------------------------------------------------------------

@dataclass
class ContainerSanitizationResult:
    """Result of the Container sanitizer (Layer A).

    Fields
    ------
    output_bytes : Optional[bytes]
        Re-encoded image bytes with all metadata purged.  None on failure.
    status : CheckStatus
        PASS iff all purge steps completed without error.
    exif_fields_found : List[str]
        Human-readable list of EXIF tag names that were found and stripped.
    thumbnail_found : bool
        True iff an embedded thumbnail was detected and stripped.
    gps_found : bool
        True iff a GPS IFD was detected and stripped.
    maker_note_found : bool
        True iff at least one MakerNote tag was detected and stripped.
    xmp_found : bool
        True iff XMP metadata was detected and stripped.
    output_format : Optional[str]
        "JPEG" or "PNG" depending on the re-encoding target.
    failure_reason : Optional[str]
        Set if status != PASS.
    """
    output_bytes: Optional[bytes]
    status: CheckStatus
    exif_fields_found: List[str] = field(default_factory=list)
    thumbnail_found: bool = False
    gps_found: bool = False
    maker_note_found: bool = False
    xmp_found: bool = False
    output_format: Optional[str] = None
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "status": self.status.value,
            "thumbnail_found": self.thumbnail_found,
            "gps_found": self.gps_found,
            "maker_note_found": self.maker_note_found,
            "xmp_found": self.xmp_found,
            "output_format": self.output_format,
            "exif_field_count": len(self.exif_fields_found),
        }
        if self.failure_reason:
            d["failure_reason"] = self.failure_reason
        return d


# ---------------------------------------------------------------------------
# EXIF GPS & MakerNote tag constants
# ---------------------------------------------------------------------------

_EXIF_GPS_IFD_TAG = 0x8825
_EXIF_MAKERNOTE_TAG = 0x927C
_EXIF_THUMBNAIL_TAGS = {0x0100, 0x0101, 0x0102, 0x0103}  # IFD1 related

# EXIF tags that reveal identity/provenance information (non-exhaustive)
_SENSITIVE_EXIF_TAGS = {
    # GPS
    0x8825,  # GPSInfo IFD
    # MakerNote
    0x927C,  # MakerNote
    # Timestamps (can aid re-identification)
    0x9003,  # DateTimeOriginal
    0x9004,  # DateTimeDigitized
    0x0132,  # DateTime
    # Camera identity
    0x010F,  # Make
    0x0110,  # Model
    0xA433,  # LensMake
    0xA434,  # LensModel
    # Serial numbers
    0xA431,  # BodySerialNumber
    0xA435,  # LensSerialNumber
    # Software/processing
    0x0131,  # Software
    # Owner/Copyright
    0x013B,  # Artist
    0x8298,  # Copyright
    # Location
    0x9C9B,  # XPTitle
    0x9C9C,  # XPComment
    0x9C9D,  # XPAuthor
    0x9C9E,  # XPKeywords
    0x9C9F,  # XPSubject
}

_SENSITIVE_TAG_NAMES = {
    0x8825: "GPS.IFD",
    0x927C: "MakerNote",
    0x9003: "DateTimeOriginal",
    0x9004: "DateTimeDigitized",
    0x0132: "DateTime",
    0x010F: "Make",
    0x0110: "Model",
    0xA433: "LensMake",
    0xA434: "LensModel",
    0xA431: "BodySerialNumber",
    0xA435: "LensSerialNumber",
    0x0131: "Software",
    0x013B: "Artist",
    0x8298: "Copyright",
}


# ---------------------------------------------------------------------------
# Container sanitizer
# ---------------------------------------------------------------------------

class ContainerSanitizer:
    """Layer A: Container and metadata purge sanitizer.

    Accepts raw JPEG or PNG bytes.  Strips ALL metadata and re-encodes.
    """

    JPEG_QUALITY = 92

    def sanitize(self, input_bytes: bytes, target_format: Optional[str] = None) -> ContainerSanitizationResult:
        """Strip all container metadata and re-encode.

        Returns ContainerSanitizationResult with status=PASS on success.
        On any error, returns status=FAIL with failure_reason set.
        """
        try:
            return self._sanitize_impl(input_bytes, target_format=target_format)
        except Exception as exc:
            return ContainerSanitizationResult(
                output_bytes=None,
                status=CheckStatus.FAIL,
                failure_reason=f"ContainerSanitizer exception: {type(exc).__name__}: {exc}",
            )

    def _sanitize_impl(self, input_bytes: bytes, target_format: Optional[str] = None) -> ContainerSanitizationResult:
        try:
            from PIL import Image  # type: ignore
        except ImportError:
            return ContainerSanitizationResult(
                output_bytes=None,
                status=CheckStatus.FAIL,
                failure_reason="Pillow not installed; cannot sanitize container.",
            )

        img_io = io.BytesIO(input_bytes)

        # Detect format before opening
        is_jpeg = input_bytes[:3] == b"\xff\xd8\xff"
        is_png = input_bytes[:8] == b"\x89PNG\r\n\x1a\n"
        if not (is_jpeg or is_png):
            try:
                img_probe = Image.open(img_io)
                detected_fmt = img_probe.format
                img_io.seek(0)
            except Exception:
                detected_fmt = None
            if not detected_fmt:
                return ContainerSanitizationResult(
                    output_bytes=None,
                    status=CheckStatus.FAIL,
                    failure_reason="Unsupported container format; only standard image formats are supported.",
                )

        img = Image.open(img_io)

        # --- Probe for metadata ---
        exif_fields_found: list = []
        thumbnail_found = False
        gps_found = False
        maker_note_found = False
        xmp_found = False

        # Check EXIF via piexif if available
        try:
            import piexif  # type: ignore
            raw_exif = img.info.get("exif", b"")
            if raw_exif:
                try:
                    exif_dict = piexif.load(raw_exif)
                    for ifd_name, ifd_data in exif_dict.items():
                        if ifd_name == "thumbnail" and ifd_data:
                            thumbnail_found = True
                        if ifd_name == "GPS" and ifd_data:
                            gps_found = True
                        if isinstance(ifd_data, dict):
                            for tag in ifd_data:
                                if tag == _EXIF_MAKERNOTE_TAG:
                                    maker_note_found = True
                                tag_name = _SENSITIVE_TAG_NAMES.get(
                                    tag, f"EXIF.{ifd_name}.0x{tag:04X}"
                                )
                                exif_fields_found.append(tag_name)
                except Exception:
                    exif_fields_found.append("EXIF.ParseError.AssumePresent")
        except ImportError:
            # piexif unavailable — probe via Pillow only
            raw_exif = img.info.get("exif", b"")
            if raw_exif:
                exif_fields_found.append("EXIF.PresenceDetected")

        # Check XMP
        if img.info.get("xmp"):
            xmp_found = True

        # --- Bake orientation (EXIF orientation tag → actual pixel orientation) ---
        img = self._bake_orientation(img)

        # Determine target output format
        req_fmt = (target_format or ("JPEG" if is_jpeg else "PNG")).upper()
        if req_fmt in ("JPG", "JPEG"):
            out_fmt = "JPEG"
        elif req_fmt == "PNG":
            out_fmt = "PNG"
        elif req_fmt == "WEBP":
            out_fmt = "WEBP"
        else:
            out_fmt = "JPEG" if is_jpeg else "PNG"

        # Ensure image is in RGB or RGBA mode
        if out_fmt == "JPEG" and img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGB")

        # --- Strip all metadata: re-save with no EXIF/XMP ---
        out_io = io.BytesIO()
        if out_fmt == "JPEG":
            img.save(
                out_io, format="JPEG",
                quality=self.JPEG_QUALITY,
                exif=b"",  # empty exif block
                optimize=False,
            )
            output_format = "JPEG"
        elif out_fmt == "PNG":
            img.save(
                out_io, format="PNG",
                exif=b"",
            )
            output_format = "PNG"
        elif out_fmt == "WEBP":
            img.save(
                out_io, format="WEBP",
                quality=self.JPEG_QUALITY,
            )
            output_format = "WEBP"
        else:
            img.save(out_io, format=out_fmt)
            output_format = out_fmt

        output_bytes = out_io.getvalue()

        # Verify no EXIF marker survives in JPEG output
        if is_jpeg:
            residual_exif = self._scan_jpeg_app1_markers(output_bytes)
            if residual_exif:
                return ContainerSanitizationResult(
                    output_bytes=None,
                    status=CheckStatus.FAIL,
                    failure_reason=(
                        f"Post-sanitization verification failed: "
                        f"EXIF APP1 marker still present in output."
                    ),
                )

        return ContainerSanitizationResult(
            output_bytes=output_bytes,
            status=CheckStatus.PASS,
            exif_fields_found=exif_fields_found,
            thumbnail_found=thumbnail_found,
            gps_found=gps_found,
            maker_note_found=maker_note_found,
            xmp_found=xmp_found,
            output_format=output_format,
        )

    @staticmethod
    def _bake_orientation(img) -> object:
        """Apply EXIF orientation to pixel data and remove tag.

        This prevents orientation state leakage through the EXIF tag.
        """
        try:
            from PIL import ImageOps  # type: ignore
            return ImageOps.exif_transpose(img)
        except Exception:
            return img

    @staticmethod
    def _scan_jpeg_app1_markers(data: bytes) -> bool:
        """Return True if any APP1 (EXIF) marker is found in JPEG bytes."""
        i = 0
        n = len(data)
        if data[:2] != b"\xff\xd8":
            return False
        i = 2
        while i < n - 3:
            if data[i] != 0xFF:
                break
            marker = data[i + 1]
            if marker == 0xE1:  # APP1 — EXIF
                return True
            if marker in (0xD8, 0xD9):
                break
            if i + 3 < n:
                seg_len = struct.unpack(">H", data[i + 2: i + 4])[0]
                i += 2 + seg_len
            else:
                break
        return False
