@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "MAVEN_CACHE=%PROJECT_ROOT%\..\.m2repo"

netstat -ano | findstr ":8082" | findstr "LISTENING" >nul
if errorlevel 1 (
    echo Starting Spring Boot backend on http://localhost:8082 ...
    start "Stock Platform DEV Backend :8082" /D "%PROJECT_ROOT%" cmd /k "mvn ""-Dmaven.repo.local=%MAVEN_CACHE%"" ""-Dspring-boot.run.profiles=dev"" spring-boot:run"
) else (
    echo Backend port 8082 is already running.
)

netstat -ano | findstr ":8083" | findstr "LISTENING" >nul
if errorlevel 1 (
    echo Starting Vue frontend on http://localhost:8083 ...
    start "Stock Platform DEV Frontend :8083" /D "%PROJECT_ROOT%\frontend" cmd /k "set ""VITE_API_TARGET=http://localhost:8082"" && npm.cmd run dev"
) else (
    echo Frontend port 8083 is already running.
)

echo.
echo Frontend: http://localhost:8083
echo Backend:  http://localhost:8082
echo Database: stock_system ^(development data^)
echo Use Ctrl+C in each service window when you want to stop it.
echo.
pause
