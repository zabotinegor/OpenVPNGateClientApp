# US-03 Manual E2E Test Suite - DEFAULT_V2 Countries Deserialization Stability

## User Story

**US-03**: DEFAULT_V2 country loading must remain stable in debug and minified/release-like execution, keep cache fallback behavior, and avoid startup crash loops when network/cache conditions degrade.

Reference: [docs/userstories/US-03-default-v2-countries-deserialization-stability.md](../../../../docs/userstories/US-03-default-v2-countries-deserialization-stability.md)

## Acceptance Criteria Summary

| ID | Criterion |
|----|-----------|
| AC-1.1 | No abstract-type instantiation crash in release/minified path |
| AC-1.2 | Country list loads from `/api/v2/servers/countries/active` |
| AC-1.3 | Parse/fetch errors emit actionable logs without sensitive payloads |
| AC-1.4 | Behavior parity in minified release build |
| AC-2.1 | Cached countries are used when network fails and cache exists |
| AC-2.2 | Graceful failure when network fails and cache is absent (no crash loop) |
| AC-2.3 | Startup, foreground sync, and settings-triggered sync callers stay stable |
| AC-2.4 | Legacy CSV source behavior remains unaffected |

## Test Cases

| Case | File | Surface | Description |
|------|------|---------|-------------|
| MQ-1 | cases/US-03-MQ-1.md | Android mobile | Clean start on debug and release/minified |
| MQ-2 | cases/US-03-MQ-2.md | Android mobile | Offline startup with existing countries cache |
| MQ-3 | cases/US-03-MQ-3.md | Android mobile | Offline startup with no countries cache |
| MQ-4 | cases/US-03-MQ-4.md | Android TV | TV sanity parity on Leanback target |

## Scope Notes
- MQ-1..MQ-3 require Android mobile ADB target.
- MQ-4 requires Leanback-capable ADB target (`android.software.leanback=true`).
- All cases should be executed against the same implementation commit under validation.
- If release APK is unsigned locally, MQ-1 release execution is blocked and must be reported as blocker evidence.
