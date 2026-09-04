"""
VeilFrame VMAF Model Configuration & Verification Module (VMAF v1.0.16).

Centralizes:
  - Official VMAF v1.0.16 model registry with verified SHA-256 hashes.
  - VMAF_MODEL_ROOT path resolution ($env:VMAF_MODEL_ROOT or Path.home() / "vmaf" / "model").
  - Model integrity verification via centralized compute_sha256.
  - Orientation-safe resolution classification (1080p-class, 2160p-class, unsupported).
  - Deterministic frame-rate / HFR classification (fps >= 50.0).
  - HDR detection and segregation (smpte2084, arib-std-b67, bt2020).
  - Windows FFmpeg filter path escaping.
"""
from dataclasses import dataclass
import os
from pathlib import Path
from typing import Any, Dict, Optional, Tuple, Union

from ..core.crypto import compute_sha256

VMAF_MODEL_VERSION = "1.0.16"


# ── Exceptions ──────────────────────────────────────────────────────────── #

class VmafModelError(Exception):
    """Base exception for VMAF model configuration and verification errors."""
    pass


class VmafModelMissingError(VmafModelError):
    """Raised when a required VMAF v1.0.16 model JSON file cannot be found."""
    pass


class VmafModelHashMismatchError(VmafModelError):
    """Raised when a model file's SHA-256 does not match the verified registry."""
    pass


class VmafNotApplicableHdrError(VmafModelError):
    """Raised when HDR content is submitted to SDR VMAF v1.0.16 evaluation."""
    pass


class VmafUnsupportedResolutionError(VmafModelError):
    """Raised when content resolution does not match 1080p or 2160p VMAF domains."""
    pass


# ── Model Data Class ─────────────────────────────────────────────────────── #

@dataclass(frozen=True)
class VmafModelSpec:
    model_id: str
    filename: str
    relative_path: str
    expected_sha256: str
    resolution_tier: str
    is_hfr: bool
    version: str = VMAF_MODEL_VERSION


# ── Official VMAF v1.0.16 Model Registry ───────────────────────────────── #

OFFICIAL_VMAF_V1_0_16_MODELS: Dict[str, VmafModelSpec] = {
    "1080p_sdr": VmafModelSpec(
        model_id="vmaf_v1.0.16_3d0h",
        filename="vmaf_v1.0.16_3d0h.json",
        relative_path="vmaf_v1.0.16/vmaf_v1.0.16_3d0h.json",
        expected_sha256="cdb62c255f17a17b6dc2b97fba5429c4b303aa5523a8b0d0316d8a112cfd893f",
        resolution_tier="1080p",
        is_hfr=False,
    ),
    "1080p_hfr": VmafModelSpec(
        model_id="vmaf_v1.0.16_hfr_3d0h",
        filename="vmaf_v1.0.16_hfr_3d0h.json",
        relative_path="vmaf_v1.0.16_hfr/vmaf_v1.0.16_hfr_3d0h.json",
        expected_sha256="6f126fe8dacf782d731a476c9b68ff1d3ed2dbf72c396b0d7288df3ca41863d5",
        resolution_tier="1080p",
        is_hfr=True,
    ),
    "2160p_sdr": VmafModelSpec(
        model_id="vmaf_v1.0.16_1d5h_2160",
        filename="vmaf_v1.0.16_1d5h_2160.json",
        relative_path="vmaf_v1.0.16/vmaf_v1.0.16_1d5h_2160.json",
        expected_sha256="3e696240ee7cc047e2867a004fe0a57caa50e8bfb24161726bbcb31cce3f3883",
        resolution_tier="2160p",
        is_hfr=False,
    ),
    "2160p_hfr": VmafModelSpec(
        model_id="vmaf_v1.0.16_hfr_1d5h_2160",
        filename="vmaf_v1.0.16_hfr_1d5h_2160.json",
        relative_path="vmaf_v1.0.16_hfr/vmaf_v1.0.16_hfr_1d5h_2160.json",
        expected_sha256="a2152a8b7da3420cb6b1a11ea33743b2599cb8c3460724116647847ca51b205f",
        resolution_tier="2160p",
        is_hfr=True,
    ),
}


# ── Model Root Resolution & Verification ───────────────────────────────── #

def get_vmaf_model_root(override_path: Optional[Path] = None) -> Path:
    """
    Returns the authoritative VMAF model directory root.
    Priority:
      1. Explicit override_path (if provided)
      2. Environment variable VMAF_MODEL_ROOT
      3. Default fallback: Path.home() / "vmaf" / "model"
    """
    if override_path is not None:
        return Path(override_path)
    env_root = os.environ.get("VMAF_MODEL_ROOT")
    if env_root:
        return Path(env_root)
    return Path.home() / "vmaf" / "model"


def resolve_and_verify_model(
    spec: VmafModelSpec,
    model_root: Optional[Path] = None,
) -> Path:
    """
    Locates the model file under model_root and verifies its SHA-256 against
    the verified registry.

    Returns:
        Verified Path to the model JSON file.

    Raises:
        VmafModelMissingError: If model file does not exist.
        VmafModelHashMismatchError: If SHA-256 does not match official specification.
    """
    root = get_vmaf_model_root(model_root)
    candidate_path = root / spec.relative_path
    if not candidate_path.exists():
        # Fallback to check flattened directory structure: root / spec.filename
        flattened = root / spec.filename
        if flattened.exists():
            candidate_path = flattened
        else:
            raise VmafModelMissingError(
                f"VMAF v1.0.16 model '{spec.filename}' not found under '{root}'. "
                f"Expected relative path: '{spec.relative_path}' or direct filename. "
                f"Configure $env:VMAF_MODEL_ROOT to your VMAF model folder."
            )

    actual_sha = compute_sha256(candidate_path)
    if actual_sha.lower() != spec.expected_sha256.lower():
        raise VmafModelHashMismatchError(
            f"VMAF model integrity check failed for '{candidate_path}'.\n"
            f"Expected SHA-256: {spec.expected_sha256}\n"
            f"Actual SHA-256:   {actual_sha}"
        )

    return candidate_path


# ── Classification & Model Selection ───────────────────────────────────── #

def is_hfr(fps: float) -> bool:
    """
    Determines High Frame Rate (HFR) status.
    Policy: fps >= 50.0.
      50, 59.94, 60, 120 fps -> HFR (True)
      23.976, 24, 25, 29.97, 30, 48, 49 fps -> Standard (False)
    """
    return float(fps) >= 50.0


def classify_resolution(width: int, height: int) -> str:
    """
    Orientation-safe, explicit classification of video resolution into VMAF technical domains.

    Domains:
      - "1080p": True 1080p-class video:
          * Standard landscape 1920x1080 or portrait 1080x1920.
          * Windowed/cropped 1080p content where min dimension is exactly 1080 and max dimension <= 1920
            (e.g., 1808x1080, 1080x1080).
      - "2160p": 2160p (4K UHD / DCI 4K) class video:
          * Standard 3840x2160, portrait 2160x3840, or DCI 4096x2160.
          * Min dimension >= 2160 or max dimension >= 3840.
      - "secondary": Legacy/secondary resolutions (720p, 480p, SD):
          * Max dimension <= 1280 and min dimension <= 720 (e.g., 1280x720, 720x1280, 854x480, 640x480).
      - "unsupported": Intermediate non-standard resolutions outside primary VMAF domains:
          * e.g., 2560x1440 (1440p), 3000x2000, 1440x1440.
    """
    max_dim = max(width, height)
    min_dim = min(width, height)

    # 1. 2160p (4K UHD / DCI 4K) class
    if max_dim >= 3840 or min_dim >= 2160:
        return "2160p"

    # 2. True 1080p class: min_dim == 1080 and max_dim <= 1920
    if min_dim == 1080 and max_dim <= 1920:
        return "1080p"

    # 3. Secondary domain: 720p, 480p, SD
    if max_dim <= 1280 and min_dim <= 720:
        return "secondary"

    # 4. Non-standard intermediate (e.g. 1440p / 2560x1440)
    return "unsupported"


def detect_hdr(
    stream_meta: Dict[str, Any],
    path: Optional[Union[str, Path]] = None,
) -> Tuple[bool, str]:
    """
    Detects HDR characteristics from FFprobe stream metadata and filename cues.
    Recognizes common HDR transfer characteristics, color spaces, and filename cues.

    Returns:
        (is_hdr, reason_description)
    """
    color_transfer = str(stream_meta.get("color_transfer", "")).lower()
    color_primaries = str(stream_meta.get("color_primaries", "")).lower()
    color_space = str(stream_meta.get("color_space", "")).lower()

    if color_transfer in ("smpte2084", "arib-std-b67"):
        return True, f"HDR transfer characteristic detected: {color_transfer}"
    if "bt2020" in color_primaries and color_transfer in ("smpte2084", "arib-std-b67", "linear"):
        return True, f"HDR BT.2020 color primaries with transfer: {color_transfer}"
    if color_transfer.startswith("arib") or "hlg" in color_transfer or "pq" in color_transfer:
        return True, f"HDR characteristic: {color_transfer}"

    # Filename cue fallback if container tags are stripped or non-standard
    if path:
        name = Path(path).name.lower()
        if "_hdr" in name or "hdr10" in name or "p3pq" in name or "_dovi" in name:
            return True, f"HDR metadata in filename: {Path(path).name}"

    return False, ""


def select_vmaf_model(
    width: int,
    height: int,
    fps: float,
    is_hdr: bool = False,
) -> VmafModelSpec:
    """
    Deterministically selects the official VMAF v1.0.16 model specification
    based on resolution, frame-rate, and dynamic range.

    Raises:
        VmafNotApplicableHdrError: If is_hdr is True.
        VmafUnsupportedResolutionError: If resolution belongs to secondary or unsupported domain.
    """
    if is_hdr:
        raise VmafNotApplicableHdrError(
            "HDR content detected: outside SDR VMAF v1.0.16 model domain. "
            "Record vmaf_status='not_applicable_hdr' rather than using SDR model."
        )

    res_tier = classify_resolution(width, height)
    if res_tier == "secondary":
        raise VmafUnsupportedResolutionError(
            f"Resolution {width}x{height} belongs to the secondary domain (720p/SD). "
            f"VMAF v1.0.16 models are validated for 1080p and 2160p domains only. "
            f"Secondary resolutions must not silently use 1080p models."
        )
    if res_tier == "unsupported":
        raise VmafUnsupportedResolutionError(
            f"Resolution {width}x{height} does not match standard 1080p or 2160p VMAF domains. "
            f"Explicit resolution classification required."
        )

    hfr_flag = is_hfr(fps)
    key = f"{res_tier}_{'hfr' if hfr_flag else 'sdr'}"
    return OFFICIAL_VMAF_V1_0_16_MODELS[key]


def format_ffmpeg_filter_path(path: Union[str, Path]) -> str:
    """
    Formats a file path so it can be safely used inside an FFmpeg lavfi filter string
    (e.g. log_path in libvmaf).
    Converts Windows backslashes to forward slashes and escapes colons (e.g. C\\:/path).
    """
    s = str(path).replace("\\", "/")
    return s.replace(":", "\\:")


def format_vmaf_model_filter_arg(model_path: Union[str, Path]) -> str:
    """
    Formats the model parameter for the libvmaf filter: model='path=<ESCAPED_PATH>'.
    In FFmpeg, libvmaf parses nested key-values inside model='...'.
    On Windows, drive colons (C:) require triple escaping ('\\\\\\:') so that
    libvmaf's inner parser receives 'C\\:' and does not treat the colon as an option separator.
    """
    p = str(model_path).replace("\\", "/")
    escaped = p.replace(":", "\\\\\\:")
    return f"model='path={escaped}'"

