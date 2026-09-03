@echo off
rem Sborka APK i ustanovka na telefony. Vsya logika v .ps1 ryadom.
rem Adres chuzhogo servera adb: -AdbServer host:port ili TIMA_ADB_SERVER.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-and-install-phone.ps1" %*
