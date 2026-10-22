@echo off
setlocal
set "PSModulePath="
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Project.ps1" -OpenBrowser
if errorlevel 1 (
  echo Startup failed. Read the message above and check .local\logs.
  pause
  exit /b 1
)
