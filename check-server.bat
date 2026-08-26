@echo off
rem Proverka servernyh testov Go so sluzhbami. Vsya logika v .ps1 ryadom.
rem Klyuchi peredayutsya naskvoz:  check-server.bat -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-server.ps1" %*
pause
