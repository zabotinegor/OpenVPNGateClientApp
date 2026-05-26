---
id: US-07-MQ-SPEC
title: US-07 DEFAULT_V2 language-localized server list manual QA
storyId: US-07
area: Android
surfaces: [android, api]
---

## Scope
- Validate DEFAULT_V2 API v2 requests include locale query parameter derived from app language.
- Validate DEFAULT_V2 UI server/country list reflects localized API response for current app language.
- Validate language switch + reload updates server list labels without stale cross-language cache leakage.
- Validate non-v2 sources (LEGACY, VPNGATE, CUSTOM) are unchanged and do not gain locale parameter.

## Acceptance Criteria Mapping
- AC1 (API v2 request includes app language):
  - Covered by US-07-MQ-01 (ENGLISH), US-07-MQ-02 (RUSSIAN), US-07-MQ-03 (POLISH), US-07-MQ-04 (SYSTEM)
  - Evidence: logcat markers and request inspection
- AC2 (UI list reflects app language for DEFAULT_V2):
  - Covered by US-07-MQ-01 through US-07-MQ-05
  - Evidence: screenshots of localized country/server labels, label changes after language switch
- RAC3 (Non-v2 sources unchanged):
  - Covered by US-07-MQ-06 (regression spot-check LEGACY/VPNGATE/CUSTOM)
  - Evidence: no locale query parameter in logs, behavioral consistency

## Test Data and Environment
- Device: Mi 9 SE (adb id: e26d5c2f) - MIUI.
- App build under validation: feature/get-servers-with-lang, commit f7bf8a65d40f06c88c45e75573ef730c577b2252.
- Network available for API requests.
- Device run-as access available for app shared prefs and cache management.

## Risks and Out of Scope
- **TV validation excluded:** No Leanback-capable target available; DEFAULT_V2 localization is primarily UI/settings behavior applicable to both mobile and TV.
- **Backend localization response validity:** Assumes backend API v2 returns valid localized country/server names for supported locales (en, ru, pl); if backend returns empty/null, test will document fallback behavior.
- **Full multi-language catalog:** Focus on four language settings (SYSTEM, ENGLISH, RUSSIAN, POLISH) per story acceptance criteria; other locales not in scope.
