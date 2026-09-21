@echo off
rem Copy the built mod into the client's mods folder.
rem Usage: install.bat [path to client]
setlocal
cd /d "%~dp0"

set "CLIENT=%~1"
if "%CLIENT%"=="" set "CLIENT=C:\Users\User\McSkill\clients\Industrial_1.7.10"

set "JAR=%~dp0build\libs\dwqbridge-1.0.jar"
if not exist "%JAR%" (
    echo Not built yet: %JAR%
    echo Run build.bat first.
    exit /b 1
)
if not exist "%CLIENT%\mods" (
    echo No mods folder in: %CLIENT%
    exit /b 1
)

copy /y "%JAR%" "%CLIENT%\mods\dwqbridge-1.0.jar" >nul
if errorlevel 1 (
    echo Copy failed. Is the game running?
    exit /b 1
)
echo Installed: %CLIENT%\mods\dwqbridge-1.0.jar
echo Start the game, then /dwqb help
endlocal
