# Manual E2E Automation

This directory stores reusable manual-e2e automation helpers that must survive cleanup of temporary QA artifacts.

## Available Scripts

- `run-mobile-pause-button-qa.ps1`: real-device Android phone/tablet validation for `VPN-PAUSE-001..003`
- `run-tv-pause-resume-e2e.ps1`: Android TV validation for `VPN-PAUSE-001`, `VPN-PAUSE-002`, `VPN-PAUSE-003`

## Recommended Install Command

Run from repository root:

```powershell
Set-Location .\src
.\gradlew :mobile:uninstallDebug :mobile:installDebug
```

## Mobile Suite Command

Run from repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-mobile-pause-button-qa.ps1 -Serial <DEVICE_SERIAL>
```

## TV Suite Command

Run from repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-tv-pause-resume-e2e.ps1 -Serial <TV_IP:5555>
```

## Output Policy

Both scripts default to writing evidence into `%TEMP%` so canonical test assets stay clean.
Use `-OutputDir` if you want to persist a specific run.
