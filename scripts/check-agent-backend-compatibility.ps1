param(
    [ValidateSet('Baseline', 'Adapted', 'Verify')]
    [string] $Mode = 'Baseline',
    [string] $MavenRepository
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $MavenRepository) {
    $MavenRepository = Join-Path (Split-Path -Parent $taskProjectRoot) '.m2repo'
}
# Use a fresh directory; never copy local secrets or overwrite another run.
$taskRunId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$taskCopyRoot = Join-Path $taskProjectRoot "target\agent-compatibility\backend-$taskRunId"
New-Item -ItemType Directory -Path (Join-Path $taskCopyRoot 'src') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskProjectRoot 'src\main') -Destination (Join-Path $taskCopyRoot 'src') -Recurse
if ($Mode -eq 'Verify') {
    Copy-Item -LiteralPath (Join-Path $taskProjectRoot 'src\test') -Destination (Join-Path $taskCopyRoot 'src') -Recurse
}

[xml] $taskPom = Get-Content -LiteralPath (Join-Path $taskProjectRoot 'pom.xml') -Raw
$taskNamespace = [System.Xml.XmlNamespaceManager]::new($taskPom.NameTable)
$taskNamespace.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
$taskPom.SelectSingleNode('/m:project/m:parent/m:version', $taskNamespace).InnerText = '4.1.1'
$taskMapper = $taskPom.SelectSingleNode('/m:project/m:dependencies/m:dependency[m:artifactId="mybatis-plus-spring-boot3-starter" or m:artifactId="mybatis-plus-spring-boot4-starter"]', $taskNamespace)
if (-not $taskMapper) { throw 'Expected MyBatis-Plus starter is missing' }
$taskMapper.artifactId = 'mybatis-plus-spring-boot4-starter'
$taskMapper.version = '3.5.17'
$taskPom.SelectSingleNode('/m:project/m:dependencies/m:dependency[m:artifactId="spring-boot-starter-web" or m:artifactId="spring-boot-starter-webmvc"]/m:artifactId', $taskNamespace).InnerText = 'spring-boot-starter-webmvc'

function Add-TaskBootDependency([string] $artifactId, [string] $scope = '') {
    $taskExistingDependency = $taskPom.SelectSingleNode("/m:project/m:dependencies/m:dependency[m:artifactId='$artifactId']", $taskNamespace)
    if ($taskExistingDependency) { return }
    $taskDependency = $taskPom.CreateElement('dependency', $taskPom.DocumentElement.NamespaceURI)
    foreach ($taskEntry in @{ groupId = 'org.springframework.boot'; artifactId = $artifactId }.GetEnumerator()) {
        $taskChild = $taskPom.CreateElement($taskEntry.Key, $taskPom.DocumentElement.NamespaceURI)
        $taskChild.InnerText = $taskEntry.Value
        [void] $taskDependency.AppendChild($taskChild)
    }
    [void] $taskPom.SelectSingleNode('/m:project/m:dependencies', $taskNamespace).AppendChild($taskDependency)
    if ($scope) {
        $taskScope = $taskPom.CreateElement('scope', $taskPom.DocumentElement.NamespaceURI)
        $taskScope.InnerText = $scope
        [void] $taskDependency.AppendChild($taskScope)
    }
}
Add-TaskBootDependency 'spring-boot-starter-flyway'

if ($Mode -in @('Adapted', 'Verify')) {
    Add-TaskBootDependency 'spring-boot-starter-jackson'
    $taskMoves = [ordered]@{
        'org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration' = 'org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration'
        'org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy' = 'org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy'
        'com.fasterxml.jackson.databind.' = 'tools.jackson.databind.'
        'com.fasterxml.jackson.core.JsonProcessingException' = 'tools.jackson.core.JacksonException'
        'JsonProcessingException' = 'JacksonException'
        'org.springframework.boot.test.autoconfigure.web.servlet.' = 'org.springframework.boot.webmvc.test.autoconfigure.'
        'org.springframework.boot.test.mock.mockito.MockBean' = 'org.springframework.test.context.bean.override.mockito.MockitoBean'
        'org.springframework.boot.test.mock.mockito.SpyBean' = 'org.springframework.test.context.bean.override.mockito.MockitoSpyBean'
        '@MockBean' = '@MockitoBean'
        '@SpyBean' = '@MockitoSpyBean'
        'new ObjectMapper().findAndRegisterModules()' = 'tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()'
    }
    foreach ($taskSource in (Get-ChildItem -LiteralPath (Join-Path $taskCopyRoot 'src') -Recurse -Filter '*.java')) {
        $taskContent = [System.IO.File]::ReadAllText($taskSource.FullName)
        foreach ($taskMove in $taskMoves.GetEnumerator()) {
            $taskContent = $taskContent.Replace($taskMove.Key, $taskMove.Value)
        }
        if ($taskSource.Name -eq 'StockResearchTransactionServiceTest.java') {
            $taskContent = $taskContent.Replace('when(researchCycleMapper).insert(any())', 'when(researchCycleMapper).insert(any(ResearchCycle.class))')
        }
        [System.IO.File]::WriteAllText($taskSource.FullName, $taskContent, [System.Text.UTF8Encoding]::new($false))
    }
}
if ($Mode -eq 'Verify') {
    Add-TaskBootDependency 'spring-boot-starter-webmvc-test' 'test'
    Add-TaskBootDependency 'spring-boot-starter-security-test' 'test'
}
$taskPom.Save((Join-Path $taskCopyRoot 'pom.xml'))
$taskGoal = if ($Mode -eq 'Verify') { 'test' } else { 'compile' }
Write-Output "Compatibility mode=$Mode copy=$taskCopyRoot goal=$taskGoal"
# Windows PowerShell treats redirected native stderr as ErrorRecords. Keep it
# in the log, and judge Maven by its process exit code instead of stderr text.
$taskPreviousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $taskMavenArgs = @("-Dmaven.repo.local=$MavenRepository", '-B', '-ntp', '-s', (Join-Path $PSScriptRoot 'agent-compatibility-maven-settings.xml'), '-f', (Join-Path $taskCopyRoot 'pom.xml'))
    if ($Mode -ne 'Verify') { $taskMavenArgs += '-Dmaven.test.skip=true' }
    & mvn.cmd @taskMavenArgs $taskGoal *> (Join-Path $taskCopyRoot 'compile.log')
    $taskCompileExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $taskPreviousPreference
}
Get-Content -LiteralPath (Join-Path $taskCopyRoot 'compile.log') -Tail 45
exit $taskCompileExit
