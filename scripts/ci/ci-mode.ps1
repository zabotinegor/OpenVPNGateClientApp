<#
.SYNOPSIS
  Switches the active Android CI/release provider between GitHub Actions and Azure DevOps Pipelines.

.DESCRIPTION
  One GitHub repository variable is the source of truth ('github' or 'azure'; unset means github):
    CI_PROVIDER   controls BOTH pull request CI and the dev/main/tag release pipelines.
  The script also enables or disables the two Azure pipeline definitions (build definition queueStatus) so the
  inactive provider can neither run a duplicate pipeline nor allocate a hosted agent:
    AZDO_PR_CI_PIPELINE_ID       the PR CI pipeline (azure-pipelines.yml)
    AZDO_RELEASE_PIPELINE_ID     the release pipeline (azure-release.yml: dev, main, v* tags)

  Ordering keeps provider transitions event-safe while CI_PROVIDER_SWITCHING is held:
    azure : enable both Azure pipelines, then set CI_PROVIDER=azure
    github: set CI_PROVIDER=github, then disable both Azure pipelines
  Prior state is captured before anything is changed; if any step fails, the steps already applied are rolled back
  so a half-switched state is not left behind.

  Release safety. A switch does not stop a release that is already running, and the providers' serialization
  (GitHub concurrency group, Azure environment lock) is provider-local. The switch therefore refuses while the
  provider being switched AWAY FROM still has a queued or running RELEASE run, re-checked immediately before every
  mutating call; a state that cannot be read is refused the same way (fail-safe). PR CI builds are deliberately not
  checked: they are short-lived and publish nothing.

  CI_PROVIDER_SWITCHING is the switch lock. It is acquired atomically with a create-only repository-variable POST
  (absence means unlocked; a present variable holds the owning invocation's token) and deleted in `finally`. Both
  providers' guards wait for it to disappear and then read CI_PROVIDER live. A hard process kill can leave it set;
  recover with `gh variable delete CI_PROVIDER_SWITCHING` after confirming no switch is active.

  Destination replay. After a real provider change, and before the lock is released, the switch queues one release
  run per release branch (dev, main) on the destination provider, pinned to the branch tip read at that moment. A push
  evaluated by neither provider during the hand-off is therefore never lost. The replay is stale-safe: android_ci.py
  detect-changes re-reads the live branch tip when the run starts and skips cleanly when the pinned commit is no longer
  the tip, and replay change detection compares against the latest release tag so an unchanged branch releases nothing.
  Tag pushes cannot be replayed automatically: re-push the tag, or queue azure-release.yml manually for it.

  Configuration (names only; values are never stored in the repo):
    repo variables : AZDO_ORG, AZDO_PROJECT, AZDO_PR_CI_PIPELINE_ID, AZDO_RELEASE_PIPELINE_ID, or same-named
                     environment variables
    secret / env   : AZDO_PAT (Azure DevOps PAT, Build read+execute), GH_TOKEN or `gh auth login` with Actions
                     read/write and repository Variables read/write (in CI this is the CI_SWITCH_PAT secret, a
                     dedicated credential that pull-request-controlled code never receives).

.PARAMETER Mode
  github | azure | status

.PARAMETER DryRun
  Print the planned actions and make no changes (no API calls).

.PARAMETER Repository
  owner/name of the GitHub repository. Defaults to the current gh repository.

.PARAMETER Force
  Switch even though the provider being left still has an active release, or its state could not be read. Only this
  one check is bypassed; validation, ordering, the lock and rollback are unchanged. It prints a warning.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('github', 'azure', 'status')]
    [string]$Mode = 'status',
    [switch]$DryRun,
    [string]$Repository,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ProviderVariable = 'CI_PROVIDER'
$script:SwitchingVariable = 'CI_PROVIDER_SWITCHING'
$script:AzurePipelines = @(
    [pscustomobject]@{ Label = 'PR CI'; PipelineIdName = 'AZDO_PR_CI_PIPELINE_ID' },
    [pscustomobject]@{ Label = 'release'; PipelineIdName = 'AZDO_RELEASE_PIPELINE_ID' }
)
# Release automation only (PR CI is intentionally absent, see .DESCRIPTION).
$script:GitHubReleaseWorkflows = @('release-by-dev.yml', 'release-by-main.yml', 'release-by-tag.yml')
# Runs held by the release-build-number concurrency group are 'pending'; include every non-completed state.
$script:GitHubActiveRunStatuses = @('in_progress', 'queued', 'pending', 'waiting', 'requested')
$script:AzureActiveBuildStatuses = 'inProgress,notStarted,cancelling'
# Release branches replayed on the destination provider: branch -> GitHub workflow file.
$script:ReplayBranches = @(
    [pscustomobject]@{ Branch = 'dev'; Workflow = 'release-by-dev.yml' },
    [pscustomobject]@{ Branch = 'main'; Workflow = 'release-by-main.yml' }
)

function Get-CiModePlan {
    # Pure function: ordered list of actions for a target mode.
    param([Parameter(Mandatory)][ValidateSet('github', 'azure')][string]$Mode)

    $variable = [pscustomobject]@{ Kind = 'SetGitHubVariable'; Name = $script:ProviderVariable; Value = $Mode }
    $state = if ($Mode -eq 'azure') { 'enabled' } else { 'disabled' }
    $pipelines = @(foreach ($p in $script:AzurePipelines) {
            [pscustomobject]@{ Kind = 'SetAzurePipeline'; Label = $p.Label; Name = $p.PipelineIdName; Value = $state }
        })

    # Make the destination provider capable of receiving events before CI_PROVIDER flips. Guards wait on
    # CI_PROVIDER_SWITCHING, so enabling the destination early cannot release early.
    if ($Mode -eq 'azure') { return @($pipelines + @($variable)) }
    return @(@($variable) + $pipelines)
}

function Get-AzureConfig {
    param([string]$Repository)
    $ghArgs = @()
    if ($Repository) { $ghArgs = @('--repo', $Repository) }
    $names = @('AZDO_ORG', 'AZDO_PROJECT') + @($script:AzurePipelines | ForEach-Object { $_.PipelineIdName })
    $cfg = @{}
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ([string]::IsNullOrWhiteSpace($value)) {
            $value = (& gh variable get $name @ghArgs 2>$null)
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Missing configuration '$name' (set the repository variable or the environment variable)."
        }
        $cfg[$name] = $value.Trim()
    }
    if ([string]::IsNullOrWhiteSpace($env:AZDO_PAT)) {
        throw 'Missing AZDO_PAT environment variable (Azure DevOps PAT with Build read and execute scope).'
    }
    return $cfg
}

function Get-AzdoAuthHeader {
    $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes(":$($env:AZDO_PAT)"))
    return @{ Authorization = "Basic $pair" }
}

function Get-AzdoDefinitionUri {
    param([hashtable]$Config, [Parameter(Mandatory)][string]$PipelineIdName)
    return "https://dev.azure.com/$($Config.AZDO_ORG)/$($Config.AZDO_PROJECT)/_apis/build/definitions/$($Config[$PipelineIdName])?api-version=7.1"
}

function Get-AzdoDefinitionState {
    param([hashtable]$Config, [Parameter(Mandatory)][string]$PipelineIdName)
    $def = Invoke-RestMethod -Method Get -Uri (Get-AzdoDefinitionUri -Config $Config -PipelineIdName $PipelineIdName) -Headers (Get-AzdoAuthHeader)
    return [string]$def.queueStatus
}

function Invoke-AzdoDefinitionUpdate {
    param([hashtable]$Config, [Parameter(Mandatory)][string]$PipelineIdName, [ValidateSet('enabled', 'disabled', 'paused')][string]$State)
    $uri = Get-AzdoDefinitionUri -Config $Config -PipelineIdName $PipelineIdName
    $headers = Get-AzdoAuthHeader
    $def = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
    if ($def.queueStatus -eq $State) {
        Write-Host "Azure pipeline ($PipelineIdName) already $State."
        return
    }
    $def.queueStatus = $State
    Invoke-RestMethod -Method Put -Uri $uri -Headers $headers -ContentType 'application/json' `
        -Body ($def | ConvertTo-Json -Depth 50) | Out-Null
    Write-Host "Azure pipeline ($PipelineIdName) set to $State."
}

function Get-GitHubActiveReleaseRuns {
    # Queued or running GitHub release runs. Throws when the state cannot be read, so the caller fails safe.
    # `gh api --paginate` follows every page so a deep queue is never truncated into "nothing active".
    param([string]$Repository)
    $repoSegment = if ($Repository) { $Repository } else { '{owner}/{repo}' }
    $runs = [System.Collections.Generic.List[object]]::new()
    foreach ($workflow in $script:GitHubReleaseWorkflows) {
        foreach ($status in $script:GitHubActiveRunStatuses) {
            $global:LASTEXITCODE = 0
            $output = & gh api "repos/$repoSegment/actions/workflows/$workflow/runs" `
                --method GET -f "status=$status" -F 'per_page=100' --paginate `
                --jq '.workflow_runs[] | {id: .id, status: .status, url: .html_url}'
            if ($LASTEXITCODE -ne 0) { throw "Could not list GitHub $workflow runs with status '$status'." }
            $text = ($output | Out-String).Trim()
            if (-not $text) { continue }
            foreach ($line in ($text -split "`r?`n")) {
                if (-not $line.Trim()) { continue }
                try { $run = $line | ConvertFrom-Json }
                catch { throw "GitHub returned an unreadable $workflow run for status '$status'." }
                $runs.Add([pscustomobject]@{ Provider = 'github'; Id = $run.id; Status = $run.status; Url = $run.url })
            }
        }
    }
    return $runs.ToArray()
}

function Get-AzureActiveReleaseBuilds {
    # Queued (notStarted), running (inProgress) or cancelling builds of the Azure RELEASE definition only.
    param([hashtable]$Config)
    $uri = "https://dev.azure.com/$($Config.AZDO_ORG)/$($Config.AZDO_PROJECT)/_apis/build/builds" +
    "?definitions=$($Config.AZDO_RELEASE_PIPELINE_ID)&statusFilter=$($script:AzureActiveBuildStatuses)&api-version=7.1"
    $response = Invoke-RestMethod -Method Get -Uri $uri -Headers (Get-AzdoAuthHeader)
    if (-not $response -or -not ($response.PSObject.Properties.Name -contains 'value')) {
        throw 'Azure DevOps returned an unreadable build list for the release pipeline.'
    }
    $builds = @(foreach ($build in @($response.value)) {
            [pscustomobject]@{
                Provider = 'azure'
                Id       = $build.id
                Status   = $build.status
                Url      = "https://dev.azure.com/$($Config.AZDO_ORG)/$($Config.AZDO_PROJECT)/_build/results?buildId=$($build.id)"
            }
        })
    return $builds
}

function Get-GitHubBranchSha {
    param([Parameter(Mandatory)][string]$Branch, [string]$Repository)
    $repoSegment = if ($Repository) { $Repository } else { '{owner}/{repo}' }
    $global:LASTEXITCODE = 0
    $sha = (& gh api "repos/$repoSegment/commits/$Branch" --method GET --jq '.sha' 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($sha | Out-String))) {
        throw "Could not resolve the current $Branch commit before replaying the release on the destination provider."
    }
    return (($sha | Out-String).Trim())
}

function Invoke-AzureReleaseReplay {
    # templateParameters replay=true makes azure-release.yml treat this run as a hand-off replay: android_ci.py
    # detect-changes re-reads the live branch tip and skips a superseded commit, and compares against the latest
    # release tag so an unchanged branch releases nothing.
    param([hashtable]$Config, [Parameter(Mandatory)][string]$Branch, [Parameter(Mandatory)][string]$Sha)
    $uri = "https://dev.azure.com/$($Config.AZDO_ORG)/$($Config.AZDO_PROJECT)/_apis/build/builds?api-version=7.1"
    $body = @{
        definition         = @{ id = [int]$Config.AZDO_RELEASE_PIPELINE_ID }
        sourceBranch       = "refs/heads/$Branch"
        sourceVersion      = $Sha
        templateParameters = @{ replay = $true }
    } | ConvertTo-Json -Depth 5
    Invoke-RestMethod -Method Post -Uri $uri -Headers (Get-AzdoAuthHeader) -ContentType 'application/json' -Body $body | Out-Null
    Write-Host "Queued Azure release replay for $Branch@$Sha."
}

function Invoke-GitHubReleaseReplay {
    param([string]$Repository, [Parameter(Mandatory)][string]$Workflow, [Parameter(Mandatory)][string]$Branch, [Parameter(Mandatory)][string]$Sha)
    $ghArgs = @()
    if ($Repository) { $ghArgs = @('--repo', $Repository) }
    $global:LASTEXITCODE = 0
    & gh workflow run $Workflow @ghArgs --ref $Branch -f 'replay=true' -f "replay_sha=$Sha"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not queue the GitHub $Workflow replay for $Branch@$Sha. The CI switch token needs Actions read/write."
    }
    Write-Host "Queued GitHub $Workflow replay for $Branch@$Sha."
}

function Invoke-ProviderReleaseReplay {
    param(
        [Parameter(Mandatory)][ValidateSet('github', 'azure')][string]$Mode,
        [hashtable]$Config,
        [string]$Repository
    )
    # Queued while CI_PROVIDER_SWITCHING is still held; the destination guard waits for release, then reads
    # CI_PROVIDER live. This closes the trigger edge where a push is accepted by neither provider.
    foreach ($entry in $script:ReplayBranches) {
        $sha = Get-GitHubBranchSha -Branch $entry.Branch -Repository $Repository
        if ($Mode -eq 'azure') { Invoke-AzureReleaseReplay -Config $Config -Branch $entry.Branch -Sha $sha }
        else { Invoke-GitHubReleaseReplay -Repository $Repository -Workflow $entry.Workflow -Branch $entry.Branch -Sha $sha }
    }
}

function Assert-NoActiveReleaseRun {
    <# Refuses the switch while the provider being left still releases. Nothing has been changed when this runs,
       so refusing needs no rollback. -Force bypasses this check only, loudly. #>
    param(
        [Parameter(Mandatory)][ValidateSet('github', 'azure')][string]$Mode,
        [hashtable]$Config,
        [string]$Repository,
        [switch]$Force
    )
    $previous = if ($Mode -eq 'azure') { 'github' } else { 'azure' }
    $active = @()
    try {
        if ($previous -eq 'github') { $active = @(Get-GitHubActiveReleaseRuns -Repository $Repository) }
        else { $active = @(Get-AzureActiveReleaseBuilds -Config $Config) }
    }
    catch {
        $reason = $_.Exception.Message
        if ($Force) {
            Write-Warning "FORCED: the $previous release state could not be read ($reason); switching to '$Mode' anyway."
            return
        }
        throw "Refusing to switch to '$Mode': the $previous release state could not be read ($reason). Nothing was changed. Re-run when it is readable, or pass -Force after verifying by hand that no release is running."
    }
    if ($active.Count -eq 0) { return }
    $names = ($active | ForEach-Object { "$($_.Provider) release $($_.Id) ($($_.Status)) $($_.Url)" }) -join '; '
    if ($Force) {
        Write-Warning "FORCED: switching to '$Mode' while the $previous provider is still releasing: $names. Both providers can allocate build numbers and publish at once until that run ends."
        return
    }
    throw "Refusing to switch to '$Mode': the $previous provider still has an active release ($names). Nothing was changed. Wait for it to finish, or pass -Force after verifying it is safe."
}

function Get-GitHubVariableValue {
    param([Parameter(Mandatory)][string]$Name, [string]$Repository)
    $ghArgs = @()
    if ($Repository) { $ghArgs = @('--repo', $Repository) }
    $value = (& gh variable get $Name @ghArgs 2>$null)
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return ([string]$value).Trim()
}

function Set-GitHubVariableValue {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Value, [string]$Repository)
    $ghArgs = @()
    if ($Repository) { $ghArgs = @('--repo', $Repository) }
    & gh variable set $Name --body $Value @ghArgs
    if ($LASTEXITCODE -ne 0) { throw "Failed to set GitHub variable $Name." }
    Write-Host "GitHub variable $Name=$Value"
}

function Try-CreateGitHubVariableValue {
    <# Create-only is the lock primitive: POST fails if the variable name already exists. #>
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Value, [string]$Repository)
    $repoSegment = if ($Repository) { $Repository } else { '{owner}/{repo}' }
    $global:LASTEXITCODE = 0
    & gh api "repos/$repoSegment/actions/variables" --method POST -f "name=$Name" -f "value=$Value" 1>$null 2>$null
    return $LASTEXITCODE -eq 0
}

function Remove-GitHubVariableValue {
    param([Parameter(Mandatory)][string]$Name, [string]$Repository)
    $repoSegment = if ($Repository) { $Repository } else { '{owner}/{repo}' }
    $global:LASTEXITCODE = 0
    & gh api "repos/$repoSegment/actions/variables/$Name" --method DELETE 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Failed to delete GitHub variable $Name." }
}

function Get-GitHubVariableValueStrict {
    param([Parameter(Mandatory)][string]$Name, [string]$Repository)
    $repoSegment = if ($Repository) { $Repository } else { '{owner}/{repo}' }
    $global:LASTEXITCODE = 0
    $value = (& gh api "repos/$repoSegment/actions/variables/$Name" --method GET --jq '.value' 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($value | Out-String))) {
        throw "Could not read GitHub variable $Name while verifying switch-lock ownership."
    }
    return (($value | Out-String).Trim())
}

function Enter-CiSwitchLock {
    param([string]$Repository)
    $token = [guid]::NewGuid().ToString('N')
    if (Try-CreateGitHubVariableValue -Name $script:SwitchingVariable -Value $token -Repository $Repository) {
        Write-Host "Acquired $($script:SwitchingVariable) lock ($token)."
        return $token
    }
    # Never auto-delete the legacy literal false: read(false) -> delete -> create is a non-atomic migration.
    $existing = Get-GitHubVariableValue -Name $script:SwitchingVariable -Repository $Repository
    if ($existing -eq 'false') {
        throw "Cannot acquire $($script:SwitchingVariable): legacy value 'false' is still present. After confirming no switch is running, delete it once with 'gh variable delete $($script:SwitchingVariable)' and retry. Nothing was changed."
    }
    if ($existing) {
        throw "Refusing to switch: another switch is already in progress ($($script:SwitchingVariable)=$existing). Nothing was changed."
    }
    throw "Could not acquire $($script:SwitchingVariable) atomically. Nothing was changed; verify GitHub Variables write access and retry."
}

function Exit-CiSwitchLock {
    param([Parameter(Mandatory)][string]$Token, [string]$Repository)
    $current = Get-GitHubVariableValueStrict -Name $script:SwitchingVariable -Repository $Repository
    if ($current -ne $Token) {
        Write-Warning "$($script:SwitchingVariable) no longer holds this switch's own token ($Token) -- it now reads '$current'. Not deleting it."
        return
    }
    Remove-GitHubVariableValue -Name $script:SwitchingVariable -Repository $Repository
    Write-Host "Released $($script:SwitchingVariable) lock."
}

function Get-CiModeStatus {
    param([string]$Repository)
    $gh = Get-GitHubVariableValue -Name $script:ProviderVariable -Repository $Repository
    if (-not $gh) { $gh = 'github (unset, default)' }
    Write-Host ("GitHub {0,-22}: {1}" -f $script:ProviderVariable, $gh)
    $switching = Get-GitHubVariableValue -Name $script:SwitchingVariable -Repository $Repository
    if (-not $switching) {
        Write-Host ("GitHub {0,-22}: unlocked (variable absent)" -f $script:SwitchingVariable)
    }
    elseif ($switching -eq 'false') {
        Write-Host ("GitHub {0,-22}: legacy false (cleanup required)" -f $script:SwitchingVariable)
        Write-Warning "$($script:SwitchingVariable) still contains legacy 'false'. After confirming no switch is running, delete it once: gh variable delete $($script:SwitchingVariable)."
    }
    else {
        Write-Host ("GitHub {0,-22}: {1}" -f $script:SwitchingVariable, $switching)
        Write-Warning "$($script:SwitchingVariable) is held (owning token: $switching): either a switch is running or a previous one crashed. If no switch is running, delete the stale lock: gh variable delete $($script:SwitchingVariable)."
    }
    $cfg = $null
    try { $cfg = Get-AzureConfig -Repository $Repository }
    catch { Write-Warning "Azure state unavailable: $($_.Exception.Message)" }
    if ($cfg) {
        foreach ($p in $script:AzurePipelines) {
            try { Write-Host ("Azure {0,-22}: {1}" -f "$($p.Label) pipeline", (Get-AzdoDefinitionState -Config $cfg -PipelineIdName $p.PipelineIdName)) }
            catch { Write-Warning "Azure $($p.Label) pipeline state unavailable: $($_.Exception.Message)" }
        }
    }
}

function Invoke-CiModeSwitch {
    param(
        [Parameter(Mandatory)][ValidateSet('github', 'azure')][string]$Mode,
        [switch]$DryRun,
        [string]$Repository,
        [switch]$Force
    )
    $plan = Get-CiModePlan -Mode $Mode

    if ($DryRun) {
        Write-Host "[dry-run] would atomically create GitHub variable $script:SwitchingVariable=<a new per-invocation token> for the duration of the switch"
        foreach ($step in $plan) {
            switch ($step.Kind) {
                'SetGitHubVariable' { Write-Host "[dry-run] would set GitHub variable $($step.Name)=$($step.Value)" }
                'SetAzurePipeline' { Write-Host "[dry-run] would set Azure $($step.Label) pipeline queueStatus=$($step.Value)" }
            }
        }
        foreach ($entry in $script:ReplayBranches) {
            Write-Host "[dry-run] would queue a stale-safe $Mode release replay for $($entry.Branch) (on a real provider change)"
        }
        Write-Host "[dry-run] would delete GitHub variable $script:SwitchingVariable to release the switch lock"
        return
    }

    # Validate all configuration before touching anything -- including the switching flag itself.
    $cfg = Get-AzureConfig -Repository $Repository
    $token = Enter-CiSwitchLock -Repository $Repository
    $clearError = $null
    try {
        # Still before the first state-changing call: never hand the new provider a release the old one is running.
        Assert-NoActiveReleaseRun -Mode $Mode -Config $cfg -Repository $Repository -Force:$Force
        $prior = @{}
        foreach ($step in $plan) {
            $key = "$($step.Kind):$($step.Name)"
            if ($step.Kind -eq 'SetGitHubVariable') {
                $prior[$key] = Get-GitHubVariableValue -Name $step.Name -Repository $Repository
            }
            else {
                $prior[$key] = Get-AzdoDefinitionState -Config $cfg -PipelineIdName $step.Name
            }
        }

        $providerBefore = $prior["SetGitHubVariable:$($script:ProviderVariable)"]
        if (-not $providerBefore) { $providerBefore = 'github' }

        $applied = [System.Collections.Generic.List[object]]::new()
        try {
            foreach ($step in $plan) {
                # Re-check immediately before every mutation: the earlier check only proves the old provider was
                # idle at that instant, and a release can still start in the gap before THIS mutation.
                Assert-NoActiveReleaseRun -Mode $Mode -Config $cfg -Repository $Repository -Force:$Force
                switch ($step.Kind) {
                    'SetGitHubVariable' { Set-GitHubVariableValue -Name $step.Name -Value $step.Value -Repository $Repository }
                    'SetAzurePipeline' { Invoke-AzdoDefinitionUpdate -Config $cfg -PipelineIdName $step.Name -State $step.Value }
                }
                $applied.Add($step)
            }

            # A trigger can race the hand-off before either provider has committed to handling it. Replay the
            # current release-branch tips on the destination while the switch lock is still held. Only for a real
            # provider change, not a no-op switch to the already-active mode.
            if ($providerBefore -ne $Mode) {
                Invoke-ProviderReleaseReplay -Mode $Mode -Config $cfg -Repository $Repository
            }
        }
        catch {
            $failure = $_.Exception.Message
            Write-Warning "Switch to '$Mode' failed ($failure). Rolling back the steps already applied."
            for ($i = $applied.Count - 1; $i -ge 0; $i--) {
                $step = $applied[$i]
                $before = $prior["$($step.Kind):$($step.Name)"]
                try {
                    if ($step.Kind -eq 'SetGitHubVariable') {
                        $restore = if ($before) { $before } else { 'github' }
                        Set-GitHubVariableValue -Name $step.Name -Value $restore -Repository $Repository
                    }
                    elseif ($before) {
                        Invoke-AzdoDefinitionUpdate -Config $cfg -PipelineIdName $step.Name -State $before
                    }
                }
                catch { Write-Warning "Rollback of $($step.Kind) $($step.Name) failed: $($_.Exception.Message)" }
            }
            throw "Switch to '$Mode' failed and was rolled back: $failure"
        }
    }
    finally {
        # Runs on every exit -- success, a rolled-back failure, or any other exception -- so releases are never
        # blocked longer than the switch itself took. Only a hard process kill can skip this.
        try { Exit-CiSwitchLock -Token $token -Repository $Repository }
        catch {
            $clearError = "Could not release $($script:SwitchingVariable): $($_.Exception.Message). Every release on both providers is blocked until the lock is removed by hand: gh variable delete $($script:SwitchingVariable)."
            Write-Warning $clearError
        }
    }
    if ($clearError) { throw $clearError }
}

# Allow dot-sourcing from tests without executing.
if ($MyInvocation.InvocationName -ne '.') {
    if ($Mode -eq 'status') { Get-CiModeStatus -Repository $Repository }
    else { Invoke-CiModeSwitch -Mode $Mode -DryRun:$DryRun -Repository $Repository -Force:$Force }
}
