@echo off
setlocal
set "MCP_PROJECT_ROOT=%~dp0.."
set "MCP_APP_JAR=%MCP_PROJECT_ROOT%\target\stock-system-0.0.1-SNAPSHOT.jar"
if not exist "%MCP_APP_JAR%" (
    >&2 echo MCP_START_FAILED: build the application JAR first.
    exit /b 1
)
if defined STOCK_MCP_JAVA (
    "%STOCK_MCP_JAVA%" -Dfile.encoding=UTF-8 -Dloader.main=com.stock.system.agent.mcp.ResearchMcpCommand -cp "%MCP_APP_JAR%" org.springframework.boot.loader.launch.PropertiesLauncher
) else (
    java.exe -Dfile.encoding=UTF-8 -Dloader.main=com.stock.system.agent.mcp.ResearchMcpCommand -cp "%MCP_APP_JAR%" org.springframework.boot.loader.launch.PropertiesLauncher
)
exit /b %errorlevel%
