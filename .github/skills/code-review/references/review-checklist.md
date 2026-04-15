# Review Checklist

Use this checklist for Android/Kotlin code in this repository.

## Architecture and Boundaries

- Keep shared business logic in `src/core`; avoid leaking it into launcher modules.
- Keep `src/mobile` and `src/tv` thin unless change is launcher-specific.
- Respect existing Koin wiring and module boundaries.

## UI and Flow

- Verify state/effect handling consistency in ViewModel/activity flow.
- Check lifecycle safety for UI observers and callback registration.
- Avoid duplicated prompt/navigation triggers.

## Concurrency and Reliability

- Verify coroutine scope ownership, cancellation, and dispatcher usage.
- Check retry/backoff logic and failure-path behavior.
- Detect race conditions in cache/network update sequences.

## Contracts and Data

- Preserve backward compatibility for update/check payload parsing.
- Validate cache key correctness and invalidation logic.
- Ensure locale/platform/build-specific data is handled consistently.

## Security and Privacy

- Do not log secrets, credentials, tokens, or full sensitive URLs.
- Follow `src/docs/logging-policy.md` for logging behavior.

## Build and CI

- Verify Gradle task correctness and required build properties.
- Check workflow changes for tag/version parsing and artifact naming.

## Testing

- Ensure changed behavior is covered by tests.
- Prefer targeted tests plus realistic build/test checks for changed scope.
- Call out any remaining test gaps explicitly.
