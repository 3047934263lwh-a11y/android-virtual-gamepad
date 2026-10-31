@echo off
chcp 65001 >nul
set PYTHONUTF8=1
title Xbox Gamepad Test - 2D Platformer
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 goto trypy
python -u xbox_platformer.py %*
goto done

:trypy
py -3 -u xbox_platformer.py %*

:done
echo.
echo Game exited. Press any key to close.
pause >nul
