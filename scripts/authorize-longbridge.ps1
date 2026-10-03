$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryPath = Join-Path (Split-Path -Parent $projectRoot) '.m2repo'
$clientConfigPath = Join-Path $projectRoot '.env.longbridge-local.properties'
$propertyName = 'local.longbridge.client-id'
$callbackPort = 18080
$needsRegistration = $true

# Public client configuration stays local; SDK OAuth credentials stay in the user directory.
# A separate Longbridge login account, rather than client_id alone, isolates cloud/local quote limits.
if (Test-Path -LiteralPath $clientConfigPath) {
    $savedConfig = Get-Content -LiteralPath $clientConfigPath -Raw
    if ($savedConfig -notmatch '(?m)^local\.longbridge\.client-id=([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})\s*$') {
        throw 'Local client config is invalid; no existing file was overwritten.'
    }
    $clientId = $Matches[1]
    $needsRegistration = $false
    $callbackPort = 60355 # Preserve the original redirect for older local configurations.
    if ($savedConfig -match '(?m)^local\.longbridge\.callback-port=(\d+)\s*$') {
        $savedPort = 0
        if (-not [int]::TryParse($Matches[1], [ref]$savedPort) -or $savedPort -lt 1 -or $savedPort -gt 65535) {
            throw 'Local OAuth callback port must be between 1 and 65535; no existing file was overwritten.'
        }
        $callbackPort = $savedPort
    }
}
if ($needsRegistration) {
    Write-Host 'Registering a public OAuth client for stock-system local Java SDK...'
    $registrationBody = @{
        redirect_uris = @("http://localhost:$callbackPort/callback")
        token_endpoint_auth_method = 'none'
        grant_types = @('authorization_code', 'refresh_token')
        response_types = @('code')
        client_name = 'stock-system-local-java-sdk'
    } | ConvertTo-Json
    try {
        $registration = Invoke-RestMethod -Method Post -Uri 'https://openapi.longbridge.com/oauth2/register' `
            -ContentType 'application/json' -Body $registrationBody
    } catch {
        throw 'OAuth client registration failed; check network access to openapi.longbridge.com.'
    }
    $clientId = $registration.client_id
    $registration = $null # Never print the registration response or its management token.
    if ($clientId -notmatch '^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') {
        throw 'OAuth registration returned an invalid client_id.'
    }
    # A concurrent setup must never overwrite an existing local client configuration.
    $stream = [IO.File]::Open($clientConfigPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes("$propertyName=$clientId`nlocal.longbridge.callback-port=$callbackPort`n")
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

$setupEnvironment = @{
    STOCK_LONGBRIDGE_OAUTH_SETUP = 'true'
    LONGBRIDGE_CLIENT_ID = $clientId
    STOCK_LONGBRIDGE_OAUTH_CALLBACK_PORT = "$callbackPort"
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
foreach ($name in $setupEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    foreach ($name in $setupEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $setupEnvironment[$name], 'Process')
    }
    Write-Host 'Keep this window open. Authorize using the NEW local Longbridge account in the browser.'
    & mvn "-Dmaven.repo.local=$repositoryPath" -f (Join-Path $projectRoot 'pom.xml') `
        '-Dtest=LongbridgeOAuthSetupTest' '-DargLine=-Djava.net.useSystemProxies=true' test
    if ($LASTEXITCODE -ne 0) { throw 'OAuth setup failed; do not enable debug logs or paste credentials.' }
    Write-Host 'SDK OAuth is saved for this Windows user. Restart the local backend to load the SDK transport.'
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
