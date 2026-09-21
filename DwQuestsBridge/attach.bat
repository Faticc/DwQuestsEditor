@echo off
rem Inject the mod into an ALREADY RUNNING client (no restart).
rem Usage: attach.bat [path to client]
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "CLIENT=%~1"
if "%CLIENT%"=="" set "CLIENT=C:\Users\User\McSkill\clients\Industrial_1.7.10"

set "JDK=C:\Program Files\Java\jdk-25.0.2"
if not exist "%JDK%\bin\java.exe" if defined JAVA_HOME set "JDK=%JAVA_HOME%"
if not exist "%JDK%\bin\java.exe" (
    echo Need a JDK ^(not a JRE^) to attach. Set JAVA_HOME.
    exit /b 1
)

if not exist "%~dp0build\libs\dwqbridge-1.0.jar" (
    echo Mod is not built yet, running build.bat...
    call "%~dp0build.bat" || exit /b 1
)
if not exist "%~dp0attach\dwq-attach.jar" (
    echo Injector is not built yet, building...
    call "%~dp0attach\build.bat" || exit /b 1
)

"%JDK%\bin\java.exe" -Dstdout.encoding=UTF-8 -jar "%~dp0attach\dwq-attach.jar" ^
    --mod "%~dp0build\libs\dwqbridge-1.0.jar" --client "%CLIENT%" %2 %3 %4
endlocal
