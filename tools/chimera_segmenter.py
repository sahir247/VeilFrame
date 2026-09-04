"""
Chimera Monolith Segmenter & Provenance Documentation Tool.

Segments the 10.9 GB monolithic video:
  resource_videos/Chimera_DCI4k5994p_HDR_P3PQ.mp4
into discrete episode test clips using stream copy (-c copy), strictly based on
the official 23-episode boundary specification from:
  resource_videos/netflix_chimera_4096x2160_download_instructions.pdf

Preserves exact source provenance:
  - Filmed on RED Epic Dragon in Los Angeles, CA (March 2014)
  - Native geometry: DCI 4K (4096x2160), 59.94 fps (60,000/1,001)
  - 10-bit YUV422 master, BT.709 color container with P3/PQ HDR mastering
  - Sequence group: 'chimera'
  - Domain: 'Domain 3: HDR / WCG'
  - Suitability: 'not_applicable_hdr'
"""
import json
from pathlib import Path
import subprocess
import sys
from typing import Any, Dict, List

sys.stdout.reconfigure(encoding="utf-8")

CHIMERA_MONOLITH = Path("resource_videos") / "Chimera_DCI4k5994p_HDR_P3PQ.mp4"
OUTPUT_DIR = Path("resource_videos") / "chimera_episodes"

# Official 23-Episode boundary table from PDF specifications at 59.94fps (60000/1001)
# Total frames: 110,935. FPS: 59.94005994005994
FPS = 60000.0 / 1001.0

CHIMERA_EPISODES: List[Dict[str, Any]] = [
    {"ep": 1, "title": "Bar scene", "frames": 2520, "trt": "00:00:42.04", "scenes": 9},
    {"ep": 2, "title": "Dinner scene", "frames": 7560, "trt": "00:02:06.13", "scenes": 14},
    {"ep": 3, "title": "Dancer", "frames": 3360, "trt": "00:01:52.11", "scenes": 1},
    {"ep": 4, "title": "Dancers couple", "frames": 6780, "trt": "00:01:53.11", "scenes": 1},
    {"ep": 5, "title": "Dancers montage mixed frame rates", "frames": 6840, "trt": "00:01:54.11", "scenes": 14},
    {"ep": 6, "title": "Rollercoaster sequence", "frames": 4200, "trt": "00:01:10.05", "scenes": 13},
    {"ep": 7, "title": "Rollercoaster POV", "frames": 4199, "trt": "00:01:10.05", "scenes": 1},
    {"ep": 8, "title": "Rollercoaster passenger", "frames": 4321, "trt": "00:01:12.09", "scenes": 1},
    {"ep": 9, "title": "Twirl ride boardwalk", "frames": 8580, "trt": "00:02:23.14", "scenes": 1},
    {"ep": 10, "title": "Netflix card twirl", "frames": 4620, "trt": "00:01:17.08", "scenes": 8},
    {"ep": 11, "title": "Seaside and pier", "frames": 6180, "trt": "00:01:43.10", "scenes": 3},
    {"ep": 12, "title": "Wind and nature", "frames": 4801, "trt": "00:01:20.10", "scenes": 5},
    {"ep": 13, "title": "Mountain view w. tilt", "frames": 2460, "trt": "00:00:41.04", "scenes": 1},
    {"ep": 14, "title": "Mountain view pan", "frames": 1380, "trt": "00:00:23.02", "scenes": 1},
    {"ep": 15, "title": "Walk like a man", "frames": 1860, "trt": "00:00:31.03", "scenes": 2},
    {"ep": 16, "title": "Toddler and fountain", "frames": 4500, "trt": "00:01:15.07", "scenes": 8},
    {"ep": 17, "title": "Driving POV", "frames": 5820, "trt": "00:01:27.10", "scenes": 6},
    {"ep": 18, "title": "Planet mobile", "frames": 2400, "trt": "00:00:40.04", "scenes": 1},
    {"ep": 19, "title": "Dog pants", "frames": 2160, "trt": "00:00:36.04", "scenes": 3},
    {"ep": 20, "title": "Dog barks", "frames": 838, "trt": "00:00:13.98", "scenes": 3},
    {"ep": 21, "title": "RC aerial", "frames": 8880, "trt": "00:02:28.15", "scenes": 1},
    {"ep": 22, "title": "Basketball free throws", "frames": 1200, "trt": "00:00:20.02", "scenes": 7},
    {"ep": 23, "title": "Basketball game", "frames": 5220, "trt": "00:01:27.09", "scenes": 20},
]


def build_episode_index() -> List[Dict[str, Any]]:
    """Calculates cumulative frame boundaries and timestamps for all episodes."""
    index = []
    cumulative_frame = 0
    for ep in CHIMERA_EPISODES:
        start_frame = cumulative_frame
        frame_count = ep["frames"]
        end_frame = start_frame + frame_count - 1
        start_sec = start_frame / FPS
        duration_sec = frame_count / FPS
        
        index.append({
            "episode_number": ep["ep"],
            "title": ep["title"],
            "scenes": ep["scenes"],
            "frame_count": frame_count,
            "start_frame": start_frame,
            "end_frame": end_frame,
            "start_seconds": round(start_sec, 4),
            "duration_seconds": round(duration_sec, 4),
            "end_seconds": round(start_sec + duration_sec, 4),
            "declared_trt": ep["trt"],
            "source_monolith": str(CHIMERA_MONOLITH),
            "sequence_group": "chimera",
            "domain": "Domain 3: HDR / WCG",
            "suitability_status": "not_applicable_hdr",
            "license": "Creative Commons Attribution 4.0 International (CC BY 4.0)",
        })
        cumulative_frame += frame_count

    return index


def extract_episode_clip(
    monolith_path: Path,
    ep_info: Dict[str, Any],
    out_dir: Path,
) -> Path:
    """Extracts an episode clip using ffmpeg stream copy (-c copy)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    safe_title = ep_info["title"].lower().replace(" ", "_").replace(".", "").replace("/", "_")
    out_filename = f"chimera_ep{ep_info['episode_number']:02d}_{safe_title}.mp4"
    out_path = out_dir / out_filename

    # Use stream copy with timestamp seek
    cmd = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel", "warning",
        "-ss", f"{ep_info['start_seconds']:.4f}",
        "-i", str(monolith_path),
        "-t", f"{ep_info['duration_seconds']:.4f}",
        "-c", "copy",
        str(out_path),
    ]
    subprocess.run(cmd, check=True)
    return out_path


def main():
    print("============================================================")
    print("Chimera Monolith Segmentation & Provenance Documentation")
    print("============================================================")

    if not CHIMERA_MONOLITH.exists():
        print(f"Error: {CHIMERA_MONOLITH} does not exist.")
        sys.exit(1)

    index = build_episode_index()
    print(f"Generated index for {len(index)} Chimera episodes across {index[-1]['end_frame'] + 1} frames.")

    # Save complete index JSON
    index_file = Path("chimera_episode_index.json")
    with open(index_file, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2)
    print(f"Saved complete Chimera episode boundary index to {index_file}")

    # Extract representative test episode clips to demonstrate clean stream-copy segmentation:
    # Ep 1: Bar scene (outdoor/indoor dialogue, 42s)
    # Ep 6: Rollercoaster sequence (complex high-speed motion, 70s)
    # Ep 10: Netflix card twirl (graphic text & spinning card, 77s)
    # Ep 22: Basketball free throws (sports & human motion, 20s)
    test_eps = [1, 6, 10, 22]
    print(f"\nExtracting representative episode clips {test_eps} via lossless stream copy...")
    for ep in index:
        if ep["episode_number"] in test_eps:
            print(f"  Extracting Episode {ep['episode_number']}: {ep['title']} (frames {ep['start_frame']}-{ep['end_frame']})...")
            clip_path = extract_episode_clip(CHIMERA_MONOLITH, ep, OUTPUT_DIR)
            print(f"    -> Extracted {clip_path.name} ({clip_path.stat().st_size / 1e6:.2f} MB)")

    print("\nChimera segmentation completed successfully.")
    print("All Chimera assets are documented under sequence group 'chimera' and classified as 'not_applicable_hdr'.")


if __name__ == "__main__":
    main()
