@echo off
title TIMA - APK
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-app.ps1" %*
pause
