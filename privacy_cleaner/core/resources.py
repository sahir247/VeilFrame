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
    1. PyInstaller bundled resources (sys._MEIPASS)
    2. Package resources directory (`privacy_cleaner/resources/ffmpeg/`)
    3. Project root `resources/ffmpeg/`
    4. Environment variable overrides (FFMPEG_BINARY / FFPROBE_BINARY)
    5. Local Cypress cache (on Windows if present)
    6. System PATH
    """
    ext = ".exe" if os.name == "nt" else ""
    exe_name = f"{name}{ext}"

    # 1. PyInstaller bundle
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        bundle_path = Path(sys._MEIPASS) / "resources" / "ffmpeg" / exe_name
        if bundle_path.exists():
            return bundle_path
        bundle_root_path = Path(sys._MEIPASS) / exe_name
        if bundle_root_path.exists():
            return bundle_root_path

    # 2. Package resources
    pkg_res = Path(__file__).parent.parent / "resources" / "ffmpeg" / exe_name
    if pkg_res.exists():
        return pkg_res

    # 3. Working directory resources
    cwd_res = Path.cwd() / "resources" / "ffmpeg" / exe_name
    if cwd_res.exists():
        return cwd_res

    # 4. Environment variable override
    env_var = f"{name.upper()}_BINARY"
    env_val = os.environ.get(env_var)
    if env_val and Path(env_val).exists():
        return Path(env_val)

    # 5. System PATH
    which_path = shutil.which(name)
    if which_path:
        return Path(which_path)

    # 6. Local Windows Cypress cache fallback if available
    if os.name == "nt":
        user_profile = os.environ.get("USERPROFILE", "")
        if user_profile:
            cypress_pattern = Path(user_profile) / "AppData" / "Local" / "Cypress" / "Cache"
            if cypress_pattern.exists():
                for installer_dir in ("@ffmpeg-installer", "@ffprobe-installer"):
                    candidate = cypress_pattern / "15.20.1" / "Cypress" / "resources" / "app" / "node_modules" / installer_dir / "win32-x64" / exe_name
                    if candidate.exists():
                        return candidate

    raise FFmpegNotFoundError(
        f"'{name}' executable was not found. Please ensure FFmpeg and FFprobe are bundled in "
        f"'privacy_cleaner/resources/ffmpeg/' or installed on system PATH."
    )


def get_ffmpeg_path() -> Path:
    return find_executable("ffmpeg")


def get_ffprobe_path() -> Path:
    return find_executable("ffprobe")
