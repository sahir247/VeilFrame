import os
from pathlib import Path

# Collect data files
datas = [
    ('veilframe/presets/profiles.json', 'veilframe/presets'),
    ('veilframe/resources', 'veilframe/resources'),
]

# Collect ffmpeg binaries if present in project resources
binaries = []
for ffmpeg_dir in [Path('resources/ffmpeg'), Path('veilframe/resources/ffmpeg')]:
    if ffmpeg_dir.exists():
        for exe_f in ffmpeg_dir.glob('*.exe'):
            binaries.append((str(exe_f), 'resources/ffmpeg'))

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
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
