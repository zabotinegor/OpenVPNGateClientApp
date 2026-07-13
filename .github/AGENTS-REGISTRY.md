# Agent and Skill Registry

Source of truth for agent-to-skill mapping and delegation.

## Agents

| Agent | File | Skill | Delegates to |
|-------|------|-------|--------------|
| agent-sync | `.github/agents/agent-sync.agent.md` | agent-sync | — |
| code-implementator | `.github/agents/code-implementator.agent.md` | code-implementator | Code Review |
| code-review | `.github/agents/code-review.agent.md` | code-review | Scope Quality Gate, Code Implementator |
| developer-flow-orchestrator | `.github/agents/developer-flow-orchestrator.agent.md` | developer-flow-orchestrator | code-implementator, code-review, scope-quality-gate, docs-maintainer, github-create-pr, github-review-comments, github-pr-merger |
| e2e-manual-tester | `.github/agents/e2e-manual-tester.agent.md` | e2e-manual-testing | User Story Spec, Code Implementator, Docs Maintainer |
| github-create-pr | `.github/agents/github-create-pr.agent.md` | github-create-pr | code-review, scope-quality-gate, GitHub Review Comments |
| github-create-release | `.github/agents/github-create-release.agent.md` | github-create-pr | github-create-pr, code-review, scope-quality-gate, GitHub Review Comments |
| github-pr-merger | `.github/agents/github-pr-merger.agent.md` | github-pr-merger | — |
| github-pr-merger-release | `.github/agents/github-pr-merger-release.agent.md` | github-pr-merger | github-pr-merger |
| github-review-comments | `.github/agents/github-review-comments.agent.md` | github-review-comments | GitHub PR Merger |
| docs-maintainer | `.github/agents/docs-maintainer.agent.md` | docs-maintenance | GitHub Create PR |
| scope-quality-gate | `.github/agents/scope-quality-gate.agent.md` | scope-quality-gate | Manual QA, Code Implementator |
| user-story-spec | `.github/agents/user-story-spec.agent.md` | user-story-spec | Code Implementator |
| update-engine | `.github/agents/update-engine.agent.md` | update-engine | — |
| master-plan | `.github/agents/master-plan.agent.md` | master-plan | — |
| bug-flow | `.github/agents/bug-flow.agent.md` | bug-flow | code-implementator, code-review, scope-quality-gate, docs-maintainer, github-create-pr, github-review-comments, github-pr-merger |
| release-flow-orchestrator | `.github/agents/release-flow-orchestrator.agent.md` | release-flow-orchestrator | code-review, scope-quality-gate, e2e-manual-testing, github-create-pr, github-review-comments, github-pr-merger |

## Skills

| Skill | File | References |
|-------|------|------------|
| agent-sync | `.github/skills/agent-sync/SKILL.md` | sync-copilot-assets.ps1 |
| code-implementator | `.github/skills/code-implementator/SKILL.md` | — |
| code-review | `.github/skills/code-review/SKILL.md` | review-checklist-*.md |
| developer-flow-orchestrator | `.github/skills/developer-flow-orchestrator/SKILL.md` | poll-pr-reviews.ps1, post-bot-review-request.ps1, start-review-round.ps1, get-pr-bot-comments.ps1, resolve-pr-threads.ps1 |
| e2e-manual-testing | `.github/skills/e2e-manual-testing/SKILL.md` | artifact-contract.md, environment-and-auth.md, scenario-patterns.md |
| github-create-pr | `.github/skills/github-create-pr/SKILL.md` | pr-style.md |
| github-pr-merger | `.github/skills/github-pr-merger/SKILL.md` | squash-merge-style.md |
| github-review-comments | `.github/skills/github-review-comments/SKILL.md` | review-comment-style.md |
| docs-maintenance | `.github/skills/docs-maintenance/SKILL.md` | documentation-sync-checklist.md, docs-report-template.md |
| user-story-spec | `.github/skills/user-story-spec/SKILL.md` | user-story-spec-template.md |
| update-engine | `.github/skills/update-engine/SKILL.md` | engine-update-checklist.md |
| master-plan | `.github/skills/master-plan/SKILL.md` | update-master-plan-status.ps1 |
| bug-flow | `.github/skills/bug-flow/SKILL.md` | post-bot-review-request.ps1, start-review-round.ps1, poll-pr-reviews.ps1, get-pr-bot-comments.ps1, resolve-pr-threads.ps1 |
| release-flow-orchestrator | `.github/skills/release-flow-orchestrator/SKILL.md` | start-review-round.ps1, poll-pr-reviews.ps1, get-pr-bot-comments.ps1, resolve-pr-threads.ps1 |
| scope-quality-gate | `.github/skills/scope-quality-gate/SKILL.md` | — |
| session-limit-tracking | `.github/skills/session-limit-tracking/SKILL.md` | checkpoint-schema.md, agent-integration.md; schema-v2 renewable reset recovery with mechanical unarmed/stale-wait gates |

## Topic Files (AGENTS/)

| File | When to load |
|------|--------------|
| `AGENTS/coding-standards.md` | Writing or reviewing code |
| `AGENTS/testing-guidelines.md` | Writing tests, quality gate, deployment |
| `AGENTS/data-access-efcore.md` | Working with EF Core, migrations, database |
| `AGENTS/error-handling-logging.md` | Implementing error handling or logging |
| `AGENTS/code-review-expectations.md` | Performing code review |
| `AGENTS/agent-runtime-artifacts.md` | Creating handoffs, prompts, or SDLC artifacts |
| `AGENTS/manual-qa-environment.md` | Manual QA testing |
| `AGENTS/cross-repo-sync.md` | Syncing agents/skills across repos |

## Key Rules

- **Protected branches:** `main`, `dev`, `master`, `develop` — direct commit/push forbidden
- **Language:** Use the language of the user's current request
- **Handoffs:** Return in chat/buttons, not markdown files
- **SDLC status:** Use `.github/scripts/update-sdlc-status.ps1` with `-SessionId`; each flow carries an enforced 15-min lease (exit 2 = foreign lease, stop)
- **Minimum report contract:** what was done, what went wrong, what was fixed, evidence, what remains, blockers

## Runtime State

- `.sdlc/status.json` — gitignored runtime state for SDLC coordination
- `.sdlc/session.json` — gitignored session rate limit tracking and checkpoint state
- Nested `.sdlc/status.json` files are drift; merge into root and remove

## Maintenance

When agents/skills are added, removed, or changed:
1. Update this registry
2. Update `AGENTS.md` topic file table
3. Re-check all markdown links
