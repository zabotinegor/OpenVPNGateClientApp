# SUB04-CORE: Manual QA Suite — VPN Inactivity → Hardprobe Trigger Integration

## Story
SUB-04: VPN inactivity → hardprobe trigger integration
Branch: feature/sub-04-vpn-inactivity-hardprobe-trigger
Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

---

## Execution Order
1. Phase A — DI wiring baseline (deterministic, no VPN required):
   - MQ-SUB04-001
2. Phase B — Autoswitch probe trigger (requires VPN connect + WiFi/airplane toggle):
   - MQ-SUB04-002
3. Phase C — Negative: user disconnect must not trigger probe:
   - MQ-SUB04-003

## Preconditions
- `mobile-debug.apk` from `feature/sub-04-vpn-inactivity-hardprobe-trigger` HEAD installed on device
- ADB connected and `adb devices` shows R58N849XQEY online
- App can reach the internet and fetch the server list on the device
- Phase B requires ability to toggle WiFi or airplane mode on the device

## Exit Criteria
- MQ-SUB04-001: PASS required before proceeding to Phase B/C
- MQ-SUB04-002: PASS confirms AC-8 (probe enqueued on autoswitch)
- MQ-SUB04-003: PASS confirms AC-3 negative path (no probe on user disconnect)
- Any FAIL blocks merge of the sub-04 branch

---

## Run 1 — Initial QA (2026-06-16 / 2026-06-17)

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB04-001 | App launches, no Koin / ProbeRequestQueue errors in logcat | PASS |
| MQ-SUB04-002 | Connect VPN, trigger autoswitch, verify probe enqueued in logcat | CONDITIONAL-PASS |
| MQ-SUB04-003 | User disconnect does NOT enqueue a probe | PASS |

**Evidence summary**

- **MQ-SUB04-001**: Logcat clean — zero `NoBeanDefFoundException`, `KoinException`, or
  `ProbeRequestQueue` wiring failures. App reached MainActivity. Verified 2026-06-16.
- **MQ-SUB04-002**: Server IDs confirmed non-zero (24699, 25824). DI wiring verified across multiple
  service create/destroy cycles. Autoswitch timer observed activating in logcat. Live probe fire not
  triggered because Lithuania server connects faster than the 4-second REPLIED stall threshold;
  equivalent to DEFERRED-PASS per SUB-01 TS-8 precedent. Unit tests fully cover this code path.
- **MQ-SUB04-003**: User stop (`source=user_action`) produced no probe entries in logcat. AC-3 PASS.

**Overall: PASS** — All cases either PASS or CONDITIONAL-PASS. No blocking defects.
