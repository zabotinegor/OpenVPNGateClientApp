# Starts the rate-limit server (port 9229) if it is not already running.
# Called by the UserPromptSubmit hook at the start of every Claude Code turn.
# Silent — no output, always exits 0.
#
# Uses an HTTP health check (not just TCP) so a zombie process that holds the
# port but doesn't respond HTTP is detected and killed before relaunching.

$ErrorActionPreference = 'SilentlyContinue'
try {
    $port       = 9229
    $healthUrl  = "http://127.0.0.1:$port/health"
    $logFile    = Join-Path $HOME '.claude' 'rate-limit-server.log'

    # HTTP health check via Node.js — bypasses PowerShell HttpClient which hangs
    # indefinitely on .GetAwaiter().GetResult() even when Timeout is set.
    $healthy = $false
    try {
        $nodeCheck = node -e @"
const http = require('http');
const req = http.get('$healthUrl', { timeout: 2000 }, (res) => {
    process.stdout.write(res.statusCode === 200 ? 'ok' : 'bad');
    process.exit(0);
});
req.on('error', () => process.exit(1));
req.on('timeout', () => { req.destroy(); process.exit(1); });
"@ 2>$null
        $healthy = ($nodeCheck -eq 'ok')
    } catch { }

    if ($healthy) { exit 0 }

    # Port held by a zombie — find and kill it so we can bind cleanly.
    $pids = (netstat -ano 2>$null | Select-String ":$port\s.*LISTENING") -replace '.*\s+(\d+)\s*$','$1' |
            Where-Object { $_ -match '^\d+$' } | Select-Object -Unique
    foreach ($procId in $pids) {
        try { Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue } catch { }
    }
    Start-Sleep -Milliseconds 500

    $serverScript = Join-Path $PSScriptRoot 'rate-limit-server.js'
    if (-not (Test-Path -LiteralPath $serverScript)) { exit 0 }

    Start-Process node -ArgumentList "`"$serverScript`"" `
        -RedirectStandardOutput $logFile -RedirectStandardError $logFile `
        -WindowStyle Hidden
} catch { }
exit 0
