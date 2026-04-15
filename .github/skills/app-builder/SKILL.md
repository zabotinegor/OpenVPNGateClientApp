---
name: app-builder
description: 'Build latest Android app and install via ADB. Use for assemble debug or release, detect adb devices, select target serial, and install mobile/tv APK. Mandatory clarification when requirements are ambiguous.'
argument-hint: 'Example: build debug mobile and install to emulator-5554'
user-invocable: true
---

# App Builder Skill

This skill provides a strict workflow for building the latest app artifact and installing it through ADB.

## When to use
- Build and deploy latest app to Android device/emulator.
- Validate installation path for mobile, tv, or both launchers.
- Run repeatable local deployment workflow in one sequence.

## Mandatory clarifications
Ask the user before execution when any of these are not explicit:
- Build type: `debug` or `release`.
- Target app: `mobile`, `tv`, or `both`.
- Target device serial(s) when more than one ADB device is online.

Default only when the user clearly allows defaults:
- Build type: `debug`
- Target app: `mobile`

## Strict procedure
1. Validate prerequisites.
2. Build selected target(s).
3. Detect online ADB devices.
4. Resolve target serial(s).
5. Locate latest APK file(s).
6. Install APK(s).
7. Verify package on device.
8. Return structured result.

## Commands
Use this script:
- [build-check-install.ps1](./scripts/build-check-install.ps1)

Example invocations:
- `pwsh -File .github/skills/app-builder/scripts/build-check-install.ps1 -BuildType debug -Target mobile -DeviceSerial emulator-5554`
- `pwsh -File .github/skills/app-builder/scripts/build-check-install.ps1 -BuildType debug -Target both`

## Result format
- BuildType and Target
- Gradle tasks executed
- APK path(s)
- Detected devices
- Selected devices
- Install status per device
- Verification output
- Next action if blocked
