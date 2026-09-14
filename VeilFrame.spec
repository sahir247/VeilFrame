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
MIN_REAL_BIN_SIZE = 5 * 1024 * 1024  # 5 MB minimum to ignore 20KB Chocolatey/WinGet shims

def add_tool_binary(src_path: Path, tool_name: str):
    p_str = str(src_path)
    binaries.append((p_str, 'resources/ffmpeg'))
    datas.append((p_str, 'resources/ffmpeg'))
    datas.append((p_str, 'veilframe/resources/ffmpeg'))
    added_names.add(tool_name.lower())
    print(f"[VeilFrame.spec] Bundled {tool_name} from {p_str} (Size: {src_path.stat().st_size:,} bytes)")

# 1. Project resource directories
for ffmpeg_dir in [Path('resources/ffmpeg'), Path('veilframe/resources/ffmpeg'), Path('privacy_cleaner/resources/ffmpeg')]:
    if ffmpeg_dir.exists():
        for exe_f in ffmpeg_dir.glob('*.exe'):
            if exe_f.name.lower() not in added_names and exe_f.is_file() and exe_f.stat().st_size > MIN_REAL_BIN_SIZE:
                add_tool_binary(exe_f, exe_f.name)

# 2. User binary directory ~/.veilframe/bin/
user_bin = Path.home() / ".veilframe" / "bin"
if user_bin.exists():
    for exe_f in user_bin.glob('*.exe'):
        if exe_f.name.lower() not in added_names and exe_f.is_file() and exe_f.stat().st_size > MIN_REAL_BIN_SIZE:
            add_tool_binary(exe_f, exe_f.name)

# 3. System PATH / Chocolatey library locations
for tool in ['ffmpeg', 'ffprobe']:
    tool_exe = f"{tool}.exe"
    if tool_exe not in added_names:
        which_p = shutil.which(tool)
        if which_p:
            p = Path(which_p)
            if p.is_file() and p.stat().st_size > MIN_REAL_BIN_SIZE:
                add_tool_binary(p, tool_exe)
            else:
                # If which_p was a shim (< 5MB), check common Chocolatey lib paths
                choco_candidates = list(Path("C:/ProgramData/chocolatey/lib/ffmpeg").rglob(tool_exe))
                for choco_p in choco_candidates:
                    if choco_p.is_file() and choco_p.stat().st_size > MIN_REAL_BIN_SIZE:
                        add_tool_binary(choco_p, tool_exe)
                        break

print(f"[VeilFrame.spec] Bundled tools: {sorted(list(added_names))}")
if 'ffmpeg.exe' not in added_names or 'ffprobe.exe' not in added_names:
    print(f"[VeilFrame.spec] WARNING: ffmpeg.exe or ffprobe.exe was NOT bundled in the executable!")

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
