#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('Offline', 'Live')][string]$Mode = 'Offline')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repository = Join-Path (Split-Path -Parent $projectRoot) '.m2repo'
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'D:\Program Files\Java\jdk-17\bin\java.exe' }
$classpath = @('io/github/longbridge/openapi-sdk/4.5.0/openapi-sdk-4.5.0.jar',
    'org/scijava/native-lib-loader/2.4.0/native-lib-loader-2.4.0.jar', 'com/google/code/gson/gson/2.10.1/gson-2.10.1.jar') |
    ForEach-Object { Join-Path $repository $_ }
$logging = Get-ChildItem (Join-Path $repository 'org/slf4j/slf4j-api') -Filter 'slf4j-api-*.jar' -Recurse | Sort-Object FullName | Select-Object -Last 1
if (-not $logging) { throw 'Existing SLF4J dependency missing; no dependency is downloaded' }
$classpath += $logging.FullName
foreach ($path in @($javaPath) + $classpath) { if (-not (Test-Path -LiteralPath $path)) { throw 'Existing JDK or SDK dependency missing' } }
if ($Mode -eq 'Live') {
    $existing = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe' OR Name = 'longbridge.exe'" -ErrorAction Stop |
        Where-Object { $_.Name -eq 'longbridge.exe' -or $_.CommandLine -match 'StockSystemApplication|stock-system-0.0.1-SNAPSHOT|Longbridge.*Probe|LongbridgeOAuthObservation' })
    if ($existing.Count) { throw 'An existing process may own the market connection or update SDK authorization; it was not stopped' }
}
$stamp = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow, 'China Standard Time').ToString('yyyyMMdd-HHmmss')
$output = Join-Path $projectRoot ('target/longbridge-oauth-observation-' + $stamp + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $output | Out-Null
$info = [System.Diagnostics.ProcessStartInfo]::new()
$info.FileName = $javaPath; $info.WorkingDirectory = $projectRoot
$info.UseShellExecute = $false; $info.CreateNoWindow = $true; $info.WindowStyle = 'Hidden'
$info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
$info.Environment['RUST_LOG'] = 'off'
$info.Environment.Remove('LONGBRIDGE_LOG_PATH') | Out-Null
foreach ($argument in @('-Dfile.encoding=UTF-8','--source','17','--class-path',($classpath -join ';'),(Join-Path $PSScriptRoot 'LongbridgeOAuthObservation.java'))) { $info.ArgumentList.Add($argument) }
if ($Mode -eq 'Offline') { $info.ArgumentList.Add('--offline') }
else { foreach ($argument in @('--live',(Join-Path $projectRoot '.env.longbridge-local.properties'),(Join-Path $output 'observation.json'))) { $info.ArgumentList.Add($argument) } }
$process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
if (-not $process.Start()) { throw 'Could not start the owned observation process' }
$processId = $process.Id; $started = $process.StartTime.ToUniversalTime()
$stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
$record = [ordered]@{ mode=$Mode; processId=$processId; started=$started.ToString('o'); java=$javaPath; exited=$false }
$record | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $output 'process.json')
try {
    if (-not $process.WaitForExit(60000)) { throw 'Bounded SDK observation timed out; no interactive authorization or retry is performed' }
    $outText = $stdout.GetAwaiter().GetResult(); $errText = $stderr.GetAwaiter().GetResult()
    $outText | Set-Content -Encoding utf8 (Join-Path $output 'stdout.log')
    $errText | Set-Content -Encoding utf8 (Join-Path $output 'stderr.log')
    if ($process.ExitCode -ne 0) { throw 'SDK observation failed; inspect the safe observation.json and process record' }
    Write-Output $outText.Trim()
    Write-Output "OAUTH_OBSERVATION_OUTPUT $output"
} finally {
    if (-not $process.HasExited) {
        $current = Get-Process -Id $processId -ErrorAction Stop
        if ($current.Path -ne $javaPath -or [Math]::Abs(($current.StartTime.ToUniversalTime() - $started).TotalMilliseconds) -gt 1) { throw 'Observation process identity changed; it was not stopped' }
        Stop-Process -Id $processId -ErrorAction Stop
        $process.WaitForExit(5000) | Out-Null
    }
    $record.exited = $process.HasExited
    $record | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $output 'process.json')
    $process.Dispose()
}
