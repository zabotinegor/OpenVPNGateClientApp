#Requires -Version 7
<#
.SYNOPSIS
    Post /gemini review + @codex review comment on a PR using gh CLI.
    Returns the comment ID for use in reactions polling.

.PARAMETER Repo
    Owner/repo string, e.g. "zabotinegor/OpenVPNGateClientServer".

.PARAMETER PrNumber
    Pull request number.

.EXAMPLE
    .\.github\scripts\post-bot-review-request.ps1 -Repo "org/repo" -PrNumber 86
#>
param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][int]$PrNumber
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$body = "/gemini review`n@codex review"

# Use gh api (POST issues comment) instead of gh pr comment because
# gh pr comment does not support --json and cannot return the comment ID.
$jsonBody = @{ body = $body } | ConvertTo-Json -Compress
$tmpFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($tmpFile, $jsonBody, [System.Text.Encoding]::UTF8)
    $result = gh api "repos/$Repo/issues/$PrNumber/comments" --method POST --input $tmpFile 2>&1
} finally {
    Remove-Item -LiteralPath $tmpFile -Force -ErrorAction SilentlyContinue
}
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to post review request: $result"
    exit 1
}

$parsed = $result | ConvertFrom-Json -ErrorAction SilentlyContinue
$commentId = if ($parsed -and $parsed.id) { $parsed.id } else { $null }

[PSCustomObject]@{
    status    = 'POSTED'
    commentId = $commentId
    repo      = $Repo
    prNumber  = $PrNumber
    body      = $body
} | ConvertTo-Json -Compress
