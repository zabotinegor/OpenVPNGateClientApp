# Cross-repository synchronisation

This app is one of two repositories sharing the same agent tooling. The other is the .NET backend,
**OpenVPNClientServer**. Its local path is in `AGENTS.local.md`.

## Agent assets

`.github/agents/`, `.github/skills/`, `.github/scripts/`, `.github/hooks/`, `.githooks/`,
`.claude/commands/`, `.claude/settings.json` and `.mcp.json` are **mirrored from CopilotTools** by
`agent-sync`. They are tool-managed:

- Do not hand-edit them here — the next sync overwrites the change.
- Do not add repository-specific files under those paths — the sync deletes anything not in source.
- The same applies to the `<!-- BEGIN COPILOT SYNC -->` block inside `AGENTS.md`. Everything outside
  those markers is this repository's own content and is never touched.

If a rule needs changing for every repository, change it in CopilotTools and sync. If it applies only
here, put it outside the markers.

## API contracts

When a change touches an API contract shared with the backend — server list payloads, update-check,
version metadata, release notes — inspect the server implementation and keep the formats aligned in
the same piece of work. A client-side assumption that drifts from the server is not caught by any
test in this repository.

The endpoints this app actually calls, including which are unused despite existing on the server, are
listed in [../reference/api-endpoints.md](../reference/api-endpoints.md).

## Documentation

Both repositories use the same knowledge-base design: one catalog at `docs/INDEX.md`, behaviour under
`docs/features/`, and lazy loading via per-file `## Index` blocks. A convention learned in one repo is
usually worth carrying to the other, but **content is not shared** — each repository documents its own
product. Server conventions do not belong here.

*Last verified against: `.github/` sync scope and `AGENTS.md` marker block (2026-07-31).*
