@echo off
rem Console mode: dwq <command> <file> [options].  Example: dwq list Botania.json
rem Keep this file pure ASCII, see the note in build.bat.
setlocal
cd /d "%~dp0"
chcp 65001 >nul
if not exist out\ru\dw\qeditor\cli\QuestCli.class (
  call "%~dp0build.bat" >nul
  if errorlevel 1 exit /b 1
)
java -Dfile.encoding=UTF-8 -cp "out;lib/*" ru.dw.qeditor.cli.QuestCli %*
endlocal
