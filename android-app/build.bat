@echo off
echo ==========================================
echo      Arc Emulator - Build ^& Run
echo ==========================================

:: Kill Java and aapt2 to release any file locks from previous failed builds
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM aapt2.exe >nul 2>&1
taskkill /F /IM kotlinc.exe >nul 2>&1

:: Stop Gradle Daemons explicitly
call gradlew --stop >nul 2>&1

:: Manually delete build folder to bypass locks
if exist "app\build" (
    echo Forcing removal of build directory...
    rd /s /q "app\build"
    if exist "app\build" (
        echo [WARNING] Could not fully delete app\build. Close VS Code if this fails.
    )
)

:: Check if Pixel 4 App Emulator is connected
echo Checking for Pixel 4 App Emulator...
adb devices | findstr /I /C:"Pixel 4 App Emulator" >nul
if %ERRORLEVEL% NEQ 0 (
    echo Pixel 4 App Emulator not found. Starting it...
    set "EMULATOR_EXE=emulator"
    if exist "%ANDROID_HOME%\emulator\emulator.exe" set "EMULATOR_EXE=%ANDROID_HOME%\emulator\emulator.exe"
    if exist "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" set "EMULATOR_EXE=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"

    start "" "%EMULATOR_EXE%" -avd "Pixel 4 App Emulator"

    echo Waiting for Pixel 4 App Emulator to boot...
    adb wait-for-device
) else (
    echo Pixel 4 App Emulator detected!
    adb wait-for-device
)

echo.
echo [1/3] Building and Installing to Pixel 4 App Emulator...
echo Running: gradlew installDebug
call gradlew installDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build Failed!
    pause
    exit /b %ERRORLEVEL%
)
echo Install complete!

echo.
echo [2/3] Launching App...
adb shell am start -n com.blinkchase.arc-emulator/.MainActivity

echo.
echo [3/3] Done! Arc Emulator should now be open on the emulator.
echo.
echo ===============================================
echo DEBUGGING - Arc Emulator Logs
echo ===============================================
echo Showing ALL Arc Emulator app logs...
echo Press Ctrl+C to stop
echo.
adb logcat --format=color ArcEmulator:D *:S
