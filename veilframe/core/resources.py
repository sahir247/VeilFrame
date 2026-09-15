"""
Executable and binary resource locator for FFmpeg and FFprobe.
"""
import os
import sys
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Optional, List, Dict


def get_subprocess_flags() -> int:
    """
    Returns platform-specific subprocess creation flags.
    On Windows, returns CREATE_NO_WINDOW (0x08000000) to suppress flashing console windows in GUI mode.
    """
    if os.name == "nt":
        return getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
    return 0


class FFmpegNotFoundError(RuntimeError):
    pass


def _is_valid_executable_file(path: Path) -> bool:
    """Verifies that a candidate path is an existing, non-empty regular file."""
    try:
        return path.is_file() and path.stat().st_size > 0
    except Exception:
        return False


def _is_functional_binary(path: Path) -> bool:
    """Verifies that a candidate path exists and can execute -version successfully."""
    try:
        if not (path.is_file() and path.stat().st_size > 1024):
            return False
        res = subprocess.run(
            [str(path), "-version"],
            capture_output=True,
            timeout=3,
            creationflags=get_subprocess_flags(),
        )
        return res.returncode == 0
    except Exception:
        return False


def find_executable(name: str) -> Path:
    """
    Locates the requested executable ('ffmpeg' or 'ffprobe') across:
    1. Environment variable overrides (FFMPEG_BINARY / FFPROBE_BINARY)
    2. User-level persistent binary directory (~/.veilframe/bin/)
    3. PyInstaller bundled resources (sys._MEIPASS)
    4. Package resources directory (`veilframe/resources/ffmpeg/`)
    5. Project root `resources/ffmpeg/`
    6. Executable adjacent directory (when running as frozen binary)
    7. System PATH (shutil.which)
    8. Local application cache directories (Windows fallback)
    """
    ext = ".exe" if os.name == "nt" else ""
    exe_name = f"{name}{ext}"
    candidates: List[Path] = []

    # 1. PyInstaller bundle (top priority for standalone frozen executable)
    if getattr(sys, "frozen", False):
        if hasattr(sys, "_MEIPASS"):
            meipass = Path(sys._MEIPASS)
            candidates.append(meipass / "resources" / "ffmpeg" / exe_name)
            candidates.append(meipass / "veilframe" / "resources" / "ffmpeg" / exe_name)
            candidates.append(meipass / exe_name)
        exe_dir = Path(sys.executable).parent
        candidates.append(exe_dir / "resources" / "ffmpeg" / exe_name)
        candidates.append(exe_dir / "veilframe" / "resources" / "ffmpeg" / exe_name)
        candidates.append(exe_dir / exe_name)

    # 2. Environment variable override (validated candidate)
    env_var = f"{name.upper()}_BINARY"
    env_val = os.environ.get(env_var)
    if env_val:
        env_p = Path(env_val).resolve()
        if env_p.exists() and not env_p.is_dir():
            candidates.append(env_p)

    # 3. User-level persistent binary directory (~/.veilframe/bin/)
    candidates.append(Path.home() / ".veilframe" / "bin" / exe_name)

    # 4. Package resources
    pkg_dir = Path(__file__).parent.parent
    candidates.append(pkg_dir / "resources" / "ffmpeg" / exe_name)
    candidates.append(pkg_dir.parent / "resources" / "ffmpeg" / exe_name)

    # 5. Working directory resources
    candidates.append(Path.cwd() / "resources" / "ffmpeg" / exe_name)
    candidates.append(Path.cwd() / "veilframe" / "resources" / "ffmpeg" / exe_name)

    # 7. System PATH
    which_path = shutil.which(name)
    if which_path:
        candidates.append(Path(which_path))

    # 8. Windows local application cache fallback
    if os.name == "nt":
        user_profile = os.environ.get("USERPROFILE", "")
        if user_profile:
            candidates.append(Path(user_profile) / "AppData" / "Local" / "VeilFrame" / "bin" / exe_name)

    # First pass: find a functional, runnable binary
    for cand in candidates:
        if _is_functional_binary(cand):
            return cand

    # Second pass fallback: find any existing non-empty file (e.g. in test fixtures)
    for cand in candidates:
        if _is_valid_executable_file(cand):
            return cand

    raise FFmpegNotFoundError(
        f"'{name}' executable was not found. Please ensure FFmpeg and FFprobe are installed on system PATH, "
        f"configured via {env_var}, installed in '~/.veilframe/bin/', or bundled in 'veilframe/resources/ffmpeg/'."
    )


def get_ffmpeg_path() -> Path:
    return find_executable("ffmpeg")


def get_ffprobe_path() -> Path:
    return find_executable("ffprobe")


def detect_physical_gpus() -> list[str]:
    """
    Queries operating system APIs to detect physically installed GPU hardware devices.
    Returns a list of device names (e.g. ['NVIDIA GeForce RTX 4050 Laptop GPU']).
    Uses zero-subprocess Win32 EnumDisplayDevicesW API on Windows for instant (<1ms) detection.
    """
    gpus: list[str] = []
    flags = get_subprocess_flags()

    # 1. Windows: Try Win32 EnumDisplayDevicesW via ctypes (Instant, 0 subprocesses, no window popups)
    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes

            class DISPLAY_DEVICEW(ctypes.Structure):
                _fields_ = [
                    ("cb", wintypes.DWORD),
                    ("DeviceName", wintypes.WCHAR * 32),
                    ("DeviceString", wintypes.WCHAR * 128),
                    ("StateFlags", wintypes.DWORD),
                    ("DeviceID", wintypes.WCHAR * 128),
                    ("DeviceKey", wintypes.WCHAR * 128),
                ]

            dev = DISPLAY_DEVICEW()
            dev.cb = ctypes.sizeof(dev)
            i = 0
            while ctypes.windll.user32.EnumDisplayDevicesW(None, i, ctypes.byref(dev), 0):
                name = dev.DeviceString.strip()
                if name and name not in gpus:
                    # Filter out virtual/remote display drivers
                    lower_name = name.lower()
                    if not any(v in lower_name for v in ("rdp", "virtual", "vnc", "indirect", "basic render")):
                        gpus.append(name)
                i += 1
        except Exception:
            pass

    # 2. Try nvidia-smi if NVIDIA GPU is present
    if not gpus or not any("nvidia" in g.lower() for g in gpus):
        try:
            res = subprocess.run(
                ["nvidia-smi", "-L"],
                capture_output=True,
                text=True,
                timeout=1.5,
                creationflags=flags,
            )
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.strip().splitlines():
                    if "GPU " in line and ":" in line:
                        name_part = line.split(":", 1)[1].split("(UUID")[0].strip()
                        if name_part and name_part not in gpus:
                            gpus.append(name_part)
        except Exception:
            pass

    # 3. Windows CIM / WMI Query fallback if ctypes returned nothing
    if os.name == "nt" and not gpus:
        try:
            cmd = ["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name"]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=2.5, creationflags=flags)
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.strip().splitlines():
                    name = line.strip()
                    if name and name not in gpus:
                        gpus.append(name)
        except Exception:
            pass

    # 4. Linux lspci / sysfs
    elif sys.platform.startswith("linux"):
        try:
            res = subprocess.run(["lspci"], capture_output=True, text=True, timeout=1.5, creationflags=flags)
            if res.returncode == 0 and res.stdout:
                for line in res.stdout.strip().splitlines():
                    if any(k in line.lower() for k in ("vga compatible", "3d controller", "display controller")):
                        parts = line.split(":", 2)
                        name = parts[-1].strip() if len(parts) >= 3 else line
                        if name and name not in gpus:
                            gpus.append(name)
        except Exception:
            pass

    # 5. macOS system_profiler
    elif sys.platform == "darwin":
        try:
            res = subprocess.run(["system_profiler", "SPDisplaysDataType"], capture_output=True, text=True, timeout=2.5, creationflags=flags)
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
    by running concurrent micro-encoding test probes against the active FFmpeg binary.
    Executes silently with CREATE_NO_WINDOW on Windows.
    """
    gpus = detect_physical_gpus()

    try:
        ffmpeg_p = get_ffmpeg_path()
    except Exception:
        return {
            "physical_gpus": gpus,
            "verified_encoders": [],
            "cpu_fallback": "libx264 (Software CPU — Primary Deterministic Privacy Engine)",
        }

    # Platform-filtered candidate hardware encoders to test
    all_candidates = []
    if sys.platform == "darwin":
        all_candidates.extend([
            ("Apple VideoToolbox", "h264_videotoolbox", "H.264 Hardware Encoder"),
            ("Apple VideoToolbox", "hevc_videotoolbox", "HEVC Hardware Encoder"),
        ])
    else:
        # Windows / Linux encoders
        all_candidates.extend([
            ("NVIDIA NVENC", "h264_nvenc", "H.264 Hardware Encoder"),
            ("NVIDIA NVENC", "hevc_nvenc", "HEVC Hardware Encoder"),
            ("NVIDIA NVENC", "av1_nvenc", "AV1 Hardware Encoder"),
            ("Intel QuickSync", "h264_qsv", "H.264 Hardware Encoder"),
            ("Intel QuickSync", "hevc_qsv", "HEVC Hardware Encoder"),
            ("Intel QuickSync", "av1_qsv", "AV1 Hardware Encoder"),
            ("AMD AMF", "h264_amf", "H.264 Hardware Encoder"),
            ("AMD AMF", "hevc_amf", "HEVC Hardware Encoder"),
            ("AMD AMF", "av1_amf", "AV1 Hardware Encoder"),
        ])

    flags = get_subprocess_flags()

    def _probe_encoder(candidate: tuple) -> Optional[dict]:
        vendor, enc_name, desc = candidate
        try:
            # Run a fast 1-frame micro-encoding probe at 192x144
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
                creationflags=flags,
            )
            if res.returncode == 0:
                return {
                    "vendor": vendor,
                    "codec": enc_name,
                    "description": desc,
                    "status": "OPERATIONAL",
                }
        except Exception:
            pass
        return None

    verified_encoders: list[dict] = []
    max_workers = min(4, max(1, os.cpu_count() or 1))
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        results = executor.map(_probe_encoder, all_candidates)
        for r in results:
            if r is not None:
                verified_encoders.append(r)

    return {
        "physical_gpus": gpus,
        "verified_encoders": verified_encoders,
        "cpu_fallback": "libx264 (Software CPU — Primary Deterministic Privacy Engine)",
    }

