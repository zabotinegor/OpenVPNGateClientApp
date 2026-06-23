# US-13 — Agent Documentation Token Optimization

## User Story

**As** an AI agent invoked in this repository,  
**I want** AGENTS.md to contain the cross-cutting rules I am told to load from it, AGENTS-REGISTRY.md Delegation Rules to be compact, and protected-branch rules to have a single canonical location,  
**so that** agent file references are accurate, token consumption per invocation is reduced, and rule drift across files is eliminated.

---

## Background

Three interrelated problems exist in the agent documentation:

**1. Stale AGENTS.md references (correctness bug):** Seven agent files contain a `## Centralized Rules` section that says "Refer to AGENTS.md for: Prompt-Generation Responses, Long-Running Operation Rules, Update-SDL Status". These sections do **not** exist in AGENTS.md (108 lines). The rules are scattered across AGENTS-REGISTRY.md Delegation Rules and the shared flow patterns file, but agents cannot find them where they are told to look.

Affected agents: code-implementator, code-review, scope-quality-gate, docs-maintainer, developer-flow-orchestrator, bug-flow, github-create-pr.

**2. AGENTS-REGISTRY.md Delegation Rules verbosity (token waste):** The Delegation Rules block (34 dense lines) contains cross-cutting rules that belong in AGENTS.md, protected-branches rules repeated 3×, and per-agent behavioral paragraphs that duplicate what each `.agent.md` Mission/Hard Stops already documents.

**3. Protected-branches rule duplication:** The rule (main, dev, master, develop) is stated in AGENTS-REGISTRY.md (×3), agent files (×3+), SKILL.md files (×2), and sdlc-shared-flow-patterns.md (×1). Drift in any one location produces inconsistency.

---

## Acceptance Criteria

| ID | Criterion |
|----|-----------|
| AC-1 | AGENTS.md gains four new compact sections: **Long-Running Operation Rules**, **Prompt-Generation Responses**, **SDLC Status Updates**, and **SDLC Minimum Report Contract**, extracted from AGENTS-REGISTRY.md Delegation Rules |
| AC-2 | AGENTS-REGISTRY.md Delegation Rules block is replaced with: (a) a one-line cross-cutting reference to AGENTS.md for the four moved sections, (b) a compact per-agent behavior table (≤2 lines per agent), (c) a one-line reference to `shared/protected-branches.md` |
| AC-3 | `.github/skills/shared/protected-branches.md` is created as the single canonical definition of the protected-branches rule |
| AC-4 | `sdlc-shared-flow-patterns.md` Common Constraints replaces its inline protected-branches restatement with a reference to `shared/protected-branches.md` |
| AC-5 | All pre-existing content and rules are preserved — no rule is deleted, only moved or compressed without loss of meaning |
| AC-6 | All internal markdown links affected by the changes are updated and resolve correctly |

---

## Out of Scope

- Splitting AGENTS.md into topic files (it is already lean at 108 lines)
- Changing agent `.agent.md` Hard Stops sections (they intentionally restate key guards as safety checks)
- Changes to SKILL.md files or reference files
- Changes to any source code files

---

## Risks and Open Questions

| Type | Item | Resolution |
|------|------|------------|
| Risk | Compressed per-agent delegation table loses behavioral nuance | Each agent's `.agent.md` Mission/Hard Stops already contains the full detail; the registry table is a lookup index only |
| Risk | AGENTS.md new sections push total length above first-load context budget | All four sections together are ~30–40 lines; AGENTS.md would reach ~140–150 lines — well within acceptable bounds |

---

## Implementation Notes

| Area | Notes |
|------|-------|
| AGENTS.md | Add four sections after the existing "Agent Documentation Governance" section. Extract Long-Running Operation Rules + terminal execution from AGENTS-REGISTRY.md delegation rules. Define Prompt-Generation Responses as the fenced `text` block rule. SDLC Status Updates as pointer to update-sdlc-status.ps1 and sdlc-status-gate.md. SDLC Minimum Report Contract from AGENTS-REGISTRY.md line 51 |
| AGENTS-REGISTRY.md | Replace the 34-line Delegation Rules paragraphs with: cross-cutting reference to AGENTS.md, agent-sync special rules, and a per-agent behavior table (one row per agent, key delegation behavior) |
| `.github/skills/shared/protected-branches.md` | New file (~15 lines). Title, rule statement, four branch names, full prohibition |
| `sdlc-shared-flow-patterns.md` | Common Constraints: replace inline protected-branch list with reference to `shared/protected-branches.md` |

---

## Test Scenarios

| # | Scenario | Input / setup | Expected result |
|---|----------|---------------|-----------------|
| T1 | AGENTS.md correctness | Read AGENTS.md | All four new sections present with full rule text |
| T2 | AGENTS-REGISTRY.md compaction | Read Delegation Rules block | Cross-cutting rules referenced, per-agent table present, protected branches referenced |
| T3 | Canonical branch file | Read shared/protected-branches.md | File exists, lists all four branch names, states the prohibition |
| T4 | No rule lost | Compare pre/post content | Every rule from the original Delegation Rules appears in exactly one location |

---

## Definition of Done

- [ ] AGENTS.md has all four new sections
- [ ] AGENTS-REGISTRY.md Delegation Rules is a compact table + cross-cutting references
- [ ] `shared/protected-branches.md` exists with canonical rule
- [ ] `sdlc-shared-flow-patterns.md` references the shared file instead of restating the rule
- [ ] No markdown links are broken
- [ ] `docs/userstories/US-13-agent-doc-token-optimization.md` is committed to the feature branch
