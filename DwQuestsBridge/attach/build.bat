@echo off
rem Build the injector: attach\dwq-attach.jar
setlocal
cd /d "%~dp0"

set "JDK=C:\Program Files\Java\jdk-25.0.2"
if not exist "%JDK%\bin\javac.exe" if defined JAVA_HOME set "JDK=%JAVA_HOME%"
if not exist "%JDK%\bin\javac.exe" (
    echo Need a JDK ^(not a JRE^): com.sun.tools.attach lives in jdk.attach.
    echo Set JAVA_HOME or fix the path inside this file.
    exit /b 1
)

if exist out rmdir /s /q out
mkdir out

rem The agent half runs INSIDE the game, which may be Java 8 -- build it for 8.
"%JDK%\bin\javac.exe" -encoding UTF-8 --release 8 -nowarn -d out src\ru\dw\qbridge\attach\DwqAgent.java
if errorlevel 1 exit /b 1

rem The launcher half runs on our JDK and needs the Attach API (jdk.attach).
"%JDK%\bin\javac.exe" -encoding UTF-8 --release 17 -d out src\ru\dw\qbridge\attach\Attacher.java
if errorlevel 1 exit /b 1

> out\manifest.txt echo Main-Class: ru.dw.qbridge.attach.Attacher
>> out\manifest.txt echo Agent-Class: ru.dw.qbridge.attach.DwqAgent
>> out\manifest.txt echo Can-Retransform-Classes: false

"%JDK%\bin\jar.exe" cfm dwq-attach.jar out\manifest.txt -C out ru
if errorlevel 1 exit /b 1

echo Done: %~dp0dwq-attach.jar
endlocal
