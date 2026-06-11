#!/usr/bin/env python3
"""Block agent shell commands that directly mutate protected Git branches."""

import json
from pathlib import Path
import re
import subprocess
import sys


PROTECTED = ("main", "dev", "master", "develop")
PROTECTED_PATTERN = r"(?:main|dev|master|develop)"
GIT_PREFIX_PATTERN = (
    r"\bgit"
    r"(?:\s+-C\s+\S+"
    r"|\s+--git-dir(?:=\S+|\s+\S+)"
    r"|\s+--work-tree(?:=\S+|\s+\S+))*"
)


def read_payload():
    try:
        raw = sys.stdin.read()
        parsed = json.loads(raw) if raw.strip() else {}
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def get_command(payload):
    tool_input = payload.get("tool_input") or payload.get("toolArgs") or {}
    if isinstance(tool_input, str):
        try:
            tool_input = json.loads(tool_input)
        except json.JSONDecodeError:
            return tool_input
    if not isinstance(tool_input, dict):
        return ""
    return str(tool_input.get("command") or "")


def current_branch(cwd):
    try:
        result = subprocess.run(
            ["git", "-C", cwd or ".", "branch", "--show-current"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip().lower()
    except (OSError, subprocess.CalledProcessError):
        return ""


def is_copilottools_source(cwd):
    try:
        result = subprocess.run(
            ["git", "-C", cwd or ".", "rev-parse", "--show-toplevel"],
            check=True,
            capture_output=True,
            text=True,
        )
        return (Path(result.stdout.strip()) / ".copilottools-source").is_file()
    except (OSError, subprocess.CalledProcessError):
        return False


def _effective_branch_after_switch(normalized, branch):
    """Return the branch active at the first mutating command, accounting for preceding switches."""
    switch_re = re.compile(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}"
        r"\s+(?:switch|checkout)\s+"
        r"(?:-\S+\s+)*(?!--)(\S+)\b",
        re.IGNORECASE,
    )
    mutate = re.search(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}\s+(?:commit|push)\b",
        normalized,
        re.IGNORECASE,
    )
    mutate_pos = mutate.start() if mutate else len(normalized)
    effective = branch
    for m in switch_re.finditer(normalized):
        if m.start() >= mutate_pos:
            break
        target = m.group(1).lower()
        if not target.startswith("-"):
            effective = target
    return effective


def protected_reason(command, branch):
    normalized = re.sub(r"\s+", " ", command.strip())
    if not re.search(r"(^|[;&|]\s*)git\s+", normalized, re.IGNORECASE):
        return ""

    effective_branch = _effective_branch_after_switch(normalized, branch)

    if re.search(rf"{GIT_PREFIX_PATTERN}\s+commit\b", normalized, re.IGNORECASE) and effective_branch in PROTECTED:
        return f"Direct commit on protected branch '{effective_branch}' is forbidden."

    if re.search(rf"{GIT_PREFIX_PATTERN}\s+push\b", normalized, re.IGNORECASE):
        if re.search(r"(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$)", normalized, re.IGNORECASE):
            return "Force-push is forbidden in client repositories."
        if effective_branch in PROTECTED and not re.search(r"\bHEAD:", normalized, re.IGNORECASE):
            return f"Direct push from protected branch '{effective_branch}' is forbidden."
        push_patterns = (
            rf"\b(?:origin|upstream)\s+{PROTECTED_PATTERN}\b",
            rf"\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?{PROTECTED_PATTERN}\b",
            rf"\brefs/heads/{PROTECTED_PATTERN}\b",
            rf"(?:^|\s)(?:--delete|-d)\s+{PROTECTED_PATTERN}\b",
            rf"(?:^|\s):{PROTECTED_PATTERN}\b",
        )
        if any(re.search(pattern, normalized, re.IGNORECASE) for pattern in push_patterns):
            return "Direct push, deletion, or recreation of a protected branch is forbidden."

    if re.search(
        rf"{GIT_PREFIX_PATTERN}\s+update-ref\b[^\r\n]*refs/heads/{PROTECTED_PATTERN}\b",
        normalized,
        re.IGNORECASE,
    ):
        return "Direct protected branch ref mutation is forbidden."

    return ""


def main():
    payload = read_payload()
    if is_copilottools_source(payload.get("cwd")):
        print("{}")
        return
    reason = protected_reason(get_command(payload), current_branch(payload.get("cwd")))
    if not reason:
        print("{}")
        return

    print(
        json.dumps(
            {
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": reason,
                },
            }
        )
    )


if __name__ == "__main__":
    main()
