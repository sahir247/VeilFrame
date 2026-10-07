@echo off
setlocal
set "DIR=%~dp0"
"%DIR%android\gradlew.bat" -p "%DIR%android" %*
