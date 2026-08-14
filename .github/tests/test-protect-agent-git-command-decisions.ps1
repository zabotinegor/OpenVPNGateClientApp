#!/usr/bin/env pwsh
# Decision-matrix regression tests for protect-agent-git-command.ps1: commit,
# push, reset, and branch-force detection across push exceptions, reset-flag
# scoping, branch -f start-point disambiguation, quoted branch names, global
# option prefixes, and bulk/refspec force-push variants.
#
# This is the committed regression suite for the guard's decision logic.
# .github/scripts/test-git-guard.ps1 covers the -C/--git-dir repository
# targeting and hook-wiring side of the same guard, but it is gitignored -
# mirrored locally from CopilotTools via the agent-sync skill - so it is not
# guaranteed present for every contributor or CI run. These scenarios were
# ported from the retired .github/tests/test_protect_agent_git_command.py,
# which exercised protect-agent-git-command.py directly; that implementation
# was removed in favor of this PowerShell guard, so the scenarios needed a
# new committed home testing the guard that actually ships.
#
# Fixtures are a single throwaway git repo in temp with several branches -
# these tests are about command-string parsing and branch-state decisions,
# not repository targeting, so one repo checked out to different branches
# per scenario is enough. No network, no real .sdlc state.
# Run: pwsh -File test-protect-agent-git-command-decisions.ps1 [-WorkingDirectory <repo>]
# Exits 0 on all pass, 1 on any failure.

param([string]$WorkingDirectory = (Get-Location).Path)

$ErrorActionPreference = 'SilentlyContinue'
$pass = 0; $fail = 0

function Assert {
    param([string]$Label, [bool]$Condition)
    if ($Condition) { Write-Host "  PASS  $Label" -ForegroundColor Green; $script:pass++ }
    else            { Write-Host "  FAIL  $Label" -ForegroundColor Red;   $script:fail++ }
}

function Resolve-RepoRoot {
    param([string]$StartPath)
    $r = (Resolve-Path -LiteralPath $StartPath -ErrorAction SilentlyContinue).Path
    if (-not $r) { return $StartPath }
    $g = git -C $r rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -eq 0 -and $g) { return (Resolve-Path $g.Trim()).Path }
    return $r
}

$repoRoot = Resolve-RepoRoot $WorkingDirectory
$psHook = Join-Path $repoRoot '.github' 'scripts' 'protect-agent-git-command.ps1'
if (-not (Test-Path -LiteralPath $psHook)) {
    Write-Host "FAIL  protect-agent-git-command.ps1 not found at $psHook" -ForegroundColor Red
    exit 1
}

$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("git-guard-decisions-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null

$repo = Join-Path $sandbox 'Repo'
New-Item -ItemType Directory -Path $repo -Force | Out-Null
git -c init.defaultBranch=main init $repo 2>$null | Out-Null
Set-Content -LiteralPath (Join-Path $repo 'README.md') -Value 'fixture' -Encoding utf8
git -C $repo add -A 2>$null | Out-Null
git -C $repo -c user.email='t@example.com' -c user.name='T' commit -m 'fixture' 2>$null | Out-Null
foreach ($b in @('feature/x', 'dev', 'master', 'develop')) {
    git -C $repo branch $b 2>$null | Out-Null
}
git -C $repo checkout main 2>$null | Out-Null

function Invoke-Guard {
    param([string]$Command, [string]$Branch = 'main')
    git -C $repo checkout -q $Branch 2>$null
    $payload = [ordered]@{
        tool_name  = 'PowerShell'
        cwd        = $repo
        tool_input = [ordered]@{ command = $Command }
    } | ConvertTo-Json -Compress -Depth 5
    $out = $payload | pwsh -NoProfile -NonInteractive -File $psHook 2>&1
    return (($out | Out-String).Trim())
}

function Assert-Denied {
    param([string]$Label, [string]$Command, [string]$Branch = 'main')
    $out = Invoke-Guard -Command $Command -Branch $Branch
    Assert $Label ($out -match '"permissionDecision"\s*:\s*"deny"')
}

function Assert-Allowed {
    param([string]$Label, [string]$Command, [string]$Branch = 'main')
    $out = Invoke-Guard -Command $Command -Branch $Branch
    Assert $Label ($out -notmatch '"permissionDecision"\s*:\s*"deny"')
}

Write-Host "== Push: branch names that merely start with a protected prefix are allowed =="

Assert-Allowed "push to dev/my-feature is allowed"      "git push origin dev/my-feature"  'feature/x'
Assert-Allowed "push to main-feature is allowed"        "git push origin main-feature"    'feature/x'
Assert-Allowed "push to develop/issue-1 is allowed"     "git push origin develop/issue-1" 'feature/x'
Assert-Allowed "push to master.backup is allowed"       "git push origin master.backup"   'feature/x'

Write-Host "== Push: exact protected branch names and their ref forms are blocked =="

Assert-Denied  "push to origin dev is blocked"          "git push origin dev"    'feature/x'
Assert-Denied  "push to origin main is blocked"         "git push origin main"   'feature/x'
Assert-Denied  "push to origin master is blocked"       "git push origin master" 'feature/x'
Assert-Allowed "push to refs/heads/dev/sub is allowed"  "git push origin refs/heads/dev/sub" 'feature/x'
Assert-Denied  "push to refs/heads/main is blocked"     "git push origin refs/heads/main"    'feature/x'
Assert-Allowed "delete of dev/old is allowed"           "git push origin --delete dev/old" 'feature/x'
Assert-Denied  "delete of dev is blocked"               "git push origin --delete dev"     'feature/x'
Assert-Denied  "delete of main via push is blocked"     "git push origin --delete main"    'feature/x'

Write-Host "== Push: bulk and force-refspec forms cannot bypass the guard =="

Assert-Denied  "push origin +main (force refspec) is blocked" "git push origin +main" 'feature/x'
Assert-Denied  "push origin +dev (force refspec) is blocked"  "git push origin +dev"  'feature/x'
Assert-Denied  "push --all is blocked"                  "git push --all origin"      'feature/x'
Assert-Denied  "push --mirror is blocked"               "git push --mirror origin"   'feature/x'
Assert-Denied  "push --branches is blocked"             "git push --branches origin" 'feature/x'
Assert-Allowed "push origin +feature/my-branch is allowed" "git push origin +feature/my-branch" 'feature/x'

Write-Host "== Reset: --hard/--keep/--merge on a protected branch are blocked; --soft/mixed are not =="

Assert-Denied  "reset --hard on main is blocked"        "git reset --hard HEAD^"  'main'
Assert-Denied  "reset --keep on main is blocked"        "git reset --keep HEAD^"  'main'
Assert-Denied  "reset --merge on dev is blocked"        "git reset --merge HEAD^" 'dev'
Assert-Allowed "reset --hard on feature/x is allowed"   "git reset --hard HEAD^"  'feature/x'
Assert-Allowed "reset --soft on main is allowed"        "git reset --soft HEAD^"  'main'
Assert-Allowed "reset (mixed, no flag) on main is allowed" "git reset HEAD^"      'main'
Assert-Allowed "a soft reset on main, then switching to feature/x before a hard reset, is allowed (the --hard flag is not misattributed to the earlier soft reset)" `
    "git reset --soft HEAD^; git switch feature/x; git reset --hard HEAD^" 'main'

Write-Host "== Branch -f: forcing a protected branch pointer is blocked; start-points are not the target =="

Assert-Denied  "branch -f main is blocked"              "git branch -f main HEAD^"        'feature/x'
Assert-Denied  "branch --force dev is blocked"          "git branch --force dev HEAD~1"   'feature/x'
Assert-Denied  "branch -f master is blocked"            "git branch -f master abc123"     'feature/x'
Assert-Allowed "branch -f feature/new is allowed"       "git branch -f feature/new HEAD^" 'main'
Assert-Allowed "branch -f dev/sub is allowed (prefix only, not the protected name itself)" `
    "git branch -f dev/sub HEAD^" 'feature/x'
Assert-Allowed "branch create without -f is allowed"    "git branch new-branch" 'main'
Assert-Allowed "branch -f feature/new with a protected start-point (main) is allowed - main is the start-point, not the branch being forced" `
    "git branch -f feature/new main" 'feature/x'
Assert-Denied  "branch -f main with a start-point is blocked - main IS the branch being forced" `
    "git branch -f main HEAD^" 'feature/x'
Assert-Allowed "branch -v -f feature/x with a main start-point (multi-flag) is allowed" `
    "git branch -v -f feature/x main" 'feature/x'
Assert-Denied  "a harmless branch command followed by a forced main is blocked (the later occurrence is not masked by the first)" `
    "git branch harmless; git branch -f main HEAD^" 'feature/x'

Write-Host "== Quoted branch names cannot bypass branch-force or switch checks =="

Assert-Denied  'branch -f "main" (double-quoted) is blocked' 'git branch -f "main" HEAD^' 'feature/x'
Assert-Denied  "branch -f 'dev' (single-quoted) is blocked"  "git branch -f 'dev' HEAD^"  'feature/x'
Assert-Denied  'switch "main" then commit is blocked'        'git switch "main" && git commit -m bad' 'feature/x'
Assert-Allowed 'switch "feature/y" then commit is allowed'   'git switch "feature/y" && git commit -m ok' 'feature/x'

Write-Host "== Mutation boundary: a switch after a mutation is tracked at the next commit =="

Assert-Allowed "branch create, switch, then commit on the new branch is allowed" `
    "git branch feature/x2 && git switch feature/x2 && git commit -m x" 'main'
Assert-Denied  "branch create without switching, then commit, is still on main and is blocked" `
    "git branch feature/x2 && git commit -m x" 'main'
Assert-Denied  "switch to feature, reset, switch back to main, then commit is blocked (the later switch is not ignored)" `
    "git switch feature/x && git reset --hard HEAD^ && git switch main && git commit -m bad" 'main'

Write-Host "== Global git options (--no-pager etc.) cannot shield a mutation from detection =="

Assert-Denied  "git --no-pager commit on main is blocked"      "git --no-pager commit -m bad"   'main'
Assert-Denied  "git --no-pager push origin main is blocked"    "git --no-pager push origin main" 'feature/x'
Assert-Denied  "git --paginate commit on main is blocked"      "git --paginate commit -m bad"   'main'
Assert-Denied  "git --bare push origin dev is blocked"         "git --bare push origin dev"     'feature/x'
Assert-Allowed "git --no-pager commit on feature/x is allowed" "git --no-pager commit -m ok"    'feature/x'

Write-Host "== A quoted -C/--git-dir path with an embedded space cannot shield a mutation from detection (round 7) =="

# Round 7 P1: $gitPrefixPattern used a bare \S+ for -C/--git-dir/etc. values,
# which stops at the first embedded space. 'git -C "<path with a space>"
# commit' would then only consume the quoted value up to that space, leaving
# the closing quote plus 'commit' unmatched by the prefix pattern - so the
# mutation regexes built from $gitPrefixPattern (commit/push/reset/branch
# -f/update-ref) never lined up with the real 'commit' token and silently
# returned an allow even though the resolved checkout (via Get-GitTargetPath,
# whose tokenizer already handled quoted paths correctly) is genuinely on a
# protected branch. Fixture path deliberately contains a space so a
# regression here reproduces the exact bug shape.
$spacedRepoRoot = Join-Path $sandbox 'Protected Repo'
New-Item -ItemType Directory -Path $spacedRepoRoot -Force | Out-Null
git -c init.defaultBranch=main init $spacedRepoRoot 2>$null | Out-Null
Set-Content -LiteralPath (Join-Path $spacedRepoRoot 'README.md') -Value 'fixture' -Encoding utf8
git -C $spacedRepoRoot add -A 2>$null | Out-Null
git -C $spacedRepoRoot -c user.email='t@example.com' -c user.name='T' commit -m 'fixture' 2>$null | Out-Null
git -C $spacedRepoRoot checkout main 2>$null | Out-Null

Assert-Denied  "commit via a double-quoted -C path with a space, targeting a repo on main, is blocked" `
    "git -C `"$spacedRepoRoot`" commit -m bad" 'feature/x'
Assert-Denied  "push to origin main via a double-quoted -C path with a space is blocked" `
    "git -C `"$spacedRepoRoot`" push origin main" 'feature/x'
Assert-Denied  "hard reset via a double-quoted --git-dir path with a space, targeting a repo on main, is blocked" `
    "git --git-dir=`"$spacedRepoRoot/.git`" reset --hard HEAD^" 'feature/x'
Assert-Denied  "branch -f main via a double-quoted -C path with a space is blocked" `
    "git -C `"$spacedRepoRoot`" branch -f main HEAD^" 'feature/x'

git -C $spacedRepoRoot checkout -b feature/spaced 2>$null | Out-Null
Assert-Allowed "commit via a double-quoted -C path with a space, targeting a repo on a non-protected branch, is allowed" `
    "git -C `"$spacedRepoRoot`" commit -m ok" 'main'

Write-Host ""
Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
if ($fail -eq 0) { Write-Host "Results: $pass passed, 0 failed" -ForegroundColor Green; exit 0 }
Write-Host "Results: $pass passed, $fail failed" -ForegroundColor Red
exit 1
