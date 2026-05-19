---
id: US-09-MANUAL-SUITE
title: US-09 manual QA suite
purpose: Regression
---

## Cases
1. US-09-MQ-01 - DEFAULT_V2 list cards show city and UTC with clean fallbacks - `../cases/us-09-mq-01-default-v2-list-city-utc-and-fallbacks.md`
2. US-09-MQ-02 - DEFAULT_V2 selected server and main screen show actual city and UTC - `../cases/us-09-mq-02-default-v2-selected-and-main-city-utc.md`
3. US-09-MQ-03 - DEFAULT_V2 city and UTC remain stable after reconnect and reopen - `../cases/us-09-mq-03-default-v2-reconnect-reopen-persistence.md`
4. US-09-MQ-04 - Legacy CSV and VPN Gate keep existing server card behavior - `../cases/us-09-mq-04-non-v2-source-switch-regression.md`

## Execution Plan
Run the cases sequentially on one Android phone so selection and persistence state can be observed across the whole flow.

## Latest Execution Result
- Date: 2026-05-19
- Device: Android phone (ADB-connected Mi 9 SE)
- Outcome: FAILED
- Case status:
	- US-09-MQ-01: FAILED
	- US-09-MQ-02: FAILED
	- US-09-MQ-03: NOT COMPLETED
	- US-09-MQ-04: NOT COMPLETED
- Evidence root: artifacts/manual-qa/2026-05-19-us09-manual-qa
