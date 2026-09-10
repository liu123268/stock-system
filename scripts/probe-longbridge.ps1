$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$pomPath = Join-Path $projectRoot 'pom.xml'
$repositoryPath = Join-Path $workspaceRoot '.m2repo'

# Set only this child run's environment; never persist credentials or put them in -D arguments.
$probeEnvironment = @{
    STOCK_LONGBRIDGE_PROBE = 'true'
    RUST_LOG = 'off'
    RUST_BACKTRACE = $null
    LONGBRIDGE_LOG_PATH = $null
    LONGBRIDGE_ENV = $null
    JAVA_TOOL_OPTIONS = $null
    JDK_JAVA_OPTIONS = $null
    MAVEN_OPTS = $null
    MAVEN_ARGS = $null
}
$previousEnvironment = @{}
foreach ($name in $probeEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$probeExitCode = 1
try {
    foreach ($name in $probeEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $probeEnvironment[$name], 'Process')
    }
    Write-Host 'Running the isolated quote probe. Keep this terminal open while authorizing in your browser.'
    # Quote the Maven property as one argument. -f locates the project from any directory.
    # Java reads Windows proxy settings only when enabled at JVM startup.
    & mvn "-Dmaven.repo.local=$repositoryPath" -f $pomPath '-Dtest=LongbridgeQuoteProbeTest' '-DargLine=-Djava.net.useSystemProxies=true' test
    $probeExitCode = $LASTEXITCODE
}
finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $probeEnvironment.Clear()
}
if ($probeExitCode -ne 0) {
    throw 'Quote probe did not succeed. Read only the LONGBRIDGE_PROBE_FAILED stage/reason above; do not enable debug logging.'
}
