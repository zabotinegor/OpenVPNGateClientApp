---
description: Audit and update repository documentation to reflect current behavior and new features
---

Audit and update documentation following .github/skills/docs-maintenance/SKILL.md.

$ARGUMENTS

Load AGENTS.md first.
Check session rate limit at start: if `rate_limits.five_hour.used_percentage` >= 90%, checkpoint and stop (see .github/skills/session-limit-tracking/SKILL.md).

Then:

1. Read .sdlc/status.json and enforce steps.manualQa.status=passed|notNeeded before starting.
   - steps.manualQa.status=passed is authoritative QA sign-off for the current cycle.
   - Historical defects with resolved|verified|closed status do not block docs.
   - If manualQa=passed but defects[] still has open statuses, stop with BLOCKED.

2. Load governance context: AGENTS.md, README.md, .github/AGENTS-REGISTRY.md, .github/FRONTMATTER-SCHEMA.md, and local overlays (AGENTS.local.md, README.local.md).

3. Run drift analysis:
   - Compare agent/skill frontmatter facts with registry and top-level docs.
   - Check whether changed behavior needs new docs or just updates to existing docs.
   - Check missing SKILL.md sections and broken relative links.
   - Determine whether a new owner document is needed (prefer new file over overloading unrelated docs).

4. Present findings-first audit report (critical/major/minor/nit) and use `vscode/askQuestions` to ask which files to update. No edits before approval.

5. Apply only approved updates. Create new markdown files when no existing page is the right owner for changed behavior.

6. Commit only relevant validated docs files and push. If audit-only or no edits, do not commit.

7. Run .github/scripts/update-sdlc-status.ps1 -Step docs -Status updated|notNeeded|blocked with evidence.

8. When docs are updated or not needed, end with a Claude `/create-pr` handoff. The first non-empty payload line must be `/create-pr`; never use `Agent: GitHub Create PR` as the Claude invocation line.

Hard stops: no edits before approval gate, do not commit to protected branches, keep language policy (governance docs in English).
