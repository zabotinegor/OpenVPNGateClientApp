#!/bin/sh
# Bash-host entrypoint for the protected-branch guard.
#
# Both PreToolUse matchers, Bash and PowerShell, run the one guard
# implementation in protect-agent-git-command.ps1, under pwsh. That is right
# when pwsh is present, but on a Bash-only host without PowerShell 7 on PATH,
# a bare `pwsh -File ...` command fails before any guard logic runs. A
# PreToolUse hook that never launches is not the same as one that denies: the
# harness sees no blocking result and lets the git command through - the
# guard was silently doing nothing. A previous Python twin of this guard
# existed for exactly this host gap and was removed (see git history: "Judge
# a git command by the repo it targets, and stop a guard that never ran")
# because it was invoked as python3, which resolves to the Windows Store
# execution alias and fails the identical way - it "resolves" without ever
# really running. Restoring a second implementation kept in parity with the
# PowerShell one is its own maintenance hazard, per that same commit. Fail
# loud and closed instead: without pwsh, deny the command with a clear reason
# rather than silently letting it through.
#
# This entrypoint relies on the hook's declared "cwd": "." - it is always
# launched from the repository root, matching every other hook command here.
set -eu

if command -v pwsh >/dev/null 2>&1; then
    exec pwsh -NoProfile -NonInteractive -File .github/scripts/protect-agent-git-command.ps1
fi

# Without pwsh, the full guard (branch resolution, -C/--git-dir target
# parsing, force-push detection, etc.) cannot run here. Denying every Bash
# command in that case - not just Git mutations - blocks the exact thing
# this fallback exists to support: agents inspecting files and running tests
# on a Bash-only host. This guard only ever exists to gate `git`, so parse
# the hook payload's actual command and fail closed solely when that command
# really invokes `git`; every other command passes through untouched.
#
# A previous version of this fallback searched the ENTIRE serialized payload
# for a word-boundary match on "git" instead of looking at the command being
# run. That is not the same question: "git" can appear in the payload as
# data with no bearing on what program is invoked - as an argument
# ('rg git docs', 'printf git'), or incidentally inside unrelated fields such
# as `cwd` (a directory merely named .../something-git-something). All of
# those were denied even though none of them run git. Parse the command
# field instead, and check whether the first token of the command actually
# invoked - or of any segment after a ';', '&', or '|' in a compound command,
# since 'cd x && git commit' must still be caught - is literally `git`.
payload="$(cat)"

# Best-effort JSON string field extraction without a JSON parser (jq is not
# assumed present - the whole point of this fallback is a minimal-dependency
# host). Matches `"<field>": "<value>"` with escaped characters inside the
# value (the standard `([^"\\]|\\.)*` idiom for a JSON string body), then
# strips the key/quotes and undoes the two escapes ("\"" and "\\") that
# matter for a plain command string.
extract_json_string_field() {
    field="$1"
    printf '%s' "$payload" | tr '\n' ' ' | \
        grep -Eo "\"$field\"[[:space:]]*:[[:space:]]*\"([^\"\\\\]|\\\\.)*\"" | \
        head -n 1 | \
        sed -E "s/^\"$field\"[[:space:]]*:[[:space:]]*\"//; s/\"\$//" | \
        sed -E 's/\\"/"/g; s/\\\\/\\/g'
}

# tool_input is usually {"command": "..."}; some payload shapes carry the
# command as a plain string directly under tool_input or toolArgs instead
# (mirrors the tool_input/toolArgs fallback in protect-agent-git-command.ps1).
command_text="$(extract_json_string_field command)"
if [ -z "$command_text" ]; then
    command_text="$(extract_json_string_field tool_input)"
fi
if [ -z "$command_text" ]; then
    command_text="$(extract_json_string_field toolArgs)"
fi

if [ -n "$command_text" ]; then
    # A real command invocation, not a data mention: split on ; & | (each
    # possibly repeated, e.g. && ||) and check whether any segment's command
    # token is exactly "git". A valid POSIX shell segment may open with one or
    # more environment-assignment words (NAME=value ...) before the actual
    # command - e.g. "X=1 git commit" still invokes git - so skip leading
    # assignment-word tokens (matching `^[A-Za-z_][A-Za-z0-9_]*=`) before
    # treating a token as the command.
    is_git="$(printf '%s' "$command_text" | awk '
        {
            n = split($0, segs, /[;&|]+/)
            found = 0
            for (i = 1; i <= n; i++) {
                seg = segs[i]
                gsub(/^[ \t]+/, "", seg)
                if (seg == "") continue
                m = split(seg, toks, /[ \t]+/)
                tok = ""
                for (j = 1; j <= m; j++) {
                    cand = toks[j]
                    if (cand ~ /^[A-Za-z_][A-Za-z0-9_]*=/) { continue }
                    tok = cand
                    break
                }
                gsub(/^["\x27]+|["\x27]+$/, "", tok)
                if (tok == "git") { found = 1; break }
            }
            print (found ? "yes" : "no")
        }')"
    if [ "$is_git" != "yes" ]; then
        printf '%s\n' '{}'
        exit 0
    fi
else
    # The command field could not be located/parsed at all (unexpected
    # payload shape, or genuinely not JSON). Fall back to the old broad
    # heuristic as a last resort rather than assuming it is safe - this
    # keeps the fail-safe property for payloads this parser cannot read,
    # without applying that blunt check to every ordinary payload.
    if ! printf '%s' "$payload" | grep -Eq '(^|[^A-Za-z0-9_])git([^A-Za-z0-9_]|$)'; then
        printf '%s\n' '{}'
        exit 0
    fi
fi

printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"protect-agent-git-command: pwsh (PowerShell 7+) was not found on PATH, so the protected-branch guard cannot verify this git command on this host. Install pwsh (https://aka.ms/install-powershell) before running git commands here - refusing to allow this command ungated rather than letting it through unguarded."}}'
