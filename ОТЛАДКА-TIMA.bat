@echo off
title TIMA debug
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0debug-tima.ps1" %*
pause
