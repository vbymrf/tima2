@echo off
title TIMA - obnovlenie APK
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-app.ps1" %*
pause
