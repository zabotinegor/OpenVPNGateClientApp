# PR Style

Use this format for generated PR body.

## Title

- English only.
- Concise, outcome-oriented.
- No `feat:` or `fix:` prefixes unless user explicitly asks.

## Description Template

```markdown
### 1. Summary

2-4 sentences: what changed and why.
Mention contract/build/workflow changes when they materially affect behavior.

### 2. Key Changes

**Core Logic (`src/core`)**
* Business logic, repositories, update/check flows, caching, parsing.

**Launcher Modules (`src/mobile`, `src/tv`)**
* Launcher-specific UI/manifest/resource/build changes.

**Build & CI**
* Gradle, workflows, artifact naming, release/tag automation.

**Testing**
* Added/updated unit tests and validation commands.

**Docs & Agent Instructions**
* `AGENTS.md`, skills, workflows, or operational docs updates.
```

## Writing Notes

- Omit non-applicable sections.
- Use concrete implementation facts from diff.
- Use backticks for classes/methods/files/routes when named explicitly.
