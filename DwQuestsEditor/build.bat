@echo off
rem Build DwQuests Editor. Needs JDK 17+ in PATH.
rem LWJGL jars are downloaded by the bundled Maven wrapper (mvnw).
rem
rem NOTE: keep this file pure ASCII. cmd.exe reads a .bat by byte offset and
rem loses its place after "chcp 65001" if the text after it is not ASCII,
rem which breaks parsing with errors like "< was unexpected at this time".
rem Anything the user should read in Russian is printed by the Java tools.
setlocal
cd /d "%~dp0"
chcp 65001 >nul

if not exist lib\*.jar (
  echo Downloading LWJGL dependencies...
  call "%~dp0mvnw.cmd" -B -q dependency:copy-dependencies
  if errorlevel 1 (
    echo.
    echo Failed to download dependencies. Check the network, or run: mvnw -B compile
    exit /b 1
  )
)

if exist out rmdir /s /q out
mkdir out

dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt
if errorlevel 1 (
  echo.
  echo Compilation failed.
  exit /b 1
)

echo Built into out\
endlocal
