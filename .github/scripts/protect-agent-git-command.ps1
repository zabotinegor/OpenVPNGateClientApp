$ErrorActionPreference = 'Stop'

$protected = @('main', 'dev', 'master', 'develop')
$protectedPattern = '(?:main|dev|master|develop)'
$gitPrefixPattern = '\bgit(?:\s+-C\s+\S+|\s+--git-dir(?:=\S+|\s+\S+)|\s+--work-tree(?:=\S+|\s+\S+)|\s+--no-pager|\s+--paginate|\s+--bare|\s+-c\s+\S+|\s+--exec-path(?:=\S+|\s+\S+)|\s+--namespace=\S+|\s+--no-replace-objects|\s+--no-optional-locks|\s+--literal-pathspecs|\s+--no-literal-pathspecs|\s+--glob-pathspecs|\s+--noglob-pathspecs|\s+--icase-pathspecs)*'
$payloadText = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($payloadText)) {
    Write-Output '{}'
    exit 0
}

$payload = $null
try {
    $payload = $payloadText | ConvertFrom-Json
}
catch {
    Write-Output '{}'
    exit 0
}
if ($null -eq $payload) {
    Write-Output '{}'
    exit 0
}
$toolInput = if ($null -ne $payload.tool_input) { $payload.tool_input } else { $payload.toolArgs }
if ($toolInput -is [string]) {
    $rawString = $toolInput
    try { $toolInput = $toolInput | ConvertFrom-Json }
    catch { $toolInput = $rawString }
}
$command = if ($toolInput -is [string]) { $toolInput } elseif ($null -ne $toolInput -and $null -ne $toolInput.command) { [string]$toolInput.command } else { '' }
$cwd = if (-not [string]::IsNullOrWhiteSpace([string]$payload.cwd)) { [string]$payload.cwd } else { (Get-Location).Path }

$normalized = ($command -replace '\s+', ' ').Trim()

# A command can act on a repository other than the session's own via
# 'git -C <path>' (--git-dir likewise). Judging it by the session cwd is
# wrong in both directions: it reads the wrong branch, and it never applies
# the target repo's '.copilottools-source' exemption - which is what denied
# a CopilotTools push issued from a client-repo session. Resolve the
# repository the command actually targets.
#
# --work-tree is NOT one of these redirecting options - see the $pathFlags
# comment in Get-GitTargetPath below for why it must stay out of this
# resolution entirely.
function Get-GitTargetPath {
    param([string]$NormalizedCommand, [string]$FallbackPath)

    # Only the options between 'git' and its subcommand redirect the repository.
    # A plain search would also hit subcommand flags that reuse the letter -
    # 'git commit -C HEAD~1' reuses a commit message, it does not change repo.
    # Comparisons are case-sensitive on purpose: -c (config) is not -C (path).
    #
    # '--work-tree' is deliberately NOT in $pathFlags. Git does not use
    # --work-tree to pick the repository: per git(1), it only "sets the path
    # to the working tree" for file operations. The repository (and therefore
    # the branch this guard cares about) still comes from the invocation cwd,
    # or from --git-dir when given. Treating --work-tree like -C let
    # 'git --work-tree=<unprotected dir> commit', run from a protected
    # checkout's cwd, get judged against the unprotected work-tree path while
    # actually committing to the protected repo - the guard would wave
    # through exactly the commit it exists to block. --work-tree still needs
    # to be recognized as a value-taking flag below (so its value is not
    # mistaken for the start of the next flag), it just must never set the
    # resolved target.
    $pathFlags = @('-C', '--git-dir')
    $valueFlags = @('-c', '-C', '--git-dir', '--work-tree', '--namespace', '--exec-path', '--super-prefix', '--config-env')

    # Each entry tracks not just the resolved path but which option supplied it
    # ('-C', '--git-dir', or 'cwd' when no flag was present, including when
    # --work-tree was the only flag present - it is parsed and skipped, but
    # never becomes the target). --git-dir names the '.git' metadata
    # directory itself - 'git -C <that path> rev-parse --show-toplevel' fails
    # there because a bare '.git' directory has no work tree, and treating
    # that failure as "unresolvable" previously discarded the target entirely
    # and fell back to evaluating the command's own cwd. The Kind lets the
    # resolver below query each option with matching Git semantics instead of
    # a uniform -C-style query.
    $targets = New-Object System.Collections.Generic.List[object]
    foreach ($segment in ($NormalizedCommand -split '[;&|]+')) {
        # Token pattern keeps a quoted run glued to its token, so both
        # '-C "C:/Copilot Tools"' and '--git-dir="C:/a b/.git"' survive.
        $tokens = @([regex]::Matches($segment.Trim(), '(?:[^\s"'']+|"[^"]*"|''[^'']*'')+') | ForEach-Object { $_.Value })
        if ($tokens.Count -eq 0 -or $tokens[0] -ne 'git') { continue }

        $target = $null
        $targetKind = 'cwd'
        # Per git(1): "Multiple -C options are cumulative on the effect of the
        # previous -C <path> option, or the current working directory". A
        # relative -C composes onto the PRECEDING -C, not onto the payload's
        # original cwd - 'git -C /workspace -C OpenVPNGateClientApp commit'
        # must resolve the second -C against /workspace, not against
        # $FallbackPath. Track that effective directory as options are parsed
        # left to right and only -C ever advances it (--git-dir/--work-tree
        # do not chdir, so a relative value on either still resolves against
        # whatever -C has established so far, but never becomes the base for
        # a later -C itself).
        $effectiveDir = $FallbackPath
        for ($i = 1; $i -lt $tokens.Count; $i++) {
            $token = $tokens[$i]
            if (-not $token.StartsWith('-')) { break }

            $name = $token
            $inlineValue = $null
            $eq = $token.IndexOf('=')
            if ($eq -gt 0) {
                $name = $token.Substring(0, $eq)
                $inlineValue = $token.Substring($eq + 1)
            }

            if ($pathFlags -ccontains $name) {
                $value = if ($null -ne $inlineValue) { $inlineValue }
                         elseif ($i + 1 -lt $tokens.Count) { $tokens[++$i] }
                         else { $null }
                if (-not [string]::IsNullOrWhiteSpace($value)) {
                    $clean = ($value -replace '["'']', '')
                    # A relative -C/--git-dir target is relative to the
                    # effective directory established so far by any preceding
                    # -C (or the command's own cwd, $FallbackPath, if none has
                    # been seen yet) - not to this hook subprocess's cwd
                    # (fixed to the repo root by protected-branches.json's
                    # "cwd": "."). An already-absolute target is left
                    # unchanged (GetFullPath still normalizes it).
                    if (-not [System.IO.Path]::IsPathRooted($clean)) {
                        if (-not [string]::IsNullOrWhiteSpace($effectiveDir)) {
                            $clean = [System.IO.Path]::GetFullPath((Join-Path $effectiveDir $clean))
                        }
                    }
                    else {
                        $clean = [System.IO.Path]::GetFullPath($clean)
                    }
                    $target = $clean
                    # Last -C/--git-dir flag wins, matching Git's own
                    # last-flag-wins option parsing. --work-tree never reaches
                    # here (it is not in $pathFlags), so it cannot overwrite
                    # a target that -C or --git-dir already set, and it never
                    # supplies one on its own.
                    $targetKind = $name
                    # Only -C itself chdir's; --git-dir merely names the
                    # metadata directory without moving the effective
                    # directory, so a later -C must not compose onto it.
                    if ($name -eq '-C') {
                        $effectiveDir = $clean
                    }
                }
            }
            elseif ($null -eq $inlineValue -and $valueFlags -ccontains $name) {
                $i++
            }
        }

        $targets.Add([pscustomobject]@{
            Path = $(if ($target) { $target } else { $FallbackPath })
            Kind = $(if ($target) { $targetKind } else { 'cwd' })
        })
    }

    # No git segment, or several different targets in one command line: fall
    # back to the session repo. Ambiguity must never widen what is allowed.
    $distinctPaths = @($targets | Select-Object -ExpandProperty Path -Unique)
    if ($distinctPaths.Count -eq 1) { return $targets[-1] }
    return [pscustomobject]@{ Path = $FallbackPath; Kind = 'cwd' }
}

function Resolve-GitRepoState {
    param([string]$Path, [string]$Kind = '-C')

    $root = ''
    $head = ''
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        if ($Kind -eq '--git-dir') {
            # --git-dir alone names the repository's metadata directory, not a
            # work tree, so 'rev-parse --show-toplevel' has nothing to report
            # there. Query with the matching '--git-dir=' option instead of
            # '-C': ref-only queries like 'branch --show-current' only read
            # HEAD and succeed regardless of a work tree. Derive Root the same
            # way Git itself does when --work-tree/core.worktree is absent -
            # the parent of a directory literally named '.git'. A target that
            # is not named '.git' is a bare repository with no working tree to
            # hold the '.copilottools-source' exemption marker in, so Root
            # stays empty on purpose (fail-safe: no exemption without a
            # resolvable working tree).
            $head = ((git --git-dir=$Path branch --show-current 2>$null) | Out-String).Trim().ToLowerInvariant()
            $gitDirName = Split-Path -Leaf $Path
            if ($gitDirName -eq '.git') {
                $root = Split-Path -Parent $Path
            }
        }
        else {
            # -C names an ordinary working-tree directory. ($Kind is never
            # '--work-tree' here - see the $pathFlags note above - so this
            # branch only ever runs for '-C' or the 'cwd' fallback.)
            $root = ((git -C $Path rev-parse --show-toplevel 2>$null) | Out-String).Trim()
            $head = ((git -C $Path branch --show-current 2>$null) | Out-String).Trim().ToLowerInvariant()
        }
    }
    finally {
        $ErrorActionPreference = $previousEap
    }

    return [pscustomobject]@{ Root = $root; Branch = $head }
}

$evalTarget = Get-GitTargetPath -NormalizedCommand $normalized -FallbackPath $cwd
$evalPath = $evalTarget.Path
$state = Resolve-GitRepoState -Path $evalPath -Kind $evalTarget.Kind

# An unresolvable target must not become an escape hatch. Branch is the signal
# that matters here (it drives every protected-branch check below); Root only
# gates the exemption lookup and is allowed to stay empty (see --git-dir case
# above). Fall back to the session repo, and keep evaluating, only when the
# target could not even tell us its branch.
if ([string]::IsNullOrWhiteSpace($state.Branch) -and $evalPath -ne $cwd) {
    $evalPath = $cwd
    $state = Resolve-GitRepoState -Path $evalPath -Kind '-C'
}

$repoRoot = $state.Root
$branch = $state.Branch

if (-not [string]::IsNullOrWhiteSpace($repoRoot) -and (Test-Path -LiteralPath (Join-Path $repoRoot '.copilottools-source'))) {
    Write-Output '{}'
    exit 0
}

$switchPattern = "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:switch|checkout)\s+(?:-\S+\s+)*(?!--)([^\s;&|]+)"

$reason = $null
if ($normalized -match '(?i)(^|[;&|]\s*)git\s+') {
    # Commit: evaluate branch state at each commit occurrence independently so a
    # switch AFTER a reset (but before the commit) is correctly accounted for.
    foreach ($cm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+commit\b")) {
        $eff = $branch
        foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
            if ($sm.Index -ge $cm.Index) { break }
            $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
            if (-not $t.StartsWith('-')) { $eff = $t }
        }
        if ($protected -contains $eff) {
            $reason = "Direct commit on protected branch '$eff' is forbidden."
            break
        }
    }

    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+push\b") {
        $pushMatch = [regex]::Match($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+push\b")
        $eff = $branch
        if ($pushMatch.Success) {
            foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
                if ($sm.Index -ge $pushMatch.Index) { break }
                $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
        }
        if ($normalized -match '(?i)(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$|[;&|])') {
            $reason = 'Force-push is forbidden in client repositories.'
        }
        elseif ($normalized -match '(?i)(?:^|\s)(?:--all|--branches|--mirror)(?:\s|$|[;&|])') {
            $reason = 'Bulk push (--all/--branches/--mirror) may update protected refs and is forbidden.'
        }
        else {
            # Release-flow archive exception: permit the two dev-branch mutations that occur in
            # the archive step after a release squash merge (step 5 of release-flow-orchestrator).
            #   1. git push origin --delete dev   (remove old dev after pushing the archive branch)
            #   2. git push [-u] origin dev        (push new dev recreated from merged main)
            # Authorization condition for (1): origin/archive/archive-dev-* exists (archive was pushed).
            # Authorization condition for (2): same + local dev SHA == origin/main SHA.
            $allowReleaseArchivePush = $false
            # Use only explicit remote-target patterns — NOT $eff -eq 'dev'. When the caller
            # is checked out on dev, $eff -eq 'dev' would set $isDevPush for ANY push command
            # (e.g. `git push origin HEAD:main`), letting archive+SHA grant $allowReleaseArchivePush
            # and skip all protected-branch checks.
            $isDevPush   = $normalized -match "(?i)\b(?:origin|upstream)\s+(?:-u\s+)?dev(?![-\w/.])"
            $isDevDelete = $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+dev(?![-\w/.])"
            # Guard: if the same command mentions any other protected branch name anywhere
            # (bare token, +token, :token, or refs/heads/ form), the exception does not
            # apply — e.g. `git push origin dev main` or `git push origin --delete dev main`
            # pass extra refspecs positionally, so position-anchored patterns are not enough.
            # False positives only deny the narrow exception (fail-safe: push stays blocked).
            if (($isDevPush -or $isDevDelete) -and
                $normalized -match '(?i)(?:^|[\s+:])(?:refs/heads/)?(?:main|master|develop)(?![-\w/.])') {
                $isDevPush = $false; $isDevDelete = $false
            }
            if ($isDevPush -or $isDevDelete) {
                $previousEap2 = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $remotes = (git -C $evalPath branch -r 2>$null) -join "`n"
                    if ($remotes -match 'archive/archive-dev-') {
                        if ($isDevDelete) {
                            $allowReleaseArchivePush = $true
                        } elseif ($isDevPush) {
                            $devSha  = ((git -C $evalPath rev-parse dev        2>$null) | Out-String).Trim()
                            $mainSha = ((git -C $evalPath rev-parse origin/main 2>$null) | Out-String).Trim()
                            if ($devSha -and $mainSha -and $devSha -eq $mainSha) { $allowReleaseArchivePush = $true }
                        }
                    }
                }
                finally { $ErrorActionPreference = $previousEap2 }
            }
            if (-not $allowReleaseArchivePush) {
                if ($protected -contains $eff -and $normalized -notmatch '(?i)\bHEAD:') {
                    $reason = "Direct push from protected branch '$eff' is forbidden."
                }
                elseif (
                    $normalized -match "(?i)\b(?:origin|upstream)\s+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\b(?:origin|upstream)\s+\+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s)\+(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)\brefs/heads/$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s)(?:--delete|-d)\s+$protectedPattern(?![-\w/.])" -or
                    $normalized -match "(?i)(?:^|\s):$protectedPattern(?![-\w/.])"
                ) {
                    $reason = 'Direct push, deletion, or recreation of a protected branch is forbidden.'
                }
            }
        }
    }

    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+update-ref\b[^\r\n]*refs/heads/$protectedPattern(?![-\w/.])") {
        $reason = 'Direct protected branch ref mutation is forbidden.'
    }

    # Reset: evaluate branch state at each reset occurrence independently.
    # Scope flag check to the argument slice of each reset so --hard from a later
    # reset does not falsely trigger on an earlier soft reset. Strip quotes from
    # switch targets so git switch "main" does not bypass the protected check.
    if (-not $reason) {
        foreach ($rm in [regex]::Matches($normalized, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+reset\b")) {
            $eff = $branch
            foreach ($sm in [regex]::Matches($normalized, $switchPattern)) {
                if ($sm.Index -ge $rm.Index) { break }
                $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                if (-not $t.StartsWith('-')) { $eff = $t }
            }
            $tail = $normalized.Substring($rm.Index + $rm.Length)
            $argSeg = ($tail -split '[;&|]+')[0]
            if ($protected -contains $eff -and
                    $argSeg -match '(?i)(?:^|\s)(?:--hard|--keep|--merge)(?:\s|$)') {
                $reason = "Hard reset on protected branch '$eff' is forbidden."
                break
            }
        }
    }

    # Branch -f: iterate each branch command independently so a later forced
    # protected branch is not masked by an earlier harmless branch command.
    if (-not $reason -and $normalized -match "(?i)$gitPrefixPattern\s+branch\b") {
        foreach ($bm in [regex]::Matches($normalized, "(?i)$gitPrefixPattern\s+branch\b")) {
            $seg = ($normalized.Substring($bm.Index) -split '[;&|]+')[0]
            if ($seg -match '(?i)(?:^|\s)(?:-f|--force)(?:\s|$|[;&|])') {
                if ($seg -match "(?i)$gitPrefixPattern\s+branch\b((?:\s+-\S+)*)\s+([^\s-]\S*)") {
                    $branchTarget = ($Matches[2] -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                    if ($protected -contains $branchTarget) {
                        $reason = 'Forcing a protected branch pointer is forbidden.'
                        break
                    }
                }
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($reason)) {
    Write-Output '{}'
    exit 0
}

[ordered]@{
    hookSpecificOutput = [ordered]@{
        hookEventName = 'PreToolUse'
        permissionDecision = 'deny'
        permissionDecisionReason = $reason
    }
} | ConvertTo-Json -Depth 5 -Compress
