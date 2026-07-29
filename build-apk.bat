@echo off
setlocal enabledelayedexpansion
rem Build the TTSing debug APK and drop it right here in the project folder,
rem ready to copy onto a phone by hand.
cd /d "%~dp0"

echo Building TTSing debug APK...
call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED.
    exit /b 1
)

set "SRC=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%SRC%" (
    echo.
    echo Build succeeded but the APK was not found at %SRC%
    exit /b 1
)

set "DEST=TTSing.apk"
copy /y "%SRC%" "%DEST%" >nul

for %%A in ("%DEST%") do set "SIZE=%%~zA"
set /a SIZEMB=!SIZE! / 1048576

echo.
echo BUILD OK
echo APK: %CD%\%DEST%  (!SIZEMB! MB^)
endlocal
