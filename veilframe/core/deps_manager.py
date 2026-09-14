"""
VeilFrame Dependency & Environment Manager.
===========================================
Audits runtime dependencies (FFmpeg, OpenCV, Pillow, Cryptography, GPU hardware)
and provides automated on-demand downloading and extraction for missing external binaries.
"""
import os
import sys
import platform
import subprocess
import shutil
import zipfile
import tarfile
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Dict, Optional, Callable, Tuple

from .resources import (
    find_executable,
    get_ffmpeg_path,
    get_ffprobe_path,
    get_hardware_capabilities,
    get_subprocess_flags,
    FFmpegNotFoundError,
)


@dataclass
class DependencyAuditItem:
    name: str
    version: str
    status: str
    is_ok: bool
    details: str
    is_critical: bool = False


@dataclass
class EnvironmentReport:
    os_info: str
    python_version: str
    items: List[DependencyAuditItem] = field(default_factory=list)
    physical_gpus: List[str] = field(default_factory=list)
    verified_encoders: List[dict] = field(default_factory=list)
    is_healthy: bool = True
    missing_critical: List[str] = field(default_factory=list)


def get_user_bin_dir() -> Path:
    """Returns the persistent user-level binary directory for downloaded tools (~/.veilframe/bin)."""
    bin_dir = Path.home() / ".veilframe" / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    return bin_dir


def is_ffmpeg_installed() -> bool:
    """Check if FFmpeg binary is available and functionally executable."""
    try:
        ffmpeg_p = get_ffmpeg_path()
        if not (ffmpeg_p.exists() and ffmpeg_p.stat().st_size > 1024):
            return False
        result = subprocess.run(
            [str(ffmpeg_p), "-version"],
            capture_output=True,
            timeout=5,
            creationflags=get_subprocess_flags(),
        )
        return result.returncode == 0
    except Exception:
        return False


def is_ffprobe_installed() -> bool:
    """Check if FFprobe binary is available and functionally executable."""
    try:
        ffprobe_p = get_ffprobe_path()
        if not (ffprobe_p.exists() and ffprobe_p.stat().st_size > 1024):
            return False
        result = subprocess.run(
            [str(ffprobe_p), "-version"],
            capture_output=True,
            timeout=5,
            creationflags=get_subprocess_flags(),
        )
        return result.returncode == 0
    except Exception:
        return False


def audit_environment() -> EnvironmentReport:
    """Performs an audit of all multimedia and cryptographic dependencies."""
    items: List[DependencyAuditItem] = []
    missing_critical: List[str] = []
    flags = get_subprocess_flags()

    # 1. FFmpeg
    ffmpeg_ok = False
    ffmpeg_ver = "Missing"
    ffmpeg_detail = "Not found on system PATH or bundle"
    try:
        p = get_ffmpeg_path()
        if p.exists() and p.stat().st_size > 1024:
            ffmpeg_ok = True
            ffmpeg_detail = str(p)
            try:
                res = subprocess.run(
                    [str(p), "-version"],
                    capture_output=True,
                    text=True,
                    timeout=3,
                    creationflags=flags,
                )
                first = res.stdout.splitlines()[0] if res.stdout else ""
                ffmpeg_ver = first.split("version")[1].split()[0] if "version" in first else "Available"
            except Exception:
                ffmpeg_ver = "Found"
    except Exception as e:
        ffmpeg_detail = str(e)

    if not ffmpeg_ok:
        missing_critical.append("FFmpeg (Required for video sanitization & audio ENF filtering)")

    items.append(DependencyAuditItem(
        name="FFmpeg Binary",
        version=ffmpeg_ver,
        status="FOUND" if ffmpeg_ok else "MISSING",
        is_ok=ffmpeg_ok,
        details=ffmpeg_detail,
        is_critical=True,
    ))

    # 2. FFprobe
    ffprobe_ok = False
    ffprobe_ver = "Missing"
    ffprobe_detail = "Not found on system PATH or bundle"
    try:
        p = get_ffprobe_path()
        if p.exists() and p.stat().st_size > 1024:
            ffprobe_ok = True
            ffprobe_ver = "Available"
            ffprobe_detail = str(p)
    except Exception as e:
        ffprobe_detail = str(e)

    if not ffprobe_ok:
        missing_critical.append("FFprobe (Required for video stream analysis)")

    items.append(DependencyAuditItem(
        name="FFprobe Binary",
        version=ffprobe_ver,
        status="FOUND" if ffprobe_ok else "MISSING",
        is_ok=ffprobe_ok,
        details=ffprobe_detail,
        is_critical=True,
    ))

    # 3. OpenCV (cv2)
    cv_ok = False
    cv_ver = "Missing"
    try:
        import cv2
        cv_ok = True
        cv_ver = f"v{cv2.__version__}"
        cv_detail = "Haar cascades & DNN vision pipelines active"
    except ImportError:
        cv_detail = "OpenCV not installed"

    items.append(DependencyAuditItem(
        name="OpenCV (cv2)",
        version=cv_ver,
        status="READY" if cv_ok else "MISSING",
        is_ok=cv_ok,
        details=cv_detail,
        is_critical=False,
    ))

    # 4. Pillow (PIL)
    pil_ok = False
    pil_ver = "Missing"
    try:
        import PIL
        pil_ok = True
        pil_ver = f"v{PIL.__version__}"
        pil_detail = "Container sanitization & image parsing active"
    except ImportError:
        pil_detail = "Pillow not installed"

    items.append(DependencyAuditItem(
        name="Pillow (PIL)",
        version=pil_ver,
        status="READY" if pil_ok else "MISSING",
        is_ok=pil_ok,
        details=pil_detail,
        is_critical=True,
    ))

    # 5. Cryptography (Ed25519 & SHA256)
    crypto_ok = False
    crypto_ver = "Missing"
    try:
        import cryptography
        crypto_ok = True
        crypto_ver = f"v{cryptography.__version__}"
        crypto_detail = "Ed25519 signing & RFC 8785 manifest provenance active"
    except ImportError:
        crypto_detail = "Cryptography package not installed"

    items.append(DependencyAuditItem(
        name="Cryptography",
        version=crypto_ver,
        status="ACCELERATED" if crypto_ok else "MISSING",
        is_ok=crypto_ok,
        details=crypto_detail,
        is_critical=True,
    ))

    # 6. NumPy & SciPy
    np_ok = False
    np_ver = "Missing"
    try:
        import numpy as np
        np_ok = True
        np_ver = f"v{np.__version__}"
    except ImportError:
        pass

    items.append(DependencyAuditItem(
        name="NumPy Numerical Engine",
        version=np_ver,
        status="ACCELERATED" if np_ok else "MISSING",
        is_ok=np_ok,
        details="Vectorized CFA PRNU & DCT dither matrices",
        is_critical=True,
    ))

    # 7. Hardware Capabilities
    hw = get_hardware_capabilities()
    physical_gpus = hw.get("physical_gpus", [])
    verified_encoders = hw.get("verified_encoders", [])

    is_healthy = len(missing_critical) == 0

    return EnvironmentReport(
        os_info=platform.platform(),
        python_version=sys.version.split()[0],
        items=items,
        physical_gpus=physical_gpus,
        verified_encoders=verified_encoders,
        is_healthy=is_healthy,
        missing_critical=missing_critical,
    )


# Download URLs for FFmpeg static releases
FFMPEG_RELEASE_URLS = {
    "win64": "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
    "win64_fallback": "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
    "win64_fallback2": "https://github.com/GyanD/codexffmpeg/releases/download/7.1/ffmpeg-7.1-essentials_build.zip",
    "linux64": "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz",
    "macos64": "https://evermeet.cx/ffmpeg/getrelease/zip",
}


def download_and_install_ffmpeg(
    progress_callback: Optional[Callable[[int, str], None]] = None,
    cancel_check: Optional[Callable[[], bool]] = None,
) -> Tuple[bool, str]:
    """
    Downloads static FFmpeg + FFprobe release archive, extracts executables to ~/.veilframe/bin/,
    and adds the directory to active PATH.
    
    Parameters
    ----------
    progress_callback : Optional[Callable[[int, str], None]]
        Function receiving (percentage: int 0-100, status_message: str).
    cancel_check : Optional[Callable[[], bool]]
        Optional function returning True if user cancelled download.
        
    Returns
    -------
    Tuple[bool, str]
        (success: bool, status_message_or_error: str)
    """
    target_bin_dir = get_user_bin_dir()

    # Determine platform URLs
    urls_to_try = []
    if os.name == "nt":
        urls_to_try = [
            FFMPEG_RELEASE_URLS["win64"],
            FFMPEG_RELEASE_URLS["win64_fallback"],
            FFMPEG_RELEASE_URLS["win64_fallback2"],
        ]
        archive_ext = ".zip"
    elif sys.platform.startswith("linux"):
        urls_to_try = [FFMPEG_RELEASE_URLS["linux64"]]
        archive_ext = ".tar.xz"
    elif sys.platform == "darwin":
        urls_to_try = [FFMPEG_RELEASE_URLS["macos64"]]
        archive_ext = ".zip"
    else:
        return False, f"Unsupported operating system: {sys.platform}"

    temp_archive = target_bin_dir / f"ffmpeg_download{archive_ext}"

    def _do_download(download_url: str) -> bool:
        import time as _time
        if progress_callback:
            domain = download_url.split('/')[2] if '/' in download_url else download_url
            progress_callback(5, f"Connecting to {domain}...")
        req = urllib.request.Request(
            download_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            }
        )
        # Per-read socket timeout (30s) + hard wall-clock deadline (10 minutes)
        TOTAL_TIMEOUT_SEC = 600
        deadline = _time.monotonic() + TOTAL_TIMEOUT_SEC
        with urllib.request.urlopen(req, timeout=30) as resp, open(temp_archive, "wb") as out_file:
            total_size = int(resp.headers.get("content-length", 0))
            downloaded = 0
            block_size = 1024 * 128  # 128 KB chunks

            while True:
                if cancel_check and cancel_check():
                    return False
                if _time.monotonic() > deadline:
                    raise TimeoutError(f"Download exceeded {TOTAL_TIMEOUT_SEC}s wall-clock timeout.")
                chunk = resp.read(block_size)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)
                if total_size > 0 and progress_callback:
                    pct = int(10 + (downloaded / total_size) * 70)  # 10% to 80%
                    mb_cur = downloaded / (1024 * 1024)
                    mb_tot = total_size / (1024 * 1024)
                    progress_callback(pct, f"Downloading FFmpeg: {mb_cur:.1f} MB / {mb_tot:.1f} MB ({pct}%)")

        return True

    # 1. Download with fallback support
    download_success = False
    last_err = "No URLs attempted"
    for candidate_url in urls_to_try:
        try:
            if _do_download(candidate_url):
                download_success = True
                break
        except Exception as e:
            last_err = str(e)
            continue

    if not download_success:
        return False, f"Download failed across all mirrors: {last_err}"

    if cancel_check and cancel_check():
        if temp_archive.exists():
            temp_archive.unlink()
        return False, "Installation cancelled by user."

    # 2. Extract executables
    if progress_callback:
        progress_callback(85, "Extracting FFmpeg & FFprobe binaries...")

    ffmpeg_target_name = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    ffprobe_target_name = "ffprobe.exe" if os.name == "nt" else "ffprobe"
    extracted_ffmpeg = False
    extracted_ffprobe = False

    try:
        if archive_ext == ".zip":
            with zipfile.ZipFile(temp_archive, "r") as zf:
                for member in zf.namelist():
                    base_name = os.path.basename(member).lower()
                    if base_name in ("ffmpeg.exe", "ffmpeg"):
                        dst_file = target_bin_dir / ffmpeg_target_name
                        with zf.open(member) as src, open(dst_file, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        if os.name != "nt":
                            dst_file.chmod(0o755)
                        extracted_ffmpeg = True
                    elif base_name in ("ffprobe.exe", "ffprobe"):
                        dst_file = target_bin_dir / ffprobe_target_name
                        with zf.open(member) as src, open(dst_file, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        if os.name != "nt":
                            dst_file.chmod(0o755)
                        extracted_ffprobe = True
        elif archive_ext == ".tar.xz":
            with tarfile.open(temp_archive, "r:xz") as tf:
                for member in tf.getmembers():
                    base_name = os.path.basename(member.name).lower()
                    if base_name in ("ffmpeg.exe", "ffmpeg"):
                        dst_file = target_bin_dir / ffmpeg_target_name
                        extracted_file = tf.extractfile(member)
                        if extracted_file:
                            with open(dst_file, "wb") as dst:
                                shutil.copyfileobj(extracted_file, dst)
                            if os.name != "nt":
                                dst_file.chmod(0o755)
                            extracted_ffmpeg = True
                    elif base_name in ("ffprobe.exe", "ffprobe"):
                        dst_file = target_bin_dir / ffprobe_target_name
                        extracted_file = tf.extractfile(member)
                        if extracted_file:
                            with open(dst_file, "wb") as dst:
                                shutil.copyfileobj(extracted_file, dst)
                            if os.name != "nt":
                                dst_file.chmod(0o755)
                            extracted_ffprobe = True
    except Exception as e:
        return False, f"Extraction failed: {e}"
    finally:
        if temp_archive.exists():
            try:
                temp_archive.unlink()
            except Exception:
                pass

    if not extracted_ffmpeg:
        return False, "Could not locate ffmpeg binary inside the downloaded archive."

    # 3. Add to live PATH environment
    bin_str = str(target_bin_dir)
    cur_path = os.environ.get("PATH", "")
    if bin_str not in cur_path:
        os.environ["PATH"] = f"{bin_str}{os.pathsep}{cur_path}"

    # 4. Verify functional execution of both binaries
    if progress_callback:
        progress_callback(95, "Verifying installed binaries...")

    flags = get_subprocess_flags()
    try:
        ffmpeg_bin = target_bin_dir / ffmpeg_target_name
        res = subprocess.run(
            [str(ffmpeg_bin), "-version"],
            capture_output=True,
            text=True,
            timeout=10,
            creationflags=flags,
        )
        if res.returncode != 0:
            return False, "FFmpeg binary failed execution probe."

        if extracted_ffprobe:
            ffprobe_bin = target_bin_dir / ffprobe_target_name
            res_probe = subprocess.run(
                [str(ffprobe_bin), "-version"],
                capture_output=True,
                text=True,
                timeout=10,
                creationflags=flags,
            )
            if res_probe.returncode != 0:
                return False, "FFprobe binary failed execution probe."
    except Exception as e:
        return False, f"Verification failed: {e}"

    if progress_callback:
        progress_callback(100, "FFmpeg & FFprobe verified & operational!")

    return True, f"FFmpeg & FFprobe installed successfully to {target_bin_dir}"

