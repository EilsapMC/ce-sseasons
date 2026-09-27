@echo off
setlocal
pushd "%~dp0" || exit /b 1
where py >nul 2>nul
if errorlevel 1 goto use_python
py -3 -X utf8 "%~dp0build.py" %*
goto finished

:use_python
where python >nul 2>nul
if errorlevel 1 (
    echo ERROR: Python 3.10 or newer is required. Install Python and add it to PATH.
    popd
    exit /b 1
)
python -X utf8 "%~dp0build.py" %*

:finished
set "BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %BUILD_EXIT%
