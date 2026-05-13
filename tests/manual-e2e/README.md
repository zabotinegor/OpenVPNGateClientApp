# Manual E2E Testing

This directory is the canonical location for manual end-to-end test documentation and evidence for Android mobile and TV surfaces.

## Structure
- [automation/](automation): reusable PowerShell helpers to execute repeatable manual checks.
- [stories/](stories): organized story folders, each containing specs, cases, and suite documentation for one feature/story.
- [reference/](reference): validated notes and supporting references organized by topic.

## Story Organization

Each story folder follows this structure:
```
stories/{story-id}-{kebab-title}/
  ├── suites/
  │   └── *-suite-*.md       (main acceptance suite)
  ├── specs/
  │   └── *-spec*.md
  └── cases/
      └── *-case*.md
```

### US-01 - DEFAULT_V2 Lazy Loading
- Location: [stories/US-01-v2-lazy-loading/](stories/US-01-v2-lazy-loading/)
- Suite: [stories/US-01-v2-lazy-loading/suites/SUITE.md](stories/US-01-v2-lazy-loading/suites/SUITE.md)
- Case: [stories/US-01-v2-lazy-loading/cases/US-01-V2-LAZY-LOADING-001.md](stories/US-01-v2-lazy-loading/cases/US-01-V2-LAZY-LOADING-001.md)
- Evidence: [../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading](../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading)

### US-02 - DEFAULT_V2 Legacy Behavior Parity
- Location: [stories/US-02-default-v2-legacy-behavior-parity/](stories/US-02-default-v2-legacy-behavior-parity/)

### US-03 - DEFAULT_V2 Countries Deserialization Stability
- Location: [stories/US-03-default-v2-countries-deserialization-stability/](stories/US-03-default-v2-countries-deserialization-stability/)

### BUG - Startup crash on Android 11+ due to URLEncoder.encode incompatibility
- Location: [stories/bug-startup-crash-urlencoder-android11/](stories/bug-startup-crash-urlencoder-android11/)
- Suite: [stories/bug-startup-crash-urlencoder-android11/suites/bug-startup-crash-urlencoder-android11-suite-full.md](stories/bug-startup-crash-urlencoder-android11/suites/bug-startup-crash-urlencoder-android11-suite-full.md)
- Spec: [stories/bug-startup-crash-urlencoder-android11/specs/bug-startup-crash-urlencoder-android11-mq-spec.md](stories/bug-startup-crash-urlencoder-android11/specs/bug-startup-crash-urlencoder-android11-mq-spec.md)
- Cases:
  - [stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-01-android-11-app-launch.md](stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-01-android-11-app-launch.md)
  - [stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-03-logcat-analysis.md](stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-03-logcat-analysis.md)

### VPN-PAUSE-RESUME-FLOW
- Location: [stories/VPN-PAUSE-RESUME-FLOW/](stories/VPN-PAUSE-RESUME-FLOW/)
- Suite: [stories/VPN-PAUSE-RESUME-FLOW/suites/SUITE.md](stories/VPN-PAUSE-RESUME-FLOW/suites/SUITE.md)
- Cases:
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-001.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-001.md)
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-002.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-002.md)
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-003.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-003.md)

### TV-DRAWER-FALSE-CLICK
- Location: [stories/TV-DRAWER-FALSE-CLICK/](stories/TV-DRAWER-FALSE-CLICK/)
- Suite: [stories/TV-DRAWER-FALSE-CLICK/suites/SUITE.md](stories/TV-DRAWER-FALSE-CLICK/suites/SUITE.md)
- Case: [stories/TV-DRAWER-FALSE-CLICK/cases/TV-DRAWER-FALSE-CLICK-001.md](stories/TV-DRAWER-FALSE-CLICK/cases/TV-DRAWER-FALSE-CLICK-001.md)

