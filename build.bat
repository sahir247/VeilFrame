@echo off
setlocal
echo Installing requirements...
python -m pip install -r requirements.txt
python -m pip install pyinstaller

echo Building standalone Windows executable for VeilFrame...
pyinstaller --noconfirm --clean --onefile --windowed ^
    --add-data "veilframe\presets\profiles.json;veilframe\presets" ^
    --name VeilFrame run.py

echo.
echo ========================================================
echo Build complete! Executable generated at: dist\VeilFrame.exe
echo ========================================================
pause
