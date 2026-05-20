# US-09 Manual QA Spec: DEFAULT_V2 City/UTC Display and Address Contract

## Scope
- Story: US-09 city/UTC display on server select and main screens for DEFAULT_V2 only, with address label/value contract.
- Surfaces: Android mobile mandatory.
- Focus: 
  - Server select list cards show city/UTC (2 lines), city-only (1 line), or IP fallback (1 line) for DEFAULT_V2
  - Main screen shows "City" label with city/UTC format for DEFAULT_V2 when city metadata exists, city only when UTC is missing, or "Address" with IP for non-DEFAULT_V2 sources or missing city metadata
  - Source switching behavior and persistence after reopen

## Acceptance Criteria Mapping
- AC-1: Server select cards render city/UTC (2 lines), city-only (1 line), or IP fallback (1 line) for DEFAULT_V2
- AC-2: Main screen shows "City" label with city/UTC format for DEFAULT_V2 when city metadata exists, city only when UTC is missing, or "Address" with IP for non-DEFAULT_V2 sources or missing city metadata
- AC-3: Label switching ("Address" ↔ "City") occurs when source changes
- AC-4: Persistence after source switch and app reopen
- AC-5: Non-DEFAULT_V2 sources show IP-only with no city/UTC rendering
- AC-6: Null-safety and graceful fallbacks

## Test Data and Environment
- Android phone target with the app installed and launchable from exported splash.
- DEFAULT_V2, Legacy CSV, VPN Gate, and Custom source switching available in Settings.
- Network access available so DEFAULT_V2 server lists can refresh with city/UTC metadata.
- Use the source-switch pattern documented in `tests/manual-e2e/environment/android-miui-manual-qa-notes.md` when the device is MIUI.

## Risks and Out of Scope
- TV validation is optional and only performed if a Leanback-capable target is available.
- Backend contract changes are out of scope.
- Locale/translation testing is optional unless localization issues are discovered during execution.