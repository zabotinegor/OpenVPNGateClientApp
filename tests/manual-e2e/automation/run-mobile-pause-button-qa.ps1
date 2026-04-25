param(
    [string]$Serial = "e26d5c2f",
    [string]$OutputDir = "$env:TEMP/OpenVPNClient-mobile-pause-button-qa"
)

$ErrorActionPreference = "Stop"

$PKG = "com.yahorzabotsin.openvpnclientgate"
$EVIDENCE = $OutputDir
$UiDumpPath = "/sdcard/qa_ui.xml"

$CONNECT_WAIT = 120
$SHORT_WAIT = 30

New-Item -ItemType Directory -Force -Path $EVIDENCE | Out-Null

function Run-Adb {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Args
    )
    return & adb -s $Serial @Args
}

function Dump-Ui {
    Run-Adb shell uiautomator dump $UiDumpPath | Out-Null
    $uiXml = Run-Adb shell cat $UiDumpPath
    return ($uiXml -join "")
}

function Has-Id([string]$xml, [string]$id) {
    return $xml -match [regex]::Escape("resource-id=`"${PKG}:id/${id}`"")
}

function Tap-Id([string]$id) {
    $xml = Dump-Ui
    if ($xml -match "resource-id=`"${PKG}:id/${id}`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"") {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        Run-Adb shell input tap $x $y | Out-Null
        return $true
    }
    return $false
}

function Wait-ForId([string]$id, [bool]$present, [int]$maxSec) {
    for ($i = 0; $i -lt $maxSec; $i++) {
        Start-Sleep -Seconds 1
        $xml = Dump-Ui
        if ((Has-Id $xml $id) -eq $present) { return $xml }
    }
    return $null
}

function Shot([string]$name) {
    $remote = "/sdcard/$name.png"
    Run-Adb shell screencap -p $remote | Out-Null
    Run-Adb pull $remote "$EVIDENCE\$name.png" | Out-Null
}

function Tap-FirstById([string]$xml, [string]$id) {
    if ($xml -match "resource-id=`"${PKG}:id/${id}`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"") {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        Run-Adb shell input tap $x $y | Out-Null
        return $true
    }
    return $false
}

function Wait-ForAnyId([string[]]$ids, [int]$maxSec) {
    for ($i = 0; $i -lt $maxSec; $i++) {
        Start-Sleep -Seconds 1
        $xml = Dump-Ui
        foreach ($id in $ids) {
            if (Has-Id $xml $id) { return $xml }
        }
    }
    return $null
}

function Is-NotSelectedText([string]$text) {
    if (-not $text) { return $true }
    return $text -match "(?i)not selected|no server"
}

function Get-IdText([string]$xml, [string]$id) {
    if ($xml -match "resource-id=`"${PKG}:id/${id}`"[^>]*text=`"([^`"]*)`"") { return $Matches[1] }
    if ($xml -match "text=`"([^`"]*)`"[^>]*resource-id=`"${PKG}:id/${id}`"") { return $Matches[1] }
    return ""
}

$results = @{}

$xmlStart = $null
for ($launchAttempt = 1; $launchAttempt -le 2 -and -not $xmlStart; $launchAttempt++) {
    Run-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Run-Adb shell wm dismiss-keyguard | Out-Null
    Run-Adb shell input keyevent KEYCODE_HOME | Out-Null
    Run-Adb shell am force-stop $PKG | Out-Null
    Start-Sleep -Seconds 1
    & adb -s $Serial shell am start -W -n "${PKG}/.mobile.SplashActivity" | Out-Null
    Start-Sleep -Seconds 2
    $xmlStart = Wait-ForId "start_connection_button" $true 30
}
Shot "00-launch"

if (-not $xmlStart) {
    $results["VPN-PAUSE-001"] = "FAIL - start button not found after launch"
    $results["VPN-PAUSE-002"] = "SKIP - case001 failed"
    $results["VPN-PAUSE-003"] = "SKIP - case001 failed"
}

if (-not $results.ContainsKey("VPN-PAUSE-001")) {
    $xmlBeforeSelect = Dump-Ui
    $selText = Get-IdText $xmlBeforeSelect "server_selection_container"
    if (Is-NotSelectedText $selText) {
        Tap-Id "server_selection_container" | Out-Null
        Start-Sleep -Seconds 4
        Shot "01-server-list"
        $xmlList = Dump-Ui

        if (-not ((Has-Id $xmlList "country_name") -or (Has-Id $xmlList "server_title"))) {
            for ($refreshTry = 1; $refreshTry -le 3; $refreshTry++) {
                if (Tap-Id "refresh_fab") {
                    Start-Sleep -Seconds 2
                    $xmlAfterRefresh = Wait-ForAnyId @("country_name", "server_title") 45
                    if ($xmlAfterRefresh) {
                        $xmlList = $xmlAfterRefresh
                        break
                    }
                }
            }
        }

        if ($xmlList -and (Has-Id $xmlList "country_name")) {
            Tap-FirstById $xmlList "country_name" | Out-Null
            Start-Sleep -Seconds 3
            $xmlList = Dump-Ui
        }

        if ($xmlList -and (Has-Id $xmlList "server_title")) {
            Tap-FirstById $xmlList "server_title" | Out-Null
        }
        Start-Sleep -Seconds 2
        Shot "02-server-selected"

        $xmlAfterPick = Dump-Ui
        $selTextAfter = Get-IdText $xmlAfterPick "server_selection_container"
        if (Is-NotSelectedText $selTextAfter) {
            $results["VPN-PAUSE-001"] = "FAIL - server selection failed"
            $results["VPN-PAUSE-002"] = "SKIP - case001 failed"
            $results["VPN-PAUSE-003"] = "SKIP - case001 failed"
        }
    }
}

if ($results.ContainsKey("VPN-PAUSE-001")) {
    # no-op: launch precondition already failed
} else {
    $xmlBeforeStart = Dump-Ui
    if (-not (Has-Id $xmlBeforeStart "start_connection_button")) {
        $results["VPN-PAUSE-001"] = "FAIL - start button not found"
    } else {
        Tap-Id "start_connection_button" | Out-Null

        Start-Sleep -Seconds 3
        $xmlPerm = Dump-Ui
        if ($xmlPerm -match 'text="OK"' -or $xmlPerm -match 'text="Allow"') {
            Run-Adb shell input keyevent 66 | Out-Null
            Start-Sleep -Seconds 1
        }

        Shot "03-connect-tapped"
        $xmlConn = Wait-ForId "pause_connection_button" $true $CONNECT_WAIT
        if ($xmlConn) {
            Shot "04-connected-pause-visible"
            $pause = Has-Id $xmlConn "pause_connection_button"
            $startPresent = Has-Id $xmlConn "start_connection_button"
            if ($pause -and $startPresent) {
                $results["VPN-PAUSE-001"] = "PASS"
            } else {
                $results["VPN-PAUSE-001"] = "FAIL - wrong controls in connected (pause=$pause start=$startPresent)"
            }
        } else {
            Shot "04-connect-timeout"
            $results["VPN-PAUSE-001"] = "FAIL - timeout waiting connected"
        }
    }
}

if ($results["VPN-PAUSE-001"] -eq "PASS") {
    if (Tap-Id "pause_connection_button") {
        Start-Sleep -Seconds 5
        $xmlPaused = Dump-Ui
        Shot "06-paused-state"
        $pauseVisible = Has-Id $xmlPaused "pause_connection_button"
        $stopStillVisible = Has-Id $xmlPaused "start_connection_button"

        if ($pauseVisible -and $stopStillVisible) {
            Tap-Id "pause_connection_button" | Out-Null
            Start-Sleep -Seconds 5
            $xmlRes = Dump-Ui
            Shot "08-resumed-state"
            $resumePause = Has-Id $xmlRes "pause_connection_button"
            $resumeStop = Has-Id $xmlRes "start_connection_button"
            if ($resumePause -and $resumeStop) {
                $results["VPN-PAUSE-002"] = "PASS"
            } else {
                $results["VPN-PAUSE-002"] = "FAIL - resume controls not restored (pause=$resumePause stop=$resumeStop)"
            }
        } else {
            $results["VPN-PAUSE-002"] = "FAIL - paused controls invalid (pause=$pauseVisible stop=$stopStillVisible)"
        }
    } else {
        $results["VPN-PAUSE-002"] = "FAIL - pause button not found"
    }
} else {
    $results["VPN-PAUSE-002"] = "SKIP - case001 failed"
}

if ($results["VPN-PAUSE-001"] -eq "PASS") {
    Tap-Id "start_connection_button" | Out-Null
    $xmlDisc1 = Wait-ForId "pause_connection_button" $false $SHORT_WAIT
    Shot "09-stop-from-connected"
    $okA = $false
    if ($xmlDisc1) {
        $okA = (-not (Has-Id $xmlDisc1 "pause_connection_button")) -and (Has-Id $xmlDisc1 "start_connection_button")
    }

    Tap-Id "start_connection_button" | Out-Null
    Start-Sleep -Seconds 2
    $xmlPerm2 = Dump-Ui
    if ($xmlPerm2 -match 'text="OK"' -or $xmlPerm2 -match 'text="Allow"') {
        Run-Adb shell input keyevent 66 | Out-Null
    }

    $xmlConn2 = Wait-ForId "pause_connection_button" $true $CONNECT_WAIT
    $okB = $false
    if ($xmlConn2) {
        Tap-Id "pause_connection_button" | Out-Null
        Start-Sleep -Seconds 4
        Shot "10-paused-for-stop"
        Tap-Id "start_connection_button" | Out-Null
        $xmlDisc2 = Wait-ForId "pause_connection_button" $false $SHORT_WAIT
        Shot "11-stop-from-paused"
        if ($xmlDisc2) {
            $okB = (-not (Has-Id $xmlDisc2 "pause_connection_button")) -and (Has-Id $xmlDisc2 "start_connection_button")
        }
    }

    if ($okA -and $okB) {
        $results["VPN-PAUSE-003"] = "PASS"
    } else {
        $results["VPN-PAUSE-003"] = "FAIL - stop flow failed (okA=$okA okB=$okB)"
    }
} else {
    $results["VPN-PAUSE-003"] = "SKIP - case001 failed"
}

$xmlFinal = Dump-Ui
if (Has-Id $xmlFinal "pause_connection_button") {
    Tap-Id "start_connection_button" | Out-Null
    Start-Sleep -Seconds 3
}
Shot "99-final"

Run-Adb shell logcat -d -t 400 -s OpenVPNClient:* OpenVPNGate:* VpnManager:* | Out-File "$EVIDENCE\logcat-suite.txt"

$pass = ($results.Values | Where-Object { $_ -eq "PASS" }).Count
$fail = ($results.Values | Where-Object { $_ -like "FAIL*" }).Count
$skip = ($results.Values | Where-Object { $_ -like "SKIP*" }).Count
$total = $pass + $fail + $skip
$rate = if ($total -eq 0) { 0 } else { [math]::Round(($pass * 100.0) / $total) }

$report = @"
# VPN Pause Button Manual QA Report
Date: $(Get-Date -Format "yyyy-MM-dd HH:mm")
Device: $Serial
Suite: VPN-PAUSE-CORE

| Case ID | Result |
|---|---|
| VPN-PAUSE-001 | $($results['VPN-PAUSE-001']) |
| VPN-PAUSE-002 | $($results['VPN-PAUSE-002']) |
| VPN-PAUSE-003 | $($results['VPN-PAUSE-003']) |

Pass: $pass
Fail: $fail
Skip: $skip
Pass rate: $rate%

Evidence dir: $EVIDENCE
"@

$report | Out-File "$EVIDENCE\QA-REPORT.md" -Encoding UTF8
Write-Output $report
