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

$isPush    = $cmd -match 'git\s+push'
$isMain    = $cmd -match '\bmain\b'
$isForce   = $cmd -match '--force|-f\b'

if ($isPush -and ($isMain -or $isForce)) {
    @{
        decision = 'block'
        reason   = 'Direct push to main is blocked. Create a feature branch and open a PR instead.'
    } | ConvertTo-Json -Compress | Write-Output
    exit 2
}

exit 0
