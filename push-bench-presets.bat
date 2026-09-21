@echo off
rem Polozhit nabory progonov na telefony. Vsya logika v .ps1 ryadom.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-bench-presets.ps1" %*
