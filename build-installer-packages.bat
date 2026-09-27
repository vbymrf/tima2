@echo off
rem Ustanovshik TIMA dlya PK (MSI) - v doc_add\packages, s novym nomerom sborki.
rem Vsya logika v build-installer.ps1 (klyuch -ToPackages): ostanovit' PK-klient iz ishodnikov,
rem podnyat' nomer, sobrat', polozhit' TIMA-<nomer>.msi v doc_add\packages.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-installer.ps1" -ToPackages %*
pause
