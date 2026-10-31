@echo off
rem Build and run the C# receiver. Requires .NET 8 SDK.
rem Usage: run.bat [port]
cd /d "%~dp0"
dotnet run -c Release -- %*
pause
