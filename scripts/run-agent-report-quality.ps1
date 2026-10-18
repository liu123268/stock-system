#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Fixture', 'Real')][string]$Mode = 'Fixture',
    [switch]$ApprovedReportData,
    [string]$ContinueFrom,
    [string[]]$CaseIds
)
# Reuse the protected test environment, budget and precise cleanup from the financial runner.
$arguments = @{ Suite = 'Reports'; Mode = $Mode; ApprovedReportData = $ApprovedReportData }
if ($PSBoundParameters.ContainsKey('ContinueFrom')) { $arguments.ContinueFrom = $ContinueFrom }
if ($PSBoundParameters.ContainsKey('CaseIds')) { $arguments.CaseIds = $CaseIds }
& (Join-Path $PSScriptRoot 'run-agent-financial-probe.ps1') @arguments
