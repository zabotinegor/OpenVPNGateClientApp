# Case MQ-US11-001

Title: Notification tap opens app without crash while connected
Surface: Android mobile

## Steps
1. Connect VPN and confirm connected UI state.
2. Send app to background with HOME.
3. Tap persistent VPN notification.
4. Verify app is foregrounded or launched without crash.

## Expected
- App process remains alive.
- Main activity becomes resumed.
- No crash dialog or fatal exception.
