<#
.SYNOPSIS
  One operator command that switches ALL Android PR and release pipelines between GitHub Actions and Azure DevOps.
  CI_PROVIDER controls both PR CI and the dev/main/tag release pipelines as one coherent provider state.

.EXAMPLE
  ./scripts/ci/pipeline-mode.ps1 azure      # PR CI and releases both on Azure DevOps
  ./scripts/ci/pipeline-mode.ps1 github     # back to GitHub Actions
  ./scripts/ci/pipeline-mode.ps1 status
  ./scripts/ci/pipeline-mode.ps1 azure -DryRun

  All parameters are forwarded unchanged to ci-mode.ps1, which holds the switch logic and the full description.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('github', 'azure', 'status')]
    [string]$Mode = 'status',
    [switch]$DryRun,
    [string]$Repository,
    [switch]$Force
)

$forward = @{ Mode = $Mode }
if ($DryRun) { $forward.DryRun = $true }
if ($Repository) { $forward.Repository = $Repository }
if ($Force) { $forward.Force = $true }
& (Join-Path $PSScriptRoot 'ci-mode.ps1') @forward
