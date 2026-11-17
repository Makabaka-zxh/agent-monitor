@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\pythonw.exe" (
  start "" ".venv\Scripts\pythonw.exe" "%~dp0desktop-widget.pyw" %*
) else (
  start "" pyw -3 "%~dp0desktop-widget.pyw" %*
)
endlocal
