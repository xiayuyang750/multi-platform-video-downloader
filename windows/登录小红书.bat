@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Xiaohongshu login helper
".venv\Scripts\python.exe" -m web_fallback
pause
