@echo off
setlocal
rem Execution policy applies only to this child process, not saved user or machine policy.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0authorize-longbridge.ps1"
exit /b %errorlevel%
