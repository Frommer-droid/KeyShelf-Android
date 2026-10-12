@echo off
cd /d "%~dp0"
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path .local-state/build-env.ps1) { . ./.local-state/build-env.ps1 }; & ./tools/build-portable.ps1 -Install"
if errorlevel 1 pause
