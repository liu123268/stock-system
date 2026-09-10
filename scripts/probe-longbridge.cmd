@echo off
setlocal

rem Apply the policy only to this child process; do not change saved user or machine settings.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0probe-longbridge.ps1"
exit /b %errorlevel%
