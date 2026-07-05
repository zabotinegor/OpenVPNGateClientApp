param(
    [string]$FlowId = '',

    [string]$CurrentStep = '',

    [string]$StoryPath = '',

    [string]$Branch = '',

    [string[]]$CompletedSteps = @(),

    [string]$ResumeAgent = '',

    [string]$ResumeArgs = '',

    [string[]]$ContextFiles = @(),

    [string]$Summary = '',

    [string]$Type = 'flow',

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

$repoRoot = Resolve-RepoRoot -StartPath $WorkingDirectory
$sdlcDir = Join-Path $repoRoot '.sdlc'
if (-not (Test-Path -LiteralPath $sdlcDir)) {
    New-Item -ItemType Directory -Path $sdlcDir -Force | Out-Null
}

$sessionPath = Join-Path $sdlcDir 'session.json'
$existing = Read-SessionJson -Path $sessionPath

$usedPct = 0
$resetsAt = $null
$sessionId = 'unknown'
$sessionStart = $null

if ($null -ne $existing) {
    $usedPct = [double]$existing.usedPercentage
    $resetsAt = $existing.resetsAtUtc
    $sessionId = $existing.sessionId
    $p = $existing.PSObject.Properties['sessionStartUtc']
    if ($null -ne $p) {
        $sessionStart = $p.Value
    }
}

$now = [DateTimeOffset]::UtcNow

if ($null -ne $sessionStart -and $null -ne $resetsAt) {
    $startDt = if ($sessionStart -is [datetime]) {
        [DateTimeOffset]::new($sessionStart, [TimeSpan]::Zero)
    } else {
        [DateTimeOffset]::Parse($sessionStart.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
    }
    $resetDt = if ($resetsAt -is [datetime]) {
        [DateTimeOffset]::new($resetsAt, [TimeSpan]::Zero)
    } else {
        [DateTimeOffset]::Parse($resetsAt.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
    }
    $totalWindow = ($resetDt - $startDt).TotalMinutes
    if ($totalWindow -gt 0) {
        $elapsed = ($now - $startDt).TotalMinutes
        $timeBasedPct = [Math]::Min(100, [Math]::Round(($elapsed / $totalWindow) * 100, 1))
        if ($timeBasedPct -gt $usedPct) {
            $usedPct = $timeBasedPct
        }
    }
}

$checkpoint = @{
    type = $Type
    flowId = $FlowId
    currentStep = $CurrentStep
    storyPath = $StoryPath
    branch = $Branch
    completedSteps = $CompletedSteps
    resumeAgent = $ResumeAgent
    resumeArgs = $ResumeArgs
    contextFiles = $ContextFiles
    summary = $Summary
    checkpointedAtUtc = ([DateTimeOffset]::UtcNow).ToString('o')
}

$session = @{
    version = 1
    sessionId = $sessionId
    lastCheckUtc = ([DateTimeOffset]::UtcNow).ToString('o')
    usedPercentage = $usedPct
    resetsAtUtc = $resetsAt
    sessionStartUtc = $sessionStart
    trackingMode = 'time-based'
    status = 'checkpointed'
    checkpoint = $checkpoint
}

$session | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $sessionPath -Encoding UTF8

@{
    success = $true
    session_path = $sessionPath
    checkpoint = $checkpoint
} | ConvertTo-Json -Depth 10
