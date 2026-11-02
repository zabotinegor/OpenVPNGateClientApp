#!/usr/bin/env python3
"""Fail-closed Android PR change classification.

Run from a trusted base revision of this file, not the PR HEAD. Only an
explicitly documented allowlist may skip expensive app/engine builds.
A rename is treated as deletion + addition to prevent code-to-doc masking.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

DOC_ONLY = re.compile(
    r"^(?:docs/(?:[^/]+/)*[^/]+\.(?:md|mdx|rst|adoc|txt)|"
    r"(?:README|CHANGELOG|CONTRIBUTING|SECURITY|CODE_OF_CONDUCT|LICENSE|NOTICE)"
    r"(?:\.(?:md|mdx|rst|adoc|txt))?)$",
    re.IGNORECASE,
)
SHA = re.compile(r"^[0-9a-f]{40}$")


def is_documentation(path: str) -> bool:
    """Only safe, known documentation paths qualify; no blanket *.md rule."""
    return bool(DOC_ONLY.fullmatch(path))


def classify(paths: list[str]) -> tuple[bool, list[str]]:
    """Unknown, empty and all non-documentation change sets require full CI."""
    relevant = [path for path in paths if not is_documentation(path)]
    return not paths or bool(relevant), relevant


def _git(repo: Path, *args: str) -> bytes:
    proc = subprocess.run(["git", *args], cwd=repo, capture_output=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"git {args[0]} failed (exit {proc.returncode})")
    return proc.stdout


def changed_files(repo: Path, base: str, head: str) -> list[str]:
    if not SHA.fullmatch(base) or not SHA.fullmatch(head):
        raise ValueError("expected two complete lowercase commit SHAs")
    ancestor = _git(repo, "merge-base", base, head).decode("ascii").strip()
    output = _git(repo, "diff", "--no-renames", "--name-only", "-z", ancestor, head)
    return [p.decode("utf-8", errors="surrogateescape") for p in output.split(b"\0") if p]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output-file")
    parser.add_argument("--summary-file")
    args = parser.parse_args(argv)
    try:
        paths = changed_files(args.repo, args.base, args.head)
        heavy, relevant = classify(paths)
    except (RuntimeError, ValueError, OSError) as exc:
        print(f"Change classification failed closed: {type(exc).__name__}", file=sys.stderr)
        return 2

    if args.output_file:
        with open(args.output_file, "a", encoding="utf-8") as handle:
            handle.write(f"run_heavy_ci={'true' if heavy else 'false'}\n")
            handle.write(f"changed_files={len(paths)}\n")
            handle.write(f"relevant_files={len(relevant)}\n")

    decision = "full unit tests and mobile/TV builds" if heavy else "docs-only; skip Gradle"
    summary = f"PR change classification: {decision} ({len(relevant)}/{len(paths)} CI-relevant paths)."
    if args.summary_file:
        with open(args.summary_file, "a", encoding="utf-8") as handle:
            handle.write(f"## Android PR change classification\n\n{summary}\n")
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
