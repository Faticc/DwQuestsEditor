@echo off
rem ---------------------------------------------------------------------------
rem Wrap dist\DwQuestsEditor (the jpackage app-image from package.bat exe)
rem into a single installer: dist\DwQuestsEditor-Setup.exe
rem
rem Uses csc from .NET Framework 4 that ships with Windows: no Inno Setup,
rem no WiX. Installs per user into %LOCALAPPDATA%\Programs, no admin rights.
rem Does not rebuild the editor: run package.bat exe first if needed.
rem
rem Keep this file pure ASCII, see the note in build.bat.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0.."

set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
set WORK=dist\installer-build
set IMAGE=dist\DwQuestsEditor

if not exist "%IMAGE%\DwQuestsEditor.exe" (
  echo No %IMAGE%\DwQuestsEditor.exe - run package.bat exe first.
  exit /b 1
)

if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%\image"

rem The personal settings file (with our own absolute paths) must not ship:
rem on a fresh machine the editor finds its bundled assets by itself.
robocopy "%IMAGE%" "%WORK%\image" /e /xf dwquests-editor.properties /njh /njs /nfl /ndl >nul
if errorlevel 8 exit /b 1

powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::CreateFromDirectory('%WORK%\image', '%WORK%\payload.zip', 'Optimal', $false)"
if errorlevel 1 exit /b 1

"%CSC%" /nologo /codepage:65001 /target:winexe /optimize+ ^
  /win32icon:dist\DwQuestsEditor.ico /win32manifest:installer\app.manifest ^
  /r:System.Windows.Forms.dll /r:System.Drawing.dll ^
  /out:"%WORK%\Uninstall.exe" installer\Uninstall.cs
if errorlevel 1 exit /b 1

"%CSC%" /nologo /codepage:65001 /target:winexe /optimize+ ^
  /win32icon:dist\DwQuestsEditor.ico /win32manifest:installer\app.manifest ^
  /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:Microsoft.CSharp.dll ^
  /r:"%FW%\System.IO.Compression.dll" ^
  /resource:"%WORK%\payload.zip",payload.zip ^
  /resource:"%WORK%\Uninstall.exe",Uninstall.exe ^
  /out:dist\DwQuestsEditor-Setup.exe installer\Setup.cs
if errorlevel 1 exit /b 1

rmdir /s /q "%WORK%"
echo.
echo   dist\DwQuestsEditor-Setup.exe   installer, nothing else needed
endlocal
