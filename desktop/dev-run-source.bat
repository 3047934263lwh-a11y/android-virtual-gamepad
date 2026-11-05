@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title Virtual Gamepad - Source Development Only
REM 仅用于源码开发调试。正式使用请双击：
REM   ..\release\VirtualGamepadReceiver-Portable\VirtualGamepadReceiver.exe
set "PYTHONUTF8=1"
set "PYIOENCODING=utf-8"

cd /d "%~dp0" || (
    echo [ERROR] Cannot change to the desktop source folder.
    pause
    exit /b 1
)
if not exist "main.py" (
    echo [ERROR] main.py not found.
    pause
    exit /b 1
)

REM ---- 1. Pick an interpreter that provides PySide6 -------------------
set "PYW="
for %%P in ("pythonw.exe" "pyw.exe") do (
    if not defined PYW (
        where %%~P >nul 2>nul && (
            python -c "import PySide6" >nul 2>nul && set "PYW=%%~P"
        )
    )
)
if not defined PYW (
    set "CAND=C:\Users\30479\.workbuddy\binaries\python\envs\default\Scripts\pythonw.exe"
    if exist "!CAND!" (
        "!CAND!" -c "import PySide6" >nul 2>nul && set "PYW=!CAND!"
    )
)
if not defined PYW (
    echo [ERROR] PySide6 is required for the desktop receiver.
    echo         Run:  python -m pip install PySide6
    echo         Then launch this file again.
    pause
    exit /b 1
)

REM ---- 2. Driver notice (non-blocking: the GUI handles missing drivers) --
sc query ViGEmBus >nul 2>nul
if errorlevel 1 (
    echo [NOTICE] ViGEmBus driver not detected.
    echo          The receiver still starts in listen-only mode; install ViGEmBus 1.22.0
    echo          and reboot to enable virtual gamepad output.
)

REM ---- 3. Launch the native window, no console attached ----------------
start "" "!PYW!" "main.py"
exit /b 0
