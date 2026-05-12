# MQ-05: Configuration and Safety Constraints Validation

**Test Case ID:** us-05-mq-05  
**Objective:** Validate configuration syntax, absence of secrets, SWIG cache declarations, and safety constraints.

## Preconditions
- Access to:
  - src/gradle.properties
  - src/external/OpenVPNEngine/main/build.gradle.kts
  - Source code for module wiring verification

## Test Steps

1. **Verify gradle.properties settings**
   - File: `src/gradle.properties`
   - Check for:
     - `org.gradle.parallel=true`
     - `org.gradle.jvmargs` set to 4GB or higher (e.g., -Xmx4g)
     - `org.gradle.workers.max` set and reasonable (e.g., 8 or system-dependent)
     - `org.gradle.caching=true`
     - `org.gradle.configureondemand=true`
   - Expected: All properties present, correct format, no syntax errors

2. **Verify no secrets in configuration**
   - Search gradle.properties for:
     - URLs without scheme prefixes (should be in -P properties, not gradle.properties)
     - Passwords, API keys, tokens
   - Expected: No secrets found in configuration

3. **Verify SWIG cache declarations**
   - File: `src/external/OpenVPNEngine/main/build.gradle.kts`
   - Check for:
     - `generateOpenVPN3Swig*` tasks (e.g., `generateOpenVPN3SwigAndroid`)
     - `inputs.files()` declarations with source paths
     - `outputs.dir()` declarations with output directories
     - `outputs.cacheIf{true}` to enable Exec task caching
   - Expected: All SWIG tasks have complete cache declarations

4. **Verify module wiring integrity**
   - Check `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt`
   - Verify all service/repository/ViewModel bindings are still present
   - Expected: No changes to DI wiring; bindings intact

5. **Verify release hardening configuration**
   - File: `src/mobile/build.gradle.kts` and `src/tv/build.gradle.kts`
   - Check:
     - `isMinifyEnabled=true` in release builds
     - `isShrinkResources=true` in release builds
   - Expected: Release hardening settings unchanged

## Acceptance Criteria
- **AC-1.1**: `org.gradle.parallel=true` is set ✓
- **AC-1.2**: `org.gradle.jvmargs` set to 4GB ✓
- **AC-1.3**: `org.gradle.workers.max` configured ✓
- **AC-1.4**: No syntax errors, no secrets ✓
- **AC-2.1**: `org.gradle.caching=true` ✓
- **AC-3.1**: `org.gradle.configureondemand=true` ✓
- **AC-4.1 through AC-4.3**: SWIG cache declarations complete ✓
- **AC-6.4**: Module structure and safety constraints intact ✓

## Expected Evidence Output
```
CONFIGURATION VALIDATION:
✓ gradle.properties settings correct
✓ No secrets found
✓ SWIG cache declarations complete
✓ Module wiring intact
✓ Release hardening unchanged
```

## Pass Condition
All 8 checks pass without findings.

## Failure Condition
Any configuration is missing, incorrect, or contains secrets/regressions.
