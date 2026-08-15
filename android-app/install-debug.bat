@echo off
cd /d "%~dp0"
echo ===============================================
echo Building and Installing Arc Emulator to phone...
echo ===============================================
echo.

REM Kill Java and aapt2 to release any file locks from previous failed builds
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM aapt2.exe >nul 2>&1
taskkill /F /IM kotlinc.exe >nul 2>&1

REM Stop Gradle Daemons explicitly
call gradlew --stop >nul 2>&1

REM Manually delete build folder to bypass locks
if exist "app\build" (
    echo Forcing removal of build directory...
    rd /s /q "app\build"
    if exist "app\build" (
        echo [WARNING] Could not fully delete app\build. Close VS Code if this fails.
    )
)

REM Build and install to phone using gradlew (faster!)
echo [1/3] Building and Installing to phone (R5CWB27N76D)...
echo Running: gradlew installDebug
powershell -Command ".\gradlew.bat installDebug"

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Build/Install failed!
    pause
    exit /b 1
)

echo Install complete!

REM Launch the app
echo.
echo [2/3] Launching Arc Emulator...
adb -s R5CWB27N76D shell am start -n com.blinkchase.arc-emulator/.MainActivity

echo.
echo [3/3] Done! Arc Emulator should now be open on your phone.
echo.
echo ===============================================
echo DEBUGGING - Arc Emulator Logs Only (Clean Format)
echo ===============================================
echo Showing ONLY Arc Emulator app logs...
echo Press Ctrl+C to stop
echo.

REM Get Arc Emulator PID and show clean logs using PowerShell
powershell -Command "$pkg='com.blinkchase.arc-emulator'; $dev='R5CWB27N76D'; Write-Host 'Waiting for app to start...'; for($i=0; $i -lt 40; $i++) { $p = (adb -s $dev shell pidof -s $pkg).Trim(); if($p) { Write-Host 'Logcat connected.'; adb -s $dev logcat --pid=$p; return } Start-Sleep -Milliseconds 500 } Write-Host 'App not found.'"
