# MQ-01: Clean Profile Build and Timing Baseline

**Test Case ID:** us-05-mq-01  
**Objective:** Establish a clean build baseline with profiling enabled to measure build time, task counts, and cache behavior.

## Preconditions
- OS: Windows
- Gradle wrapper available: `src/gradlew.bat`
- Working directory: `src/`
- git repo at clean state with committed changes from implementation

## Test Steps

1. **Clean build state**
   - Command: `.\gradlew clean`
   - Expected: clean target deletes build artifacts without errors

2. **Profile build with enabled caching**
   - Command: `.\gradlew assembleDebugApp --profile`
   - Capture:
     - Full command output (stdout/stderr)
     - Total build duration (visible in Gradle output)
     - Profile report file path (typically `src/build/reports/profile/profile-*.html`)
   - Expected:
     - BUILD SUCCESSFUL status
     - Profile report generated
     - No task execution errors

3. **Extract profile metrics**
   - Open generated profile report
   - Record:
     - Total build time (seconds)
     - Number of tasks: total, up-to-date, from-cache, executed
     - Critical path task list (longest-running tasks)

## Acceptance Criteria
- **AC-5.1**: Profile build completes and generates report ✓
- **AC-5.2**: Report contains actionable metrics ✓
- **AC-6.3**: APK assembly succeeds ✓

## Expected Evidence Output
```
BUILD SUCCESSFUL with metrics:
- Total time: [X seconds]
- Tasks: [T] total, [C] from cache, [E] executed, [U] up-to-date
- Report: src/build/reports/profile/profile-YYYY-MM-DD-HH-MM-SS.html
```

## Pass Condition
Profile build completes successfully, report generated, and contains cache/execution metrics.

## Failure Condition
- BUILD FAILED with task errors
- Profile report not generated
- Missing cache metrics in report
