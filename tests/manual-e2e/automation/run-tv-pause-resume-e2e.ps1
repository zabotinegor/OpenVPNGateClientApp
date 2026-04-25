param(
    [string]$Serial = "192.168.1.95:5555",
    [string]$Adb = "c:/platform-tools/adb.exe",
    [string]$OutputDir = "$env:TEMP/OpenVPNClient-tv-pause-resume-e2e"
)

$ErrorActionPreference = "Stop"

$pkg = "com.yahorzabotsin.openvpnclientgate"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-AdbQuiet {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $stdout = Join-Path $env:TEMP ([System.IO.Path]::GetRandomFileName())
    $stderr = Join-Path $env:TEMP ([System.IO.Path]::GetRandomFileName())
    try {
        $process = Start-Process -FilePath $Adb -ArgumentList $Arguments -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if ($process.ExitCode -ne 0) {
            $errorText = if (Test-Path $stderr) { [System.IO.File]::ReadAllText($stderr) } else { '' }
            throw "adb failed ($($Arguments -join ' ')): $errorText"
        }
    } finally {
        Remove-Item $stdout, $stderr -ErrorAction SilentlyContinue
    }
}

function Ui {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        try {
            Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'uiautomator', 'dump')
            Invoke-AdbQuiet -Arguments @('-s', $Serial, 'pull', '/sdcard/window_dump.xml', "$env:TEMP/tv_e2e_run.xml")
            if (Test-Path "$env:TEMP/tv_e2e_run.xml") {
                $content = [System.IO.File]::ReadAllText("$env:TEMP\tv_e2e_run.xml")
                if (-not [string]::IsNullOrWhiteSpace($content)) {
                    return $content
                }
            }
        } catch {
            if ($attempt -eq 2) { throw }
        }
    }
    return ''
}
function HasId($x, $id) { [regex]::IsMatch($x, 'resource-id="' + [regex]::Escape($pkg + ':id/' + $id) + '"') }
function Txt($x, $id) {
    $m = [regex]::Match($x, 'text="([^"]*)" resource-id="' + [regex]::Escape($pkg + ':id/' + $id) + '"')
    if ($m.Success) { return $m.Groups[1].Value }
    $m = [regex]::Match($x, 'resource-id="' + [regex]::Escape($pkg + ':id/' + $id) + '"[^>]*text="([^"]*)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
function TapId($x, $id) {
    $m = [regex]::Match($x, 'resource-id="' + [regex]::Escape($pkg + ':id/' + $id) + '"[^>]*bounds="(\[[^\]]+\]\[[^\]]+\])"')
    if (-not $m.Success) { return $false }
    $nums = [regex]::Matches($m.Groups[1].Value, '\d+') | ForEach-Object { [int]$_.Value }
    Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'input', 'tap', ([int](($nums[0] + $nums[2]) / 2)).ToString(), ([int](($nums[1] + $nums[3]) / 2)).ToString())
    return $true
}
function DismissVpnDialog($x) {
    if ($x -match 'com\.android\.vpndialogs') {
        Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'input', 'tap', '1464', '746')
        return $true
    }
    return $false
}
function Shot($name) {
    Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'screencap', '-p', "/sdcard/$name.png")
    Invoke-AdbQuiet -Arguments @('-s', $Serial, 'pull', "/sdcard/$name.png", "$OutputDir/$name.png")
}

$res = @{}
$transition = @{
    pausingObserved = $false
    pauseLeftConnectedImmediately = $false
    resumePauseHidden = $false
    resumeProgressObserved = $false
    resumeBounceToPaused = $false
}
$resumeSeenTexts = New-Object System.Collections.Generic.List[string]
$pauseSeenStatuses = New-Object System.Collections.Generic.List[string]

Invoke-AdbQuiet -Arguments @('connect', $Serial)
Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'am', 'force-stop', $pkg)
Invoke-AdbQuiet -Arguments @('-s', $Serial, 'shell', 'am', 'start', '-n', "${pkg}/.tv.SplashActivity")

$xml = ''
for ($i = 0; $i -lt 60; $i++) {
    $xml = Ui
    if ($xml -match 'com\.android\.vpndialogs') { DismissVpnDialog $xml | Out-Null }
    elseif ((HasId $xml 'start_connection_button') -or (HasId $xml 'pause_connection_button')) { break }
}

if (-not (HasId $xml 'pause_connection_button')) {
    TapId $xml 'start_connection_button' | Out-Null
    for ($i = 0; $i -lt 220; $i++) {
        $xml = Ui
        if ($xml -match 'com\.android\.vpndialogs') { DismissVpnDialog $xml | Out-Null }
        elseif ((Txt $xml 'pause_connection_button') -eq 'ПАУЗА') { break }
    }
}

Shot 'tv_case1_connected'
$pauseTxt = Txt $xml 'pause_connection_button'
$hasStop = HasId $xml 'start_connection_button'
$res['VPN-PAUSE-001'] = if ($pauseTxt -eq 'ПАУЗА' -and $hasStop) { 'PASS' } else { "FAIL - connected: pause='$pauseTxt' stop=$hasStop" }
Write-Host "VPN-PAUSE-001: $($res['VPN-PAUSE-001'])"

if ($res['VPN-PAUSE-001'] -eq 'PASS') {

    TapId $xml 'pause_connection_button' | Out-Null
    for ($i = 0; $i -lt 120; $i++) {
        $xml = Ui
        $statusTxt = Txt $xml 'status_value'
        $pauseTxtDuringPause = Txt $xml 'pause_connection_button'
        if ($statusTxt) { [void]$pauseSeenStatuses.Add($statusTxt) }
        if ($statusTxt -and $statusTxt -ne 'Подключено') { $transition.pauseLeftConnectedImmediately = $true }
        if ($statusTxt -and $statusTxt -match 'Пауза') { $transition.pausingObserved = $true }
        if ($pauseTxtDuringPause -eq 'ПРОДОЛЖИТЬ' -and (HasId $xml 'start_connection_button')) { break }
    }
    for ($i = 0; $i -lt 180; $i++) {
        $xml = Ui
        if ((Txt $xml 'pause_connection_button') -eq 'ПРОДОЛЖИТЬ' -and (HasId $xml 'start_connection_button')) { break }
    }
    Shot 'tv_case2_paused'
    $resumeTxt = Txt $xml 'pause_connection_button'
    $hasStop2 = HasId $xml 'start_connection_button'
    $res['VPN-PAUSE-002-paused'] = if ($resumeTxt -eq 'ПРОДОЛЖИТЬ' -and $hasStop2) { 'PASS' } else { "FAIL - paused: pause='$resumeTxt' stop=$hasStop2" }
    Write-Host "VPN-PAUSE-002-paused: $($res['VPN-PAUSE-002-paused'])"

    $hidden2 = $false
    $prog2 = $false
    $bounce2 = $false
    if ($res['VPN-PAUSE-002-paused'] -eq 'PASS') {
        TapId $xml 'pause_connection_button' | Out-Null
        for ($i = 0; $i -lt 320; $i++) {
            $xml = Ui
            $startTxt = Txt $xml 'start_connection_button'
            $pauseTxtResume = Txt $xml 'pause_connection_button'
            if ($startTxt) {
                [void]$resumeSeenTexts.Add($startTxt)
            }
            if (-not (HasId $xml 'pause_connection_button')) {
                $hidden2 = $true
                $transition.resumePauseHidden = $true
            } elseif ($hidden2 -and $pauseTxtResume -eq 'ПРОДОЛЖИТЬ') {
                $bounce2 = $true
                $transition.resumeBounceToPaused = $true
            }
            if ($startTxt -and $startTxt -ne 'ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ' -and $startTxt -ne 'НАЧАТЬ ПОДКЛЮЧЕНИЕ') {
                $prog2 = $true
                $transition.resumeProgressObserved = $true
                Shot 'tv_case2_resuming_progress'
            }
            if ((Txt $xml 'status_value') -eq 'Подключено' -and (Txt $xml 'pause_connection_button') -eq 'ПАУЗА') { break }
        }
        Shot 'tv_case2_resumed'
        $res['VPN-PAUSE-002-resume'] = if ($hidden2 -and $prog2 -and -not $bounce2) { 'PASS' } else { "FAIL - AC4/AC5: hidden=$hidden2 progress=$prog2 bounce=$bounce2" }
        Write-Host "VPN-PAUSE-002-resume: $($res['VPN-PAUSE-002-resume'])"
    }

    for ($i = 0; $i -lt 30; $i++) {
        $xml = Ui
        if ((Txt $xml 'pause_connection_button') -eq 'ПАУЗА') { break }
    }
    TapId $xml 'start_connection_button' | Out-Null
    for ($i = 0; $i -lt 180; $i++) {
        $xml = Ui
        if (((Txt $xml 'start_connection_button') -match 'НАЧАТЬ') -and -not (HasId $xml 'pause_connection_button')) { break }
    }
    Shot 'tv_case3_after_stop_connected'
    $startAfterStop = Txt $xml 'start_connection_button'
    $pauseAfterStop = HasId $xml 'pause_connection_button'
    $res['VPN-PAUSE-003-from-connected'] = if ($startAfterStop -match 'НАЧАТЬ' -and -not $pauseAfterStop) { 'PASS' } else { "FAIL - stop from connected: start='$startAfterStop' pause=$pauseAfterStop" }
    Write-Host "VPN-PAUSE-003-from-connected: $($res['VPN-PAUSE-003-from-connected'])"

    if ($startAfterStop -match 'НАЧАТЬ') {
        TapId $xml 'start_connection_button' | Out-Null
        for ($i = 0; $i -lt 220; $i++) {
            $xml = Ui
            if ($xml -match 'com\.android\.vpndialogs') { DismissVpnDialog $xml | Out-Null }
            elseif ((Txt $xml 'pause_connection_button') -eq 'ПАУЗА') { break }
        }
        TapId $xml 'pause_connection_button' | Out-Null
        for ($i = 0; $i -lt 180; $i++) {
            $xml = Ui
            if ((Txt $xml 'pause_connection_button') -eq 'ПРОДОЛЖИТЬ') { break }
        }
        TapId $xml 'start_connection_button' | Out-Null
        for ($i = 0; $i -lt 180; $i++) {
            $xml = Ui
            if (((Txt $xml 'start_connection_button') -match 'НАЧАТЬ') -and -not (HasId $xml 'pause_connection_button')) { break }
        }
        Shot 'tv_case3_after_stop_paused'
        $startAfterStop2 = Txt $xml 'start_connection_button'
        $pauseAfterStop2 = HasId $xml 'pause_connection_button'
        $res['VPN-PAUSE-003-from-paused'] = if ($startAfterStop2 -match 'НАЧАТЬ' -and -not $pauseAfterStop2) { 'PASS' } else { "FAIL - stop from paused: start='$startAfterStop2' pause=$pauseAfterStop2" }
        Write-Host "VPN-PAUSE-003-from-paused: $($res['VPN-PAUSE-003-from-paused'])"
    }

} else {
    foreach ($k in @('VPN-PAUSE-002-paused', 'VPN-PAUSE-002-resume', 'VPN-PAUSE-003-from-connected', 'VPN-PAUSE-003-from-paused')) {
        $res[$k] = 'SKIP'
    }
}

$pass = ($res.Values | Where-Object { $_ -eq 'PASS' }).Count
$fail = ($res.Values | Where-Object { $_ -like 'FAIL*' }).Count
$skip = ($res.Values | Where-Object { $_ -like 'SKIP*' }).Count

$report = @(
    '# QA Report: TV Pause/Resume E2E',
    "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "Device: $Serial",
    '',
    '| Case ID | Result |',
    '|---|---|'
)
foreach ($k in @('VPN-PAUSE-001', 'VPN-PAUSE-002-paused', 'VPN-PAUSE-002-resume', 'VPN-PAUSE-003-from-connected', 'VPN-PAUSE-003-from-paused')) {
    $report += "| $k | $($res[$k]) |"
}
$report += ''
$report += "Pausing observed: $($transition.pausingObserved)"
$report += "Pause left connected immediately: $($transition.pauseLeftConnectedImmediately)"
$report += "Resume hidden observed: $($transition.resumePauseHidden)"
$report += "Resume progress observed: $($transition.resumeProgressObserved)"
$report += "Resume bounced to paused: $($transition.resumeBounceToPaused)"
$report += "Pause statuses: $((($pauseSeenStatuses | Select-Object -Unique) -join ', '))"
$report += "Resume button texts: $((($resumeSeenTexts | Select-Object -Unique) -join ', '))"
$report += ''
$report += "Pass: $pass"
$report += "Fail: $fail"
$report += "Skip: $skip"
$report += "Evidence: $OutputDir"

$rp = Join-Path $OutputDir 'QA-REPORT-tv-e2e.md'
Set-Content -Path $rp -Value $report -Encoding UTF8
Write-Host ''
Get-Content $rp
