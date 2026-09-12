"""
veilframe.image.redteam.probes.container_probe — Container structure attack probe.

Scans for ANY non-image-data payload in the sanitized output container:
  - Unexpected JPEG markers (COM, APP2-APP15)
  - ICC profile data leakage (can contain device serial numbers)
  - PNG ancillary chunks (tEXt, iTXt, zTXt, eXIf)
"""

from __future__ import annotations

import struct
from typing import List, Optional

from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult

# JPEG markers that should not be present in clean output
_PROHIBITED_JPEG_MARKERS = {
    0xFE: "COM (comment)",
    0xE2: "APP2",
    0xE3: "APP3",
    0xE4: "APP4",
    0xE5: "APP5",
    0xE6: "APP6",
    0xE7: "APP7",
    0xE8: "APP8",
    0xE9: "APP9",
    0xEA: "APP10",
    0xEB: "APP11",
    0xEC: "APP12",
    0xED: "APP13 (Photoshop/IPTC)",
    0xEE: "APP14",
    0xEF: "APP15",
}

# PNG ancillary chunks that must not be present
_PROHIBITED_PNG_CHUNKS = {
    b"tEXt", b"iTXt", b"zTXt", b"eXIf", b"iCCP",
    b"sBIT", b"sRGB", b"cHRM", b"gAMA", b"hIST",
    b"pHYs", b"tIME", b"bKGD", b"sPLT",
}


class ContainerProbe(AttackProbe):
    """Attack: scan for any non-pixel payload in sanitized container."""

    @property
    def name(self) -> str:
        return "container"

    def attack(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> ProbeResult:
        findings: List[str] = []
        data = sanitized_bytes

        if data[:3] == b"\xff\xd8\xff":
            # JPEG scan
            i = 2
            n = len(data)
            while i < n - 1:
                if data[i] != 0xFF:
                    break
                marker = data[i + 1]
                if marker == 0xD9:  # EOI
                    break
                if marker in _PROHIBITED_JPEG_MARKERS:
                    findings.append(
                        f"Prohibited JPEG marker 0xFF{marker:02X} "
                        f"({_PROHIBITED_JPEG_MARKERS[marker]}) found"
                    )
                if marker == 0xE1:
                    # APP1 — already covered by metadata probe
                    pass
                if i + 3 < n and marker not in (0xD8, 0xD9):
                    seg_len = struct.unpack(">H", data[i + 2: i + 4])[0]
                    i += 2 + seg_len
                else:
                    break

        elif data[:8] == b"\x89PNG\r\n\x1a\n":
            # PNG chunk scan
            offset = 8
            n = len(data)
            while offset + 12 <= n:
                chunk_len = struct.unpack(">I", data[offset: offset + 4])[0]
                chunk_type = data[offset + 4: offset + 8]
                if chunk_type in _PROHIBITED_PNG_CHUNKS:
                    findings.append(
                        f"Prohibited PNG chunk type {chunk_type!r} found"
                    )
                if chunk_type == b"IEND":
                    break
                offset += 12 + chunk_len

        if findings:
            return ProbeResult(
                probe_name=self.name,
                status=CheckStatus.FAIL,
                failure_reason=f"Container payload found ({len(findings)} finding(s))",
                findings=findings,
            )
        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
