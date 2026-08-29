"""
FFmpegNativeProvider — SSIM and PSNR via FFmpeg lavfi filters.

This adapter relocates the canonical-fidelity computation that previously
lived directly in validator.evaluate_canonical_fidelity(). The algorithm is
identical — no behavioral change for existing tests.

Architectural role: measurement only. Pass/fail thresholds are owned by
QualityGate, not by this provider.
"""
import re
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..models import QualityConfig, QualityResult, PerFrameMetric
from ...core.resources import get_ffmpeg_path


def _get_ffmpeg_version() -> Optional[str]:
    """Returns the FFmpeg version string. Returns None if not detectable."""
    try:
        result = subprocess.run(
            [str(get_ffmpeg_path()), "-version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        for line in result.stdout.splitlines():
            if line.startswith("ffmpeg version"):
                return line.split()[2]
    except Exception:
        pass
    return None


def _calc_percentile(sorted_data: List[float], p: float) -> float:
    """Calculates percentile p (0-100) using linear interpolation."""
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


class FFmpegNativeProvider:
    """
    Produces SSIM and PSNR QualityResult objects by running the FFmpeg lavfi
    filter chain against the canonical-domain representation (scaled to
    config.canonical_w x config.canonical_h using Lanczos).

    This is a read-only measurement provider. It does not modify files.
    """

    name: str = "ffmpeg-native"
    version: str = "1.0.0"          # VeilFrame adapter semver
    capabilities: List[str] = ["ssim", "psnr", "per_frame"]

    # ------------------------------------------------------------------ #
    # Protocol methods                                                     #
    # ------------------------------------------------------------------ #

    def is_available(self) -> bool:
        """Returns True if FFmpeg is present and responsive. Never raises."""
        try:
            result = subprocess.run(
                [str(get_ffmpeg_path()), "-version"],
                capture_output=True,
                timeout=10,
            )
            return result.returncode == 0
        except Exception:
            return False

    def runtime_info(self) -> Dict[str, Any]:
        """
        Returns structured provider metadata for the signed manifest.
        libvmaf fields are always None/unavailable for this provider.
        """
        return {
            "adapter_version": self.version,
            "runtime_version": _get_ffmpeg_version(),
            "libvmaf_version": None,
            "libvmaf_version_source": "unavailable",
            "model_identity": None,
            "capabilities": list(self.capabilities),
        }

    def evaluate(self, config: QualityConfig) -> List[QualityResult]:
        """
        Runs SSIM + PSNR via FFmpeg lavfi on the canonical canvas.
        Returns one QualityResult per metric (ssim, psnr).
        """
        ssim_stats, psnr_stats, ssim_frames, psnr_frames = (
            self._run_canonical_fidelity(config)
        )
        return [
            self._build_result("ssim", ssim_stats, ssim_frames),
            self._build_result("psnr", psnr_stats, psnr_frames),
        ]

    # ------------------------------------------------------------------ #
    # Internal computation                                                 #
    # ------------------------------------------------------------------ #

    def _run_canonical_fidelity(
        self,
        config: QualityConfig,
    ) -> Tuple[Dict, Dict, List[float], List[float]]:
        """
        Runs the canonical-domain SSIM + PSNR lavfi filter chain.
        Identical algorithm to the former validator.evaluate_canonical_fidelity().
        """
        ffmpeg = get_ffmpeg_path()
        w, h = config.canonical_w, config.canonical_h

        with tempfile.TemporaryDirectory() as td:
            ssim_log = Path(td) / "ssim.log"
            psnr_log = Path(td) / "psnr.log"

            filtergraph = (
                f"[0:v]scale={w}:{h}:flags=lanczos,setsar=1,format=yuv420p,split[ref1][ref2];"
                f"[1:v]scale={w}:{h}:flags=lanczos,setsar=1,format=yuv420p[trn];"
                f"[trn][ref1]ssim=stats_file=ssim.log[s_out];"
                f"[s_out][ref2]psnr=stats_file=psnr.log[p_out]"
            )

            cmd = [
                str(ffmpeg),
                "-hide_banner", "-nostats", "-y",
                "-i", str(config.reference),
                "-i", str(config.distorted),
                "-filter_complex", filtergraph,
                "-map", "[p_out]",
                "-f", "null", "-",
            ]

            proc = subprocess.Popen(
                cmd,
                cwd=td,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

            stderr_lines: List[str] = []

            def drain():
                try:
                    if proc.stderr:
                        for line in proc.stderr:
                            stderr_lines.append(line)
                except Exception:
                    pass

            t = threading.Thread(target=drain, daemon=True)
            t.start()
            proc.wait()
            t.join(timeout=2.0)
            if proc.stderr:
                try:
                    proc.stderr.close()
                except Exception:
                    pass

            if proc.returncode != 0:
                err = "".join(stderr_lines)[-2000:]
                raise RuntimeError(
                    f"FFmpegNativeProvider: canonical fidelity failed: {err}"
                )

            ssim_scores: List[float] = []
            ssim_all_re = re.compile(r"All:([\d\.]+)")
            if ssim_log.exists():
                for line in ssim_log.read_text(encoding="utf-8", errors="replace").splitlines():
                    m = ssim_all_re.search(line)
                    if m:
                        try:
                            ssim_scores.append(float(m.group(1)))
                        except Exception:
                            pass

            psnr_scores: List[float] = []
            psnr_avg_re = re.compile(r"psnr_avg:([\d\.]+|inf)")
            if psnr_log.exists():
                for line in psnr_log.read_text(encoding="utf-8", errors="replace").splitlines():
                    m = psnr_avg_re.search(line)
                    if m:
                        val = m.group(1)
                        psnr_scores.append(100.0 if val == "inf" else float(val))

        return (
            self._compute_stats(ssim_scores),
            self._compute_stats(psnr_scores),
            ssim_scores,
            psnr_scores,
        )

    @staticmethod
    def _compute_stats(values: List[float]) -> Dict[str, float]:
        import math
        if not values:
            return {"mean": 0.0, "min": 0.0, "p1": 0.0, "p5": 0.0, "p95": 0.0}
        clean = sorted([v for v in values if not math.isnan(v)])
        if not clean:
            return {"mean": 0.0, "min": 0.0, "p1": 0.0, "p5": 0.0, "p95": 0.0}
        return {
            "mean": sum(clean) / len(clean),
            "min": clean[0],
            "p1": _calc_percentile(clean, 1.0),
            "p5": _calc_percentile(clean, 5.0),
            "p95": _calc_percentile(clean, 95.0),
        }

    @staticmethod
    def _build_result(
        metric_name: str,
        stats: Dict[str, float],
        raw_scores: List[float],
    ) -> QualityResult:
        per_frame = [
            PerFrameMetric(frame_index=i, timestamp_sec=float(i), value=v)
            for i, v in enumerate(raw_scores)
        ]
        return QualityResult(
            provider_name="ffmpeg-native",
            metric_name=metric_name,
            mean=stats["mean"],
            minimum=stats["min"],
            p1=stats["p1"],
            p5=stats["p5"],
            p95=stats["p95"],
            per_frame=per_frame,
        )
