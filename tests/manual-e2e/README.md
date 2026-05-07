# Manual E2E Testing

This directory is the canonical location for manual end-to-end test documentation and evidence for Android mobile and TV surfaces.

## Structure
- [automation/README.md](automation/README.md): reusable PowerShell helpers to execute repeatable manual checks.
- [specs](specs): high-level behavior specifications mapped to suites.
- [suites](suites): grouped acceptance suites with execution order.
- [cases](cases): individual step-by-step test cases.
- [reference](reference): validated notes and supporting references.

## US-01 Coverage (Lazy Loading V2 Server Source)
- Specification: [specs/SPEC-US-01-V2-LAZY-LOADING.md](specs/SPEC-US-01-V2-LAZY-LOADING.md)
- Suite: [suites/US-01-V2-LAZY-LOADING-CORE.md](suites/US-01-V2-LAZY-LOADING-CORE.md)
- Case: [cases/US-01-V2-LAZY-LOADING-001.md](cases/US-01-V2-LAZY-LOADING-001.md)
- Evidence folder: [../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading](../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading)

## Existing Suites
- [suites/VPN-PAUSE-CORE.md](suites/VPN-PAUSE-CORE.md)
- [suites/TV-DRAWER-FALSE-CLICK-CORE.md](suites/TV-DRAWER-FALSE-CLICK-CORE.md)
