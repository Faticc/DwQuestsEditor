@echo off
chcp 65001 >nul
rem Запуск редактора. Первым аргументом можно передать файл вкладки.
setlocal
cd /d "%~dp0"

if exist DwQuestsEditor.jar (
  start "DwQuests Editor" javaw -Dfile.encoding=UTF-8 -jar DwQuestsEditor.jar %*
) else (
  if not exist out\ru\dw\qeditor\ui\MainFrame.class call build.bat
  start "DwQuests Editor" javaw -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.ui.MainFrame %*
)
endlocal
