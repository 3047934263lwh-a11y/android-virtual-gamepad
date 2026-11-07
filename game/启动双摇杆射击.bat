@echo off
chcp 65001 >nul
set PYTHONUTF8=1
title Twin-Stick Arena - Dual Stick Test
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 goto trypy
python -u twin_stick.py %*
goto done

:trypy
py -3 -u twin_stick.py %*

:done
echo.
echo Game exited. Press any key to close.
pause >nul
