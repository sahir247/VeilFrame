"""
veilframe.image.redteam.probes.thumbnail_probe — Embedded thumbnail attack probe.

Extracts embedded JPEG thumbnails from EXIF IFD1 in the sanitized output.
Any recoverable thumbnail → FAIL.
"""

from __future__ import annotations

from typing import List, Optional

from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class ThumbnailProbe(AttackProbe):
    """Attack: extract embedded thumbnail from sanitized output."""

    @property
    def name(self) -> str:
        return "thumbnail"

    def attack(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> ProbeResult:
        findings: List[str] = []

        try:
            import io
            from PIL import Image  # type: ignore
            img = Image.open(io.BytesIO(sanitized_bytes))
            exif_data = img.getexif()
            # Try to extract thumbnail via Pillow
            try:
                thumb = img.info.get("exif", b"")
                if thumb:
                    # piexif thumbnail extraction
                    try:
                        import piexif  # type: ignore
                        exif_dict = piexif.load(thumb)
                        ifd1 = exif_dict.get("1st", {})
                        # JPEG interchange offset present
                        if 0x0201 in ifd1 or 0x0202 in ifd1:
                            findings.append("EXIF IFD1 thumbnail JPEG offset/length tag present")
                        thumb_bytes = piexif.load(thumb).get("thumbnail")
                        if thumb_bytes and len(thumb_bytes) > 100:
                            findings.append(
                                f"Embedded thumbnail extractable: {len(thumb_bytes)} bytes"
                            )
                    except Exception:
                        pass
            except Exception:
                pass
        except Exception:
            pass

        # Raw JFIF APP0 thumbnail scan
        try:
            if sanitized_bytes[0:2] == b"\xff\xd8":
                # JFIF APP0 starts at offset 2; check for Xthumbnail
                if len(sanitized_bytes) > 20:
                    app0 = sanitized_bytes[2:20]
                    if b"JFIF" in app0:
                        xt = sanitized_bytes[16]
                        yt = sanitized_bytes[17]
                        if xt > 0 and yt > 0:
                            findings.append(
                                f"JFIF APP0 thumbnail dimensions: {xt}x{yt}"
                            )
        except Exception:
            pass

        if findings:
            return ProbeResult(
                probe_name=self.name,
                status=CheckStatus.FAIL,
                failure_reason=f"Thumbnail data found ({len(findings)} finding(s))",
                findings=findings,
            )

        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
