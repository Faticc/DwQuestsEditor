@echo off
rem Build the client mod. Output: build\libs\dwqbridge-1.0.jar
cd /d "%~dp0"
call "%~dp0gradlew.bat" --offline build %*
if errorlevel 1 (
    echo.
    echo Build failed. If it complains about downloads, drop --offline:
    echo     gradlew.bat build
    exit /b 1
)
echo.
echo Done: %~dp0build\libs\dwqbridge-1.0.jar
