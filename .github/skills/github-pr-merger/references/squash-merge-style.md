# Squash Merge Style

Use this format for squash merge commit messages.

## Commit Title

```
{Exact PR title} (#{PR number})
```

- Keep PR title exactly as-is.
- Append ` (#{PR number})`.

## Commit Body Template

```markdown
{1-2 sentence summary of what changed and why.}

**Core Logic (`src/core`)**
* {change}

**Launcher Modules (`src/mobile`, `src/tv`)**
* {change}

**Build & CI**
* {change}

**Testing**
* {change}

**Docs & Agent Instructions**
* {change}
```

## Format Rules

- One blank line between sections.
- Section headers are bold.
- Bullets use `*`.
- Omit empty sections.
- English only.
- Re-derive message from actual diff and log.
