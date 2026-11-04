@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
set PYTHONUTF8=1
title Virtual Xbox 360 Gamepad - One-Click PC Receiver

REM ============================================================
REM  One-click launcher: dependency check -> stale-instance
REM  cleanup -> port check -> start the UDP receiver.
REM  Keep this .bat inside the virtual-gamepad folder.
REM ============================================================

REM ---- 0. Locate server.py (same folder as this .bat) ----
set "PROJ="
if exist "%~dp0server-python\server.py" set "PROJ=%~dp0"
if not defined PROJ (
    echo [ERROR] Cannot find server-python\server.py next to this .bat.
    echo         Keep this .bat inside the virtual-gamepad project folder.
    goto fail
)
cd /d "%PROJ%"
echo ==============================================
echo    Virtual Xbox 360 Gamepad - PC Receiver
echo ==============================================
echo [OK] server: %PROJ%server-python\server.py

REM ---- 1. Python check: pick an interpreter that can import vgamepad ----
set "PY="
where python >nul 2>nul && python -c "import vgamepad" >nul 2>nul && set "PY=python"
if not defined PY (
    where py >nul 2>nul && py -3 -c "import vgamepad" >nul 2>nul && set "PY=py -3"
)
REM No library anywhere: try a one-time install for the PATH python
if not defined PY (
    where python >nul 2>nul && (
        echo [..] Installing the vgamepad library for system Python (needs internet)...
        python -m pip install --disable-pip-version-check -q vgamepad >nul 2>nul
        python -c "import vgamepad" >nul 2>nul && set "PY=python"
    )
)
if not defined PY (
    echo [ERROR] No usable Python found (needs 3.8+ with the vgamepad library).
    echo         Fix (either):
    echo         a) Run: python -m pip install vgamepad
    echo         b) Reinstall Python with "Add Python to PATH" checked
    goto fail
)
echo [OK] Python ready: !PY!

REM ---- 2. ViGEmBus driver check ----
sc query ViGEmBus >nul 2>nul
if errorlevel 1 (
    echo [ERROR] ViGEmBus driver not found - it is the core system dependency.
    echo         Install from: https://github.com/nefarius/ViGEmBus/releases
    echo         REBOOT once after installing, then re-run this script.
    goto fail
)
set VST=unknown
for /f "tokens=4" %%s in ('sc query ViGEmBus ^| findstr STATE') do set VST=%%s
echo [OK] ViGEmBus driver installed (state: !VST!)

REM ---- 3. Kill leftover receiver instances (prevents port conflicts) ----
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name like 'python%'\" | Where-Object { $_.CommandLine -like '*server-python*server.py*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('[OK] Killed stale instance PID=' + $_.ProcessId) }" 2>nul
timeout /t 1 /nobreak >nul

netstat -ano 2>nul | findstr "26760" >nul 2>nul
if not errorlevel 1 (
    echo [ERROR] Port 26760 is still occupied by another program.
    echo         Close that program in Task Manager, then retry.
    goto fail
)
echo [OK] Port 26760 available

REM ---- 4. Run receiver (this window is the live status monitor) ----
echo.
echo [RUN] Receiver running. How to use:
echo    1) Connect the phone and the PC to the same Wi-Fi
echo    2) Open the phone app - it auto-connects (no button needed)
echo    3) When the console shows a connection line and 125 pps, start your game
echo    4) Closing this window removes the virtual gamepad
echo.
"%PY%" -u "%PROJ%server-python\server.py" 26760

echo.
echo [STOP] Receiver exited (virtual gamepad removed).
goto end

:fail
echo.
echo [FAIL] Startup incomplete - follow the hints above and re-run.

:end
echo.
pause
