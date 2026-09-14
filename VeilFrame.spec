import os
import shutil
from pathlib import Path

# Collect data files
datas = [
    ('veilframe/presets/profiles.json', 'veilframe/presets'),
    ('veilframe/resources', 'veilframe/resources'),
]

# Collect ffmpeg & ffprobe binaries if present in project resources, user bin, or build environment PATH
binaries = []
added_names = set()

# 1. Project resource directories
for ffmpeg_dir in [Path('resources/ffmpeg'), Path('veilframe/resources/ffmpeg')]:
    if ffmpeg_dir.exists():
        for exe_f in ffmpeg_dir.glob('*.exe'):
            if exe_f.name.lower() not in added_names and exe_f.is_file() and exe_f.stat().st_size > 1024:
                binaries.append((str(exe_f), 'resources/ffmpeg'))
                added_names.add(exe_f.name.lower())

# 2. User binary directory ~/.veilframe/bin/
user_bin = Path.home() / ".veilframe" / "bin"
if user_bin.exists():
    for exe_f in user_bin.glob('*.exe'):
        if exe_f.name.lower() not in added_names and exe_f.is_file() and exe_f.stat().st_size > 1024:
            binaries.append((str(exe_f), 'resources/ffmpeg'))
            added_names.add(exe_f.name.lower())

# 3. System PATH (e.g. Chocolatey / WinGet / local installation on builder)
for tool in ['ffmpeg', 'ffprobe']:
    tool_exe = f"{tool}.exe"
    if tool_exe not in added_names:
        which_p = shutil.which(tool)
        if which_p:
            p = Path(which_p)
            if p.is_file() and p.stat().st_size > 1024:
                binaries.append((str(p), 'resources/ffmpeg'))
                added_names.add(tool_exe)

a = Analysis(
    ['run.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=[
        'cryptography',
        'cv2',
        'PIL',
        'PIL.Image',
        'numpy',
        'PySide6',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

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
    upx=False,  # UPX disabled: causes AV false positives and fails on CI without UPX installed
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
