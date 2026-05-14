# US-08 Manual QA Spec: DEFAULT_V2 Selected Country Relocalization

## Scope
- Story: US-08 default-v2 selected country relocalization on language switch.
- Surfaces: Android mobile mandatory, Android TV if Leanback target exists.

## Acceptance Criteria Mapping
- AC1: Verify selected DEFAULT_V2 country label relocalizes after language change.
- AC2: Verify persisted selection remains index-safe after language change.
- AC3: Verify language switch triggers relocalization path in-session.
- AC4: Verify non-v2 sources remain unchanged.
- AC5: Verify logging and regression safety expectations.

## Cases
- us-08-mq-01-english-to-russian-relocalization.md
- us-08-mq-02-russian-to-english-relocalization.md
- us-08-mq-03-cache-warm-no-mixed-language.md
- us-08-mq-04-preserve-index-when-server-exists.md
- us-08-mq-05-safe-fallback-when-server-disappears.md
- us-08-mq-06-non-v2-no-relocalization.md

## Execution Notes
- Mobile execution was run on Mi 9 SE (adb id: e26d5c2f).
- TV execution was blocked due missing Leanback-capable target.