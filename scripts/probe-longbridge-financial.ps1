param(
    [ValidateSet('Offline', 'Live', 'Diagnostic', 'Verify')]
    [string] $Mode = 'Offline',
    [string] $MavenRepository,
    [string] $JavaDirectory = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaDirectory) { throw 'Configure JAVA_HOME or pass -JavaDirectory for the installed JDK' }
$taskJavaExecutable = Join-Path $JavaDirectory 'bin\java.exe'
if (-not (Test-Path -LiteralPath $taskJavaExecutable)) { throw 'Configured JDK java executable missing' }
if (-not $MavenRepository) {
    $MavenRepository = Join-Path (Split-Path -Parent $taskProjectRoot) '.m2repo'
}
$taskArtifacts = @(
    'io\github\longbridge\openapi-sdk\4.5.0\openapi-sdk-4.5.0.jar',
    'org\scijava\native-lib-loader\2.4.0\native-lib-loader-2.4.0.jar',
    'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
)
$taskClasspathEntries = @()
foreach ($taskArtifact in $taskArtifacts) {
    $taskJar = Join-Path $MavenRepository $taskArtifact
    if (-not (Test-Path -LiteralPath $taskJar)) {
        throw "Required local dependency missing: $taskArtifact"
    }
    $taskClasspathEntries += $taskJar
}
$taskLoggingRoot = Join-Path $MavenRepository 'org\slf4j\slf4j-api'
$taskLoggingJar = Get-ChildItem -LiteralPath $taskLoggingRoot -Filter 'slf4j-api-*.jar' -Recurse |
    Sort-Object FullName | Select-Object -Last 1
if (-not $taskLoggingJar) { throw 'Required local slf4j-api dependency missing' }
$taskClasspathEntries += $taskLoggingJar.FullName
$taskSource = Join-Path $PSScriptRoot 'LongbridgeFinancialProbe.java'
$taskPreviousRustLog = $env:RUST_LOG
try {
    $env:RUST_LOG = 'off'
    if ($Mode -ne 'Offline') {
        $taskClientConfig = Join-Path $taskProjectRoot '.env.longbridge-local.properties'
        if (-not (Test-Path -LiteralPath $taskClientConfig)) { throw 'Local public-client configuration missing' }
        $taskProbeMode = switch ($Mode) {
            'Diagnostic' { '--diagnostic' }
            'Verify' { '--verify' }
            default { '--live' }
        }
        & $taskJavaExecutable '-Dfile.encoding=UTF-8' '--source' '17' '--class-path' ($taskClasspathEntries -join ';') $taskSource $taskProbeMode $taskClientConfig
    } else {
        & $taskJavaExecutable '-Dfile.encoding=UTF-8' '--source' '17' '--class-path' ($taskClasspathEntries -join ';') $taskSource '--offline'
    }
    $taskProbeExitCode = $LASTEXITCODE
} finally {
    $env:RUST_LOG = $taskPreviousRustLog
}
exit $taskProbeExitCode
