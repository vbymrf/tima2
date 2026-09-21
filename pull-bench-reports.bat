@echo off
rem Zabrat otchety stenda s telefonov. Vsya logika v .ps1 ryadom.
rem Adres chuzhogo servera adb: -AdbServer host:port ili TIMA_ADB_SERVER.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pull-bench-reports.ps1" %*
