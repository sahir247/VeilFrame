"""
HUE_Controlled Event-Camera & Illumination Dataset Inspector.

Audits the 17 controlled illumination conditions in:
  resource_videos/HUE_Controlled/
and the corresponding 30fps MP4 video renders in:
  resource_videos/HUE_Controlled/videos/

Extracts:
  - Illumination levels (lux mean, min, max from lux_values.txt)
  - Sensor parameters (Prophesee CCam5 IMX636 event camera)
  - Frame camera parameters (RGB sensor resolution 1280x720 / 1456x1088 render)
  - Video stream characteristics (H.264, 30fps, 267 frames, bitrate)
  - Cryptographic SHA-256 hashes

Outputs:
  - hue_controlled_audit.json
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any, Dict, List

sys.stdout.reconfigure(encoding="utf-8")

HUE_DIR = Path("resource_videos") / "HUE_Controlled"
VIDEOS_DIR = HUE_DIR / "videos"


def compute_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def parse_lux_file(lux_path: Path) -> Dict[str, Any]:
    if not lux_path.exists():
        return {"error": "lux_values.txt missing"}
    with open(lux_path, "r", encoding="utf-8") as f:
        vals = [float(line.strip()) for line in f if line.strip()]
    if not vals:
        return {"count": 0, "mean": None, "min": None, "max": None}
    return {
        "sample_count": len(vals),
        "mean_lux": round(sum(vals) / len(vals), 2),
        "min_lux": min(vals),
        "max_lux": max(vals),
    }


def probe_video(video_path: Path) -> Dict[str, Any]:
    cmd = [
        "ffprobe",
        "-hide_banner",
        "-show_format",
        "-show_streams",
        "-print_format",
        "json",
        str(video_path),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return {"error": res.stderr[:300]}
    try:
        data = json.loads(res.stdout)
        fmt = data.get("format", {})
        streams = [s for s in data.get("streams", []) if s.get("codec_type") == "video"]
        v = streams[0] if streams else {}
        return {
            "duration": float(fmt.get("duration", 0.0)),
            "size_bytes": int(fmt.get("size", 0)),
            "bitrate_kbps": round(float(fmt.get("bit_rate", 0)) / 1000.0, 1),
            "codec": v.get("codec_name"),
            "pix_fmt": v.get("pix_fmt"),
            "width": v.get("width"),
            "height": v.get("height"),
            "fps": v.get("r_frame_rate"),
            "nb_frames": int(v.get("nb_frames", 0)),
        }
    except Exception as e:
        return {"error": str(e)}


def main():
    print("============================================================")
    print("HUE_Controlled Dataset & Illumination Series Audit")
    print("============================================================")

    if not HUE_DIR.exists():
        print(f"Error: {HUE_DIR} does not exist.")
        sys.exit(1)

    # Find all 17 zebra illumination folders
    cond_folders = sorted([p for p in HUE_DIR.iterdir() if p.is_dir() and p.name.startswith("zebra_")])
    print(f"Found {len(cond_folders)} illumination condition folders.")

    audit_records = []
    for fld in cond_folders:
        cond_name = fld.name
        print(f"Auditing condition {cond_name}...")

        # Parse illumination
        lux_stats = parse_lux_file(fld / "lux_values.txt")

        # Parse event camera params
        ec_params_file = fld / "event_camera_params.json"
        ec_params = {}
        if ec_params_file.exists():
            with open(ec_params_file, "r", encoding="utf-8") as f:
                ec_params = json.load(f)

        # Count PNG images
        png_dir = fld / "png_images"
        png_count = len(list(png_dir.glob("*.png"))) if png_dir.exists() else 0

        # Probe corresponding converted MP4 video
        video_file = VIDEOS_DIR / f"{cond_name}.mp4"
        video_info = {}
        video_sha256 = None
        if video_file.exists():
            video_info = probe_video(video_file)
            video_sha256 = compute_sha256(video_file)

        audit_records.append({
            "condition_id": cond_name,
            "folder_path": str(fld.resolve()),
            "png_frame_count": png_count,
            "lux_statistics": lux_stats,
            "event_camera": {
                "sensor": ec_params.get("system_info", {}).get("Sensor Name", "IMX636"),
                "device": ec_params.get("system_info", {}).get("device0 name", "CCam5"),
                "sensor_width": ec_params.get("width", 1280),
                "sensor_height": ec_params.get("height", 720),
            },
            "converted_video": {
                "present": video_file.exists(),
                "path": str(video_file.resolve()) if video_file.exists() else None,
                "sha256": video_sha256,
                "specs": video_info,
            },
            "domain": "Domain 4: Non-Video Sensor / Illumination Domain",
            "suitability_status": "sensor_domain",
            "exclusion_reason": "Controlled illumination event-camera sensor dataset; segregated from primary SDR video calibration",
        })

    out_audit = Path("hue_controlled_audit.json")
    with open(out_audit, "w", encoding="utf-8") as f:
        json.dump(audit_records, f, indent=2)
    print(f"\nSaved HUE_Controlled Audit to {out_audit}")


if __name__ == "__main__":
    main()
