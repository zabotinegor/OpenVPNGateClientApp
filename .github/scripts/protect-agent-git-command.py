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
        return json.loads(raw) if raw.strip() else {}
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


def _switched_branch(normalized):
    """Return the protected branch name if the command switches/checks out to one."""
    m = re.search(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}"
        r"\s+(?:switch|checkout)\s+"
        rf"(?:-\S+\s+)*({PROTECTED_PATTERN})\b",
        normalized,
        re.IGNORECASE,
    )
    return m.group(1).lower() if m else ""


def protected_reason(command, branch):
    normalized = re.sub(r"\s+", " ", command.strip())
    if not re.search(r"(^|[;&|]\s*)git\s+", normalized, re.IGNORECASE):
        return ""

    effective_branch = _switched_branch(normalized) or branch

    if re.search(rf"{GIT_PREFIX_PATTERN}\s+commit\b", normalized, re.IGNORECASE) and effective_branch in PROTECTED:
        return f"Direct commit on protected branch '{effective_branch}' is forbidden."

    if re.search(rf"{GIT_PREFIX_PATTERN}\s+push\b", normalized, re.IGNORECASE):
        if re.search(r"(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$)", normalized, re.IGNORECASE):
            return "Force-push is forbidden in client repositories."
        if effective_branch in PROTECTED and not re.search(r"\bHEAD:", normalized, re.IGNORECASE):
            return f"Direct push from protected branch '{effective_branch}' is forbidden."
        push_patterns = (
            rf"\b(?:origin|upstream)\s+{PROTECTED_PATTERN}\b",
            rf"\bHEAD:(?:refs/heads/)?{PROTECTED_PATTERN}\b",
            rf"\brefs/heads/{PROTECTED_PATTERN}\b",
            rf"\b--delete\s+{PROTECTED_PATTERN}\b",
            rf"\b:{PROTECTED_PATTERN}\b",
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
