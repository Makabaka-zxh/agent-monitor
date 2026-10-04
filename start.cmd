@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  python -m venv .venv
  if errorlevel 1 goto error
)
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto error
echo Open http://127.0.0.1:8766 in your browser.
.venv\Scripts\python.exe -m agent_monitor
goto end
:error
echo Setup failed. Please check Python 3.12+ and network access.
pause
:end
endlocal
