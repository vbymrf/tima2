@echo off
rem Sborka ustanovshika TIMA dlya PK (MSI). Vsya logika v build-installer.ps1 ryadom.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-installer.ps1" %*
pause
