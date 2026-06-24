$lines = @()
while ($null -ne ($line = [Console]::In.ReadLine())) { $lines += $line }
$json = $lines -join "`n"

try {
    $obj = $json | ConvertFrom-Json
    $cmd = $obj.tool_input.command
} catch {
    exit 0
}

if ([string]::IsNullOrWhiteSpace($cmd)) { exit 0 }

$isPush  = $cmd -match 'git\s+push'
$isForce = $cmd -match '(?:--force(?:-with-lease)?|-f\b)'
# Refspec-aware check: catches bare "main", "HEAD:main", and "refs/heads/main"
# while avoiding false positives on hierarchical names like "feature/main-reconnect".
$isMain = $false
foreach ($token in ($cmd -split '\s+')) {
    $dest = if ($token -like '*:*') { ($token -split ':')[-1] } else { $token }
    $dest = $dest.Trim("'").Trim('"')
    if (@('main', 'refs/heads/main') -contains $dest) { $isMain = $true; break }
}
if (-not $isMain) {
    $currentBranch = (git branch --show-current 2>$null)
    if ($currentBranch -and @('main') -contains $currentBranch.Trim()) { $isMain = $true }
}

if ($isPush) {
    if ($isMain) {
        @{
            decision = 'block'
            reason   = 'Direct push to main is blocked. Create a feature branch and open a PR instead.'
        } | ConvertTo-Json -Compress | Write-Output
        exit 2
    }
    if ($isForce) {
        @{
            decision = 'block'
            reason   = 'Force-push is forbidden in client repositories.'
        } | ConvertTo-Json -Compress | Write-Output
        exit 2
    }
}

exit 0
