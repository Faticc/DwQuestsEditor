@echo off
rem ---------------------------------------------------------------------------
rem Build something you can just hand to a person.
rem
rem   package.bat        both variants
rem   package.bat jar    a single self-contained jar (needs Java 17+)
rem   package.bat exe    a folder with an exe and a bundled Java (needs nothing)
rem
rem Assets (item icons and mod resources) go inside: no folders next to the
rem program, no paths to configure. Everything lands in dist\.
rem
rem Keep this file pure ASCII, see the note in build.bat.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"
chcp 65001 >nul

set WHAT=%1
if "%WHAT%"=="" set WHAT=all

call "%~dp0build.bat"
if errorlevel 1 exit /b 1
if not exist dist mkdir dist

java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.Distribution || exit /b 1
if "%WHAT%"=="jar" goto done

java -Dfile.encoding=UTF-8 -cp "out;lib/*" ru.dw.qeditor.util.AppIcon dist\DwQuestsEditor.ico || exit /b 1

if exist dist\DwQuestsEditor rmdir /s /q dist\DwQuestsEditor
rem jdk.unsupported is required: LWJGL reaches for sun.misc.Unsafe and will not
rem start without it. java.desktop is needed for ImageIO and font rasterizing.
jpackage ^
  --type app-image ^
  --name DwQuestsEditor ^
  --app-version 2.0.0 ^
  --description "DwQuests quest tab editor" ^
  --vendor "DwQuests Editor" ^
  --icon dist\DwQuestsEditor.ico ^
  --input dist\jar ^
  --main-jar DwQuestsEditor.jar ^
  --main-class ru.dw.qeditor.Main ^
  --add-modules java.base,java.desktop,jdk.unsupported ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --dest dist
if errorlevel 1 (
  echo.
  echo jpackage failed. Usually that means PATH has a JRE instead of a JDK.
  exit /b 1
)

if exist dist\DwQuestsEditor-win64.zip del dist\DwQuestsEditor-win64.zip
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.Distribution zip dist\DwQuestsEditor dist\DwQuestsEditor-win64.zip || exit /b 1

:done
echo.
if exist dist\jar\DwQuestsEditor.jar echo   dist\jar\DwQuestsEditor.jar      single file, needs Java 17+
if exist dist\DwQuestsEditor-win64.zip echo   dist\DwQuestsEditor-win64.zip   unzip and run the exe, no Java needed
endlocal
