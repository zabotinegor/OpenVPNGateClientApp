---
description: Mirror-sync CopilotTools agents, skills, scripts, and Claude commands into this repository
---

Sync CopilotTools assets from the latest zabotinegor/CopilotTools@main commit into this repository.

$ARGUMENTS

Load AGENTS.md first. Check session rate limit at start: if `rate_limits.five_hour.used_percentage` >= 90%, checkpoint and stop (see .github/skills/session-limit-tracking/SKILL.md).

For baseline rules, then follow .github/skills/agent-sync/SKILL.md as the authoritative workflow.

Work in the current branch — do not create or switch branches. Do not commit, stage, or push — only sync file contents into the working tree.

Sync scope includes: .github/agents, .github/skills, .github/scripts, .github/hooks, .githooks, .claude/commands, and .claude/settings.json.
For .claude/commands, apply the same mirror-sync logic as for .github/agents: add new files, update changed files, delete stale files not present in source, and exclude paths containing agent-sync or sync-copilot-assets from deletion and gitignore.
Keep branch-guard hooks/scripts trackable and configure target local Git with core.hooksPath=.githooks.
