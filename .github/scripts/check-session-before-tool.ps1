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

$repoRoot = Resolve-RepoRoot -StartPath $WorkingDirectory
$sessionPath = Join-Path $repoRoot '.sdlc' 'session.json'

if (-not (Test-Path -LiteralPath $sessionPath)) { exit 0 }

$checkScript = Join-Path $repoRoot '.github' 'scripts' 'check-rate-limit.ps1'
if (-not (Test-Path -LiteralPath $checkScript)) { exit 0 }

# Read the hook payload from stdin to detect long-running operations.
# PreToolUse payload: { "tool_name": "Bash"|"PowerShell"|..., "tool_input": { "command": "..." } }
$isLongOp = $false
$toolName  = ''
try {
    if ([Console]::IsInputRedirected) {
        $payloadRaw = [Console]::In.ReadToEnd()
        if (-not [string]::IsNullOrWhiteSpace($payloadRaw)) {
            $payload  = $payloadRaw | ConvertFrom-Json
            $toolName = $payload.tool_name ?? ''
            $command  = $payload.tool_input?.command ?? $payload.tool_input?.script ?? ''
            # Patterns known to run for minutes-to-hours; block them earlier to leave
            # enough window for the agent to commit, checkpoint, and clean up.
            $longOpPatterns = @(
                'dotnet build', 'dotnet test', 'dotnet run',
                'npm install', 'npm ci', 'npm run build', 'npm run test', 'npm test',
                'yarn install', 'yarn build',
                'cargo build', 'cargo test',
                'pytest', 'python -m pytest',
                'docker build', 'docker-compose',
                'mvn ', 'gradle ',
                'go build', 'go test',
                'make ', 'cmake '
            )
            foreach ($pattern in $longOpPatterns) {
                if ($command -match [regex]::Escape($pattern)) {
                    $isLongOp = $true
                    break
                }
            }
        }
    }
} catch { }

try {
    $resultRaw = & pwsh -NoProfile -NonInteractive -File $checkScript -WorkingDirectory $WorkingDirectory 2>$null
    $result = $resultRaw | ConvertFrom-Json
} catch {
    exit 0
}

$pct = [double]$result.used_percentage
$resetsAt = if ($result.resets_at) { $result.resets_at } else { 'unknown' }

# Long-running operations are blocked earlier (70%) so the agent has time to
# commit in-progress work and checkpoint cleanly before the window expires.
# Normal tool calls are blocked at 90%.
$blockThreshold = if ($isLongOp) { 70 } else { 90 }

$shouldBlock = $result.status -eq 'exhausted' -or
               ($result.status -eq 'warning' -and $pct -ge $blockThreshold)

if ($shouldBlock) {
    $opNote = if ($isLongOp) {
        "This is a long-running operation ($toolName) that could outlast the remaining session window. "
    } else { '' }

    @{
        permissionDecision       = 'deny'
        permissionDecisionReason = "${opNote}Session at $pct% — hard stop. Before waiting for reset at $resetsAt`: (1) commit any in-progress code changes to git, (2) update .sdlc/status.json if needed, (3) write a brief checkpoint note. After the reset, restart with the same orchestrator command — it reads status.json and resumes from the last completed step."
    } | ConvertTo-Json -Compress
}

exit 0
