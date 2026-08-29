"""
Deterministic media I/O, hash verification, and frame/audio stream decoding for benchmarks.
"""
import hashlib
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union
import numpy as np

from veilframe.core.resources import get_ffmpeg_path, get_ffprobe_path


def compute_file_sha256(path: Union[str, Path]) -> str:
    """Computes SHA-256 hex digest of a file bitstream."""
    p = Path(path)
    if not p.exists() or not p.is_file():
        raise FileNotFoundError(f"File not found: {path}")

    h = hashlib.sha256()
    with open(p, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def get_ffmpeg_build_info() -> Dict[str, str]:
    """Retrieves exact FFmpeg version and compiler configuration."""
    try:
        ffmpeg = get_ffmpeg_path()
        res = subprocess.run(
            [str(ffmpeg), "-version"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        lines = res.stdout.splitlines()
        version = lines[0].split()[2] if len(lines) > 0 and len(lines[0].split()) > 2 else "unknown"
        return {"version": version, "banner": lines[0] if lines else ""}
    except Exception as e:
        return {"version": "unavailable", "error": str(e)}


def decode_audio_pcm(
    video_path: Union[str, Path],
    target_sample_rate: int = 1000,
    duration_sec: Optional[float] = None,
) -> Tuple[np.ndarray, int]:
    """
    Decodes audio stream to raw mono floating-point PCM normalized to [-1.0, 1.0].
    Returns (samples_array, sample_rate).
    """
    p = Path(video_path)
    if not p.exists():
        raise FileNotFoundError(f"Video file not found: {video_path}")

    ffmpeg = get_ffmpeg_path()
    cmd = [
        str(ffmpeg),
        "-hide_banner",
        "-y",
        "-i", str(p),
        "-vn",
        "-ac", "1",
        "-ar", str(target_sample_rate),
        "-f", "f32le",
    ]
    if duration_sec:
        cmd += ["-t", f"{duration_sec:.3f}"]
    cmd.append("-")

    proc = subprocess.run(
        cmd,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0 or len(proc.stdout) == 0:
        return np.array([], dtype=np.float32), target_sample_rate

    pcm = np.frombuffer(proc.stdout, dtype=np.float32)
    return pcm, target_sample_rate


def decode_video_frames_yuv(
    video_path: Union[str, Path],
    max_frames: int = 60,
    target_res: Tuple[int, int] = (320, 240),
) -> List[np.ndarray]:
    """
    Decodes up to max_frames video frames into normalized grayscale luminance matrices (Y plane).
    Returns list of 2D numpy arrays with shape (height, width) and values in [0.0, 1.0].
    """
    p = Path(video_path)
    if not p.exists():
        raise FileNotFoundError(f"Video file not found: {video_path}")

    w, h = target_res
    frame_bytes = w * h
    ffmpeg = get_ffmpeg_path()

    cmd = [
        str(ffmpeg),
        "-hide_banner",
        "-y",
        "-i", str(p),
        "-vf", f"scale={w}:{h},format=gray",
        "-vframes", str(max_frames),
        "-f", "rawvideo",
        "-pix_fmt", "gray",
        "-",
    ]

    proc = subprocess.run(
        cmd,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0 or len(proc.stdout) == 0:
        return []

    raw = proc.stdout
    total_frames = len(raw) // frame_bytes
    frames: List[np.ndarray] = []

    for i in range(total_frames):
        chunk = raw[i * frame_bytes : (i + 1) * frame_bytes]
        arr = np.frombuffer(chunk, dtype=np.uint8).reshape((h, w)).astype(np.float32) / 255.0
        frames.append(arr)

    return frames
