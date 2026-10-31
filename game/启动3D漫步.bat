@echo off
chcp 65001 >nul
set PYTHONUTF8=1
title 3D Free View Walk Test
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 goto trypy
python -u fps_walk.py %*
goto done

:trypy
py -3 -u fps_walk.py %*

:done
echo.
echo Test exited. Press any key to close.
pause >nul
