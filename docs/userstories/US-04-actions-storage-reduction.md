# US-04 - GitHub Actions Storage Reduction for PR Builds

## User story

As a repository maintainer,
I want pull request CI workflow artifacts and caches to use less GitHub Actions storage,
so that CI remains sustainable and cost-efficient without reducing critical PR quality signals.

## Background

Repository discovery confirms the pull request workflow in .github/workflows/build-by-pull-request.yml currently:

- uploads unit test artifacts on every run
- uploads both mobile and TV debug APK artifacts on every run
- does not define retention-days for uploaded artifacts
- uses Gradle caching with write enabled

This combination can increase retained artifact and cache volume over time. The requested scope is a low-risk, incremental reduction strategy that preserves essential PR feedback:

- unit tests must remain mandatory and visible in PR checks
- at least one practical APK access path must remain for reviewer validation
- release workflows must remain functionally intact

## Acceptance criteria

### AC-1 - Artifact retention controls are explicit in PR workflow

| ID | Criterion |
| --- | --- |
| AC-1.1 | Every actions/upload-artifact step in .github/workflows/build-by-pull-request.yml must define retention-days explicitly. |
| AC-1.2 | Unit test report artifacts must use short retention suitable for PR debugging only (target 3 to 7 days). |
| AC-1.3 | Debug APK artifacts must use short retention suitable for PR review only (target 2 to 5 days). |
| AC-1.4 | Retention values must be documented inline in workflow comments or step names so future maintainers understand the storage intent. |

### AC-2 - PR artifact scope is reduced with low-risk behavior

| ID | Criterion |
| --- | --- |
| AC-2.1 | Unit test artifact upload scope must remove redundant or non-essential files while keeping data needed for troubleshooting failed tests. |
| AC-2.2 | Unit test artifacts should not be uploaded on fully successful PR runs unless a manual/debug path explicitly requests them. |
| AC-2.3 | APK artifact publishing must keep at least one practical reviewer download path on PR runs. |
| AC-2.4 | If conditional APK uploads are introduced, conditions must be deterministic and easy to understand from workflow YAML. |

### AC-3 - Gradle cache storage growth is constrained in PR runs

| ID | Criterion |
| --- | --- |
| AC-3.1 | PR workflow Gradle cache configuration must be updated to reduce cache writes that are not essential for PR validation stability. |
| AC-3.2 | The cache strategy must remain low-risk: it may trade some runtime for lower storage use but must not break PR build reproducibility. |
| AC-3.3 | Any cache mode change must be limited to the PR workflow scope and must not alter release workflow behavior. |

### AC-4 - Release workflow behavior remains intact

| ID | Criterion |
| --- | --- |
| AC-4.1 | .github/workflows/release-by-dev.yml, .github/workflows/release-by-main.yml, and .github/workflows/release-by-tag.yml must keep functional behavior unchanged for release artifact generation and publication. |
| AC-4.2 | If retention-related alignment is applied in release workflows for consistency, it must not change release outputs, publishing targets, or versioning logic. |

### AC-5 - Validation and observability

| ID | Criterion |
| --- | --- |
| AC-5.1 | At least one PR workflow run after changes must confirm tests execute and still gate PR quality as before. |
| AC-5.2 | At least one PR workflow run after changes must confirm reviewer-accessible APK artifact availability through the kept path. |
| AC-5.3 | Validation notes must include before/after artifact list and retention settings from workflow configuration to demonstrate expected storage reduction. |

## Out of scope

- Replacing GitHub Actions with another CI system
- Changing release signing, release publication, or backend version push behavior
- Reworking Android build tasks, product flavors, or module packaging
- Adding high-complexity CI orchestration that increases operational risk
- Any source-code feature changes in src modules unrelated to CI workflow storage behavior

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | Aggressive artifact reduction can remove evidence needed for debugging intermittent PR failures. | Keep troubleshooting-focused artifacts for failure paths and retain at least one APK path. |
| R-2 | PR cache write reduction may increase build duration for some runs. | Acceptable trade-off if workflow remains stable and deterministic. |
| R-3 | Conditional artifact logic can become confusing for contributors. | Require explicit, readable conditions and brief inline intent notes in YAML. |
| R-4 | Team expectations for TV APK availability on every PR may differ. | Default requirement is at least one practical APK path; TV-specific policy can be tightened later if needed. |

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

- .github/workflows/build-by-pull-request.yml

### Optional consistency check only

- .github/workflows/release-by-dev.yml
- .github/workflows/release-by-main.yml
- .github/workflows/release-by-tag.yml

### Low-risk implementation direction

- add explicit retention-days to each upload-artifact usage in PR workflow
- trim unit test artifact paths to failure-diagnostics essentials
- keep one default APK artifact path for reviewers, with optional conditional logic for additional APK artifacts
- tune setup-gradle cache mode in PR workflow to reduce storage growth while preserving successful builds

## Test scenarios

### Automated workflow validation

| ID | Scenario |
| --- | --- |
| TS-1 | Pull request run with successful tests confirms required checks remain green and required APK path remains downloadable. |
| TS-2 | Pull request run with an induced test failure confirms failure diagnostics artifact is still uploaded and retained with configured retention-days. |
| TS-3 | Workflow YAML inspection confirms all upload-artifact steps define retention-days and match approved short retention targets. |
| TS-4 | Workflow YAML inspection confirms PR cache mode changes are scoped to PR workflow only. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | From a PR, reviewer can open workflow run and retrieve the kept APK artifact path without extra tooling. |
| MQ-2 | Compare workflow run summary before and after implementation to verify reduced artifact footprint and explicit retention configuration. |

## Definition of done

- All acceptance criteria are implemented in workflow configuration.
- PR workflow preserves test gating and at least one practical APK download path.
- Artifact retention and scope changes are explicit and documented in YAML.
- Release workflows remain functionally unchanged unless minimal consistency-only retention metadata is added.
- Validation evidence confirms expected storage reduction behavior.