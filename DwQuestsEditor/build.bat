@echo off
chcp 65001 >nul
rem Сборка DwQuests Editor. Нужен только JDK 17+ в PATH.
setlocal
cd /d "%~dp0"

if exist out rmdir /s /q out
mkdir out

dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
if errorlevel 1 (
  echo.
  echo Сборка не удалась.
  exit /b 1
)

jar --create --file DwQuestsEditor.jar --main-class ru.dw.qeditor.ui.MainFrame -C out .
if errorlevel 1 (
  echo.
  echo Не удалось собрать jar.
  exit /b 1
)

echo.
echo Готово: DwQuestsEditor.jar
endlocal
