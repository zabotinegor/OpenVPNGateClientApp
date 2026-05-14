---
id: US-07-MANUAL-SUITE
title: US-07 manual QA suite
purpose: Validation of DEFAULT_V2 language-aware server list localization
---

## Cases
1. US-07-MQ-01 - DEFAULT_V2 + ENGLISH - verify locale=en in v2 requests and English labels in UI - `../cases/us-07-mq-01-default-v2-english.md`
2. US-07-MQ-02 - DEFAULT_V2 + RUSSIAN - verify locale=ru in v2 requests and Russian labels in UI - `../cases/us-07-mq-02-default-v2-russian.md`
3. US-07-MQ-03 - DEFAULT_V2 + POLISH - verify locale=pl in v2 requests and Polish labels in UI - `../cases/us-07-mq-03-default-v2-polish.md`
4. US-07-MQ-04 - DEFAULT_V2 + SYSTEM - verify system locale is used, fallback to en when blank - `../cases/us-07-mq-04-default-v2-system.md`
5. US-07-MQ-05 - Switch language and reload DEFAULT_V2 - verify labels change without stale cache leakage - `../cases/us-07-mq-05-language-switch-reload.md`
6. US-07-MQ-06 - Regression spot-check LEGACY, VPNGATE, CUSTOM - verify no locale parameter, unchanged behavior - `../cases/us-07-mq-06-regression-non-v2-sources.md`

## Execution Plan
Execute cases sequentially in order US-07-MQ-01 → US-07-MQ-06.
Parallel execution not recommended due to device state and cache dependencies.

## Test Environment
- Device: Mi 9 SE (adb id: e26d5c2f) running MIUI.
- Build: feature/get-servers-with-lang, commit f7bf8a65d40f06c88c45e75573ef730c577b2252.
- Network: Available for API requests.

## Execution Result
*To be updated after manual QA execution.*

### Observed outcomes
- (Pending execution)

### Evidence index
- (Pending execution)
