#requires -Version 7.0
[CmdletBinding()]
param([switch]$EnableLocal)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$assetRoot = Join-Path $projectRoot '.local/report-rag'
$binaryRoot = Join-Path $assetRoot 'qdrant-1.19.2'
$archive = Join-Path $assetRoot 'qdrant-v1.19.2.zip'
New-Item -ItemType Directory -Path $assetRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/qdrant/qdrant/releases/download/v1.19.2/qdrant-x86_64-pc-windows-msvc.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '7d86596f16c6e85d45a50312f5e16ccb51e059b62e3d7308110cb65b4c799a4d') {
    throw 'Official Qdrant archive hash mismatch; no executable was launched'
}
if (-not (Test-Path -LiteralPath (Join-Path $binaryRoot 'qdrant.exe'))) { Expand-Archive -LiteralPath $archive -DestinationPath $binaryRoot }
if ((Get-FileHash -LiteralPath (Join-Path $binaryRoot 'qdrant.exe') -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'b25bcc612583333289b00754907639ee97cd28b8741d50f1b4ce0e2bd1ad12eb') {
    throw 'Qdrant executable identity mismatch'
}
$python = Join-Path $projectRoot '.local/rag-preflight/venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'The isolated report-preflight Python environment is required to verify the pinned public model assets' }
Push-Location $projectRoot
try {
    & $python -X utf8 scripts/rag-preflight/download_model.py
    if ($LASTEXITCODE -ne 0) { throw 'Pinned model verification failed' }
} finally { Pop-Location }
if ($EnableLocal) {
    [System.IO.File]::WriteAllText((Join-Path $projectRoot '.env.report-rag-local.properties'), "stock-system.agent.reports.enabled=true`n")
}
Write-Output "REPORT_RAG_ASSETS_READY localEnabled=$([bool]$EnableLocal)"
Write-Output 'No service started. Restart the dev backend after Maven reload; it starts and stops its own local Qdrant on demand.'
