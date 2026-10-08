param(
    [switch]$PrepareOnly,
    [string]$PythonPath = 'python.exe',
    [string]$JavaPath = 'java.exe'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.local\stock-directory'
$taskName = 'StockSystem-DirectorySync'
$taskPath = '\'
$pythonExecutable = (Get-Command $PythonPath -ErrorAction Stop).Source
$javaExecutable = (Get-Command $JavaPath -ErrorAction Stop).Source
$jarSource = Join-Path $projectRoot 'target\stock-system-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarSource)) {
    throw 'Build the executable JAR with Maven before installing the local directory task.'
}
if ((Get-TimeZone).Id -ne 'China Standard Time') {
    throw 'This daily 06:30 task requires the Windows time zone China Standard Time.'
}
$venvPython = Join-Path $runtimeRoot 'venv\Scripts\python.exe'
$taskPython = Join-Path $runtimeRoot 'venv\Scripts\pythonw.exe'
$runner = Join-Path $runtimeRoot 'scripts\run-local-directory-sync.py'
$existing = Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath -ErrorAction SilentlyContinue
if ($existing -and ($existing.Actions.Count -ne 1 -or $existing.Actions[0].Execute -ne $taskPython -or
    $existing.Actions[0].Arguments -notlike ('*' + $runner + '*'))) {
    throw 'A task with this name exists but is not owned by this project.'
}
if ($existing -and $existing.State -eq 'Running') {
    throw 'The owned directory task is running; wait for completion before updating its runtime.'
}

# A runtime outside target survives mvn clean. No secrets are copied into it.
New-Item -ItemType Directory -Path (Join-Path $runtimeRoot 'scripts') -Force | Out-Null
if (-not (Test-Path -LiteralPath $venvPython)) {
    & $pythonExecutable -m venv (Join-Path $runtimeRoot 'venv')
    if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed.' }
}
& $venvPython -m pip --version | Out-Null
if ($LASTEXITCODE -ne 0) {
    & $venvPython -m ensurepip --upgrade --default-pip
    if ($LASTEXITCODE -ne 0) { throw 'Virtual environment pip initialization failed.' }
}
if (-not (Test-Path -LiteralPath $taskPython)) { throw 'The prepared Windows runtime is missing pythonw.exe.' }
& $venvPython -m pip install --disable-pip-version-check -r (Join-Path $PSScriptRoot 'directory-requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Directory dependencies could not be installed.' }
$allowedScripts = @('probe-stock-directory.py', 'prepare-stock-directory.py',
    'stock_directory_coverage.py', 'stock_directory_pinyin.py', 'sync-stock-directory.py',
    'run-local-directory-sync.py')
foreach ($fileName in $allowedScripts) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $fileName) -Destination (Join-Path $runtimeRoot "scripts\$fileName")
}
Copy-Item -LiteralPath $jarSource -Destination (Join-Path $runtimeRoot 'app.jar')
$config = @{ python = $venvPython; java = $javaExecutable; jar = (Join-Path $runtimeRoot 'app.jar') }
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'runtime.json') -Encoding UTF8
& $venvPython -B $runner --runtime-dir $runtimeRoot --check
if ($LASTEXITCODE -ne 0) { throw 'Local directory runtime preflight failed.' }
if ($PrepareOnly) { Write-Output 'LOCAL_DIRECTORY_PREPARED'; return }

# Installation is an explicitly requested development data operation, not a test.
& $venvPython -B $runner --runtime-dir $runtimeRoot --force
if ($LASTEXITCODE -ne 0) { throw 'First local directory synchronization failed; task was not registered.' }
$receipt = Get-Content -LiteralPath (Join-Path $runtimeRoot 'state\last-success.json') -Raw | ConvertFrom-Json
if ($receipt.status -ne 'APPLIED' -or $receipt.import.database -ne 'stock_system') {
    throw 'The first run did not commit to the expected local development database.'
}
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $taskPython -Argument ('-B "' + $runner + '" --runtime-dir "' + $runtimeRoot + '"') -WorkingDirectory $projectRoot
$triggers = @((New-ScheduledTaskTrigger -Daily -At '06:30'), (New-ScheduledTaskTrigger -AtLogOn -User $user))
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -RestartCount 2 -RestartInterval (New-TimeSpan -Minutes 15) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$task = New-ScheduledTask -Action $action -Trigger $triggers -Principal $principal -Settings $settings `
    -Description 'Daily local stock identity sync at 06:30; catch up after login, retain historical identities.'
Register-ScheduledTask -TaskName $taskName -TaskPath $taskPath -InputObject $task -Force | Out-Null
Write-Output 'LOCAL_DIRECTORY_TASK_INSTALLED'
Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath | Select-Object TaskName, State
Get-ScheduledTaskInfo -TaskName $taskName -TaskPath $taskPath | Select-Object NextRunTime, LastTaskResult
