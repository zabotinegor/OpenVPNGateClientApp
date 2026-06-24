# Manual QA Environment Knowledge

* **Docker-based testing:** All Manual QA stories requiring API or Web services must use Docker. See `tests/manual-e2e/environment/DOCKER-TESTING-POLICY.md` for the full policy and requirements.
* Helper scripts and Docker configs are centralized in `OpenVPNClientServer.LocalDeployment/` (start_all.bat, docker-compose.yml, etc.).
* Before blocking on environment readiness, Manual QA must inspect service-owned runbooks, root README/docs, `OpenVPNClientServer.LocalDeployment`, `scripts/e2e`, Docker compose files, package scripts, launch profiles, CI setup, and automated test setup scripts.
* If a command, service order, readiness signal, cleanup step, or troubleshooting fix helps testing, document the non-secret reusable detail under `tests/manual-e2e/environment/`.
* Story-specific runbooks should link to the shared environment runbook for common setup rather than becoming the only source of environment knowledge.
* Do not store real secrets, tokens, private endpoints, personal machine paths, or long logs in Manual QA docs.
