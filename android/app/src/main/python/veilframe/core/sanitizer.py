"""
Metadata sanitization module providing format-specific scrubbing for pre- and post-processing passes.
"""
import subprocess
from pathlib import Path
from typing import Optional, List

from .resources import get_ffmpeg_path, get_subprocess_flags


def build_sanitization_args(format_ext: str = ".mp4") -> List[str]:
    """
    Builds FFmpeg arguments for thorough metadata stripping across container & stream levels.
    """
    args = [
        "-map_metadata", "-1",
        "-map_chapters", "-1",
        "-metadata", "comment=",
        "-metadata", "description=",
        "-metadata", "title=",
        "-metadata", "artist=",
        "-metadata", "album=",
        "-metadata", "author=",
        "-metadata", "composer=",
        "-metadata", "copyright=",
        "-metadata", "creation_time=",
        "-metadata", "date=",
        "-metadata", "encoded_date=",
        "-metadata", "tagged_date=",
        "-metadata", "encoder=",
        "-metadata", "ENCODER=",
        "-metadata", "software=",
        "-metadata", "SOFTWARE=",
        "-metadata", "handler_name=",
        "-metadata", "location=",
        "-metadata", "location-eng=",
        "-metadata", "make=",
        "-metadata", "model=",
        "-metadata", "device=",
        "-metadata:s:v", "handler_name=",
        "-metadata:s:a", "handler_name=",
        "-metadata:s:v", "title=",
        "-metadata:s:a", "title=",
        "-metadata:s:v", "creation_time=",
        "-metadata:s:a", "creation_time=",
        "-fflags", "+bitexact",
        "-flags:v", "+bitexact",
        "-flags:a", "+bitexact",
    ]

    ext = format_ext.lower()
    if ext in (".mp4", ".m4v", ".mov"):
        args += [
            "-movflags", "+faststart+disable_chpl",
        ]
    elif ext in (".mkv", ".webm"):
        args += [
            "-map_metadata:s:v", "-1",
            "-map_metadata:s:a", "-1",
        ]

    return args


def pre_sanitize(src: Path, dst: Path) -> None:
    """
    Pass 1: Pre-sanitization before processing.
    Strips existing metadata, chapters, attachments, and cover art streams via lossless copy.
    """
    ffmpeg = get_ffmpeg_path()
    cmd = [
        str(ffmpeg),
        "-hide_banner",
        "-y",
        "-i", str(src),
        "-map", "0:v:0",   # Pick only the primary video stream (drops cover art / thumbnails)
        "-map", "0:a?",     # Pick audio stream if present
        "-c", "copy",       # Stream copy (lossless)
    ]
    cmd += build_sanitization_args(dst.suffix or src.suffix)
    cmd.append(str(dst))

    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        creationflags=get_subprocess_flags(),
    )
    if proc.returncode != 0:
        stderr_tail = proc.stderr[-6000:] if len(proc.stderr) > 6000 else proc.stderr
        hint = ""
        if "No such stream" in proc.stderr or "no video stream" in proc.stderr.lower():
            hint = " (Hint: Input may have no primary video stream or only data/subtitle tracks.)"
        elif "codec not currently supported" in proc.stderr.lower():
            hint = " (Hint: Input codec is not supported by the installed FFmpeg build.)"
        raise RuntimeError(f"Pre-sanitization failed:{hint}\n{stderr_tail}")


def post_sanitize(src: Path, dst: Path) -> None:
    """
    Pass 3: Post-sanitization after encoding.
    Scans and removes any encoder artifacts or container tags left behind by FFmpeg encoders.
    """
    ffmpeg = get_ffmpeg_path()
    cmd = [
        str(ffmpeg),
        "-hide_banner",
        "-y",
        "-i", str(src),
        "-map", "0:v:0",
        "-map", "0:a?",
        "-c", "copy",
    ]
    cmd += build_sanitization_args(dst.suffix or src.suffix)
    cmd.append(str(dst))

    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        creationflags=get_subprocess_flags(),
    )
    if proc.returncode != 0:
        stderr_tail = proc.stderr[-6000:] if len(proc.stderr) > 6000 else proc.stderr
        hint = ""
        if "No such stream" in proc.stderr or "no video stream" in proc.stderr.lower():
            hint = " (Hint: Encoded output may have no primary video stream.)"
        raise RuntimeError(f"Post-sanitization failed:{hint}\n{stderr_tail}")
