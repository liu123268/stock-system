#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Fixture', 'Real')][string]$Mode = 'Fixture',
    [switch]$ApprovedClosureData,
    [ValidateRange(0, 1000)][int]$ExpectedUnknownReservations = 0,
    [string[]]$CaseIds,
    [string]$FixtureFailAt,
    [string]$ContinueFrom
)
$arguments = @{ Suite = 'Closure'; Mode = $Mode; ApprovedFinancialData = $ApprovedClosureData;
    ExpectedUnknownReservations = $ExpectedUnknownReservations; ReviewEach = ($Mode -eq 'Real') }
if ($PSBoundParameters.ContainsKey('CaseIds')) { $arguments.CaseIds = $CaseIds }
if ($PSBoundParameters.ContainsKey('FixtureFailAt')) { $arguments.FixtureFailAt = $FixtureFailAt }
if ($PSBoundParameters.ContainsKey('ContinueFrom')) { $arguments.ContinueFrom = $ContinueFrom }
& (Join-Path $PSScriptRoot 'run-agent-financial-probe.ps1') @arguments
