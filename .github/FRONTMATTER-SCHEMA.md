# Frontmatter Schema for Agent and Skill Files

This schema standardizes YAML frontmatter for:

- .github/agents/*.agent.md
- .github/skills/*/SKILL.md

## Required Baseline Fields

All agent and skill files must include:

- name: human-readable name.
- description: concise purpose statement scoped to the actual invocation intent.
- argument-hint: short input hint for invocation.

## Agent File Fields

Required:

- user-invocable: true or false

Recommended:

- tools: list of allowed capability groups or explicit tool names. Keep this list minimal and do not include tools that the agent does not actually need.
- model: explicit model identifier when behavior depends on it.

Optional:

- agents: delegated agent names, only when delegation exists.
- skills: consumed skill slugs, only when declared explicitly and needed for routing.
- handoffs: explicit downstream agent actions, only when the agent prepares a user-approved next step.

## Skill File Fields

Required baseline fields only:

- name
- description: concise purpose statement scoped to the skill's actual use cases
- argument-hint

Optional:

- user-invocable: include only if the skill is intentionally directly invokable by users.

## Skill Markdown Section Contract

Each SKILL.md should include these sections in order when applicable:

1. Summary
2. When to use
3. Expected input
4. Blocking gates
5. Workflow
6. Output format
7. Constraints or rules
8. References and related skills

If a section is intentionally not applicable, state that explicitly.

## Validation Notes

- Keep frontmatter keys lowercase with hyphen-separated names where applicable.
- Keep the file ASCII unless existing repository language requires Unicode.
- Keep links relative and repository-local.
- Keep naming stable across agent slug, skill slug, and referenced paths.
- Check token efficiency for every new or materially changed agent, skill, or workflow document.
- Keep `.agent.md` files as compact entrypoints and `SKILL.md` files as the authoritative workflow source.
- Move long examples, templates, stack-specific checklists, reply styles, output samples, and edge-case matrices into `references/`.
- Ensure references, scripts, and checklists are loaded lazily from the workflow phase that needs them.
- Do not remove or hide safety gates, required approvals, validation steps, security constraints, conflict handling, or required workflow order for token savings.
- Remove avoidable duplicate instructions unless the repetition is needed to prevent a concrete safety or routing failure.
