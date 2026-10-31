@echo off
chcp 65001 >nul
set PYTHONUTF8=1
title Xbox Right-Stick Camera Test
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 goto trypy
python -u look_test.py %*
goto done

:trypy
py -3 -u look_test.py %*

:done
echo.
echo Test exited. Press any key to close.
pause >nul
