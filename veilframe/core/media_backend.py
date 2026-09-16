"""
veilframe.core.media_backend — Abstraction layer for media execution and Scoped Storage sources.
Decouples video/image processing from host execution models (Desktop CLI vs Android JNI/SAF).
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import tempfile
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, BinaryIO, Dict, List, Optional, Tuple, Union


@dataclass
class MediaSource:
    """
    Portable representation of a media asset across Desktop and Android environments.
    Supports local filesystem paths, Android SAF content:// URIs, file descriptors,
    and temporary materialized files.
    """
    path: Optional[str] = None
    content_uri: Optional[str] = None
    file_descriptor: Optional[int] = None
    mime_type: Optional[str] = None
    _temp_materialized_path: Optional[str] = None

    def open_read(self) -> BinaryIO:
        """Open binary stream for reading."""
        if self.path and os.path.exists(self.path):
            return open(self.path, "rb")
        if self.file_descriptor is not None:
            return os.fdopen(os.dup(self.file_descriptor), "rb")
        if self.content_uri:
            try:
                from com.chaquo.python import Python  # type: ignore
                context = Python.getPlatform().getApplication()
                from android.net import Uri  # type: ignore
                uri_obj = Uri.parse(self.content_uri)
                in_stream = context.getContentResolver().openInputStream(uri_obj)
                from java.io import ByteArrayOutputStream  # type: ignore
                bos = ByteArrayOutputStream()
                buffer = bytearray(8192)
                while True:
                    read = in_stream.read(buffer)
                    if read <= 0:
                        break
                    bos.write(buffer, 0, read)
                return io.BytesIO(bytes(bos.toByteArray()))
            except Exception as e:
                raise OSError(f"Failed to read Android content URI {self.content_uri}: {e}")

        raise OSError("No readable source defined in MediaSource")

    def open_write(self) -> BinaryIO:
        """Open binary stream for writing."""
        if self.path:
            os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
            return open(self.path, "wb")
        if self.file_descriptor is not None:
            return os.fdopen(os.dup(self.file_descriptor), "wb")
        if self.content_uri:
            try:
                temp_f = tempfile.NamedTemporaryFile(delete=False)
                self._temp_materialized_path = temp_f.name
                return temp_f
            except Exception as e:
                raise OSError(f"Failed to open write stream for {self.content_uri}: {e}")

        raise OSError("No writable destination defined in MediaSource")

    def metadata(self) -> Dict[str, Any]:
        """Retrieve size, filename, and type metadata."""
        size = 0
        name = "media_asset"
        if self.path:
            name = os.path.basename(self.path)
            if os.path.exists(self.path):
                size = os.path.getsize(self.path)
        elif self.content_uri:
            name = self.content_uri.split("/")[-1]
            try:
                stream = self.open_read()
                size = len(stream.read())
                stream.seek(0)
            except Exception:
                pass

        return {
            "name": name,
            "size": size,
            "mime_type": self.mime_type or "application/octet-stream",
            "is_uri": bool(self.content_uri),
        }

    def as_path(self) -> str:
        """
        Materialize asset to a local filesystem path if not already accessible by path.
        Guarantees compatibility for tools requiring POSIX / Windows filesystem paths.
        """
        if self.path and os.path.exists(self.path):
            return self.path

        if self._temp_materialized_path and os.path.exists(self._temp_materialized_path):
            return self._temp_materialized_path

        temp_dir = tempfile.gettempdir()
        fd, tmp_path = tempfile.mkstemp(prefix="veilframe_media_", dir=temp_dir)
        os.close(fd)

        with self.open_read() as in_f, open(tmp_path, "wb") as out_f:
            shutil.copyfileobj(in_f, out_f)

        self._temp_materialized_path = tmp_path
        return tmp_path

    def cleanup(self) -> None:
        """Clean up temporary materialized files."""
        if self._temp_materialized_path and os.path.exists(self._temp_materialized_path):
            try:
                os.remove(self._temp_materialized_path)
            except OSError:
                pass
            self._temp_materialized_path = None


class MediaBackend(ABC):
    """Abstract interface isolating media transcoding, probing, and remuxing operations."""

    @abstractmethod
    def probe(self, source: MediaSource) -> Dict[str, Any]:
        """Probe media stream attributes (codecs, bitrate, dimensions, duration)."""
        pass

    @abstractmethod
    def transcode(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        """Transcode video/audio stream to destination with configured privacy parameters."""
        pass

    @abstractmethod
    def remux(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        """Losslessly remux streams stripping all container and track-level metadata."""
        pass


class DesktopFFmpegBackend(MediaBackend):
    """Executes media operations via local FFmpeg/FFprobe binaries on desktop OS."""

    def __init__(self, ffmpeg_bin: str = "ffmpeg", ffprobe_bin: str = "ffprobe") -> None:
        self.ffmpeg_bin = ffmpeg_bin
        self.ffprobe_bin = ffprobe_bin

    def probe(self, source: MediaSource) -> Dict[str, Any]:
        path = source.as_path()
        cmd = [
            self.ffprobe_bin,
            "-v", "error",
            "-show_format",
            "-show_streams",
            "-of", "json",
            path,
        ]
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            import json
            return json.loads(res.stdout)
        except Exception as e:
            return {"error": str(e)}

    def transcode(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        src_path = source.as_path()
        dst_path = destination.path or destination.as_path()

        cmd = [self.ffmpeg_bin, "-y", "-i", src_path]
        for opt, val in options.items():
            if val is not None and val is not False:
                cmd.append(f"-{opt}")
                if val is not True:
                    cmd.append(str(val))
        cmd.append(dst_path)

        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            return res.returncode, res.stdout + res.stderr
        except Exception as e:
            return 1, str(e)

    def remux(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        opts = {"c": "copy", "map_metadata": "-1"}
        opts.update(options)
        return self.transcode(source, destination, opts)


class AndroidMediaBackend(MediaBackend):
    """Executes media operations on Android via JNI (ffmpegkit) or bundled library."""

    def probe(self, source: MediaSource) -> Dict[str, Any]:
        from veilframe.core.android_bridge import android_bridge
        src_path = source.as_path()
        args = ["-v", "error", "-show_format", "-show_streams", "-of", "json", src_path]
        rc, out = android_bridge.execute_ffmpeg_android(args)
        if rc == 0:
            import json
            try:
                return json.loads(out)
            except Exception:
                pass
        return {"rc": rc, "output": out}

    def transcode(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        from veilframe.core.android_bridge import android_bridge
        src_path = source.as_path()
        dst_path = destination.path or destination.as_path()

        args = ["-y", "-i", src_path]
        for opt, val in options.items():
            if val is not None and val is not False:
                args.append(f"-{opt}")
                if val is not True:
                    args.append(str(val))
        args.append(dst_path)

        return android_bridge.execute_ffmpeg_android(args)

    def remux(
        self,
        source: MediaSource,
        destination: MediaSource,
        options: Dict[str, Any],
    ) -> Tuple[int, str]:
        opts = {"c": "copy", "map_metadata": "-1"}
        opts.update(options)
        return self.transcode(source, destination, opts)


def get_media_backend() -> MediaBackend:
    """Factory returning the active media execution backend for the current platform."""
    from veilframe.core.android_bridge import is_android
    if is_android():
        return AndroidMediaBackend()
    return DesktopFFmpegBackend()
