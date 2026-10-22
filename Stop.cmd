@echo off
setlocal
set "PSModulePath="
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-Project.ps1" -IncludeDependencies
if errorlevel 1 (
  echo Stop failed. No unverified process will be stopped.
  pause
  exit /b 1
)
