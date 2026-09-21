@echo off
rem Run the editor. Optional first argument: a quest tab .json file.
rem Needs a GPU with OpenGL 3.3.
rem Keep this file pure ASCII, see the note in build.bat.
setlocal
cd /d "%~dp0"
chcp 65001 >nul

if exist dist\jar\DwQuestsEditor.jar (
  start "DwQuests Editor" javaw -Dfile.encoding=UTF-8 -jar dist\jar\DwQuestsEditor.jar %*
  goto end
)
if not exist out\ru\dw\qeditor\Main.class (
  call "%~dp0build.bat"
  if errorlevel 1 exit /b 1
)
start "DwQuests Editor" javaw -Dfile.encoding=UTF-8 -cp "out;lib/*" ru.dw.qeditor.Main %*

:end
endlocal
