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

    sign = "-" if math.copysign(1.0, n) < 0 else ""
    abs_n = abs(n)

    # Exact integer check within safe precision range
    if abs_n.is_integer() and abs_n <= 9007199254740991:
        if abs_n < 1e21:
            return sign + str(int(abs_n))

    # Parse Python float repr to extract significant digits and exponent
    s = repr(abs_n)
    if "e" in s or "E" in s:
        parts = s.lower().split("e")
        mantissa_str = parts[0].replace(".", "")
        exp = int(parts[1])
        if "." in parts[0]:
            dot_pos = parts[0].index(".")
            k = len(mantissa_str)
            n_exp = exp + dot_pos
        else:
            k = len(mantissa_str)
            n_exp = exp + k
        m = mantissa_str
    else:
        parts = s.split(".")
        m = parts[0] + (parts[1] if len(parts) > 1 else "")
        m = m.lstrip("0")
        if not m:
            return "0"
        k = len(m)
        if parts[0] == "0":
            leading_zeros = len(parts[1]) - len(parts[1].lstrip("0"))
            n_exp = -leading_zeros
        else:
            n_exp = len(parts[0])

    # Standard ECMAScript 7.1.12.1 ToString formatting
    if k <= n_exp <= 21:
        res = m + "0" * (n_exp - k)
    elif 0 < n_exp <= 21:
        res = m[:n_exp] + "." + m[n_exp:]
    elif -6 < n_exp <= 0:
        res = "0." + ("0" * (-n_exp)) + m
    elif k == 1:
        exp_val = n_exp - 1
        res = m + ("e+" if exp_val > 0 else "e") + str(exp_val)
    else:
        exp_val = n_exp - 1
        res = m[0] + "." + m[1:] + ("e+" if exp_val > 0 else "e") + str(exp_val)

    return sign + res


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
