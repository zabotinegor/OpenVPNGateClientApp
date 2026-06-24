# Testing Guidelines

## General

* Write tests for all new features and bug fixes
* Prefer automated tests over manual verification
* Use appropriate test types: unit, integration, e2e
* Before any `commit` or `push` that follows code changes, run the full available validation set for the current change scope; parallel or asynchronous execution is fine when practical, but do not commit or push known-red changes unless the user explicitly asks for that tradeoff

## Unit Tests

* Test business logic in isolation
* Mock external dependencies
* Cover edge cases and error paths

## Integration Tests

* Test component interactions
* Use real databases for data layer tests
* Verify end-to-end flows within the application

## E2E Tests

* Required for UI changes
* Use Playwright for browser automation
* Test user-facing functionality
* Ensure tests run in CI/CD
* **Manual QA:** Use Docker Compose via `OpenVPNClientServer.LocalDeployment/` for all API/Web testing (see `tests/manual-e2e/environment/DOCKER-TESTING-POLICY.md`)
* **Automated E2E:** May start API directly or via Docker; CI/CD defines the strategy
* E2E setup must apply migrations, seed deterministic test data, and clean it after the run

## Test Data

* Use realistic test data
* For missing data in local development:
  * Add to appsettings.Local.json for configuration
  * Seed database via migrations or scripts
  * Ensure data is available for tests

## CI/CD and Deployment

* Treat `.github/workflows/deploy.yml` as the automatic deploy entrypoint for `main`
* Treat `.github/workflows/migrations.yml` as the automatic migration application path before API deploy
* Generated idempotent SQL migration scripts are review/audit artifacts, not a manual prerequisite for deploy unless a human explicitly chooses script-based rollout
* When changing EF migrations or persistence shape, ensure PR CI still validates the migration chain on temporary SQL and still uploads the migration script artifacts
* Do not introduce a deployment flow that requires a human to remember a separate manual migration step unless the user explicitly asks for that tradeoff
* Before `commit`/`push`, verify the changed area with the broadest realistic local validation available for that scope, and report exactly what did and did not run

---

## Test Quality Checklist

* Add or update tests when behavior changes
* Prefer deterministic tests
* Cover:

  * main flows
  * edge cases
  * failure scenarios
* Avoid brittle tests
* Do not test implementation details unnecessarily
