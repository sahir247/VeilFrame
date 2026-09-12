"""
veilframe.image.redteam.probes.metadata_probe — EXIF/XMP metadata attack probe.

Attempts to read EXIF/XMP/IPTC metadata from the sanitized output.
Any readable metadata field → FAIL.
"""

from __future__ import annotations

from typing import List, Optional

from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class MetadataProbe(AttackProbe):
    """Attack: attempt to extract EXIF/XMP metadata from sanitized output."""

    @property
    def name(self) -> str:
        return "metadata"

    def attack(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> ProbeResult:
        findings: List[str] = []

        # Method 1: piexif scan
        try:
            import piexif  # type: ignore
            import io
            from PIL import Image  # type: ignore
            img = Image.open(io.BytesIO(sanitized_bytes))
            raw_exif = img.info.get("exif", b"")
            if raw_exif:
                try:
                    exif_dict = piexif.load(raw_exif)
                    for ifd_name, ifd in exif_dict.items():
                        if isinstance(ifd, dict) and ifd:
                            findings.append(f"EXIF IFD '{ifd_name}' has {len(ifd)} tags")
                except Exception:
                    # If piexif can't parse, the raw bytes themselves are a finding
                    if len(raw_exif) > 4:
                        findings.append(f"Raw EXIF bytes present: {len(raw_exif)} bytes")
        except ImportError:
            # piexif unavailable — use Pillow only
            try:
                import io
                from PIL import Image  # type: ignore
                img = Image.open(io.BytesIO(sanitized_bytes))
                raw_exif = img.info.get("exif", b"")
                if raw_exif and len(raw_exif) > 4:
                    findings.append(f"Raw EXIF bytes present: {len(raw_exif)} bytes")
            except Exception:
                pass

        # Method 2: raw APP1 JPEG marker scan
        if sanitized_bytes[:3] == b"\xff\xd8\xff":
            i = 2
            while i < len(sanitized_bytes) - 3:
                if sanitized_bytes[i] != 0xFF:
                    break
                marker = sanitized_bytes[i + 1]
                if marker == 0xE1:  # APP1 — EXIF/XMP
                    seg_len = int.from_bytes(sanitized_bytes[i + 2: i + 4], "big")
                    seg_data = sanitized_bytes[i + 4: i + 2 + seg_len]
                    if seg_data[:4] == b"Exif":
                        findings.append("JPEG APP1 EXIF marker detected in output")
                    elif seg_data[:28] == b"http://ns.adobe.com/xap/1.0/":
                        findings.append("JPEG APP1 XMP marker detected in output")
                if i + 3 < len(sanitized_bytes):
                    seg_len = int.from_bytes(sanitized_bytes[i + 2: i + 4], "big")
                    i += 2 + seg_len
                else:
                    break

        # Method 3: XMP text scan
        try:
            lower = sanitized_bytes.lower()
            if b"xpacket" in lower or b"ns.adobe.com" in lower:
                findings.append("XMP namespace detected in raw output bytes")
        except Exception:
            pass

        if findings:
            return ProbeResult(
                probe_name=self.name,
                status=CheckStatus.FAIL,
                failure_reason=f"Metadata extracted from sanitized output ({len(findings)} finding(s))",
                findings=findings,
            )

        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
