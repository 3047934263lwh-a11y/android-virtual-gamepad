@echo off
chcp 65001 >nul
set PYTHONUTF8=1
title Virtual Xbox 360 Gamepad - PC Receiver
cd /d "%~dp0server-python"

where python >nul 2>nul
if errorlevel 1 goto trypy

python -u server.py %1
goto done

:trypy
py -3 -u server.py %1

:done
echo.
echo Server stopped. Press any key to close.
pause >nul
