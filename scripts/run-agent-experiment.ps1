param(
    [ValidateSet('Offline', 'Live')]
    [string] $Mode = 'Offline',
    [string] $MavenRepository,
    [string] $JavaDirectory = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaDirectory) { throw 'Configure JAVA_HOME or pass -JavaDirectory for the installed JDK' }
$taskJava = Join-Path $JavaDirectory 'bin\java.exe'
$taskJavac = Join-Path $JavaDirectory 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $taskJava) -or -not (Test-Path -LiteralPath $taskJavac)) {
    throw 'Configured JDK java/javac executable missing'
}
if (-not $MavenRepository) { $MavenRepository = Join-Path (Split-Path -Parent $taskProjectRoot) '.m2repo' }
$taskGson = Join-Path $MavenRepository 'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
if (-not (Test-Path -LiteralPath $taskGson)) { throw 'Required existing Gson 2.10.1 dependency missing' }
$taskLabRoot = Join-Path $PSScriptRoot 'agent-lab'
$taskClasses = Join-Path $taskProjectRoot 'target\agent-experiment\classes'
$taskEvidence = Join-Path $taskProjectRoot '.local\agent-experiment'
$taskConfig = Join-Path $taskProjectRoot '.env.deepseek-local.properties'
if ($Mode -eq 'Live' -and -not (Test-Path -LiteralPath $taskConfig)) {
    throw 'Local DeepSeek configuration missing; follow docs/engineering/agent-experiment.md'
}
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources = @('ResearchAgentExperiment.java', 'DeepSeekExperimentClient.java', 'ExperimentChecks.java') |
    ForEach-Object { Join-Path $taskLabRoot $_ }
& $taskJavac '--release' '17' '-encoding' 'UTF-8' '-cp' $taskGson '-d' $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskClasspath = $taskClasses + ';' + $taskGson
if ($Mode -eq 'Live') {
    & $taskJava '-Dfile.encoding=UTF-8' '-cp' $taskClasspath 'ResearchAgentExperiment' '--live' $taskConfig $taskEvidence
} else {
    & $taskJava '-Dfile.encoding=UTF-8' '-cp' $taskClasspath 'ResearchAgentExperiment' '--offline' $taskEvidence
}
exit $LASTEXITCODE
