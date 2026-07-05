param(
    [string]$WorkingDirectory = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    param([string]$StartPath)
    $resolved = (Resolve-Path -LiteralPath $StartPath).Path
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $gitRoot = git -C $resolved rev-parse --show-toplevel 2>$null
    }
    finally { $ErrorActionPreference = $previousEap }

    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitRoot)) {
        return (Resolve-Path -LiteralPath $gitRoot.Trim()).Path
    }
    return $resolved
}

function Read-SessionJson {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $raw = Get-Content -LiteralPath $Path -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try { return $raw | ConvertFrom-Json }
    catch { return $null }
}


# Read Stop hook payload from stdin (raw only — for diagnostic capture)
$payloadRaw = $null
try {
    if ([Console]::IsInputRedirected) {
        $payloadRaw = [Console]::In.ReadToEnd()
    }
} catch { }

$repoRoot = Resolve-RepoRoot -StartPath $WorkingDirectory
$sdlcDir  = Join-Path $repoRoot '.sdlc'
$sessionPath = Join-Path $sdlcDir 'session.json'

# Always persist the raw hook payload — lets us inspect the real structure
# on the next run without needing a debug flag.
if (-not [string]::IsNullOrWhiteSpace($payloadRaw)) {
    if (-not (Test-Path -LiteralPath $sdlcDir)) {
        New-Item -ItemType Directory -Path $sdlcDir -Force | Out-Null
    }
    $payloadRaw | Set-Content -LiteralPath (Join-Path $sdlcDir 'session-hook-last.json') -Encoding UTF8
}

# Auto-init session.json if missing
if (-not (Test-Path -LiteralPath $sessionPath)) {
    $initScript = Join-Path $repoRoot '.github' 'scripts' 'init-session.ps1'
    if (Test-Path -LiteralPath $initScript) {
        & $initScript -WorkingDirectory $WorkingDirectory | Out-Null
    }
}

$session = Read-SessionJson -Path $sessionPath
if ($null -eq $session) { exit 0 }
if ($session.status -ne 'active') { exit 0 }

$now     = [DateTimeOffset]::UtcNow
$updated = $false

# --- Fetch real rate-limit values from the Anthropic OAuth usage API ---
# The Stop hook payload contains no rate-limit data; use the dedicated fetch script instead.

$apiUsedPct  = $null
$apiResetsAt = $null

$fetchScript = Join-Path $repoRoot '.github' 'scripts' 'fetch-usage-api.ps1'
if (Test-Path -LiteralPath $fetchScript) {
    try {
        $fetchOut = & pwsh -NoProfile -NonInteractive -File $fetchScript 2>$null
        if (-not [string]::IsNullOrWhiteSpace($fetchOut)) {
            $fetchData = $fetchOut | ConvertFrom-Json
            if ($fetchData.success -eq $true) {
                $apiUsedPct = [double]$fetchData.usedPercentage
                # ConvertFrom-Json auto-coerces ISO date strings to DateTime (local tz).
                # Always normalize to UTC ISO 8601 regardless of the incoming type.
                try {
                    $raw = $fetchData.resetsAtUtc
                    $apiResetsAt = if ($raw -is [DateTimeOffset]) {
                        $raw.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    } elseif ($raw -is [DateTime]) {
                        $utcDt = if ($raw.Kind -eq [DateTimeKind]::Local) { $raw.ToUniversalTime() } else { $raw }
                        $utcDt.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    } else {
                        [DateTimeOffset]::Parse($raw.ToString(), [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    }
                } catch { $apiResetsAt = $null }
            }
        }
    } catch { }
}

if ($null -ne $apiUsedPct) {
    # Real API values — always trust over time-based estimate
    $session.usedPercentage = $apiUsedPct
    $session.trackingMode   = 'api'
    if ($null -ne $apiResetsAt) {
        # If value is epoch seconds, convert to ISO-8601
        $epochSecs = $null
        if ([double]::TryParse($apiResetsAt, [ref]$epochSecs) -and $epochSecs -gt 1000000000) {
            $apiResetsAt = [DateTimeOffset]::FromUnixTimeSeconds([long]$epochSecs).ToString('o')
        }
        $session.resetsAtUtc = $apiResetsAt
    }
    $updated = $true
}

if ($updated) {
    $session.lastCheckUtc = $now.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $json = $session | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($sessionPath, ($json + "`r`n"), $encoding)
}

exit 0
