"""
Shared cryptographic utilities.

Extracted from validator.py to break the circular import between
veilframe.core.validator and veilframe.quality.adapters.vmaf.

Both modules (and any other code) should import compute_sha256 from here,
not from validator.py. The validator re-exports it for backward compatibility.
"""
import hashlib
import json
import math
from pathlib import Path
from typing import Any


def compute_sha256(file_path: Path) -> str:
    """Computes SHA-256 cryptographic hash of the specified file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def _serialize_rfc8785_string(s: str) -> str:
    """
    Serializes a JSON string according to RFC 8785 Section 3.2.4.
    Escapes only quotation mark ("), reverse solidus (\\), and control characters (U+0000..U+001F).
    All other Unicode characters are written literally in UTF-8.
    """
    out = ['"']
    for char in s:
        code = ord(char)
        if char == '"':
            out.append('\\"')
        elif char == '\\':
            out.append('\\\\')
        elif code < 0x20:
            out.append(f"\\u{code:04x}")
        else:
            out.append(char)
    out.append('"')
    return "".join(out)


def _serialize_rfc8785_number(n: float | int) -> str:
    """
    Serializes a number according to RFC 8785 Section 3.2.3 and ECMAScript 7.1.12.1.
    """
    if isinstance(n, int):
        return str(n)
    
    if math.isnan(n) or math.isinf(n):
        raise ValueError("NaN and Infinity are forbidden in RFC 8785 JSON")

    if n == 0.0:
        return "0"

    # If it is an exact integer within safe precision range
    if n.is_integer() and -9007199254740991 <= n <= 9007199254740991:
        return str(int(n))

    # Shortest round-tripping float representation in ECMAScript / Python
    # Python 3 repr(float) uses the David Gay algorithm (same as V8/ECMAScript ToString)
    s = repr(n)
    # Ensure exponent format is lowercase 'e' without '+' sign (e.g. 1e20 not 1e+20, 1e-05 not 1e-05)
    if "e" in s or "E" in s:
        s = s.lower()
        parts = s.split("e")
        exp = int(parts[1])
        s = f"{parts[0]}e{exp}"
    return s


def canonicalize_rfc8785(data: Any) -> bytes:
    """
    Genuine RFC 8785 (JSON Canonicalization Scheme - JCS) serializer.
    Produces deterministic canonical UTF-8 bytes for cryptographic signatures and manifests.
    """
    def _encode(val: Any) -> str:
        if val is None:
            return "null"
        elif isinstance(val, bool):
            return "true" if val else "false"
        elif isinstance(val, (int, float)):
            return _serialize_rfc8785_number(val)
        elif isinstance(val, str):
            return _serialize_rfc8785_string(val)
        elif isinstance(val, (list, tuple)):
            return "[" + ",".join(_encode(item) for item in val) + "]"
        elif isinstance(val, dict):
            # Keys sorted by UTF-16 code units (RFC 8785 Section 3.2.2)
            sorted_keys = sorted(val.keys(), key=lambda k: k.encode("utf-16be"))
            return "{" + ",".join(
                f"{_serialize_rfc8785_string(k)}:{_encode(val[k])}" for k in sorted_keys
            ) + "}"
        else:
            raise TypeError(f"Type {type(val)} is not JSON serializable under RFC 8785")

    return _encode(data).encode("utf-8")
