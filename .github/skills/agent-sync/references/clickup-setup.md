# Agent Sync: Conditional ClickUp Setup

This is the source of truth for the detailed, user-assisted ClickUp setup. The canonical [Agent Sync skill](../SKILL.md) always runs the deterministic setup probe after syncing; load this file only when the probe requires remediation, onboarding, credentials/consent guidance, or list migration. Do not read it on a healthy preconfigured ClickUp target or a local-mode target that does not request onboarding.

## ClickUp setup and verification (load after the base sync)

The synced SDLC-core skills run in ClickUp mode or local mode depending on whether the target repo has a valid `.sdlc/clickup-config.json` (see [../shared/clickup-integration.md](../../shared/clickup-integration.md#mode-selection-every-sdlc-core-skill)). Agent Sync resolves that after the file sync, because the skills it just installed depend on it.

**Agent Sync is the only owner of ClickUp setup.** No SDLC flow provisions any of it: a flow finds the integration configured or it does not. Anything Agent Sync cannot do itself — anything needing a human's credentials, consent, or a decision only the repo owner can make — must be reported to the user as an explicit, numbered instruction, never left as an implied prerequisite.

**Run the deterministic check first, then act on what it reports:**

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .github/scripts/setup-clickup.ps1 -Probe
```

It checks, in one pass: the `.mcp.json` `clickup` entry, config presence and schema (including that the required status keys exist for this repo's `release_flow`), `.gitignore` coverage of the config/token/migration-map, whether the token file has been committed, REST token presence, live reachability of every configured List, whether every configured status name actually exists on the board, and — with `-Probe` — a create-and-delete write probe on each QA List. Every finding carries `remediation` text. Omit `-Probe` for a read-only pass; include it whenever the config changed or the QA lists have never been probed.

Then handle what it reports:

1. **`mcp.entry` FAIL** — `.mcp.json` is in sync scope and merged, so the `clickup` server entry should land automatically. Re-run the sync; if the merge still skips it, report that rather than hand-editing around the sync.
2. **`mcp.auth`** always reports `WARN`: OAuth state is per-developer and not inspectable from a script. Tell the user to run `/mcp` in an interactive Claude Code session and authenticate the `clickup` server. This is one of the steps Agent Sync cannot perform — surface it as an instruction, do not skip past it.
3. **`config.present` SKIPPED** (no config) → target is in local mode. Ask the user whether to set ClickUp up now. If they decline, report `artifactMode: local` and stop this section — local mode is fully supported and is not a blocker. If they agree, run `setup-clickup.ps1 -Scaffold`, which writes `.sdlc/clickup-config.template.json` for them to fill in. It deliberately does not write the real config: a config present with placeholder IDs is a hard error for every skill (correctly — a half-configured repo scatters one story across two systems), so the template is the safe artifact.
4. **Filling the config** — resolve real IDs with the user, never by guessing: `clickup.ps1 -Action Discover` prints every Workspace/Space/Folder/List id, and `clickup.ps1 -Action ListStatuses -ListId <tasks list>` prints the exact status names the `statuses` map must use. Set `release_flow` (`simple` for a repo with no `dev` branch, `dev-main` for one with a separate release cycle) and populate the release-only keys (`ready_for_release`, `release_review`, `qa_approval`) only for `dev-main`. Rename the template to `clickup-config.json` and re-run the check.
5. **`config.present` FAIL / `config.schema` FAIL** — report as a blocker with the script's detail; do not silently rewrite the user's config.
6. **`token.present` FAIL** — this is the step most often missed and the most consequential. Without `.sdlc/clickup-token` there is no REST fallback for a rate-limited MCP server, and every ClickUp verification in every flow reports `UNVERIFIED` and exits 0 — the whole safety net present and doing nothing, while flows still report success. Agent Sync cannot create the token; give the user these exact steps:
   1. In ClickUp: avatar → **Settings** → **Apps** → **API Token** → generate (it starts with `pk_`).
   2. Save it as a single line in `.sdlc/clickup-token` in this repository (already gitignored).
   3. Do not paste it into chat, into a tracked file, or into a shell environment variable — a variable exported in an interactive shell does not reach separately spawned processes, which is where the scripts run.
   4. Re-run `setup-clickup.ps1 -Probe`.
7. **`token.tracked` FAIL** — the token is in the git index. Tell the user to run `git rm --cached .sdlc/clickup-token`, confirm the ignore rule, and **rotate the token in ClickUp**, since it has been committed.
8. **`gitignore` FAIL** — re-run the sync; `sync-agent-assets.ps1` rebuilds the transient-artifact block, which covers the config, template, token, and migration map.
9. **`probe.<list>` FAIL with `ITEM_246`** — that List has permanently spent the workspace-lifetime "custom task types" quota and can never accept another new task. Apply the fix in [../shared/clickup-integration.md](../../shared/clickup-integration.md#free-plan-limits-exhausted-custom-task-types-quota---separate-from-custom-fields): provision a fresh List directly in the `QA` Folder (never from a template), set its 3-status pipeline explicitly, point the config at the new ID, and re-probe. Leave the exhausted List in place — its existing tasks stay valid.
10. **`statuses.<list>` FAIL** — a configured status name does not exist on the board, so every push of that key would be rejected at runtime. The check prints the board's actual names; correct the config to match exactly.
11. **Report the resolved mode** so the user knows which workflow the synced skills will take.
