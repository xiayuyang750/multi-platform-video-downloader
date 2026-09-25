@echo off
cd /d "%~dp0"
title yt-dlp GUI - local service (close this window to stop)
".venv\Scripts\python.exe" "main.py"
if errorlevel 1 pause