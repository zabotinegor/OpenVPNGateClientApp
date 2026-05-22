# Logging Policy (`core`/`tv`/`mobile`)

## Levels
- `error`: operation failed and user-visible/functional flow is broken.
- `warning`: degraded behavior or fallback/retry path, but flow continues.
- `info`: key lifecycle/business events used for diagnostics.
- `debug`: verbose diagnostics, periodic state, and high-frequency internals.

## Release behavior
- Release keeps `info`, `warning`, `error`.
- Release drops `debug` and `verbose`.

## Anti-spam
- Repeated high-frequency messages should use throttled logging.
- Default throttle window is 30 seconds keyed by `priority + tag + message key`.
- Suppressed events are summarized with a single info message after the window.

## Watchdog decision logs
- Connected-state watchdog logs must remain structured and privacy-safe.
- Include diagnostic context needed for recovery analysis: traffic delta bucket/value, probe outcome, threshold counter, and recovery attempt index.
- Log healthy restore events with recovery-state markers (for example, degraded cleared and reconnect-after-restore state) to confirm state reset behavior.
- Do not include credentials, full private endpoint URLs, or raw VPN config payloads.

## Privacy
- Avoid logging secrets, full configs, credentials, or full URLs with sensitive query params.
- Prefer redacted values in logs.
