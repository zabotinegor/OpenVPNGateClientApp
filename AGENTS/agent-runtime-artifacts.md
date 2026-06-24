# Agent Runtime Artifacts

* Handoff and prompt text belongs in chat output or handoff buttons, not repository markdown files.
* Do not create `*_HANDOFF*.md`, `*_PROMPT*.md`, `*_PROMT*.md`, `CODE_REVIEW_HANDOFF_*.md`, or similar chat-transfer files unless the user explicitly requests a persistent file.
* If a handoff/prompt artifact is created accidentally, delete it before final output and return the same content in chat.
* `.sdlc/status.json` is runtime-only and must live at the Git repository root. Nested `.sdlc/status.json` files are drift; merge useful state into root status and remove the nested copy.
* Use `.github/scripts/update-sdlc-status.ps1` for status updates instead of editing status JSON manually.
