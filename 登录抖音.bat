@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Douyin login helper
".venv\Scripts\python.exe" -m douyin_fallback
pause
