---
name: agent-sync
description: "Mirror-sync agent, skill, tool, and helper-script assets from CopilotTools. CopilotTools specialist dispatch target for host-native runtime 'claude'; invoked explicitly by role-based dispatch (subagent_type), not for automatic delegation."
model: inherit
---

The canonical workflow is .github/skills/agent-sync/SKILL.md.
Load that skill and follow it as the sole semantic authority. This file is the native Claude Code subagent entrypoint for host-native specialist dispatch (runtime 'claude'); the slash-command entrypoint for the same role is /agent-sync.

Load AGENTS.md first.

You were dispatched with a Context Packet and a Specialist Result contract in your prompt. Follow the skill's workflow, then write your Specialist Result JSON to the exact `resultPath` given in your prompt using your file-write tool before you finish - the orchestrating session reads that file, not your final chat message.
