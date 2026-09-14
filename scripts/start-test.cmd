@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "MAVEN_CACHE=%PROJECT_ROOT%\..\.m2repo"

if not defined DB_PASSWORD (
    echo DB_PASSWORD is not set. Set the test database password before starting.
    exit /b 1
)
if not defined JWT_SECRET (
    echo JWT_SECRET is not set. Set a test secret with at least 32 UTF-8 bytes before starting.
    exit /b 1
)

netstat -ano | findstr ":18082" | findstr "LISTENING" >nul
if errorlevel 1 (
    echo Starting isolated TEST backend on http://localhost:18082 ...
    start "Stock Platform TEST Backend :18082" /D "%PROJECT_ROOT%" cmd /k "mvn ""-Dmaven.repo.local=%MAVEN_CACHE%"" ""-Dspring-boot.run.profiles=test"" ""-Dspring-boot.run.arguments=--server.port=18082"" spring-boot:run"
) else (
    echo Test backend port 18082 is already running. Verify its TEST banner before testing.
)

netstat -ano | findstr ":18083" | findstr "LISTENING" >nul
if errorlevel 1 (
    echo Starting isolated TEST frontend on http://localhost:18083 ...
    start "Stock Platform TEST Frontend :18083" /D "%PROJECT_ROOT%\frontend" cmd /k "set ""VITE_DEV_PORT=18083"" && set ""VITE_API_TARGET=http://localhost:18082"" && npm.cmd run dev"
) else (
    echo Test frontend port 18083 is already running.
)

echo.
echo TEST Frontend: http://localhost:18083
echo TEST Backend:  http://localhost:18082
echo TEST Database: stock_system_test
echo Run frontend\npm.cmd run test:e2e only against these isolated addresses.
echo Use Ctrl+C in each service window when you want to stop it.
echo.
pause
