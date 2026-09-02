#Requires -Version 7
<#
.SYNOPSIS
    Attach a PR URL to the linked ClickUp task as a comment - then verify it
    landed, in the same call.

.DESCRIPTION
    A board status push has push-clickup-status.ps1; the PR link had nothing. On
    PR creation the URL went only into the gitignored '.sdlc/status.json', so a
    client reading the board had no path from the task to its PR - and because a
    task id cannot go in the PR body (AGENTS.md), ClickUp's native GitHub
    auto-linking never fired either. This is the mechanism: it posts a
    'PR: <url>' comment on the linked task over REST (off the MCP rate-limit
    surface, like every other mechanical ClickUp write), then reads the comments
    back to confirm the URL is there.

    Idempotent: a task that already carries a comment mentioning this exact URL
    is reported ALREADY and no second comment is posted, so re-running after a
    resume does not litter the task with duplicates.

.PARAMETER PrUrl
    The pull-request URL to attach.

.PARAMETER FlowId
    Flow the PR belongs to. Used to resolve the task from '.sdlc/status.json'
    when -TaskId is not given, and to key the flow-log entry.

.PARAMETER TaskId
    Attach to these task ids instead of the flow's recorded 'clickupTaskId'.
    Repeatable - a release PR links to one task per release item.

.PARAMETER NoVerify
    Skip the read-back. Only for tests; an attach you did not verify is an
    attach you cannot claim happened.

.OUTPUTS
    One compact JSON object on stdout.

    Exit codes:
      0  OK | ALREADY | SKIPPED   the link is on the task, or the repo is in
                                  local mode / this flow shape carries no task
                                  pointer
      1  FAILED                   the comment was rejected, the task could not
                                  be read, the flow has no task to attach to,
                                  or the read-back did not find the URL
      2  UNAVAILABLE              no REST token configured. Not a failure: attach
                                  via the ClickUp MCP create_task_comment tool
                                  instead, then read the comments back to verify.
      3  RATE_LIMITED             ClickUp REST is throttled; retry within the
                                  minute. Do not proceed as if attached.

.EXAMPLE
    .\.github\scripts\attach-pr-to-clickup.ps1 -FlowId "feature/us-05::US-05" -PrUrl "https://github.com/org/repo/pull/117"

.EXAMPLE
    .\.github\scripts\attach-pr-to-clickup.ps1 -FlowId "release/12.09.2026" -PrUrl $url -TaskId 86cbaa111,86cbaa222
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PrUrl,
    [string]$FlowId = '',
    [string[]]$TaskId = @(),
    [switch]$NoVerify,
    [string]$Token = '',
    [string]$Root = '',
    # Overridable so the tests can drive the whole post -> verify path against a
    # local stub instead of the live workspace.
    [string]$ApiBase = 'https://api.clickup.com/api/v2'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'clickup-lib.ps1')

function Write-Result {
    param([string]$Status, [string]$Reason, [hashtable]$Extra = @{})
    $out = [ordered]@{ status = $Status; flowId = $FlowId; prUrl = $PrUrl; reason = $Reason }
    foreach ($k in $Extra.Keys) { $out[$k] = $Extra[$k] }
    [pscustomobject]$out | ConvertTo-Json -Depth 6 -Compress
}

function Get-Prop {
    # Strict-mode-safe property read: '' when the property is absent.
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $p = $Object.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

$repoRoot = Resolve-ClickUpRoot -StartPath $Root

$config = Get-ClickUpConfig -RepoRoot $repoRoot
if ($null -eq $config) {
    if (Test-Path -LiteralPath (Get-ClickUpConfigPath -RepoRoot $repoRoot)) {
        # A config that exists but will not parse is an error, not local mode.
        Write-Result -Status 'FAILED' -Reason '.sdlc/clickup-config.json exists but could not be parsed'
        exit 1
    }
    Write-Result -Status 'SKIPPED' -Reason 'local-mode: no .sdlc/clickup-config.json'
    exit 0
}

$flow = if ($FlowId) { Get-ClickUpFlow -RepoRoot $repoRoot -FlowId $FlowId } else { $null }

$flowTaskId = [string](Get-Prop -Object $flow -Name 'clickupTaskId')

# Accept both '-TaskId a,b' (array) and '-TaskId "a,b"' (one comma-joined string).
$targetTaskIds = @($TaskId | ForEach-Object { $_ -split ',' } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
if ($targetTaskIds.Count -eq 0 -and -not [string]::IsNullOrWhiteSpace($flowTaskId)) {
    $targetTaskIds = @($flowTaskId)
}
if ($targetTaskIds.Count -eq 0) {
    # A release flow legitimately has no flow-level pointer: it drives many
    # items through one flow and links each with -TaskId. Fail only for a
    # feature/bug flow, where a missing pointer means the board silently loses
    # the PR link.
    if ($flow -and $null -ne $flow.PSObject.Properties['release_items']) {
        Write-Result -Status 'SKIPPED' -Reason 'release flow: items carry no flow-level pointer and are linked individually with -TaskId'
        exit 0
    }
    Write-Result -Status 'FAILED' -Reason 'no ClickUp task to attach to (flow has no clickupTaskId and no -TaskId given)'
    exit 1
}

$resolvedToken = Get-ClickUpToken -Token $Token -RepoRoot $repoRoot
if ([string]::IsNullOrWhiteSpace($resolvedToken)) {
    Write-Result -Status 'UNAVAILABLE' `
        -Reason 'no ClickUp REST token (env CLICKUP_TOKEN or .sdlc/clickup-token) - attach via the ClickUp MCP create_task_comment tool instead, then read the comments back to verify' `
        -Extra @{ taskIds = $targetTaskIds }
    exit 2
}

function Get-TaskComments {
    param([string]$Id)
    $resp = Invoke-ClickUpApi -Method GET -Path "/task/$Id/comment" -Token $resolvedToken -MaxRetries 2 -ApiBase $ApiBase
    return @($resp.comments)
}

function Test-UrlPresent {
    param($Comments)
    foreach ($c in @($Comments)) {
        if ([string]$c.comment_text -and ([string]$c.comment_text).Contains($PrUrl)) { return $true }
    }
    return $false
}

$utc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$marker = "<!-- sdlc:note kind=context step=pr"
if ($FlowId) { $marker += " flow=$FlowId" }
$marker += " utc=$utc -->"
$commentBody = "PR: $PrUrl`n`n$marker"

$results = @()
$anyPosted = $false

foreach ($id in $targetTaskIds) {
    try {
        $before = Get-TaskComments -Id $id
    } catch {
        if (Test-ClickUpRateLimited -ErrorRecord $_) {
            Write-Result -Status 'RATE_LIMITED' -Reason "ClickUp REST is throttled: $($_.Exception.Message)" -Extra @{ taskId = $id; results = $results }
            exit 3
        }
        Write-Result -Status 'FAILED' -Reason "could not read comments for $id : $($_.Exception.Message)" -Extra @{ taskId = $id; results = $results }
        exit 1
    }

    if (Test-UrlPresent -Comments $before) {
        $results += [ordered]@{ taskId = $id; result = 'ALREADY' }
        continue
    }

    try {
        $null = Invoke-ClickUpApi -Method POST -Path "/task/$id/comment" -Token $resolvedToken -MaxRetries 3 -ApiBase $ApiBase -Body @{
            comment_text = $commentBody
            notify_all   = $false
        }
    } catch {
        if (Test-ClickUpRateLimited -ErrorRecord $_) {
            Write-Result -Status 'RATE_LIMITED' -Reason "ClickUp REST is throttled: $($_.Exception.Message)" -Extra @{ taskId = $id; results = $results }
            exit 3
        }
        Write-Result -Status 'FAILED' -Reason "comment POST rejected for $id : $($_.Exception.Message)" -Extra @{ taskId = $id; results = $results }
        exit 1
    }
    $anyPosted = $true

    if (-not $NoVerify) {
        $found = $false
        try {
            $found = Test-UrlPresent -Comments (Get-TaskComments -Id $id)
        } catch {
            # A read that failed is not a verified attach.
            Write-Result -Status 'FAILED' -Reason "posted the comment on $id but could not read it back: $($_.Exception.Message)" -Extra @{ taskId = $id; results = $results }
            exit 1
        }
        if (-not $found) {
            Write-Result -Status 'FAILED' -Reason "posted the comment on $id but the read-back did not find the PR URL" -Extra @{ taskId = $id; results = $results }
            exit 1
        }
    }

    $results += [ordered]@{ taskId = $id; result = 'OK' }
}

# Log after the fact, non-fatal - the attach already landed and verified.
if ($FlowId) {
    $logScript = Join-Path $PSScriptRoot 'write-flow-log.ps1'
    if (Test-Path -LiteralPath $logScript) {
        try {
            & $logScript -FlowId $FlowId -Event 'clickup.pr.attach' -Step 'pr' `
                -Status ($(if ($anyPosted) { 'OK' } else { 'ALREADY' })) `
                -Detail ("tasks=" + ($targetTaskIds -join ',') + " url=$PrUrl") -Root $repoRoot | Out-Null
        } catch { }
    }
}

$overall = if ($anyPosted) { 'OK' } else { 'ALREADY' }
$reason = if ($anyPosted) { "attached 'PR: <url>' comment and verified it" } else { "PR link already present on every task; nothing posted" }
Write-Result -Status $overall -Reason $reason -Extra @{ results = $results }
exit 0
