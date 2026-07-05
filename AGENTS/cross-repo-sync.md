# Cross-Repo Agent Sync (Mandatory)

Any change to the client agent files and related skill packages listed below MUST be synchronized to this server repository in the same work session.

Mandatory scope:

* `.github/agents/code-review.agent.md`
* `.github/agents/github-create-pr.agent.md`
* `.github/agents/github-pr-merger.agent.md`
* `.github/agents/github-review-comments.agent.md`

Skill packages in scope:

* `.github/skills/code-review/**`
* `.github/skills/github-create-pr/**`
* `.github/skills/github-pr-merger/**`
* `.github/skills/github-review-comments/**`

Rules:

* No-delay policy: apply equivalent server-side changes immediately after client-side edits, within the same session.
* Consistency policy: keep intent, workflow steps, constraints, and output contracts aligned across client and server versions.
