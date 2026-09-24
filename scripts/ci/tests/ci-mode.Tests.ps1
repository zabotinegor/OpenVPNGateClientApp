# Pester tests for scripts/ci/ci-mode.ps1 (provider switch). Requires Pester 5+.
# Run: Invoke-Pester scripts/ci/tests   (write any NUnit/-CI result file outside the repository)
# The script is dot-sourced in BeforeAll and every side effect (gh, Azure DevOps REST) is mocked.

BeforeAll {
    $here = Split-Path -Parent $PSCommandPath
    $script:ModeScript = Join-Path (Split-Path -Parent $here) 'ci-mode.ps1'
    $script:RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $here))
    . $script:ModeScript

    function Reset-CiTestLog { $global:CiTestLog = New-Object System.Collections.ArrayList }
    function Add-CiTestLog([string]$Entry) { [void]$global:CiTestLog.Add($Entry) }

    function Initialize-CiSwitchMocks {
        param([string]$ProviderBefore = 'github', [object[]]$GitHubRuns = @(), [object[]]$AzureRuns = @())
        $global:CiTestProviderBefore = $ProviderBefore
        $global:CiTestGitHubRuns = $GitHubRuns
        $global:CiTestAzureRuns = $AzureRuns
        Reset-CiTestLog
        Mock Get-AzureConfig { @{ AZDO_ORG = 'o'; AZDO_PROJECT = 'p'; AZDO_PR_CI_PIPELINE_ID = '1'; AZDO_RELEASE_PIPELINE_ID = '2' } } -Verifiable
        Mock Enter-CiSwitchLock { Add-CiTestLog 'lock:acquire'; 'token-1' }
        Mock Exit-CiSwitchLock { Add-CiTestLog 'lock:release' }
        Mock Get-GitHubActiveReleaseRuns { @($global:CiTestGitHubRuns) }
        Mock Get-AzureActiveReleaseBuilds { @($global:CiTestAzureRuns) }
        Mock Get-GitHubVariableValue { $global:CiTestProviderBefore }
        Mock Get-AzdoDefinitionState { 'disabled' }
        Mock Set-GitHubVariableValue { Add-CiTestLog "var:$Name=$Value" }
        Mock Invoke-AzdoDefinitionUpdate { Add-CiTestLog "azure:$PipelineIdName=$State" }
        Mock Get-GitHubBranchSha { "sha-$Branch" }
        Mock Invoke-GitHubReleaseReplay { Add-CiTestLog "replay:github:$Branch@$Sha" }
        Mock Invoke-AzureReleaseReplay { Add-CiTestLog "replay:azure:$Branch@$Sha" }
        Mock Write-Host { }
        Mock Write-Warning { }
    }

    function Get-CiThrownMessage([scriptblock]$Block) {
        try { & $Block; return $null } catch { return $_.Exception.Message }
    }
}

Describe 'Get-CiModePlan' {
    It 'enables both Azure pipelines before flipping CI_PROVIDER when moving to azure' {
        $plan = Get-CiModePlan -Mode azure
        $plan.Count | Should -Be 3
        $plan[0].Kind | Should -Be 'SetAzurePipeline'
        $plan[1].Kind | Should -Be 'SetAzurePipeline'
        $plan[0].Value | Should -Be 'enabled'
        $plan[2].Kind | Should -Be 'SetGitHubVariable'
        $plan[2].Value | Should -Be 'azure'
    }

    It 'flips CI_PROVIDER first and then disables both Azure pipelines when moving to github' {
        $plan = Get-CiModePlan -Mode github
        $plan[0].Kind | Should -Be 'SetGitHubVariable'
        $plan[0].Value | Should -Be 'github'
        $plan[1].Value | Should -Be 'disabled'
        $plan[2].Value | Should -Be 'disabled'
    }

    It 'controls the PR CI and the release pipeline as one provider state' {
        $names = (Get-CiModePlan -Mode azure | Where-Object { $_.Kind -eq 'SetAzurePipeline' } | ForEach-Object { $_.Name })
        ($names -join ',') | Should -Be 'AZDO_PR_CI_PIPELINE_ID,AZDO_RELEASE_PIPELINE_ID'
    }
}

Describe 'Invoke-CiModeSwitch' {
    It 'github -> azure: takes the lock, applies the plan, replays dev and main on azure, releases the lock last' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Invoke-CiModeSwitch -Mode azure
        $log = $global:CiTestLog -join '|'
        $log | Should -Be 'lock:acquire|azure:AZDO_PR_CI_PIPELINE_ID=enabled|azure:AZDO_RELEASE_PIPELINE_ID=enabled|var:CI_PROVIDER=azure|replay:azure:dev@sha-dev|replay:azure:main@sha-main|lock:release'
    }

    It 'azure -> github: sets github first, disables azure and replays on github' {
        Initialize-CiSwitchMocks -ProviderBefore 'azure'
        Invoke-CiModeSwitch -Mode github
        $log = $global:CiTestLog -join '|'
        $log | Should -Be 'lock:acquire|var:CI_PROVIDER=github|azure:AZDO_PR_CI_PIPELINE_ID=disabled|azure:AZDO_RELEASE_PIPELINE_ID=disabled|replay:github:dev@sha-dev|replay:github:main@sha-main|lock:release'
    }

    It 'does not replay for a no-op switch to the already active provider' {
        Initialize-CiSwitchMocks -ProviderBefore 'azure'
        Invoke-CiModeSwitch -Mode azure
        ($global:CiTestLog -join '|') | Should -Not -Match 'replay'
    }

    It 'treats an unset CI_PROVIDER as github' {
        Initialize-CiSwitchMocks -ProviderBefore ''
        Mock Get-GitHubVariableValue { $null }
        Invoke-CiModeSwitch -Mode azure
        ($global:CiTestLog -join '|') | Should -Match 'replay:azure:dev'
    }

    It 'refuses while the provider being left has an active release and changes nothing' {
        Initialize-CiSwitchMocks -ProviderBefore 'github' -GitHubRuns @([pscustomobject]@{ Provider = 'github'; Id = 42; Status = 'in_progress'; Url = 'https://run/42' })
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'active release'
        $message | Should -Match '42'
        $log = $global:CiTestLog -join '|'
        $log | Should -Be 'lock:acquire|lock:release'
    }

    It 'checks the azure release pipeline when leaving azure' {
        Initialize-CiSwitchMocks -ProviderBefore 'azure' -AzureRuns @([pscustomobject]@{ Provider = 'azure'; Id = 7; Status = 'inProgress'; Url = 'https://b/7' })
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode github }
        $message | Should -Match 'azure provider still has an active release'
        ($global:CiTestLog -join '|') | Should -Be 'lock:acquire|lock:release'
    }

    It 'fails closed when the old provider state cannot be read' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Mock Get-GitHubActiveReleaseRuns { throw 'gh unauthenticated' }
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'could not be read'
        ($global:CiTestLog -join '|') | Should -Be 'lock:acquire|lock:release'
    }

    It '-Force bypasses only the active-release check' {
        Initialize-CiSwitchMocks -ProviderBefore 'github' -GitHubRuns @([pscustomobject]@{ Provider = 'github'; Id = 42; Status = 'queued'; Url = 'u' })
        Invoke-CiModeSwitch -Mode azure -Force
        $log = $global:CiTestLog -join '|'
        $log | Should -Match 'var:CI_PROVIDER=azure'
        $log | Should -Match '^lock:acquire'
        $log | Should -Match 'lock:release$'
    }

    It 'refuses a concurrent switch when the lock is already held' {
        Initialize-CiSwitchMocks
        Mock Enter-CiSwitchLock { throw 'another switch is already in progress' }
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'already in progress'
        $global:CiTestLog.Count | Should -Be 0
        Assert-MockCalled Exit-CiSwitchLock -Times 0
    }

    It 'rolls back applied steps and still releases the lock when a step fails' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Mock Set-GitHubVariableValue {
            if ($Value -eq 'azure') { throw 'variable write refused' }
            Add-CiTestLog "var:$Name=$Value"
        }
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'rolled back'
        $log = $global:CiTestLog -join '|'
        $log | Should -Match 'azure:AZDO_RELEASE_PIPELINE_ID=disabled'
        $log | Should -Match 'lock:release$'
        $log | Should -Not -Match 'replay'
    }

    It 'rolls back when the destination replay fails' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Mock Invoke-AzureReleaseReplay { throw 'queue refused' }
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'rolled back'
        ($global:CiTestLog -join '|') | Should -Match 'var:CI_PROVIDER=github'
        ($global:CiTestLog -join '|') | Should -Match 'lock:release$'
    }

    It 're-checks for active releases before every mutation, not only once' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Invoke-CiModeSwitch -Mode azure
        # once before reading prior state plus once per plan step (three steps)
        Assert-MockCalled Get-GitHubActiveReleaseRuns -Times 4
    }

    It 'dry run makes no calls' {
        Initialize-CiSwitchMocks
        Invoke-CiModeSwitch -Mode azure -DryRun
        $global:CiTestLog.Count | Should -Be 0
        Assert-MockCalled Enter-CiSwitchLock -Times 0
        Assert-MockCalled Get-AzureConfig -Times 0
    }

    It 'fails loudly when the lock cannot be released after a successful switch' {
        Initialize-CiSwitchMocks -ProviderBefore 'github'
        Mock Exit-CiSwitchLock { throw 'delete refused' }
        $message = Get-CiThrownMessage { Invoke-CiModeSwitch -Mode azure }
        $message | Should -Match 'Could not release CI_PROVIDER_SWITCHING'
    }
}

Describe 'release replay' {
    It 'pins each replay to the branch tip read at hand-off time and covers exactly dev and main' {
        Initialize-CiSwitchMocks
        Invoke-ProviderReleaseReplay -Mode github -Config @{} -Repository 'o/r'
        ($global:CiTestLog -join '|') | Should -Be 'replay:github:dev@sha-dev|replay:github:main@sha-main'
    }

    It 'unresolvable branch tip aborts the replay instead of guessing a commit' {
        Initialize-CiSwitchMocks
        Mock Get-GitHubBranchSha { throw 'no tip' }
        $message = Get-CiThrownMessage { Invoke-ProviderReleaseReplay -Mode azure -Config @{} -Repository 'o/r' }
        $message | Should -Be 'no tip'
        $global:CiTestLog.Count | Should -Be 0
    }
}

Describe 'reset workflow wiring' {
    It 'runs monthly and resets through the single operator command' {
        $text = Get-Content -Raw (Join-Path $script:RepoRoot '.github/workflows/ci-provider-reset.yml')
        $text | Should -Match 'cron: "10 1 1 \* \*"'
        $text | Should -Match "Mode = 'github'"
        $text | Should -Match 'scripts/ci/pipeline-mode.ps1'
        $text | Should -Match '--consumer ci-provider-reset'
        $text | Should -Match 'AZDO_RELEASE_PIPELINE_ID'
    }

    It 'the single operator command forwards every parameter to ci-mode.ps1' {
        $text = Get-Content -Raw (Join-Path $script:RepoRoot 'scripts/ci/pipeline-mode.ps1')
        $text | Should -Match 'ci-mode.ps1'
        foreach ($name in 'Mode', 'DryRun', 'Repository', 'Force') { $text | Should -Match ('\$' + $name) }
    }
}

Describe 'Get-GitHubActiveReleaseRuns' {
    It 'counts queued, pending, waiting and requested runs as in flight, not only queued and in_progress' {
        $global:CiTestGhStatuses = New-Object System.Collections.ArrayList
        Mock gh {
            $global:LASTEXITCODE = 0
            foreach ($arg in $args) { if ("$arg" -like 'status=*') { [void]$global:CiTestGhStatuses.Add("$arg") } }
        }
        Get-GitHubActiveReleaseRuns -Repository 'o/r' | Out-Null
        foreach ($status in 'in_progress', 'queued', 'pending', 'waiting', 'requested') {
            $global:CiTestGhStatuses | Should -Contain "status=$status"
        }
    }
}
