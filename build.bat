@echo off
setlocal
echo Building standalone Windows executable for VeilFrame using VeilFrame.spec...
where uv >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo Using uv environment...
    uv run pyinstaller --noconfirm --clean VeilFrame.spec
) else (
    echo Using python environment...
    python -m pip install -r requirements.txt
    python -m pip install pyinstaller
    python -m pyinstaller --noconfirm --clean VeilFrame.spec
)

echo.
echo ========================================================
echo Build complete! Executable generated at: dist\VeilFrame.exe
echo ========================================================
pause
