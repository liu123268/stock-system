#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaPath = 'D:\Program Files\Java\jdk-17\bin\java.exe'
$nodePath = (Get-Command node.exe).Source
$mysqlPath = if ($env:MYSQL_CLIENT_PATH) { $env:MYSQL_CLIENT_PATH } else { 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe' }
foreach ($name in @('DB_PASSWORD', 'JWT_SECRET')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "$name is required" }
}
$ports = @(18082, 18083, 18084, 16335, 16336)
if (@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $ports).Count) { throw 'A test port is occupied; existing services will be preserved' }
$id = [guid]::NewGuid().ToString('N').Substring(0, 8)
$output = Join-Path $projectRoot "target/report-rag-browser-$(Get-Date -Format yyyyMMdd-HHmmss)-$id"
$runtime = Join-Path $projectRoot "target/rr-$id"
New-Item -ItemType Directory -Path $output | Out-Null
$owned = [System.Collections.Generic.List[object]]::new()
function Start-Owned([string]$role, [string]$file, [string[]]$arguments, [string]$working, [hashtable]$variables) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$file; $info.WorkingDirectory=$working; $info.UseShellExecute=$false
    $info.CreateNoWindow=$true; $info.WindowStyle='Hidden'; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    foreach ($key in $variables.Keys) { $info.Environment[$key]=$variables[$key] }
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo=$info
    if (-not $process.Start()) { throw "Could not start $role" }
    $out=[System.IO.File]::Open((Join-Path $output "$role.log"),'Create','Write','Read')
    $err=[System.IO.File]::Open((Join-Path $output "$role-error.log"),'Create','Write','Read')
    $owned.Add([pscustomobject]@{ Role=$role; Pid=$process.Id; Started=$process.StartTime.ToUniversalTime(); Process=$process; Out=$out; Err=$err
        OutTask=$process.StandardOutput.BaseStream.CopyToAsync($out); ErrTask=$process.StandardError.BaseStream.CopyToAsync($err) })
    $owned | Select-Object Role,Pid,Started | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $output 'processes.json')
    return $process
}
try {
    $null=Start-Owned 'fixture' $nodePath @('e2e/agent-model-fixture.mjs') (Join-Path $projectRoot 'frontend') @{}
    $null=Start-Owned 'backend' $javaPath @('-jar','target/stock-system-0.0.1-SNAPSHOT.jar',
        '--spring.profiles.active=test','--server.address=127.0.0.1','--server.port=18082',
        '--spring.datasource.url=jdbc:mysql://localhost:3306/stock_system_test?createDatabaseIfNotExist=false&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai',
        '--stock-system.market.mode=mock','--spring.ai.model.chat=deepseek','--spring.ai.deepseek.base-url=http://127.0.0.1:18084',
        '--stock-system.agent.budget-yuan=10','--stock-system.agent.experiment-ledger=false',
        '--stock-system.agent.reports.enabled=true','--stock-system.agent.reports.port=16335',"--stock-system.agent.reports.storage-directory=$runtime",
        "--stock-system.agent.reports.catalog-directory=$(Join-Path $runtime 'library')"
    ) $projectRoot @{ DEEPSEEK_API_KEY='offline-report-fixture'; SPRING_AI_DEEPSEEK_API_KEY='offline-report-fixture' }
    $null=Start-Owned 'frontend' $nodePath @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','18083','--strictPort') (Join-Path $projectRoot 'frontend') @{ VITE_API_TARGET='http://localhost:18082'; VITE_DEV_PORT='18083' }
    foreach ($origin in @('http://localhost:18082','http://localhost:18083')) {
        $probe=$null
        for ($attempt=0; $attempt -lt 60; $attempt++) {
            try { $probe=Invoke-RestMethod "$origin/api/test/environment" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 500 }
        }
        if ($null -eq $probe -or $probe.data.profile -ne 'test' -or $probe.data.database -ne 'stock_system_test') { throw 'Protected test environment unavailable; no business samples submitted' }
    }
    $runner=Start-Owned 'probe' $nodePath @('e2e/research-agent-smoke.mjs') (Join-Path $projectRoot 'frontend') @{
        AGENT_E2E_OUTPUT=$output; AGENT_REPORT_RAG_PROBE='true'; MYSQL_CLIENT_PATH=$mysqlPath
        AGENT_REPORT_LIBRARY=(Join-Path $runtime 'library'); AGENT_JAVA_PATH=$javaPath
    }
    $runner.WaitForExit()
    $null=$owned[-1].OutTask.GetAwaiter().GetResult(); $null=$owned[-1].ErrTask.GetAwaiter().GetResult()
    $owned[-1].Out.Flush(); $owned[-1].Err.Flush()
    Get-Content (Join-Path $output 'probe.log')
    if ($runner.ExitCode -ne 0) { Get-Content (Join-Path $output 'probe-error.log') -Tail 20; throw 'Report browser probe failed' }
    Write-Output "REPORT_BROWSER_PROBE_OK output=$output paidRequests=0"
} finally {
    foreach ($entry in $owned) {
        $current=Get-Process -Id $entry.Pid -ErrorAction SilentlyContinue
        if ($current) {
            if ([Math]::Abs(($current.StartTime.ToUniversalTime()-$entry.Started).TotalMilliseconds) -gt 1) { throw 'Owned process identity changed; it was not stopped' }
            Stop-Process -Id $entry.Pid; $entry.Process.WaitForExit(5000) | Out-Null
        }
        $null=$entry.OutTask.GetAwaiter().GetResult(); $null=$entry.ErrTask.GetAwaiter().GetResult()
        $entry.Out.Dispose(); $entry.Err.Dispose(); $entry.Process.Dispose()
    }
    $record=Join-Path $runtime 'owned-process.json'
    if (Test-Path -LiteralPath $record) {
        $saved=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
        $backendOwner=$owned | Where-Object Role -eq 'backend'
        $current=Get-Process -Id $saved.pid -ErrorAction SilentlyContinue
        if ($current) {
            if ($saved.parentPid -ne $backendOwner.Pid -or $current.Path -ne (Join-Path $projectRoot '.local/report-rag/qdrant-1.19.2/qdrant.exe') -or
                [Math]::Abs(($current.StartTime.ToUniversalTime()-[datetime]$saved.startedAt).TotalMilliseconds) -gt 1) { throw 'Report subprocess identity changed; it was not stopped' }
            Stop-Process -Id $current.Id; $current.WaitForExit(5000) | Out-Null
        }
    }
    if (Test-Path -LiteralPath $runtime) {
        $resolved=(Resolve-Path -LiteralPath $runtime).Path
        if ([System.IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $projectRoot 'target') -or [System.IO.Path]::GetFileName($resolved) -ne "rr-$id") { throw 'Unsafe test vector cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    if (@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $ports).Count) { throw 'Test listener remains after cleanup' }
    Write-Output 'REPORT_TEST_RESOURCES_CLEANED vectorsRemaining=0 testPortsReleased=true'
}
