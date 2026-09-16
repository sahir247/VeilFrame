"""
veilframe.core.android_bridge — Android runtime detection, Scoped Storage resolution, and JNI bridging.
Enables full VeilFrame capabilities (Video cleaning, Image cleaning, Folder scanning, AI Bundle generation)
on Android 8.0+ (API 26 to 34+).
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple


def is_android() -> bool:
    """
    Detect if VeilFrame is currently executing inside an Android runtime
    (Chaquopy, PyJNIus, Termux, or Android Python wrapper).
    """
    if "ANDROID_ROOT" in os.environ or "ANDROID_BOOTLOGO" in os.environ:
        return True
    if hasattr(sys, "getandroidapilevel"):
        return True
    try:
        # Chaquopy runtime check
        import java.lang.System  # type: ignore
        return True
    except ImportError:
        pass
    return False


def get_android_api_level() -> Optional[int]:
    """Retrieve Android SDK API level if available."""
    if hasattr(sys, "getandroidapilevel"):
        try:
            return sys.getandroidapilevel()  # type: ignore
        except Exception:
            pass
    try:
        from android.os import Build  # type: ignore
        return Build.VERSION.SDK_INT
    except Exception:
        pass
    return None


@dataclass
class AndroidStorageContext:
    """Paths and storage descriptors for Android Scoped Storage."""
    internal_cache_dir: str
    external_files_dir: Optional[str]
    is_scoped_storage_active: bool
    can_manage_external_storage: bool


class AndroidBridge:
    """
    Central gateway coordinating Android system capabilities with VeilFrame's 4 core subsystems:
      1. Video Cleaner (routes to FFmpegKit JNI or bundled libffmpeg.so)
      2. Image Cleaner (manages EXIF/PRNU via mobile Pillow/OpenCV)
      3. Folder Scanner (mediates SAF content:// URIs and internal caches)
      4. AI Bundle Generator (runs full multi-language context compression locally on-device)
    """

    def __init__(self) -> None:
        self._is_android = is_android()
        self._api_level = get_android_api_level()
        self._cached_context: Optional[AndroidStorageContext] = None

    @property
    def is_active(self) -> bool:
        return self._is_android

    @property
    def api_level(self) -> Optional[int]:
        return self._api_level

    def get_storage_context(self) -> AndroidStorageContext:
        """Resolve storage directories adhering to Android Scoped Storage rules."""
        if self._cached_context:
            return self._cached_context

        cache_dir = os.environ.get("TMPDIR", "/data/local/tmp")
        ext_dir = None
        is_scoped = False
        can_manage = False

        if self._is_android:
            try:
                from com.chaquo.python import Python  # type: ignore
                context = Python.getPlatform().getApplication()
                cache_dir = str(context.getCacheDir().getAbsolutePath())
                ext_files = context.getExternalFilesDir(None)
                if ext_files:
                    ext_dir = str(ext_files.getAbsolutePath())

                from android.os import Build, Environment  # type: ignore
                if Build.VERSION.SDK_INT >= 29:
                    is_scoped = True
                if Build.VERSION.SDK_INT >= 30:
                    can_manage = Environment.isExternalStorageManager()
            except Exception:
                # Fallback to standard environment heuristics
                is_scoped = (self._api_level or 0) >= 29

        self._cached_context = AndroidStorageContext(
            internal_cache_dir=cache_dir,
            external_files_dir=ext_dir,
            is_scoped_storage_active=is_scoped,
            can_manage_external_storage=can_manage,
        )
        return self._cached_context

    def resolve_ffmpeg_binary_or_jni(self) -> Tuple[str, str]:
        """
        Locate the suitable FFmpeg invocation method for Android:
        Returns (method: 'ffmpegkit' | 'native_binary' | 'system', path_or_class: str)
        """
        if not self._is_android:
            return "system", "ffmpeg"

        # 1. Check for FFmpegKit JNI
        try:
            from com.arthenica.ffmpegkit import FFmpegKit  # type: ignore
            return "ffmpegkit", "com.arthenica.ffmpegkit.FFmpegKit"
        except ImportError:
            pass

        # 2. Check for nativeLibraryDir libffmpeg.so
        try:
            from com.chaquo.python import Python  # type: ignore
            native_dir = str(Python.getPlatform().getApplication().getApplicationInfo().nativeLibraryDir)
            bundled = os.path.join(native_dir, "libffmpeg.so")
            if os.path.exists(bundled):
                return "native_binary", bundled
        except Exception:
            pass

        # 3. Termux or root fallback
        termux_bin = "/data/data/com.termux/files/usr/bin/ffmpeg"
        if os.path.exists(termux_bin):
            return "system", termux_bin

        return "system", "ffmpeg"

    def execute_ffmpeg_android(self, args: List[str]) -> Tuple[int, str]:
        """
        Execute FFmpeg command on Android safely handling JNI bridging or subprocess.
        """
        method, target = self.resolve_ffmpeg_binary_or_jni()
        if method == "ffmpegkit":
            try:
                from com.arthenica.ffmpegkit import FFmpegKit  # type: ignore
                cmd_str = " ".join(f'"{a}"' if " " in a else a for a in args)
                session = FFmpegKit.execute(cmd_str)
                rc = int(session.getReturnCode().getValue())
                logs = str(session.getAllLogsAsString())
                return rc, logs
            except Exception as e:
                return 1, f"FFmpegKit execution failed: {e}"

        import subprocess
        full_cmd = [target] + args
        try:
            res = subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            return res.returncode, res.stdout + res.stderr
        except Exception as e:
            return 1, f"Failed to execute {target}: {e}"

    def get_system_diagnostics(self) -> Dict[str, Any]:
        """Health and environment report for VeilFrame Android runtime."""
        storage = self.get_storage_context()
        method, target = self.resolve_ffmpeg_binary_or_jni()
        return {
            "is_android": self._is_android,
            "api_level": self._api_level,
            "ffmpeg_provider": method,
            "ffmpeg_target": target,
            "internal_cache": storage.internal_cache_dir,
            "external_files": storage.external_files_dir,
            "scoped_storage": storage.is_scoped_storage_active,
            "can_manage_storage": storage.can_manage_external_storage,
        }


# Singleton bridge instance
android_bridge = AndroidBridge()
