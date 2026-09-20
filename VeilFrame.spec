# -*- mode: python ; coding: utf-8 -*-
"""
VeilFrame PyInstaller Specification File
Cross-platform standalone packaging for Windows (x64), Linux (x86_64), and macOS (arm64/x86_64).
"""
import os
import shutil
import sys
from pathlib import Path

# --------------------------------------------------------------------------- #
# Platform Identification                                                     #
# --------------------------------------------------------------------------- #
IS_WINDOWS = sys.platform == "win32"
IS_LINUX = sys.platform.startswith("linux")
IS_MACOS = sys.platform == "darwin"

# On Windows, Chocolatey shims are ~20KB so 5MB threshold filters shims.
# On Linux and macOS, dynamic system binaries are >50KB.
MIN_REAL_BIN_SIZE = 5 * 1024 * 1024 if IS_WINDOWS else 50 * 1024


# --------------------------------------------------------------------------- #
# Modular Collectors                                                          #
# --------------------------------------------------------------------------- #
def collect_application_resources():
    """Collect non-code JSON presets, SVG/PNG icons, and static assets."""
    collected_datas = [
        ('veilframe/presets/profiles.json', 'veilframe/presets'),
        ('veilframe/resources', 'veilframe/resources'),
        ('veilframe/folder/rules', 'veilframe/folder/rules'),
    ]
    return collected_datas


def collect_ffmpeg_binaries():
    """
    Collects genuine FFmpeg & FFprobe binaries from project resources, user bin, or PATH.
    Does NOT attempt to bundle macOS system frameworks (e.g. Cocoa, VideoToolbox).
    """
    collected_binaries = []
    collected_datas = []
    added_names = set()

    def _add_binary(src_path: Path, name: str):
        p_str = str(src_path)
        collected_binaries.append((p_str, 'resources/ffmpeg'))
        collected_datas.append((p_str, 'resources/ffmpeg'))
        collected_datas.append((p_str, 'veilframe/resources/ffmpeg'))
        added_names.add(name.lower())
        print(f"[VeilFrame.spec] Bundled binary: {name} from {p_str} ({src_path.stat().st_size:,} bytes)")

    def _inspect_candidate(f_path: Path):
        if not f_path.is_file():
            return
        fname_lower = f_path.name.lower()
        base_name = fname_lower.replace(".exe", "")
        if base_name in ('ffmpeg', 'ffprobe') and fname_lower not in added_names:
            try:
                if f_path.stat().st_size > MIN_REAL_BIN_SIZE:
                    _add_binary(f_path, f_path.name)
            except Exception:
                pass

    # 1. Project resource directories
    for search_dir in [Path('resources/ffmpeg'), Path('veilframe/resources/ffmpeg')]:
        if search_dir.exists():
            for f in search_dir.iterdir():
                _inspect_candidate(f)

    # 2. User persistent binary directory (~/.veilframe/bin)
    user_bin = Path.home() / ".veilframe" / "bin"
    if user_bin.exists():
        for f in user_bin.iterdir():
            _inspect_candidate(f)

    # 3. System PATH / Package manager library locations
    for tool in ['ffmpeg', 'ffprobe']:
        candidates = [f"{tool}.exe", tool] if IS_WINDOWS else [tool]
        for c in candidates:
            if c.lower() not in added_names:
                which_p = shutil.which(tool)
                if which_p:
                    p = Path(which_p)
                    if p.is_file() and p.stat().st_size > MIN_REAL_BIN_SIZE:
                        _add_binary(p, c)
                        break
                if IS_WINDOWS:
                    choco_candidates = list(Path("C:/ProgramData/chocolatey/lib/ffmpeg").rglob(c))
                    for choco_p in choco_candidates:
                        if choco_p.is_file() and choco_p.stat().st_size > MIN_REAL_BIN_SIZE:
                            _add_binary(choco_p, c)
                            break

    print(f"[VeilFrame.spec] Bundled external tools: {sorted(list(added_names))}")
    return collected_binaries, collected_datas


def collect_hidden_imports():
    """Collect core dynamic / native runtime modules."""
    return [
        'cryptography',
        'cryptography.hazmat.primitives.asymmetric.ed25519',
        'cv2',
        'PIL',
        'PIL.Image',
        'numpy',
        'PySide6',
        'PySide6.QtCore',
        'PySide6.QtGui',
        'PySide6.QtWidgets',
        'pathspec',
        'yaml',
    ]


# --------------------------------------------------------------------------- #
# PyInstaller Analysis & Build Configuration                                  #
# --------------------------------------------------------------------------- #
datas = collect_application_resources()
extra_binaries, extra_datas = collect_ffmpeg_binaries()
datas.extend(extra_datas)

a = Analysis(
    ['run.py'],
    pathex=[],
    binaries=extra_binaries,
    datas=datas,
    hiddenimports=collect_hidden_imports(),
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

if IS_MACOS:
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name='VeilFrame',
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=False,
        disable_windowed_traceback=False,
        argv_emulation=False,
        target_arch=None,
        codesign_identity=None,
        entitlements_file=None,
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.datas,
        strip=False,
        upx=False,
        upx_exclude=[],
        name='VeilFrame',
    )
    app = BUNDLE(
        coll,
        name='VeilFrame.app',
        icon=None,
        bundle_identifier='org.veilframe.desktop',
        info_plist={
            'CFBundleDisplayName': 'VeilFrame',
            'CFBundleName': 'VeilFrame',
            'CFBundleShortVersionString': '2.2.7',
            'CFBundleVersion': '2.2.7',
            'NSHumanReadableCopyright': 'MIT License',
            'NSHighResolutionCapable': 'True',
            'LSMinimumSystemVersion': '11.0',
        },
    )
else:
    exe = EXE(
        pyz,
        a.scripts,
        a.binaries,
        a.datas,
        [],
        name='VeilFrame',
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,  # UPX disabled: prevents AV false positives and headless CI failure
        upx_exclude=[],
        runtime_tmpdir=None,
        console=False,
        disable_windowed_traceback=False,
        argv_emulation=False,
        target_arch=None,
        codesign_identity=None,
        entitlements_file=None,
    )

