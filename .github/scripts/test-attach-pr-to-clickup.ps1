#Requires -Version 7
<#
.SYNOPSIS
    Tests for attach-pr-to-clickup.ps1 - the single entry point for putting a
    PR link on the linked ClickUp task.

.DESCRIPTION
    Isolated: every case builds a throwaway repo root under the system temp
    directory with its own .sdlc/. The routing decisions (local mode, malformed
    config, missing pointer, release-flow shape, missing token) are all
    observable before a token is needed, so they run with no network at all; the
    post -> verify -> log path is driven once against a loopback stub of the
    ClickUp REST comment surface.
#>
[CmdletBinding()]
param([string]$WorkingDirectory = (Get-Location).Path)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

function Assert {
    param([string]$Label, [bool]$Condition)
    if ($Condition) {
        Write-Host "  PASS  $Label" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  FAIL  $Label" -ForegroundColor Red
        $script:fail++
    }
}

$attachScript = Join-Path $PSScriptRoot 'attach-pr-to-clickup.ps1'
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("clickup-attach-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$stubErrorPath = Join-Path $testRoot 'stub-error.txt'
$prUrl = 'https://github.com/org/repo/pull/117'

function New-TestRoot {
    param([hashtable]$Flow, [switch]$NoConfig, [switch]$BadConfig)
    $root = Join-Path $testRoot ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $sdlc = Join-Path $root '.sdlc'
    New-Item -ItemType Directory -Path $sdlc -Force | Out-Null

    if ($BadConfig) {
        'not json at all {' | Set-Content -LiteralPath (Join-Path $sdlc 'clickup-config.json') -Encoding UTF8
    } elseif (-not $NoConfig) {
        @{ workspace = 'test'; release_flow = 'simple'; statuses = @{ todo = 'to do' }; spaces = @{} } |
            ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $sdlc 'clickup-config.json') -Encoding UTF8
    }
    if ($Flow) {
        @{ flows = @{ 'f::F-1' = $Flow } } | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath (Join-Path $sdlc 'status.json') -Encoding UTF8
    }
    return $root
}

function Invoke-Attach {
    param([string]$Root, [hashtable]$Arguments = @{})
    $Arguments['Root'] = $Root
    if (-not $Arguments.ContainsKey('FlowId')) { $Arguments['FlowId'] = 'f::F-1' }
    if (-not $Arguments.ContainsKey('PrUrl')) { $Arguments['PrUrl'] = $prUrl }
    $out = $null
    try {
        $out = & $attachScript @Arguments 2>$null
        $code = $LASTEXITCODE
    } catch {
        $code = 1
    }
    $json = $null
    try { $json = @($out)[0] | ConvertFrom-Json } catch { }
    return @{ Code = $code; Json = $json }
}

$prevToken = $env:CLICKUP_TOKEN
$env:CLICKUP_TOKEN = ''

try {

Write-Host "== 1. Local mode is not a failure =="
$root = New-TestRoot -Flow @{ clickupTaskId = '86cazp563' } -NoConfig
$r = Invoke-Attach -Root $root
Assert 'no clickup-config.json exits 0' ($r.Code -eq 0)
Assert 'reports SKIPPED for local mode' ($r.Json.status -eq 'SKIPPED')

Write-Host "== 2. A malformed config is an error, not a fallback =="
$root = New-TestRoot -Flow @{ clickupTaskId = '86cazp563' } -BadConfig
$r = Invoke-Attach -Root $root
Assert 'unparseable config exits 1' ($r.Code -eq 1)
Assert 'unparseable config reports FAILED' ($r.Json.status -eq 'FAILED')

Write-Host "== 3. A feature flow with no task pointer is a hard failure =="
$root = New-TestRoot -Flow @{ storyPath = 'https://app.clickup.com/t/86cazp563' }
$r = Invoke-Attach -Root $root
Assert 'missing clickupTaskId exits 1' ($r.Code -eq 1)
Assert 'missing clickupTaskId reports FAILED' ($r.Json.status -eq 'FAILED')

Write-Host "== 4. A release flow carries no flow-level pointer by design =="
$root = New-TestRoot -Flow @{ release_items = @(@{ task_id = '86cazp01'; space_key = 'app' }) }
$r = Invoke-Attach -Root $root
Assert 'a release flow without a pointer exits 0' ($r.Code -eq 0)
Assert 'a release flow without a pointer reports SKIPPED' ($r.Json.status -eq 'SKIPPED')

Write-Host "== 5. No token means fall back to MCP, not fail =="
$root = New-TestRoot -Flow @{ clickupTaskId = '86cazp563' }
$r = Invoke-Attach -Root $root
Assert 'no token exits 2' ($r.Code -eq 2)
Assert 'no token reports UNAVAILABLE' ($r.Json.status -eq 'UNAVAILABLE')
Assert 'UNAVAILABLE names the MCP fallback' ($r.Json.reason -match 'MCP')

Write-Host "== 6. Full post -> verify -> log against a stub API =="
$stubState = Join-Path $testRoot 'attach-stub-state.json'
$port = 0
$http = [System.Net.HttpListener]::new()
for ($p = 8410; $p -lt 8510; $p++) {
    try {
        $http.Prefixes.Clear()
        $http.Prefixes.Add("http://127.0.0.1:$p/")
        $http.Start()
        $port = $p
        break
    } catch { continue }
}

if ($port -eq 0) {
    Write-Host "  SKIP  stub API could not bind a loopback port in 8410-8509; the full post/verify/log path was NOT exercised this run" -ForegroundColor Yellow
} else {
    @{ byTask = @{}; posts = 0; dropPosts = $false } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $stubState -Encoding UTF8

    $runspace = [powershell]::Create()
    $null = $runspace.AddScript({
        param($http, $statePath, $errorPath)
        while ($http.IsListening) {
            try {
                $ctx = $http.GetContext()
                $req = $ctx.Request; $res = $ctx.Response
                # /api/v2/task/<id>/comment
                $taskId = ($req.Url.AbsolutePath -replace '.*/task/', '' -replace '/comment.*', '')
                $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
                if ($state.byTask -isnot [System.Management.Automation.PSCustomObject]) { $state | Add-Member -NotePropertyName byTask -NotePropertyValue ([pscustomobject]@{}) -Force }
                $existing = @()
                if ($state.byTask.PSObject.Properties[$taskId]) { $existing = @($state.byTask.$taskId) }
                $body = $null
                if ($req.HasEntityBody) {
                    $sr = [System.IO.StreamReader]::new($req.InputStream)
                    $body = $sr.ReadToEnd(); $sr.Close()
                }
                if ($req.HttpMethod -eq 'POST') {
                    $parsed = $body | ConvertFrom-Json
                    $state.posts = [int]$state.posts + 1
                    if (-not $state.dropPosts) {
                        $existing = @($existing + @([pscustomobject]@{ comment_text = [string]$parsed.comment_text }))
                        $state.byTask | Add-Member -NotePropertyName $taskId -NotePropertyValue $existing -Force
                    }
                    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
                    $payload = @{ id = 'c1' }
                } else {
                    $payload = @{ comments = @($existing) }
                }
                $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 8))
                $res.ContentType = 'application/json'
                $res.OutputStream.Write($bytes, 0, $bytes.Length)
                $res.Close()
            } catch {
                try { "$($_.Exception.GetType().Name): $($_.Exception.Message)" | Set-Content -LiteralPath $errorPath -Encoding UTF8 } catch { }
                break
            }
        }
    }).AddArgument($http).AddArgument($stubState).AddArgument($stubErrorPath)
    $async = $runspace.BeginInvoke()

    try {
        $apiBase = "http://127.0.0.1:$port/api/v2"
        $root = New-TestRoot -Flow @{ clickupTaskId = 'T1' }
        $stubArgs = @{ Token = 'pk_stub'; ApiBase = $apiBase }

        $r = Invoke-Attach -Root $root -Arguments $stubArgs.Clone()
        $state = Get-Content -LiteralPath $stubState -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert 'attach exits 0' ($r.Code -eq 0)
        Assert 'attach reports OK' ($r.Json.status -eq 'OK')
        Assert 'exactly one comment was posted' ([int]$state.posts -eq 1)
        Assert 'the posted comment carries the PR URL' (@($state.byTask.T1)[0].comment_text -match [regex]::Escape($prUrl))
        Assert 'the posted comment carries the parseable marker' (@($state.byTask.T1)[0].comment_text -match 'sdlc:note kind=context step=pr')

        $logDir = Join-Path $root '.sdlc/logs'
        $logged = $false
        if (Test-Path -LiteralPath $logDir) {
            $logged = @(Get-ChildItem -LiteralPath $logDir -Filter '*.jsonl' -ErrorAction SilentlyContinue |
                ForEach-Object { Get-Content -LiteralPath $_.FullName } |
                Where-Object { $_ -match 'clickup\.pr\.attach' }).Count -gt 0
        }
        Assert 'the attach was written to the flow log' $logged

        # Idempotent: a task that already mentions this URL is not re-commented.
        $r = Invoke-Attach -Root $root -Arguments $stubArgs.Clone()
        $state = Get-Content -LiteralPath $stubState -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert 'a second attach reports ALREADY' ($r.Json.status -eq 'ALREADY')
        Assert 'and issues no second comment' ([int]$state.posts -eq 1)

        # Verification is real: if the comment does not land, the attach fails.
        @{ byTask = @{}; posts = 0; dropPosts = $true } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $stubState -Encoding UTF8
        $root2 = New-TestRoot -Flow @{ clickupTaskId = 'T2' }
        $r = Invoke-Attach -Root $root2 -Arguments $stubArgs.Clone()
        Assert 'a post that does not stick reports FAILED' ($r.Json.status -eq 'FAILED')
        Assert 'and exits 1' ($r.Code -eq 1)

        # Multiple task ids (a release PR linking one task per item).
        @{ byTask = @{}; posts = 0; dropPosts = $false } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $stubState -Encoding UTF8
        $root3 = New-TestRoot -Flow @{ release_items = @(@{ task_id = 'R1' }) }
        $multiArgs = $stubArgs.Clone(); $multiArgs['TaskId'] = @('R1', 'R2')
        $r = Invoke-Attach -Root $root3 -Arguments $multiArgs
        $state = Get-Content -LiteralPath $stubState -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert 'explicit -TaskId list attaches to every task' ($r.Json.status -eq 'OK' -and [int]$state.posts -eq 2)

        # A single comma-joined -TaskId string is split, not treated as one id.
        @{ byTask = @{}; posts = 0; dropPosts = $false } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $stubState -Encoding UTF8
        $root4 = New-TestRoot -Flow @{ release_items = @(@{ task_id = 'R3' }) }
        $joinArgs = $stubArgs.Clone(); $joinArgs['TaskId'] = 'R3,R4'
        $r = Invoke-Attach -Root $root4 -Arguments $joinArgs
        $state = Get-Content -LiteralPath $stubState -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert 'a comma-joined -TaskId string is split into ids' ($r.Json.status -eq 'OK' -and [int]$state.posts -eq 2)
    } finally {
        try { $http.Stop(); $http.Close() } catch { }
        try { $runspace.Dispose() } catch { }
    }
}

} catch {
    $script:fail++
    $abortMessage = "$($_.Exception.GetType().Name): $($_.Exception.Message)"
    $frames = (@($_.ScriptStackTrace -split "`r?`n" | Where-Object { $_.Trim() }) | Select-Object -First 3) -join ' <- '
    $stubNote = ''
    if (Test-Path -LiteralPath $stubErrorPath) {
        $stubNote = " | stub API handler died first: $((Get-Content -LiteralPath $stubErrorPath -Raw).Trim())"
    }
    Write-Host "  ABORT  $abortMessage" -ForegroundColor Red
    [Console]::Error.WriteLine("ABORT - unhandled exception after $script:pass passed assertion(s): $abortMessage | $frames$stubNote")
} finally {
    if ($null -ne $prevToken) { $env:CLICKUP_TOKEN = $prevToken } else { Remove-Item Env:CLICKUP_TOKEN -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Results: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
exit 0
