#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Fixture', 'Real')][string]$Mode = 'Fixture',
    [switch]$ApprovedFinancialData,
    [string]$ContinueFrom,
    [string[]]$CaseIds,
    [ValidateSet('Financial', 'Reports', 'Closure')][string]$Suite = 'Financial',
    [switch]$ApprovedReportData,
    [ValidateRange(0, 1000)][int]$ExpectedUnknownReservations = 0,
    [string]$FixtureFailAt,
    [switch]$ReviewEach
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nodePath = (Get-Command node.exe -ErrorAction Stop).Source
$probeArguments = @('e2e/research-agent-financial-probe.mjs')
$planArguments = @((Join-Path $projectRoot 'frontend/e2e/research-agent-financial-probe.mjs'), '--dry-run')
if ($Suite -eq 'Reports') { $probeArguments += '--suite=reports'; $planArguments += '--suite=reports' }
if ($Suite -eq 'Closure') { $probeArguments += '--suite=closure'; $planArguments += '--suite=closure' }
$unknownArgument = "--expected-unknown=$ExpectedUnknownReservations"
$probeArguments += $unknownArgument; $planArguments += $unknownArgument
if ($ReviewEach) { $probeArguments += '--review-each'; $planArguments += '--review-each' }
if ($PSBoundParameters.ContainsKey('FixtureFailAt')) {
    if ($Mode -ne 'Fixture') { throw 'Fixture failure injection is forbidden in Real mode' }
    $probeArguments += "--fixture-fail-at=$FixtureFailAt"; $planArguments += "--fixture-fail-at=$FixtureFailAt"
}
if ($PSBoundParameters.ContainsKey('CaseIds')) {
    $selection = '--cases=' + ($CaseIds -join ',')
    $probeArguments += $selection
    $planArguments += $selection
}
# 在读取 Key、启动服务或写测试数据之前核对本轮问题范围。
$null = & $nodePath @planArguments
if ($LASTEXITCODE -ne 0) { throw 'Invalid question selection; no service or model request was started' }
$javaPath = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else { 'D:\Program Files\Java\jdk-17\bin\java.exe' }
$mysqlPath = if ($env:MYSQL_CLIENT_PATH) { $env:MYSQL_CLIENT_PATH } else { 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe' }
foreach ($name in @('DB_PASSWORD', 'JWT_SECRET')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "$name is required" }
}
foreach ($path in @($javaPath, $mysqlPath, (Join-Path $projectRoot 'target/stock-system-0.0.1-SNAPSHOT.jar'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw 'Required Java, MySQL client or packaged application is missing' }
}
$ledgerPath = Join-Path $projectRoot '.local/agent-experiment/budget.jsonl'
if ($ContinueFrom) { $ContinueFrom = (Resolve-Path -LiteralPath $ContinueFrom -ErrorAction Stop).Path }
$modelKey = 'offline-financial-fixture-key'
if ($Mode -eq 'Real') {
    if ($Suite -eq 'Reports') {
        if (-not $ApprovedReportData) { throw 'Real mode requires the bounded report passages and question scope to have been explicitly authorized' }
    } elseif (-not $ApprovedFinancialData) { throw 'Real mode requires the financial data scope to have been explicitly authorized' }
    if (-not (Test-Path -LiteralPath $ledgerPath)) { throw 'The original experiment ledger is required; it must not be recreated' }
    $keyPath = Join-Path $projectRoot '.env.deepseek-local.properties'
    $keyLine = Get-Content -LiteralPath $keyPath | Where-Object { $_ -match '^deepseek\.api-key\s*=' } | Select-Object -First 1
    $modelKey = ($keyLine -replace '^deepseek\.api-key\s*=', '').Trim()
    if ($modelKey -notmatch '^sk-\S+$') { throw 'A valid local DeepSeek Key is required; it is never printed or passed on the command line' }
}
$testPorts = @(18082, 18083, 18084)
if ($Suite -eq 'Reports') { $testPorts += 16335 }
$occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $testPorts)
if ($occupied.Count) { throw 'A test port is occupied; no existing service will be stopped' }
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
$logRoot = Join-Path $projectRoot ".local/financial-quality-$stamp"
$outputRoot = Join-Path $projectRoot "target/agent-$($Suite.ToLowerInvariant())-$($Mode.ToLowerInvariant())-$stamp"
$reportRuntime = Join-Path $projectRoot ('target/rq-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $logRoot, $outputRoot | Out-Null
$owned = [System.Collections.Generic.List[object]]::new()
function Start-Owned([string]$role, [string]$file, [string[]]$arguments, [string]$workingDirectory, [hashtable]$variables) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $file; $info.WorkingDirectory = $workingDirectory
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true; $info.WindowStyle = 'Hidden'
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    foreach ($name in $variables.Keys) { $info.Environment[$name] = $variables[$name] }
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start $role" }
    $out = [System.IO.File]::Open((Join-Path $logRoot "$role.log"), 'Create', 'Write', 'Read')
    $err = [System.IO.File]::Open((Join-Path $logRoot "$role-error.log"), 'Create', 'Write', 'Read')
    $owned.Add([pscustomobject]@{ Role = $role; Pid = $process.Id; Started = $process.StartTime.ToUniversalTime();
        Process = $process; Out = $out; Err = $err;
        OutTask = $process.StandardOutput.BaseStream.CopyToAsync($out); ErrTask = $process.StandardError.BaseStream.CopyToAsync($err) })
    $owned | Select-Object Role, Pid, Started | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $logRoot 'processes.json')
    return $process
}
try {
    if ($Mode -eq 'Fixture') {
        $null = Start-Owned 'fixture' $nodePath @('e2e/agent-model-fixture.mjs') (Join-Path $projectRoot 'frontend') @{}
    }
    $arguments = @('-jar', 'target/stock-system-0.0.1-SNAPSHOT.jar', '--spring.profiles.active=test',
        '--server.port=18082', '--server.address=127.0.0.1',
        '--spring.datasource.url=jdbc:mysql://localhost:3306/stock_system_test?createDatabaseIfNotExist=false&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai',
        '--stock-system.market.mode=mock', '--spring.ai.model.chat=deepseek', '--stock-system.agent.budget-yuan=10',
        '--spring.ai.deepseek.chat.model=deepseek-flash', '--spring.ai.deepseek.chat.thinking.type=disabled', '--spring.ai.deepseek.chat.max-tokens=2000')
    if ($Mode -eq 'Real') {
        $arguments += @('--spring.ai.deepseek.base-url=https://api.deepseek.com', "--stock-system.agent.experiment-ledger=$ledgerPath")
    } else { $arguments += @('--spring.ai.deepseek.base-url=http://127.0.0.1:18084', '--stock-system.agent.experiment-ledger=false') }
    if ($Suite -eq 'Reports') {
        $arguments += @('--stock-system.agent.reports.enabled=true', '--stock-system.agent.reports.port=16335',
            "--stock-system.agent.reports.storage-directory=$reportRuntime", "--stock-system.agent.reports.catalog-directory=$(Join-Path $reportRuntime 'library')")
    }
    $null = Start-Owned 'backend' $javaPath $arguments $projectRoot @{ DEEPSEEK_API_KEY = $modelKey; SPRING_AI_DEEPSEEK_API_KEY = $modelKey }
    $null = Start-Owned 'frontend' $nodePath @('node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '18083', '--strictPort') (Join-Path $projectRoot 'frontend') @{ VITE_API_TARGET = 'http://localhost:18082'; VITE_DEV_PORT = '18083' }
    foreach ($origin in @('http://localhost:18082', 'http://localhost:18083')) {
        $probe = $null
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            try { $probe = Invoke-RestMethod "$origin/api/test/environment" -TimeoutSec 2; break }
            catch { Start-Sleep -Milliseconds 500 }
        }
        if ($null -eq $probe -or $probe.data.profile -ne 'test' -or $probe.data.database -ne 'stock_system_test') {
            throw 'Protected test environment did not become ready; no sample data was submitted'
        }
    }
    $runner = Start-Owned 'probe' $nodePath $probeArguments (Join-Path $projectRoot 'frontend') @{
        AGENT_FINANCIAL_PROBE_MODE = $Mode.ToLowerInvariant(); AGENT_FINANCIAL_MODEL_PROBE = if ($Mode -eq 'Real') { 'true' } else { 'false' };
        AGENT_FINANCIAL_PROBE_OUTPUT = $outputRoot; MYSQL_CLIENT_PATH = $mysqlPath;
        AGENT_FINANCIAL_CONTINUE_FROM = $ContinueFrom;
        AGENT_REPORT_MODEL_PROBE = if ($Mode -eq 'Real' -and $Suite -eq 'Reports') { 'true' } else { 'false' }
    }
    $runner.WaitForExit()
    $null = $owned[-1].OutTask.GetAwaiter().GetResult(); $null = $owned[-1].ErrTask.GetAwaiter().GetResult()
    $owned[-1].Out.Flush(); $owned[-1].Err.Flush()
    Get-Content (Join-Path $logRoot 'probe.log') -ErrorAction SilentlyContinue
    if ($runner.ExitCode -ne 0) {
        Get-Content (Join-Path $logRoot 'probe-error.log') -Tail 18 -ErrorAction SilentlyContinue
        throw 'Financial quality probe failed; inspect its results and preserved original budget ledger before any further real request'
    }
    Write-Output "FINANCIAL_PROBE_OK mode=$Mode output=$outputRoot"
} finally {
    foreach ($entry in $owned) {
        $current = Get-Process -Id $entry.Pid -ErrorAction SilentlyContinue
        if ($current) {
            if ([Math]::Abs(($current.StartTime.ToUniversalTime() - $entry.Started).TotalMilliseconds) -gt 1) {
                throw 'Recorded process identity changed; it was not stopped'
            }
            Stop-Process -Id $entry.Pid -ErrorAction Stop
            $entry.Process.WaitForExit(5000) | Out-Null
        }
        $null = $entry.OutTask.GetAwaiter().GetResult(); $null = $entry.ErrTask.GetAwaiter().GetResult()
        $entry.Out.Dispose(); $entry.Err.Dispose(); $entry.Process.Dispose()
    }
    if ($Suite -eq 'Reports' -and (Test-Path -LiteralPath $reportRuntime)) {
        $record = Join-Path $reportRuntime 'owned-process.json'
        if (Test-Path -LiteralPath $record) {
            $saved = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
            $current = Get-Process -Id $saved.pid -ErrorAction SilentlyContinue
            $backendOwner = $owned | Where-Object Role -eq 'backend'
            if ($current) {
                if ($saved.parentPid -ne $backendOwner.Pid -or $saved.dataDirectory -ne $reportRuntime -or
                    $current.Path -ne (Join-Path $projectRoot '.local/report-rag/qdrant-1.19.2/qdrant.exe') -or
                    [Math]::Abs(($current.StartTime.ToUniversalTime() - [datetime]$saved.startedAt).TotalMilliseconds) -gt 1) { throw 'Report process ownership changed; it was not stopped' }
                Stop-Process -Id $current.Id; $current.WaitForExit(5000) | Out-Null
            }
        }
        $resolved = (Resolve-Path -LiteralPath $reportRuntime).Path
        if ([System.IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $projectRoot 'target') -or
            [System.IO.Path]::GetFileName($resolved) -notmatch '^rq-[a-f0-9]{8}$') { throw 'Unsafe report quality cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
        Write-Output 'REPORT_QUALITY_VECTORS_CLEANED vectorsRemaining=0'
    }
    $remaining = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $testPorts)
    if ($remaining.Count) { throw 'A test port remains occupied after closing this run processes' }
    Write-Output 'FINANCIAL_PROBE_SERVICES_CLOSED testPortsReleased=true'
}
