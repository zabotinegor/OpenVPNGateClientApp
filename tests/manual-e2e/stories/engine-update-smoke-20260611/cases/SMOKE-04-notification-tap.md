# SMOKE-04 — Notification tap → MainActivity (US-11 regression)

**Result: PASS**

## Steps

1. VPN connected (CONNECTED state)
2. Pressed HOME, opened notification shade via `cmd statusbar expand-notifications`
3. Tapped "Австралия" VPN notification row at ~(540, 1300)

## Evidence

Notification visible in shade (screenshot 10-shade-cmd.png):
```
Австралия  12:13
↑2,4 кбит/с 8,7 МБ – ↓5,1 кбит/с 1,5 МБ
```

Post-tap dumpsys:
```
topResumedActivity = .mobile.MainActivity (task 154)
mLastPausedActivity = .mobile.SplashActivity (t-1 f)
```

- `topResumedActivity = MainActivity` ✅
- `SplashActivity` in `t-1 f` = new instance, finishing, not disrupting existing task ✅
- No `RuntimeException` or `NullPointerException` in logcat ✅
- VPN remained `LEVEL_CONNECTED` throughout ✅

Post-tap screenshot (12-current-state.png): MainActivity showing CONNECTED state, 00:18:08 duration, ПАУЗА + ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ buttons present.

## Observation (not a defect)

The notification tap briefly shows SplashActivity (~3 s) before transitioning to MainActivity. SplashActivity instance was in `t-1` (no-task) with `f` (finishing) — it did not reset the existing task 154. This is consistent with the US-11 fix: `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` was removed so the existing task is not disrupted; the notification intent routes through a transient SplashActivity that immediately navigates to MainActivity.

Primary US-11 criterion (no crash on notification tap) is met.
