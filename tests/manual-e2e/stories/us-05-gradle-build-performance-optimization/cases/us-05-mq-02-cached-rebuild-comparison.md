# MQ-02: Cached Rebuild Comparison and Speedup Measurement

**Test Case ID:** us-05-mq-02  
**Objective:** Validate that the second debug build benefits from cache and runs noticeably faster than the profile baseline.

## Preconditions
- MQ-01 (clean profile build) completed successfully
- Profile baseline metrics recorded
- No source or configuration changes between builds

## Test Steps

1. **Trigger cached rebuild (no clean)**
   - Command: `.\gradlew assembleDebugApp`
   - Capture:
     - Command output with timing
     - Build duration (seconds)
     - Task execution summary (from-cache vs. executed vs. up-to-date)
   - Expected:
     - BUILD SUCCESSFUL
     - Significantly more tasks from cache than profile baseline
     - Noticeably shorter total time (ideally >30% faster)

2. **Compare rebuild timing to baseline**
   - Profile run time: [T1] seconds
   - Cached rebuild time: [T2] seconds
   - Speedup: (T1 - T2) / T1 * 100 = [X]% improvement
   - Expected: >30% faster (cache hit rate > 70%)

3. **Validate cache effectiveness**
   - Extract task counts from both runs
   - Verify: Cached rebuild has fewer executed tasks
   - Expected: >60% of tasks from cache in second run

## Acceptance Criteria
- **AC-5.3**: Second build shows cache improvement over profile baseline (speedup > 30%) ✓
- **AC-2.2**: Cache is configured for local use and improves repeated build performance ✓

## Expected Evidence Output
```
REBUILD SUCCESSFUL:
- Baseline (profile): [T1] seconds, [E1] tasks executed
- Cached rebuild: [T2] seconds, [E2] tasks executed
- Speedup: [X]% faster
- Cache hit rate: [H]%
```

## Pass Condition
- T2 < T1 (cached build is faster)
- At least 30% improvement observed
- Cache hit rate > 60%

## Failure Condition
- T2 >= T1 (no speedup)
- Cache hit rate < 40%
- BUILD FAILED on cached rebuild
