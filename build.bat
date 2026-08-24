@echo off
echo Installing requirements...
python -m pip install -r requirements.txt
python -m pip install pyinstaller

echo Building standalone Windows executable...
pyinstaller --noconfirm --clean --onefile --windowed ^
    --add-data "privacy_cleaner\presets\profiles.json;privacy_cleaner\presets" ^
    --add-data "privacy_cleaner\resources\ffmpeg;resources\ffmpeg" ^
    --name PrivacyCleaner run.py

echo.
echo ========================================================
echo Build complete! EXE generated at: dist\PrivacyCleaner.exe
echo ========================================================
pause
