@echo off
rem Zapusk prilozheniya TIMA dlya PK. Vsya logika v .ps1 ryadom.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-and-run-desktop.ps1" %*
