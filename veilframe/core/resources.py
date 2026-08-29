"""
Executable and binary resource locator for FFmpeg and FFprobe.
"""
import os
import sys
import shutil
from pathlib import Path
from typing import Optional


class FFmpegNotFoundError(RuntimeError):
    pass


def find_executable(name: str) -> Path:
    """
    Locates the requested executable ('ffmpeg' or 'ffprobe') across:
    1. System PATH (shutil.which)
    2. Environment variable overrides (FFMPEG_BINARY / FFPROBE_BINARY)
    3. PyInstaller bundled resources (sys._MEIPASS)
    4. Package resources directory (`veilframe/resources/ffmpeg/`)
    5. Project root `resources/ffmpeg/`
    6. Local application cache directories (Windows fallback)
    """
    ext = ".exe" if os.name == "nt" else ""
    exe_name = f"{name}{ext}"

    # 1. System PATH
    which_path = shutil.which(name)
    if which_path:
        return Path(which_path)

    # 2. Environment variable override
    env_var = f"{name.upper()}_BINARY"
    env_val = os.environ.get(env_var)
    if env_val and Path(env_val).exists():
        return Path(env_val)

    # 3. PyInstaller bundle
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        bundle_path = Path(sys._MEIPASS) / "resources" / "ffmpeg" / exe_name
        if bundle_path.exists():
            return bundle_path
        bundle_root_path = Path(sys._MEIPASS) / exe_name
        if bundle_root_path.exists():
            return bundle_root_path

    # 4. Package resources
    pkg_res = Path(__file__).parent.parent / "resources" / "ffmpeg" / exe_name
    if pkg_res.exists():
        return pkg_res

    # 5. Working directory resources
    cwd_res = Path.cwd() / "resources" / "ffmpeg" / exe_name
    if cwd_res.exists():
        return cwd_res

    # 6. Windows local application cache fallback (searches dynamically without version hardcoding)
    if os.name == "nt":
        user_profile = os.environ.get("USERPROFILE", "")
        if user_profile:
            appdata_local = Path(user_profile) / "AppData" / "Local"
            if appdata_local.exists():
                for installer in ("ffmpeg-installer", "ffprobe-installer", "@ffmpeg-installer", "@ffprobe-installer"):
                    for candidate in appdata_local.glob(f"**/node_modules/{installer}/**/{exe_name}"):
                        if candidate.exists():
                            return candidate

    raise FFmpegNotFoundError(
        f"'{name}' executable was not found. Please ensure FFmpeg and FFprobe are installed on system PATH, "
        f"configured via {env_var}, or bundled in 'veilframe/resources/ffmpeg/'."
    )


def get_ffmpeg_path() -> Path:
    return find_executable("ffmpeg")


def get_ffprobe_path() -> Path:
    return find_executable("ffprobe")


def detect_physical_gpus() -> list[str]:
    """
    Queries operating system APIs to detect physically installed GPU hardware devices.
    Returns a list of device names (e.g. ['NVIDIA GeForce RTX 4050 Laptop GPU']).
    """
    import subprocess
    gpus: list[str] = []

    # 1. Try nvidia-smi if NVIDIA GPU is present
    try:
        res = subprocess.run(["nvidia-smi", "-L"], capture_output=True, text=True, timeout=2)
        if res.returncode == 0 and res.stdout:
            for line in res.stdout.strip().splitlines():
                if "GPU " in line and ":" in line:
                    # e.g. "GPU 0: NVIDIA GeForce RTX 4050 Laptop GPU (UUID: ...)"
                    name_part = line.split(":", 1)[1].split("(UUID")[0].strip()
                    if name_part and name_part not in gpus:
                        gpus.append(name_part)
    except Exception:
        pass

    # 2. Windows CIM / WMI Query
    if os.name == "nt":
        try:
            cmd = ["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name"]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.strip().splitlines():
                    name = line.strip()
                    if name and name not in gpus:
                        gpus.append(name)
        except Exception:
            pass

    # 3. Linux lspci / sysfs
    elif sys.platform.startswith("linux"):
        try:
            res = subprocess.run(["lspci"], capture_output=True, text=True, timeout=2)
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.strip().splitlines():
                    if any(k in line.lower() for k in ("vga compatible", "3d controller", "display controller")):
                        parts = line.split(":", 2)
                        name = parts[-1].strip() if len(parts) >= 3 else line
                        if name and name not in gpus:
                            gpus.append(name)
        except Exception:
            pass

    # 4. macOS system_profiler
    elif sys.platform == "darwin":
        try:
            res = subprocess.run(["system_profiler", "SPDisplaysDataType"], capture_output=True, text=True, timeout=3)
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.splitlines():
                    if "Chipset Model:" in line:
                        name = line.split("Chipset Model:")[1].strip()
                        if name and name not in gpus:
                            gpus.append(name)
        except Exception:
            pass

    return gpus


def get_hardware_capabilities() -> dict:
    """
    Probes real physical GPU hardware and verifies functional hardware encoders
    by running micro-encoding test probes against the active FFmpeg binary.
    """
    import subprocess
    gpus = detect_physical_gpus()

    verified_encoders: list[dict] = []
    try:
        ffmpeg_p = get_ffmpeg_path()
    except Exception:
        return {
            "physical_gpus": gpus,
            "verified_encoders": [],
            "cpu_fallback": "libx264 (Software CPU — Primary Deterministic Privacy Engine)",
        }

    # Candidate hardware encoders to test
    candidates = [
        ("NVIDIA NVENC", "h264_nvenc", "H.264 Hardware Encoder"),
        ("NVIDIA NVENC", "hevc_nvenc", "HEVC Hardware Encoder"),
        ("NVIDIA NVENC", "av1_nvenc", "AV1 Hardware Encoder"),
        ("Intel QuickSync", "h264_qsv", "H.264 Hardware Encoder"),
        ("Intel QuickSync", "hevc_qsv", "HEVC Hardware Encoder"),
        ("Intel QuickSync", "av1_qsv", "AV1 Hardware Encoder"),
        ("AMD AMF", "h264_amf", "H.264 Hardware Encoder"),
        ("AMD AMF", "hevc_amf", "HEVC Hardware Encoder"),
        ("AMD AMF", "av1_amf", "AV1 Hardware Encoder"),
        ("Apple VideoToolbox", "h264_videotoolbox", "H.264 Hardware Encoder"),
        ("Apple VideoToolbox", "hevc_videotoolbox", "HEVC Hardware Encoder"),
    ]

    for vendor, enc_name, desc in candidates:
        try:
            # Run a fast 1-frame micro-encoding probe at 192x144 (satisfies NVENC/QSV/AMF minimum dimension limits)
            probe_cmd = [
                str(ffmpeg_p), "-y",
                "-f", "lavfi", "-i", "nullsrc=s=192x144:d=0.04",
                "-c:v", enc_name,
                "-pix_fmt", "yuv420p",
                "-f", "null", "-",
            ]
            res = subprocess.run(
                probe_cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=1.5,
            )
            if res.returncode == 0:
                verified_encoders.append({
                    "vendor": vendor,
                    "codec": enc_name,
                    "description": desc,
                    "status": "OPERATIONAL",
                })
        except Exception:
            continue

    return {
        "physical_gpus": gpus,
        "verified_encoders": verified_encoders,
        "cpu_fallback": "libx264 (Software CPU — Primary Deterministic Privacy Engine)",
    }
