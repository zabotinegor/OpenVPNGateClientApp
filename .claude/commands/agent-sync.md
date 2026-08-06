---
description: Mirror-sync CopilotTools agents, skills, scripts, and Claude commands into this repository
---

Sync CopilotTools assets from the latest zabotinegor/CopilotTools@main commit into this repository.

$ARGUMENTS

Load AGENTS.md first, but skip its MANDATORY FIRST STEP: agent-sync is exempt from every session-limit and session-recovery rule. Do not run init-session.ps1, do not check session usage or reset time, do not arm a recovery cron, do not checkpoint to .sdlc/status.json, and do not run check-tracking-preflight.ps1. A sync is short and idempotent — if it is cut short, just run it again.

For baseline rules, then follow .github/skills/agent-sync/SKILL.md as the authoritative workflow.

Work in the current branch — do not create or switch branches. Do not commit, stage, or push — only sync file contents into the working tree.

Sync scope includes: .github/agents, .github/skills, .github/scripts, .github/hooks, .githooks, .claude/commands, .claude/settings.json, and .mcp.json (merged, not overwritten — this is how the clickup MCP server entry lands).

After the file sync, finish ClickUp setup: run `.github/scripts/setup-clickup.ps1 -Probe` and act on what it reports, per the skill's "ClickUp setup and verification" section. Agent Sync is the only owner of that setup — anything it cannot do itself (MCP OAuth via /mcp, creating the REST token, supplying real List IDs) must be handed to the user as explicit numbered steps, never left implied.
For .claude/commands, apply the same mirror-sync logic as for .github/agents: add new files, update changed files, delete stale files not present in source, and exclude paths containing agent-sync or sync-copilot-assets from deletion and gitignore.
Keep branch-guard hooks/scripts trackable and configure target local Git with core.hooksPath=.githooks.
