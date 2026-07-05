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
    r"|\s+--work-tree(?:=\S+|\s+\S+)"
    r"|\s+--no-pager"
    r"|\s+--paginate"
    r"|\s+--bare"
    r"|\s+-c\s+\S+"
    r"|\s+--exec-path(?:=\S+|\s+\S+)"
    r"|\s+--namespace=\S+"
    r"|\s+--no-replace-objects"
    r"|\s+--no-optional-locks"
    r"|\s+--literal-pathspecs"
    r"|\s+--no-literal-pathspecs"
    r"|\s+--glob-pathspecs"
    r"|\s+--noglob-pathspecs"
    r"|\s+--icase-pathspecs)*"
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


def _effective_branch_at(normalized, branch, pos):
    """Return the active branch immediately before position `pos`, accounting for any switches."""
    switch_re = re.compile(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}"
        r"\s+(?:switch|checkout)\s+"
        r"(?:-\S+\s+)*(?!--)([^\s;&|]+)",
        re.IGNORECASE,
    )
    effective = branch
    for m in switch_re.finditer(normalized):
        if m.start() >= pos:
            break
        target = m.group(1).strip("'\"").lower()
        if not target.startswith("-"):
            effective = target
    return effective


def protected_reason(command, branch):
    normalized = re.sub(r"\s+", " ", command.strip())
    if not re.search(r"(^|[;&|]\s*)git\s+", normalized, re.IGNORECASE):
        return ""

    # Evaluate each commit occurrence independently so a switch AFTER a reset
    # (but before the commit) is correctly tracked.
    for m in re.finditer(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}\s+commit\b", normalized, re.IGNORECASE
    ):
        eff = _effective_branch_at(normalized, branch, m.start())
        if eff in PROTECTED:
            return f"Direct commit on protected branch '{eff}' is forbidden."

    push_m = re.search(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}\s+push\b", normalized, re.IGNORECASE
    )
    if push_m:
        eff = _effective_branch_at(normalized, branch, push_m.start())
        is_force = re.search(r"(?:^|\s)(?:--force(?:-with-lease(?:=\S*)?|-if-includes)?|-f)(?:\s|$|[;&|])", normalized, re.IGNORECASE)
        is_bulk = re.search(r"(?:^|\s)(?:--all|--branches|--mirror)(?:\s|$|[;&|])", normalized, re.IGNORECASE)
        # Narrow, explicit exception for the release-flow archive step: recreating `dev`
        # from a merged `main` requires `git push origin --delete dev` followed by
        # `git push [-u] origin dev`. Only the literal `dev` ref is exempted here — main,
        # master, and develop remain fully protected, and force/bulk pushes are still
        # blocked above regardless of this exception.
        is_dev_only_push_or_delete = not is_force and not is_bulk and (
            bool(re.search(r"(?:^|\s)(?:--delete|-d)\s+dev(?![-\w/.])", normalized, re.IGNORECASE))
            or bool(re.search(r"\b(?:origin|upstream)\s+dev(?![-\w/.])", normalized, re.IGNORECASE))
            or bool(re.search(r"(?:^|\s):dev(?![-\w/.])", normalized, re.IGNORECASE))
        )
        # Guard: if the same command also targets main/master/develop in any push/delete
        # context the exception does not apply — e.g. `git push origin --delete dev main`.
        if is_dev_only_push_or_delete:
            for op in ("main", "master", "develop"):
                if (re.search(rf"(?:^|\s)(?:--delete|-d)\s+{op}(?![-\w/.])", normalized, re.IGNORECASE)
                        or re.search(rf"\b(?:origin|upstream)\s+\+?{op}(?![-\w/.])", normalized, re.IGNORECASE)
                        or re.search(rf"(?:^|\s):{op}(?![-\w/.])", normalized, re.IGNORECASE)
                        or re.search(rf"\brefs/heads/{op}(?![-\w/.])", normalized, re.IGNORECASE)):
                    is_dev_only_push_or_delete = False
                    break
        if is_force:
            return "Force-push is forbidden in client repositories."
        if is_bulk:
            return "Bulk push (--all/--branches/--mirror) may update protected refs and is forbidden."
        if is_dev_only_push_or_delete:
            pass  # Allowed: release-flow archive step recreating dev from merged main.
        elif eff in PROTECTED and not re.search(r"\bHEAD:", normalized, re.IGNORECASE):
            return f"Direct push from protected branch '{eff}' is forbidden."
        push_patterns = (
            rf"\b(?:origin|upstream)\s+{PROTECTED_PATTERN}(?![-\w/.])",
            rf"\b(?:origin|upstream)\s+\+{PROTECTED_PATTERN}(?![-\w/.])",
            rf"\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?{PROTECTED_PATTERN}(?![-\w/.])",
            rf"(?:^|\s)\+(?:refs/heads/)?{PROTECTED_PATTERN}(?![-\w/.])",
            rf"\brefs/heads/{PROTECTED_PATTERN}(?![-\w/.])",
            rf"(?:^|\s)(?:--delete|-d)\s+{PROTECTED_PATTERN}(?![-\w/.])",
            rf"(?:^|\s):{PROTECTED_PATTERN}(?![-\w/.])",
        )
        if not is_dev_only_push_or_delete and any(re.search(pattern, normalized, re.IGNORECASE) for pattern in push_patterns):
            return "Direct push, deletion, or recreation of a protected branch is forbidden."

    if re.search(
        rf"{GIT_PREFIX_PATTERN}\s+update-ref\b[^\r\n]*refs/heads/{PROTECTED_PATTERN}(?![-\w/.])",
        normalized,
        re.IGNORECASE,
    ):
        return "Direct protected branch ref mutation is forbidden."

    # Evaluate each reset occurrence independently so a switch between a reset
    # and a later commit is accounted for.  Use [;&|] in the flag terminator so
    # --hard;next (no space) is still detected.
    for m in re.finditer(
        rf"(?:^|[;&|]\s*){GIT_PREFIX_PATTERN}\s+reset\b", normalized, re.IGNORECASE
    ):
        eff = _effective_branch_at(normalized, branch, m.start())
        arg_seg = re.split(r"[;&|]+", normalized[m.end():])[0]
        if eff in PROTECTED and re.search(
            r"(?:^|\s)(?:--hard|--keep|--merge)(?:\s|$|[;&|])", arg_seg, re.IGNORECASE
        ):
            return f"Hard reset on protected branch '{eff}' is forbidden."

    for bm in re.finditer(rf"{GIT_PREFIX_PATTERN}\s+branch\b", normalized, re.IGNORECASE):
        seg = re.split(r"[;&|]+", normalized[bm.start():])[0]
        if re.search(r"(?:^|\s)(?:-f|--force)(?:\s|$|[;&|])", seg, re.IGNORECASE):
            tm = re.search(
                rf"{GIT_PREFIX_PATTERN}\s+branch\b((?:\s+-\S+)*)\s+([^\s-]\S*)",
                seg,
                re.IGNORECASE,
            )
            if tm and tm.group(2).strip("'\"").lower() in PROTECTED:
                return "Forcing a protected branch pointer is forbidden."

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
