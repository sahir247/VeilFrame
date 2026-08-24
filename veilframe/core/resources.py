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
