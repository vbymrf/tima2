@echo off
title TIMA
set "EXE=%~dp0client\composeApp\build\compose\binaries\main\app\TIMA\TIMA.exe"
if exist "%EXE%" (
    start "" "%EXE%"
) else (
    echo TIMA.exe not found. Build it once:
    echo   cd C:\!tima2\client
    echo   gradle :composeApp:createDistributable
    pause
)
