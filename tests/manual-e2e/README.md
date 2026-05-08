# Manual E2E Testing

This directory is the canonical location for manual end-to-end test documentation and evidence for Android mobile and TV surfaces.

## Structure
- [automation/](automation): reusable PowerShell helpers to execute repeatable manual checks.
- [stories/](stories): organized story folders, each containing specs, cases, and SUITE.md for one feature/story.
- [reference/](reference): validated notes and supporting references organized by topic.

## Story Organization

Each story folder follows this structure:
```
stories/{story-id}-{kebab-title}/
  ├── SUITE.md               (main acceptance suite)
  ├── specs/
  │   └── SPEC-*.md
  └── cases/
      └── CASE-*.md
```

### US-01 - DEFAULT_V2 Lazy Loading
- Location: [stories/US-01-v2-lazy-loading/](stories/US-01-v2-lazy-loading/)
- Suite: [stories/US-01-v2-lazy-loading/SUITE.md](stories/US-01-v2-lazy-loading/SUITE.md)
- Case: [stories/US-01-v2-lazy-loading/cases/US-01-V2-LAZY-LOADING-001.md](stories/US-01-v2-lazy-loading/cases/US-01-V2-LAZY-LOADING-001.md)
- Evidence: [../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading](../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading)

### US-02 - DEFAULT_V2 Legacy Behavior Parity
- Location: [stories/US-02-default-v2-legacy-behavior-parity/](stories/US-02-default-v2-legacy-behavior-parity/)

### US-03 - DEFAULT_V2 Countries Deserialization Stability
- Location: [stories/US-03-default-v2-countries-deserialization-stability/](stories/US-03-default-v2-countries-deserialization-stability/)

### VPN-PAUSE-RESUME-FLOW
- Location: [stories/VPN-PAUSE-RESUME-FLOW/](stories/VPN-PAUSE-RESUME-FLOW/)
- Suite: [stories/VPN-PAUSE-RESUME-FLOW/SUITE.md](stories/VPN-PAUSE-RESUME-FLOW/SUITE.md)
- Cases:
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-001.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-001.md)
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-002.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-002.md)
  - [stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-003.md](stories/VPN-PAUSE-RESUME-FLOW/cases/VPN-PAUSE-003.md)

### TV-DRAWER-FALSE-CLICK
- Location: [stories/TV-DRAWER-FALSE-CLICK/](stories/TV-DRAWER-FALSE-CLICK/)
- Suite: [stories/TV-DRAWER-FALSE-CLICK/SUITE.md](stories/TV-DRAWER-FALSE-CLICK/SUITE.md)
- Case: [stories/TV-DRAWER-FALSE-CLICK/cases/TV-DRAWER-FALSE-CLICK-001.md](stories/TV-DRAWER-FALSE-CLICK/cases/TV-DRAWER-FALSE-CLICK-001.md)
