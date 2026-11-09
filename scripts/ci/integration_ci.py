#!/usr/bin/env python3
"""Trusted helper for .github/workflows/ci-integration.yml (standard library only).

The workflow revalidates a pull request against the live tip of dev without changing the PR head:
it builds and tests the exact merge of the PR head onto dev, then publishes the bound evidence as the
`Integration CI Gate` commit status that the review orchestrator host consumes.

Two subcommands, each run as `python3 -I scripts/ci/integration_ci.py <command>`:

  prepare  (job integration-build, runs from the dev checkout BEFORE any PR file is in the workspace)
           validates the dispatch inputs and context, fetches dev and the PR head with a read-only token
           that never reaches argv or .git/config, requires both to equal the dispatched values, merges
           the PR head with `git merge --no-commit --no-ff` and emits the merge tree as the step output
           `tree`. It creates no commit, ref or tag and pushes nothing.

  publish  (job publish-evidence, runs dev code only, never touches PR code)
           re-derives the truth from the GitHub API (dev tip, PR head, the PR merge commit and its tree),
           polls with a bounded budget while GitHub is still computing the merge, re-checks for drift right
           before posting and then posts exactly one success status. Any contradiction, drift, ambiguous
           or non-2xx response fails the job without posting anything.

The status context must be posted by this file only; a static test pins that.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Callable, Mapping, Sequence

CONTEXT = "Integration CI Gate"
TARGET_BASE_BRANCH = "dev"
TARGET_BASE_REF = "refs/heads/dev"

# Bounded mergeability polling: at most 12 reads, 10 seconds apart (11 waits, 110 seconds in total).
MAX_READS = 12
READ_INTERVAL_SECONDS = 10

PR_PATTERN = r"[1-9][0-9]*"
SHA_PATTERN = r"[0-9a-f]{40}"
DESCRIPTION_PATTERN = rf"pr={PR_PATTERN} base={SHA_PATTERN} tree={SHA_PATTERN}"
REPOSITORY_PATTERN = r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
RUN_ID_PATTERN = r"[0-9]+"

GIT_IDENTITY = ("-c", "rerere.enabled=false", "-c", "user.name=integration-ci", "-c", "user.email=integration-ci@invalid")


class CiError(Exception):
    """A fail-closed condition with a stable machine-readable reason."""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(f"{reason}: {message}")
        self.reason = reason
        self.message = message


GitRunner = Callable[[Sequence[str], str, "Mapping[str, str] | None"], "subprocess.CompletedProcess[str]"]
HttpTransport = Callable[[str, str, str, "dict[str, Any] | None"], "tuple[int, Any]"]


def run_git(args: Sequence[str], cwd: str, extra_env: Mapping[str, str] | None = None) -> "subprocess.CompletedProcess[str]":
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GIT_LFS_SKIP_SMUDGE"] = "1"
    env.pop("GITHUB_TOKEN", None)
    env.update(extra_env or {})
    return subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True, text=True, check=False)


def http_request(method: str, url: str, token: str, body: "dict[str, Any] | None" = None) -> "tuple[int, Any]":
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "integration-ci",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, exc.read()
    try:
        payload = json.loads(raw) if raw else None
    except ValueError:
        payload = None
    return status, payload


def read_inputs(env: Mapping[str, str]) -> tuple[str, str, str]:
    """Validate the dispatch inputs. The raw values are never echoed (they are attacker-influenced strings)."""
    pr = env.get("PR", "")
    head = env.get("HEAD_SHA", "")
    base = env.get("BASE_SHA", "")
    if not re.fullmatch(PR_PATTERN, pr):
        raise CiError("input-invalid", "pr must be a positive integer without leading zeros")
    if not re.fullmatch(SHA_PATTERN, head):
        raise CiError("input-invalid", "head must be a 40-character lowercase hex sha")
    if not re.fullmatch(SHA_PATTERN, base):
        raise CiError("input-invalid", "base must be a 40-character lowercase hex sha")
    return pr, head, base


def require_dispatch_context(env: Mapping[str, str], base: str) -> None:
    if env.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        raise CiError("event-not-dispatch", "the workflow only runs from workflow_dispatch")
    if env.get("GITHUB_REF") != TARGET_BASE_REF:
        raise CiError("ref-not-base", f"the workflow must run from {TARGET_BASE_REF}")
    if env.get("GITHUB_SHA") != base:
        raise CiError("sha-not-base", "the workflow revision must equal the dispatched base")


def _git_ok(git: GitRunner, args: Sequence[str], cwd: str, reason: str, extra_env: Mapping[str, str] | None = None) -> str:
    result = git(args, cwd, extra_env)
    if result.returncode != 0:
        detail = (result.stderr or "").strip().splitlines()
        raise CiError(reason, f"git {args[0]} failed" + (f": {detail[-1]}" if detail else ""))
    return (result.stdout or "").strip()


def _restore_workspace(git: GitRunner, cwd: str, base: str) -> None:
    """Drop a half-finished merge so later always() steps never see PR files in the workspace."""
    git(["merge", "--abort"], cwd, None)
    git(["reset", "--hard", base], cwd, None)
    git(["clean", "-fdq"], cwd, None)


def prepare(env: Mapping[str, str], cwd: str | None = None, git: GitRunner = run_git) -> str:
    pr, head, base = read_inputs(env)
    require_dispatch_context(env, base)
    token = env.get("GITHUB_TOKEN", "")
    if not token:
        raise CiError("token-missing", "a read-only token is required to fetch the pull request head")
    output_path = env.get("GITHUB_OUTPUT", "")
    if not output_path:
        raise CiError("output-missing", "GITHUB_OUTPUT is not set")
    cwd = cwd or os.getcwd()

    checked_out = _git_ok(git, ["rev-parse", "HEAD"], cwd, "sha-not-base")
    if checked_out != base:
        raise CiError("sha-not-base", "the checked-out commit is not the dispatched base")
    if _git_ok(git, ["status", "--porcelain", "--untracked-files=no"], cwd, "workspace-dirty"):
        raise CiError("workspace-dirty", "the base checkout is not clean")

    # The token travels only in this process environment, as an HTTP extra header scoped to the server URL.
    # It is not part of argv and is never written to .git/config.
    server = env.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
    basic = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    print(f"::add-mask::{basic}")
    fetch_env = {
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": f"http.{server}/.extraheader",
        "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}",
    }
    pr_ref = f"refs/remotes/pr/{pr}"
    _git_ok(git, ["fetch", "--no-tags", "origin", f"+refs/heads/{TARGET_BASE_BRANCH}:refs/remotes/origin/{TARGET_BASE_BRANCH}", f"+refs/pull/{pr}/head:{pr_ref}"],
            cwd, "fetch-failed", fetch_env)

    live_main = _git_ok(git, ["rev-parse", f"refs/remotes/origin/{TARGET_BASE_BRANCH}^{{commit}}"], cwd, "fetch-failed")
    if live_main != base:
        raise CiError("base-drift", "dev moved since the dispatch")
    live_head = _git_ok(git, ["rev-parse", f"{pr_ref}^{{commit}}"], cwd, "fetch-failed")
    if live_head != head:
        raise CiError("head-drift", "the pull request head moved since the dispatch")

    merge = git([*GIT_IDENTITY, "merge", "--no-commit", "--no-ff", "--no-verify", head], cwd, None)
    conflicted = git(["diff", "--name-only", "--diff-filter=U"], cwd, None)
    if merge.returncode != 0 or conflicted.returncode != 0 or (conflicted.stdout or "").strip():
        _restore_workspace(git, cwd, base)
        raise CiError("merge-conflict", "the pull request head does not merge cleanly onto dev")

    tree = _git_ok(git, ["write-tree"], cwd, "tree-invalid")
    if not re.fullmatch(SHA_PATTERN, tree):
        raise CiError("tree-invalid", "write-tree did not return a 40-character hex tree")
    if _git_ok(git, ["rev-parse", "HEAD"], cwd, "tree-invalid") != base:
        raise CiError("tree-invalid", "the merge unexpectedly moved HEAD")

    with open(output_path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(f"tree={tree}\n")
    print(f"Merge of {head} onto {base} produced tree {tree}.")
    return tree


class GitHubApi:
    def __init__(self, base_url: str, token: str, http: HttpTransport) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._http = http

    def get(self, path: str) -> dict[str, Any]:
        try:
            status, payload = self._http("GET", f"{self._base_url}/{path}", self._token, None)
        except OSError as exc:
            raise CiError("api-error", f"GET {path} failed: {type(exc).__name__}") from exc
        if status != 200 or not isinstance(payload, dict):
            raise CiError("api-error", f"GET {path} returned HTTP {status}")
        return payload

    def post(self, path: str, body: dict[str, Any], expected: int) -> None:
        try:
            status, _ = self._http("POST", f"{self._base_url}/{path}", self._token, body)
        except OSError as exc:
            raise CiError("status-post-failed", f"POST {path} failed: {type(exc).__name__}") from exc
        if status != expected:
            raise CiError("status-post-failed", f"POST {path} returned HTTP {status}, expected {expected}")


def _check_main_tip(api: GitHubApi, repo: str, base: str) -> None:
    ref = api.get(f"repos/{repo}/git/ref/heads/{TARGET_BASE_BRANCH}")
    obj = ref.get("object")
    tip = obj.get("sha") if isinstance(obj, dict) else None
    if tip != base:
        raise CiError("base-drift", "dev moved since the dispatch")


def _read_pull(api: GitHubApi, repo: str, pr: str, head: str) -> dict[str, Any]:
    pull = api.get(f"repos/{repo}/pulls/{pr}")
    if pull.get("state") != "open":
        raise CiError("pr-not-open", "the pull request is not open")
    base_info = pull.get("base")
    if not isinstance(base_info, dict) or base_info.get("ref") != TARGET_BASE_BRANCH:
        raise CiError("pr-base-not-dev", "the pull request does not target dev")
    head_info = pull.get("head")
    if not isinstance(head_info, dict) or head_info.get("sha") != head:
        raise CiError("head-drift", "the pull request head moved since the dispatch")
    return pull


def publish(env: Mapping[str, str], http: HttpTransport = http_request, sleep: Callable[[float], None] = time.sleep) -> str:
    pr, head, base = read_inputs(env)
    require_dispatch_context(env, base)
    tree = env.get("TREE", "")
    if not re.fullmatch(SHA_PATTERN, tree):
        raise CiError("tree-invalid", "the build job did not hand over a 40-character hex tree")
    repo = env.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(REPOSITORY_PATTERN, repo):
        raise CiError("input-invalid", "GITHUB_REPOSITORY is malformed")
    run_id = env.get("GITHUB_RUN_ID", "")
    if not re.fullmatch(RUN_ID_PATTERN, run_id):
        raise CiError("input-invalid", "GITHUB_RUN_ID is malformed")
    token = env.get("GITHUB_TOKEN", "")
    if not token:
        raise CiError("token-missing", "GITHUB_TOKEN is required")
    api_url = env.get("GITHUB_API_URL", "https://api.github.com")
    server_url = env.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
    api = GitHubApi(api_url, token, http)

    failure: CiError | None = None
    for attempt in range(MAX_READS):
        if attempt:
            sleep(READ_INTERVAL_SECONDS)
        _check_main_tip(api, repo, base)
        pull = _read_pull(api, repo, pr, head)
        mergeable = pull.get("mergeable")
        if mergeable is False:
            raise CiError("merge-conflict", "GitHub reports the pull request as not mergeable")
        merge_sha = pull.get("merge_commit_sha")
        if mergeable is not True or not isinstance(merge_sha, str) or not re.fullmatch(SHA_PATTERN, merge_sha):
            failure = CiError("mergeability-timeout", "GitHub did not finish computing the merge commit in time")
            continue
        commit = api.get(f"repos/{repo}/git/commits/{merge_sha}")
        parents_raw = commit.get("parents")
        parents = [p.get("sha") if isinstance(p, dict) else None for p in parents_raw] if isinstance(parents_raw, list) else []
        if parents != [base, head]:
            failure = CiError("merge-commit-stale", "the test merge commit was not computed from the dispatched base and head")
            continue
        merge_tree = commit.get("tree")
        if not isinstance(merge_tree, dict) or merge_tree.get("sha") != tree:
            raise CiError("tree-mismatch", "the built merge tree differs from the tree of the pull request merge commit")
        failure = None
        break
    if failure is not None:
        raise failure

    # Last drift check immediately before the only write.
    _check_main_tip(api, repo, base)
    _read_pull(api, repo, pr, head)

    description = f"pr={pr} base={base} tree={tree}"
    if not re.fullmatch(DESCRIPTION_PATTERN, description):
        raise CiError("input-invalid", "the status description does not match the gate format")
    target_url = f"{server_url}/{repo}/actions/runs/{run_id}"
    api.post(f"repos/{repo}/statuses/{head}", {
        "state": "success",
        "context": CONTEXT,
        "description": description,
        "target_url": target_url,
    }, expected=201)
    print(f"Posted {CONTEXT} for {head}: {description}")
    return description


def main(argv: Sequence[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("prepare", "publish"))
    args = parser.parse_args(argv)
    environment = os.environ if env is None else env
    try:
        if args.command == "prepare":
            prepare(environment)
        else:
            publish(environment)
    except CiError as exc:
        print(f"::error title={exc.reason}::{exc.message}")
        return 1
    except Exception as exc:  # fail closed on anything unexpected
        print(f"::error title=unexpected::{type(exc).__name__}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
