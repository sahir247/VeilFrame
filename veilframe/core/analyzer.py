"""
Input video analyzer parsing ffprobe output into structured VideoInfo models.
"""
import json
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional

from .resources import get_ffprobe_path, FFmpegNotFoundError
from ..models.video_info import (
    VideoInfo,
    VideoStreamInfo,
    AudioStreamInfo,
    MetadataInfo,
)


def _eval_fraction(val: Optional[str]) -> float:
    if not val:
        return 0.0
    try:
        if "/" in val:
            num, den = val.split("/", 1)
            den_f = float(den)
            return float(num) / den_f if den_f != 0 else 0.0
        return float(val)
    except Exception:
        return 0.0


def probe_raw(file_path: Path) -> Dict[str, Any]:
    """Runs ffprobe on the target file and returns raw parsed JSON."""
    ffprobe = get_ffprobe_path()
    cmd = [
        str(ffprobe),
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        "-show_chapters",
        str(file_path),
    ]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
        )
        return json.loads(proc.stdout)
    except subprocess.CalledProcessError as e:
        err = e.stderr.strip() if e.stderr else str(e)
        raise RuntimeError(f"FFprobe inspection failed: {err}")
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse FFprobe JSON output: {e}")


def analyze_video(file_path: Path) -> VideoInfo:
    """Performs comprehensive inspection of video file and returns a structured VideoInfo."""
    raw = probe_raw(file_path)
    fmt = raw.get("format", {})
    streams = raw.get("streams", [])
    chapters = raw.get("chapters", [])

    duration = float(fmt.get("duration", 0.0) or 0.0)
    size_bytes = int(fmt.get("size", 0) or 0)
    overall_bitrate = int(fmt.get("bit_rate", 0) or 0)
    format_name = fmt.get("format_name", "")
    format_long_name = fmt.get("format_long_name", "")

    # Video stream
    v_info: Optional[VideoStreamInfo] = None
    v_streams = [s for s in streams if s.get("codec_type") == "video"]
    # Filter out attached pictures / cover art
    primary_v = next((s for s in v_streams if s.get("disposition", {}).get("attached_pic") != 1), None)
    if not primary_v and v_streams:
        primary_v = v_streams[0]

    if primary_v:
        w = int(primary_v.get("width", 0) or 0)
        h = int(primary_v.get("height", 0) or 0)
        fps = _eval_fraction(primary_v.get("r_frame_rate"))
        avg_fps = _eval_fraction(primary_v.get("avg_frame_rate"))
        v_dur = float(primary_v.get("duration", 0.0) or duration or 0.0)
        v_bitrate = int(primary_v.get("bit_rate", 0) or 0)
        frame_count = int(primary_v.get("nb_frames", 0) or 0)
        if frame_count == 0 and fps > 0 and v_dur > 0:
            frame_count = int(fps * v_dur)

        # Rotation
        rotation = 0
        side_data_list = primary_v.get("side_data_list", [])
        for sd in side_data_list:
            if "rotation" in sd:
                rotation = int(sd["rotation"])
        if rotation == 0 and "tags" in primary_v:
            v_tags = primary_v["tags"]
            if "rotate" in v_tags:
                try:
                    rotation = int(v_tags["rotate"])
                except Exception:
                    pass

        # Aspect ratio
        aspect = primary_v.get("display_aspect_ratio")
        if not aspect and w > 0 and h > 0:
            import math
            gcd = math.gcd(w, h)
            aspect = f"{w // gcd}:{h // gcd}"

        # GOP / B-frame info
        has_b = int(primary_v.get("has_b_frames", 0) or 0)

        v_info = VideoStreamInfo(
            width=w,
            height=h,
            fps=fps if fps > 0 else avg_fps,
            avg_fps=avg_fps,
            duration=v_dur,
            codec=primary_v.get("codec_name", ""),
            codec_long_name=primary_v.get("codec_long_name", ""),
            profile=primary_v.get("profile", ""),
            pixel_format=primary_v.get("pix_fmt", ""),
            color_space=primary_v.get("color_space", ""),
            color_range=primary_v.get("color_range", ""),
            bitrate=v_bitrate,
            frame_count=frame_count,
            rotation=rotation,
            aspect_ratio=aspect or "Unknown",
            gop_b_frames=has_b,
            tags=primary_v.get("tags", {}),
        )

    # Audio stream
    a_info: Optional[AudioStreamInfo] = None
    a_streams = [s for s in streams if s.get("codec_type") == "audio"]
    if a_streams:
        primary_a = a_streams[0]
        a_tags = primary_a.get("tags", {})
        a_dur = float(primary_a.get("duration", 0.0) or duration or 0.0)
        a_bitrate = int(primary_a.get("bit_rate", 0) or 0)
        a_info = AudioStreamInfo(
            codec=primary_a.get("codec_name", ""),
            codec_long_name=primary_a.get("codec_long_name", ""),
            sample_rate=int(primary_a.get("sample_rate", 0) or 0),
            channels=int(primary_a.get("channels", 0) or 0),
            channel_layout=primary_a.get("channel_layout", ""),
            bitrate=a_bitrate,
            duration=a_dur,
            language=a_tags.get("language", "und"),
            stream_count=len(a_streams),
            tags=a_tags,
        )

    # Metadata extraction
    fmt_tags = fmt.get("tags", {}) or {}
    stream_tags = {}
    for i, s in enumerate(streams):
        if "tags" in s and s["tags"]:
            stream_tags[f"stream_{i}_{s.get('codec_type','unknown')}"] = s["tags"]

    combined_tags = {**fmt_tags}
    for st in stream_tags.values():
        combined_tags.update(st)

    # Look for known privacy / tracking fields
    creation = combined_tags.get("creation_time") or combined_tags.get("date") or combined_tags.get("encoded_date")
    mod_date = combined_tags.get("modification_date") or combined_tags.get("tagged_date")
    comment = combined_tags.get("comment") or combined_tags.get("description")
    encoder = combined_tags.get("encoder") or combined_tags.get("ENCODER")
    software = combined_tags.get("software") or combined_tags.get("SOFTWARE")
    
    # GPS tags (e.g. location, location-eng, com.apple.quicktime.location.ISO6709)
    gps_candidates = [
        combined_tags.get("location"),
        combined_tags.get("location-eng"),
        combined_tags.get("com.apple.quicktime.location.ISO6709"),
        combined_tags.get("GPSCoordinates"),
        combined_tags.get("xyz"),
    ]
    gps = next((g for g in gps_candidates if g), None)

    # Camera / Device info
    make = combined_tags.get("make") or combined_tags.get("com.apple.quicktime.make") or combined_tags.get("camera_make")
    model = combined_tags.get("model") or combined_tags.get("com.apple.quicktime.model") or combined_tags.get("camera_model")
    device = combined_tags.get("device") or combined_tags.get("device_name") or combined_tags.get("com.apple.quicktime.software")

    attachments = [s for s in streams if s.get("codec_type") == "attachment" or s.get("disposition", {}).get("attached_pic") == 1]

    meta_info = MetadataInfo(
        creation_date=str(creation) if creation else None,
        modification_date=str(mod_date) if mod_date else None,
        comment=str(comment) if comment else None,
        encoder=str(encoder) if encoder else None,
        software=str(software) if software else None,
        gps=str(gps) if gps else None,
        camera_make=str(make) if make else None,
        camera_model=str(model) if model else None,
        device_info=str(device) if device else None,
        title=str(combined_tags.get("title")) if combined_tags.get("title") else None,
        artist=str(combined_tags.get("artist")) if combined_tags.get("artist") else None,
        chapters_count=len(chapters),
        attachments_count=len(attachments),
        container_tags=fmt_tags,
        stream_tags=stream_tags,
        raw_tags=combined_tags,
    )

    return VideoInfo(
        file_path=str(file_path),
        format_name=format_name,
        format_long_name=format_long_name,
        duration=duration or (v_info.duration if v_info else 0.0),
        size_bytes=size_bytes,
        overall_bitrate=overall_bitrate,
        video=v_info,
        audio=a_info,
        metadata=meta_info,
        raw_probe=raw,
    )
