"""
LibvmafFFmpegProvider — VMAF measurement via FFmpeg's libvmaf filter.

v1.1 role: measurement only.
VMAF results are written to vmaf.json in the evidence_dir (default: on).
The signed manifest records aggregate statistics and evidence_sha256.
VMAF is NOT in the gate predicate in v1.1.

Key design rules:
  - is_available() never raises; unavailability is a first-class state.
  - libvmaf_version is None when not reliably detectable (never manufactured).
  - audit_mode=True requires an explicit model_path; without it, is_available()
    returns False so the release CI gate can fail fast.
  - compute_sha256() from validator.py is used for all file hashing (centralized).
"""
import json
import logging
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..models import QualityConfig, QualityResult, PerFrameMetric
from veilframe.quality.vmaf_models import (
    VMAF_MODEL_VERSION,
    format_ffmpeg_filter_path,
    format_vmaf_model_filter_arg,
)
from ...core.resources import get_ffmpeg_path
from ...core.crypto import compute_sha256


logger = logging.getLogger(__name__)


def _get_ffmpeg_version() -> Optional[str]:
    """Returns FFmpeg binary version string. None if not detectable."""
    try:
        result = subprocess.run(
            [str(get_ffmpeg_path()), "-version"],
            capture_output=True, text=True, timeout=10,
        )
        for line in result.stdout.splitlines():
            if line.startswith("ffmpeg version"):
                return line.split()[2]
    except Exception:
        pass
    return None


def _get_libvmaf_version() -> Tuple[Optional[str], str]:
    """
    Attempts to parse the linked libvmaf version from 'ffmpeg -version'.

    Returns:
        (version_string | None, source_description)

    The version is returned only when reliably parsed from the output.
    It is never guessed or manufactured. When not detectable, returns
    (None, "unavailable") — not an error, just missing evidence.
    """
    try:
        result = subprocess.run(
            [str(get_ffmpeg_path()), "-version"],
            capture_output=True, text=True, timeout=10,
        )
        combined = result.stdout + result.stderr
        match = re.search(r"libvmaf\s+([\d.]+)", combined)
        if match:
            return match.group(1), "ffmpeg-version-output"
    except Exception:
        pass
    return None, "unavailable"


class LibvmafFFmpegProvider:
    """
    Measures VMAF using FFmpeg's built-in libvmaf filter.

    In v1.1, this provider is measurement-only. It writes vmaf.json to
    evidence_dir (retained by default) and records the SHA-256 of that
    file in the returned QualityResult. The gate does not act on VMAF.
    """

    name: str = "libvmaf"
    version: str = "1.0.0"          # VeilFrame adapter semver
    capabilities: List[str] = ["vmaf", "per_frame", "adm2", "vif"]

    def __init__(
        self,
        model_path: Optional[Path] = None,
        audit_mode: bool = False,
    ):
        """
        Args:
            model_path:  Path to the VMAF model JSON file. None = dev mode
                         (FFmpeg default model search). In audit_mode, this
                         must be set explicitly so the model hash is recorded.
            audit_mode:  When True, is_available() returns False if model_path
                         is not set or does not exist — ensures release CI gates
                         cannot pass with an implicit/unverified model.
        """
        self._model_path = model_path
        self._audit_mode = audit_mode

    # ------------------------------------------------------------------ #
    # Protocol methods                                                     #
    # ------------------------------------------------------------------ #

    def is_available(self) -> bool:
        """
        Returns True only if all required conditions are met. Never raises.

        Conditions:
          1. FFmpeg binary is present and callable.
          2. FFmpeg was built with libvmaf (libvmaf appears in -filters output).
          3. In audit_mode: model_path must be set and exist on disk.
          4. If model_path is set: the file must exist.
        """
        try:
            result = subprocess.run(
                [str(get_ffmpeg_path()), "-filters"],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode != 0:
                return False
            if "libvmaf" not in result.stdout:
                return False
            if self._audit_mode and not self._model_path:
                return False
            if self._model_path and not self._model_path.exists():
                return False
            return True
        except Exception:
            return False

    def runtime_info(self) -> Dict[str, Any]:
        """
        Returns structured provider metadata for the signed manifest.

        libvmaf_version is None when not reliably detectable from ffmpeg output.
        model_identity is None in dev mode (model_path=None).
        """
        libvmaf_ver, libvmaf_source = _get_libvmaf_version()
        model_identity: Optional[Dict[str, Any]] = None
        if self._model_path and self._model_path.exists():
            model_identity = {
                "name": self._model_path.name,
                "version": VMAF_MODEL_VERSION if "1.0.16" in self._model_path.name else None,
                "sha256": compute_sha256(self._model_path),
                "source": str(self._model_path),
            }
        elif not self._audit_mode:
            # Dev mode: model resolved by FFmpeg internally — hash not computable
            model_identity = {
                "name": "default",
                "sha256": None,
                "source": "ffmpeg-default-search",
            }
        # audit_mode + no model_path → model_identity stays None (should not reach here
        # if is_available() is checked first, but defensive)

        model_provenance = "explicit" if (self._model_path or self._audit_mode) else "implicit"
        if model_identity:
            model_identity["provenance"] = model_provenance

        return {
            "provider": self.name,
            "adapter_version": self.version,
            "runtime_version": _get_ffmpeg_version(),
            "libvmaf_version": libvmaf_ver,
            "libvmaf_version_source": libvmaf_source,
            "model_identity": model_identity,
            "model_provenance": model_provenance,
            "capabilities": list(self.capabilities),
        }

    def evaluate(self, config: QualityConfig) -> List[QualityResult]:
        """
        Runs VMAF measurement via FFmpeg libvmaf filter.

        Writes vmaf.json to config.evidence_dir by default (suppressed only
        when evidence_dir is None, which is not the default path).
        Returns QualityResult with aggregate stats, model provenance, and
        evidence_sha256 pointing to the retained JSON file.
        """
        evidence_file: Optional[Path] = None
        if config.evidence_dir:
            config.evidence_dir.mkdir(parents=True, exist_ok=True)
            evidence_file = config.evidence_dir / "vmaf.json"

        raw = self._run_vmaf(config, evidence_file)

        evidence_sha256: Optional[str] = None
        if evidence_file and evidence_file.exists():
            evidence_sha256 = compute_sha256(evidence_file)

        model_info = self.runtime_info().get("model_identity") or {}

        return [QualityResult(
            provider_name=self.name,
            metric_name="vmaf",
            mean=raw["mean"],
            minimum=raw.get("min"),
            p1=raw.get("p1"),
            p5=raw.get("p5"),
            p95=raw.get("p95"),
            model_name=model_info.get("name"),
            model_sha256=model_info.get("sha256"),
            evidence_file=evidence_file,
            evidence_sha256=evidence_sha256,
            # VMAF sub-metrics stored flat in feature_metrics — no VmafResult subclass
            feature_metrics={
                "adm2": raw.get("adm2", 0.0),
                "vif_scale0": raw.get("vif_scale0", 0.0),
                "vif_scale1": raw.get("vif_scale1", 0.0),
                "vif_scale2": raw.get("vif_scale2", 0.0),
                "vif_scale3": raw.get("vif_scale3", 0.0),
                "integer_motion": raw.get("integer_motion", 0.0),
                "integer_motion2": raw.get("integer_motion2", 0.0),
            },
        )]

    # ------------------------------------------------------------------ #
    # Internal helpers                                                     #
    # ------------------------------------------------------------------ #

    def _build_filter(self, evidence_file: Optional[Path]) -> str:
        """Constructs the libvmaf lavfi filter string."""
        parts = ["log_fmt=json"]
        if evidence_file:
            log_path = format_ffmpeg_filter_path(evidence_file)
            parts.append(f"log_path='{log_path}'")
        if self._model_path and self._model_path.exists():
            parts.append(format_vmaf_model_filter_arg(self._model_path))
        parts.append("feature='name=psnr'")       # also collect sub-metrics
        return "libvmaf=" + ":".join(parts)

    def _run_vmaf(
        self,
        config: QualityConfig,
        evidence_file: Optional[Path],
    ) -> Dict[str, float]:
        """
        Runs FFmpeg with the libvmaf filter and parses aggregate output.
        Returns a dict with keys: mean, min, p1, p5, p95, and sub-metric names.
        """
        ffmpeg = get_ffmpeg_path()
        filter_str = self._build_filter(evidence_file)

        # FFmpeg libvmaf filter takes two inputs:
        # pad 0 = distorted, pad 1 = reference.
        # Stream 0:v (input 0) is distorted, stream 1:v (input 1) is reference.
        cmd = [
            str(ffmpeg),
            "-hide_banner", "-nostats", "-y",
            "-i", str(config.distorted),    # input 0 = distorted (mapped to pad 0)
            "-i", str(config.reference),    # input 1 = reference (mapped to pad 1)
            "-lavfi", filter_str,
            "-f", "null", "-",
        ]

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=3600,  # allow up to 1h for long videos
        )

        if result.returncode != 0:
            raise RuntimeError(
                f"LibvmafFFmpegProvider: FFmpeg returned non-zero: "
                f"{result.stderr[-2000:]}"
            )

        # Parse the evidence JSON if written; otherwise parse stderr summary
        if evidence_file and evidence_file.exists():
            return self._parse_vmaf_json(evidence_file)

        return self._parse_stderr_summary(result.stderr)

    @staticmethod
    def _parse_vmaf_json(evidence_file: Path) -> Dict[str, float]:
        """Parses aggregated VMAF scores from the JSON evidence log."""
        try:
            data = json.loads(evidence_file.read_text(encoding="utf-8", errors="replace"))
        except Exception as exc:
            raise RuntimeError(f"Failed to parse vmaf.json: {exc}") from exc

        # JSON schema varies between libvmaf versions; handle both 0.x and 3.x
        pooled = data.get("pooled_metrics", {})
        frames = data.get("frames", [])

        vmaf_scores: List[float] = []
        adm2_scores: List[float] = []
        vif0_scores: List[float] = []
        vif1_scores: List[float] = []
        vif2_scores: List[float] = []
        vif3_scores: List[float] = []
        motion_scores: List[float] = []
        motion2_scores: List[float] = []

        for frame in frames:
            m = frame.get("metrics", {})
            if "vmaf" in m:
                vmaf_scores.append(float(m["vmaf"]))
            if "adm2" in m:
                adm2_scores.append(float(m["adm2"]))
            for k, lst in [
                ("vif_scale0", vif0_scores), ("vif_scale1", vif1_scores),
                ("vif_scale2", vif2_scores), ("vif_scale3", vif3_scores),
                ("integer_motion", motion_scores), ("integer_motion2", motion2_scores),
            ]:
                if k in m:
                    lst.append(float(m[k]))

        def _agg(scores: List[float]) -> Dict[str, float]:
            if not scores:
                return {"mean": 0.0, "min": 0.0, "p1": 0.0, "p5": 0.0, "p95": 0.0}
            s = sorted(scores)
            n = len(s)
            return {
                "mean": sum(s) / n,
                "min": s[0],
                "p1": _percentile(s, 1.0),
                "p5": _percentile(s, 5.0),
                "p95": _percentile(s, 95.0),
            }

        base = _agg(vmaf_scores)
        base.update({
            "adm2": sum(adm2_scores) / len(adm2_scores) if adm2_scores else 0.0,
            "vif_scale0": sum(vif0_scores) / len(vif0_scores) if vif0_scores else 0.0,
            "vif_scale1": sum(vif1_scores) / len(vif1_scores) if vif1_scores else 0.0,
            "vif_scale2": sum(vif2_scores) / len(vif2_scores) if vif2_scores else 0.0,
            "vif_scale3": sum(vif3_scores) / len(vif3_scores) if vif3_scores else 0.0,
            "integer_motion": sum(motion_scores) / len(motion_scores) if motion_scores else 0.0,
            "integer_motion2": sum(motion2_scores) / len(motion2_scores) if motion2_scores else 0.0,
        })

        # If pooled_metrics present (libvmaf 3.x), prefer those for mean
        if pooled and "vmaf" in pooled:
            vmaf_pool = pooled["vmaf"]
            base["mean"] = float(vmaf_pool.get("mean", base["mean"]))
            base["min"] = float(vmaf_pool.get("min", base["min"]))

        return base

    @staticmethod
    def _parse_stderr_summary(stderr: str) -> Dict[str, Any]:
        """
        Fallback: parse the VMAF summary line from FFmpeg stderr when no
        JSON evidence file was written.
        Example: VMAF score = 97.831442 or VMAF score: 97.831442
        """
        match = re.search(r"VMAF score\s*[:=]\s*([\d.]+)", stderr)
        mean = float(match.group(1)) if match else 0.0
        return {
            "mean": mean,
            "min": None,
            "p1": None,
            "p5": None,
            "p95": None,
        }


def _percentile(sorted_data: List[float], p: float) -> float:
    """Linear interpolation percentile helper."""
    import math
    if not sorted_data:
        return 0.0
    if len(sorted_data) == 1:
        return sorted_data[0]
    k = (len(sorted_data) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_data[int(k)]
    return sorted_data[int(f)] * (c - k) + sorted_data[int(c)] * (k - f)
