@echo off
chcp 65001 >nul
rem Консольная работа со вкладками: dwq <команда> <файл> [ключи]
rem Пример: dwq list Botania.json
setlocal
cd /d "%~dp0"
if not exist out\ru\dw\qeditor\cli\QuestCli.class call build.bat >nul
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli %*
endlocal
