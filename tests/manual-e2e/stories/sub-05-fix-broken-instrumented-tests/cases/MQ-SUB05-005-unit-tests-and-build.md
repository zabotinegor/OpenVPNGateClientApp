# MQ-SUB05-005 — Unit tests and debug build still pass

## Preconditions
- Source code at `fix/sub-05-instrumented-tests` HEAD
- Gradle available from `src/` directory

## Steps
1. Run debug build:
   ```
   cd src && ./gradlew assembleDebugApp
   ```
2. Run unit tests:
   ```
   cd src && ./gradlew testDebugUnitTestApp
   ```
3. Capture build and test output
4. Verify BUILD SUCCESSFUL for both tasks
5. Check test counts (all tests pass, 0 failures)

## Expected
- `assembleDebugApp`: BUILD SUCCESSFUL
- `testDebugUnitTestApp`: BUILD SUCCESSFUL with all tests passing
- No new test failures introduced

## Evidence Required
- Gradle build output (BUILD SUCCESSFUL confirmation)
- Unit test summary (pass/fail/skip counts)

## Cleanup
- None required
