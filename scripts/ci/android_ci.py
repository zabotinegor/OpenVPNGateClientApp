#!/usr/bin/env python3
"""Shared Android client CI/release logic for GitHub Actions and Azure DevOps.

Both providers are thin adapters that call the sub-commands below; all behaviour (change detection, version and
build-number calculation, signing setup, Gradle invocations, tagging, GitHub Release publication, Versions API
publication, provider guard, CI Gate status) lives here and in .ci/pipeline-contract.json, never in provider YAML.
scripts/ci/check_pipeline_parity.py fails when an adapter stops calling these sub-commands as the contract demands.

Sub-commands: guard, detect-changes, resolve-mode, pr-metadata, install-swig, install-android-sdk, gradle,
rename-apks, version, write-signing, stage-artifacts, create-tag, publish-release, versions-api, ci-gate.

Secrets arrive only through the environment (GH_PAT, SIGNING_KEY_BASE64, KEY_ALIAS, KEY_PASSWORD, STORE_PASSWORD,
VERSIONS_API_KEY) and are never printed. Non-secret configuration arrives as environment variables named like the
GitHub repository variables (PRIMARY_SERVERS_URL, FALLBACK_SERVERS_URL, VERSIONS_API_BASE_URL, CI_REPOSITORY).
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, Iterable

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / ".ci" / "pipeline-contract.json"
API = "https://api.github.com"
ZERO_SHA = "0" * 40
AUTO_TAG = re.compile(r"-auto\(([0-9]+)\)$")


class CiError(Exception):
    """A failure with a message that is safe to print (never contains a secret value)."""


# --------------------------------------------------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------------------------------------------------

def load_contract(path: Path = CONTRACT_PATH) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def is_azure() -> bool:
    return bool(os.environ.get("TF_BUILD"))


def azure_escape(value: str) -> str:
    return value.replace("%", "%AZP25").replace("\r", "%0D").replace("\n", "%0A")


def emit(name: str, value: str, out=None) -> None:
    """Publishes a step output for the running provider (GitHub step output, Azure output variable, or plain text)."""
    out = out or sys.stdout
    value = str(value)
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8", newline="\n") as handle:
            handle.write(f"{name}={value}\n")
    if is_azure():
        out.write(f"##vso[task.setvariable variable={name};isOutput=true]{azure_escape(value)}\n")
    out.write(f"{name}={value}\n")


def run(command: list[str], cwd: Path | None = None, env: dict | None = None, check: bool = True,
        capture: bool = False, input_text: str | None = None) -> subprocess.CompletedProcess:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    result = subprocess.run(command, cwd=str(cwd) if cwd else None, env=merged, text=True, input=input_text,
                            capture_output=capture, encoding="utf-8" if capture else None)
    if check and result.returncode != 0:
        raise CiError(f"Command failed with exit code {result.returncode}: {' '.join(command[:3])} ...")
    return result


def git(*args: str, cwd: Path | None = None, check: bool = True) -> str:
    result = run(["git", *args], cwd=cwd or ROOT, check=check, capture=True)
    return (result.stdout or "").strip()


def version_key(text: str) -> list:
    """Natural ('sort -V'-like) ordering key: digit runs compare numerically, other runs as text."""
    parts = re.split(r"(\d+)", text)
    return [(0, int(p), "") if p.isdigit() else (1, 0, p) for p in parts if p != ""]


def strip_auto_suffix(tag_without_v: str) -> str:
    cleaned = re.sub(r"-auto\([0-9]+\)$", "", tag_without_v)
    return re.sub(r"\([0-9]+\)$", "", cleaned)


def list_tags(cwd: Path | None = None) -> list[str]:
    output = git("tag", "--list", cwd=cwd)
    return [line for line in output.splitlines() if line]


def next_build_number(tags: Iterable[str]) -> int:
    used = [int(m.group(1)) for t in tags for m in [AUTO_TAG.search(t)] if m]
    return (max(used) if used else 0) + 1


class GitHubApi:
    """Minimal GitHub REST client. `opener` is injectable for tests. The token never appears in errors."""

    def __init__(self, token: str, opener: Callable = urllib.request.urlopen):
        if not token:
            raise CiError("GH_PAT is required but empty or unset.")
        self._token = token
        self._opener = opener

    def request(self, method: str, url: str, body: object | None = None, headers: dict | None = None,
                raw: bytes | None = None) -> tuple[int, object]:
        data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
        merged = {
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if body is not None:
            merged["Content-Type"] = "application/json"
        merged.update(headers or {})
        req = urllib.request.Request(url, data=data, method=method, headers=merged)
        try:
            with self._opener(req, timeout=120) as response:
                payload = response.read()
                status = response.status
        except urllib.error.HTTPError as exc:
            payload = exc.read() if hasattr(exc, "read") else b""
            status = exc.code
        except (urllib.error.URLError, OSError) as exc:
            raise CiError(f"GitHub API request failed ({type(exc).__name__}).") from None
        try:
            parsed = json.loads(payload.decode("utf-8")) if payload else None
        except ValueError:
            parsed = None
        return status, parsed


def repository() -> str:
    repo = os.environ.get("CI_REPOSITORY", "")
    if not repo or "/" not in repo:
        raise CiError("CI_REPOSITORY (owner/name) is required.")
    return repo


# --------------------------------------------------------------------------------------------------------------------
# guard: provider state
# --------------------------------------------------------------------------------------------------------------------

def read_repo_variable(api: GitHubApi, repo: str, name: str) -> str | None:
    """None when the variable is absent (HTTP 404); raises on any other failure."""
    status, body = api.request("GET", f"{API}/repos/{repo}/actions/variables/{name}")
    if status == 404:
        return None
    if status != 200 or not isinstance(body, dict) or "value" not in body:
        raise CiError(f"Could not read repository variable {name} (HTTP {status}).")
    return str(body["value"])


def cmd_guard(args, api: GitHubApi | None = None, sleep: Callable = time.sleep, clock: Callable = time.time) -> int:
    """Waits for a running provider switch to finish, then reads CI_PROVIDER live and reports whether `--provider`
    is the active one. Azure fails closed on any read error. GitHub may fall back to the workflow-start snapshot
    (`--snapshot`) when the live read is refused, so a token without Variables read cannot block the primary provider."""
    contract = load_contract()
    state = contract["providerState"]
    default = state["default"]
    try:
        api = api or GitHubApi(os.environ.get("GH_PAT", ""))
        repo = repository()
        deadline = clock() + args.max_wait
        while True:
            lock = read_repo_variable(api, repo, state["lockVariable"])
            if lock is None or lock == "false":
                break
            if clock() >= deadline:
                raise CiError(f"{state['lockVariable']} has stayed set for over {args.max_wait}s; a switch may be stuck "
                              "(crashed before clearing it). Not proceeding: check 'pipeline-mode.ps1 status' and clear it "
                              "by hand only after confirming no switch is running.")
            print(f"A provider switch is in progress ({state['lockVariable']} is set); waiting {args.poll}s.")
            sleep(args.poll)
        live = read_repo_variable(api, repo, state["variable"])
        provider = live if live else default
    except CiError as exc:
        if args.provider == "github" and args.snapshot is not None and "stayed set" not in str(exc):
            provider = args.snapshot or default
            print(f"WARNING: live provider read failed ({exc}); using the workflow-start snapshot '{provider}'.")
        else:
            print(str(exc), file=sys.stderr)
            return 1
    active = provider == args.provider
    print(f"{state['variable']}={provider}; {args.provider} is {'active' if active else 'inactive'}.")
    emit("active", "true" if active else "false")
    return 0


# --------------------------------------------------------------------------------------------------------------------
# detect-changes (+ stale replay guard)
# --------------------------------------------------------------------------------------------------------------------

def is_code_change(files: Iterable[str], pattern: str) -> bool:
    regex = re.compile(pattern)
    return any(regex.search(f) for f in files)


def latest_release_tag_ref(commit: str, cwd: Path | None = None) -> str | None:
    """Newest release tag (-auto(N)) that is an ancestor of `commit`, by commit date."""
    output = git("tag", "--merged", commit, "--sort=-committerdate", cwd=cwd, check=False)
    for tag in output.splitlines():
        if AUTO_TAG.search(tag):
            return tag
    return None


def changed_files(before: str | None, after: str, cwd: Path | None = None) -> list[str]:
    if not before or before == ZERO_SHA:
        output = git("log", "--name-only", "--pretty=format:", "-r", after, cwd=cwd, check=False)
        return sorted({line for line in output.splitlines() if line})
    output = git("diff", "--no-renames", "--name-only", before, after, cwd=cwd, check=False)
    return [line for line in output.splitlines() if line]


def azure_previous_sha(opener: Callable = urllib.request.urlopen) -> str | None:
    """Head of the previous completed build of this pipeline and branch (Azure has no push 'before' sha)."""
    token = os.environ.get("SYSTEM_ACCESSTOKEN", "")
    needed = ("SYSTEM_COLLECTIONURI", "SYSTEM_TEAMPROJECT", "SYSTEM_DEFINITIONID", "BUILD_SOURCEBRANCH")
    if not token or any(not os.environ.get(n) for n in needed):
        return None
    base = os.environ["SYSTEM_COLLECTIONURI"].rstrip("/")
    url = (f"{base}/{os.environ['SYSTEM_TEAMPROJECT']}/_apis/build/builds?definitions={os.environ['SYSTEM_DEFINITIONID']}"
           f"&branchName={os.environ['BUILD_SOURCEBRANCH']}&statusFilter=completed&resultFilter=succeeded,partiallySucceeded"
           "&queryOrder=finishTimeDescending&$top=1&api-version=7.1")
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with opener(request, timeout=60) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None
    builds = payload.get("value") or []
    return builds[0].get("sourceVersion") if builds else None


def commit_exists(sha: str, cwd: Path | None = None) -> bool:
    return run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], cwd=cwd or ROOT, check=False, capture=True).returncode == 0


def is_ancestor(ancestor: str, descendant: str, cwd: Path | None = None) -> bool:
    return run(["git", "merge-base", "--is-ancestor", ancestor, descendant], cwd=cwd or ROOT, check=False,
               capture=True).returncode == 0


def decide_changes(event: str, before: str | None, after: str, pattern: str, cwd: Path | None = None,
                   previous_lookup: Callable[[], str | None] | None = None) -> tuple[bool, str, list[str]]:
    """Returns (proceed, reason, files). Pure with respect to git state given the arguments.

    Baseline: a replay always diffs against the latest release tag reachable from `after`. A push uses the push
    `before` sha (GitHub) or, when there is none (Azure), the previous successful build of the branch; if the latest
    release tag reachable from `after` is newer than that build (releases made by the other provider in the meantime),
    the tag wins so already-released code is never released again."""
    if event == "manual":
        return True, "Manual trigger: always proceed with release.", []
    replay = event == "replay"
    baseline: str | None = None
    if replay:
        baseline = latest_release_tag_ref(after, cwd)
    else:
        baseline = before
        looked_up = False
        if (not baseline or baseline == ZERO_SHA) and previous_lookup:
            baseline = previous_lookup()
            looked_up = True
        if baseline and baseline != ZERO_SHA and not commit_exists(baseline, cwd):
            baseline = None
        if looked_up and baseline:
            tag = latest_release_tag_ref(after, cwd)
            if tag and is_ancestor(baseline, f"{tag}^{{commit}}", cwd):
                baseline = tag
        if not baseline or baseline == ZERO_SHA:
            # No usable baseline (no prior build, lookup failure, unknown/force-pushed commit): use the latest release
            # tag rather than diffing the whole history; with no tag either, the whole history is inspected.
            baseline = latest_release_tag_ref(after, cwd)
    files = changed_files(baseline, after, cwd)
    if not files:
        if replay:
            return False, "Replay: nothing changed since the last release baseline.", files
        return True, "No changes detected (first commit or empty diff): proceed.", files
    if is_code_change(files, pattern):
        return True, "Code changes detected: release build will proceed.", files
    return False, "No code changes detected: only non-code files changed.", files


def stale_replay(branch: str, pinned_sha: str, api: GitHubApi, repo: str) -> tuple[bool, str]:
    status, body = api.request("GET", f"{API}/repos/{repo}/commits/{branch}")
    if status != 200 or not isinstance(body, dict) or not body.get("sha"):
        raise CiError(f"Could not resolve the live tip of {branch} (HTTP {status}).")
    live = str(body["sha"])
    return live != pinned_sha, live


def resolve_event(event: str, replay: str) -> str:
    """`auto` (Azure) derives the event from the queue reason and the replay parameter."""
    if event != "auto":
        return event
    if str(replay).lower() == "true":
        return "replay"
    return "manual" if os.environ.get("BUILD_REASON", "") == "Manual" else "push"


def cmd_detect_changes(args, api: GitHubApi | None = None) -> int:
    contract = load_contract()
    pattern = contract["changeDetection"]["codePathRegex"]
    try:
        after = args.after or git("rev-parse", "HEAD")
        event = resolve_event(args.event, args.replay)
        branch = args.branch or re.sub(r"^refs/heads/", "", os.environ.get("BUILD_SOURCEBRANCH", ""))
        if args.mode == "tag":
            print("Tag release: every pushed release tag builds; no change detection.")
            emit("has_code_changes", "true")
            return 0
        if event == "replay":
            if not args.pinned_sha or not branch:
                raise CiError("A replay needs --pinned-sha and --branch.")
            api = api or GitHubApi(os.environ.get("GH_PAT", ""))
            stale, live = stale_replay(branch, args.pinned_sha, api, repository())
            if stale:
                print(f"Stale replay skipped: pinned {args.pinned_sha[:7]} is no longer the tip of {branch} "
                      f"({live[:7]}). A newer push supersedes it; this is expected, not a failure.")
                emit("has_code_changes", "false")
                emit("skip_reason", "stale-replay")
                return 0
        lookup = azure_previous_sha if (is_azure() and args.before in (None, "", "auto")) else None
        before = None if args.before in (None, "", "auto") else args.before
        proceed, reason, files = decide_changes(event, before, after, pattern, previous_lookup=lookup)
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"Release decision: {'PROCEED' if proceed else 'SKIP'}. {reason}")
    for name in files:
        print(f"  {name}")
    emit("has_code_changes", "true" if proceed else "false")
    if not proceed:
        emit("skip_reason", "no-code-changes")
    return 0


def resolve_mode_from_ref(ref: str, contract: dict) -> str | None:
    types = contract["release"]["types"]
    if ref.startswith("refs/heads/"):
        branch = ref[len("refs/heads/"):]
        for mode, cfg in types.items():
            if cfg.get("branch") == branch:
                return mode
        return None
    if ref.startswith("refs/tags/"):
        tag = ref[len("refs/tags/"):]
        cfg = types["tag"]
        if tag.startswith("v") and cfg["ignoreTagsContaining"] not in tag:
            return "tag"
    return None


def cmd_resolve_mode(args) -> int:
    ref = args.ref or os.environ.get("BUILD_SOURCEBRANCH", "")
    mode = resolve_mode_from_ref(ref, load_contract())
    if not mode:
        print(f"Ref '{ref}' is not a release trigger (dev, main or a v* tag without '-auto'); nothing to release.")
        emit("mode", "")
        emit("run", "false")
        return 0
    print(f"Release mode for {ref}: {mode}")
    emit("mode", mode)
    emit("run", "true")
    return 0


# --------------------------------------------------------------------------------------------------------------------
# Toolchain and Gradle
# --------------------------------------------------------------------------------------------------------------------

def cmd_pr_metadata(args) -> int:
    sha = args.sha or git("rev-parse", "HEAD")
    short = sha[:7]
    print(f"Build commit {sha}")
    emit("short_sha", short)
    return 0


def cmd_install_swig(_args) -> int:
    prefix = [] if (hasattr(os, "geteuid") and os.geteuid() == 0) else ["sudo"]
    run([*prefix, "apt-get", "update"])
    run([*prefix, "apt-get", "install", "-y", "swig"])
    run(["swig", "-version"])
    return 0


def find_sdkmanager() -> tuple[str, str]:
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
    found = shutil.which("sdkmanager")
    if not found and root:
        candidate = Path(root) / "cmdline-tools" / "latest" / "bin" / "sdkmanager"
        if candidate.exists():
            found = str(candidate)
    if not found or not root:
        raise CiError("sdkmanager or ANDROID_SDK_ROOT/ANDROID_HOME was not found; install the Android command-line tools first.")
    return found, root


def cmd_install_android_sdk(_args) -> int:
    packages = load_contract()["toolchain"]["androidSdkPackages"]
    try:
        sdkmanager, root = find_sdkmanager()
        run([sdkmanager, f"--sdk_root={root}", *packages])
        run([sdkmanager, f"--sdk_root={root}", "--licenses"], input_text="y\n" * 100, check=False)
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


def gradle_command(contract: dict, task_set: str, extra_args: list[str] | None = None) -> tuple[list[str], dict]:
    cfg = contract["gradle"]
    if task_set not in cfg["taskSets"]:
        raise CiError(f"Unknown Gradle task set '{task_set}'.")
    entry = cfg["taskSets"][task_set]
    profile = cfg["profiles"][entry["profile"]]
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    command = [wrapper, *profile["flags"], *entry["tasks"], *(extra_args or [])]
    env = {"GRADLE_OPTS": profile["gradleOpts"]}
    for source, target in cfg["buildConfigVariables"].items():
        value = os.environ.get(source, "")
        if not value:
            raise CiError(f"Required build configuration variable {source} is missing or empty.")
        env[target] = value
    return command, env


def read_meta(contract: dict) -> dict:
    path = ROOT / contract["release"]["metadataFile"]
    if not path.exists():
        raise CiError(f"Release metadata {contract['release']['metadataFile']} is missing; run 'version' first.")
    return json.loads(path.read_text(encoding="utf-8"))


def cmd_gradle(args) -> int:
    contract = load_contract()
    try:
        extra: list[str] = []
        env_extra: dict = {}
        if args.task_set == "stage-release":
            meta = read_meta(contract)
            extra = [f"-PappVersionName={meta['app_version']}", f"-PappVersionCodeMobile={meta['build_number']}",
                     f"-PappVersionCodeTv={meta['build_number']}"]
            env_extra["ORG_GRADLE_PROJECT_appReleaseType"] = contract["release"]["types"][meta["mode"]]["appReleaseType"]
        command, env = gradle_command(contract, args.task_set, extra)
        env.update(env_extra)
        wrapper_dir = ROOT / contract["toolchain"]["gradleWrapperDir"]
        if os.name != "nt":
            (wrapper_dir / "gradlew").chmod(0o755)
        run(command, cwd=wrapper_dir, env=env)
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


def cmd_rename_apks(args) -> int:
    sha = args.sha or git("rev-parse", "HEAD")
    short = sha[:7]
    for variant in load_contract()["release"]["assets"]["variants"]:
        for apk in sorted((ROOT / "src" / variant / "build" / "outputs" / "apk" / "debug").glob("*.apk")):
            apk.rename(apk.with_name(f"{apk.stem}_{short}.apk"))
            print(f"Renamed {apk.name}")
    return 0


# --------------------------------------------------------------------------------------------------------------------
# Versioning
# --------------------------------------------------------------------------------------------------------------------

def stable_base_version(tags: list[str], contract: dict) -> tuple[str, str | None]:
    """Next patch version after the newest stable tag matching the baseline glob; (version, stable_tag)."""
    baseline = contract["release"]["stableBaseline"]
    prefix = baseline["tagGlob"].rstrip("*")
    stable = [t for t in tags if t.startswith(prefix) and baseline["excludeContains"] not in t]
    if not stable:
        return baseline["seedVersion"], None
    newest = sorted(stable, key=version_key, reverse=True)[0]
    major, minor, patch = strip_auto_suffix(newest[1:]).split(".")[:3]
    return f"{major}.{minor}.{int(patch) + 1}", newest


def compute_version(mode: str, tags: list[str], contract: dict, ref_name: str = "", last_commit: str = "",
                    repo: str = "") -> dict:
    cfg = contract["release"]["types"][mode]
    build = next_build_number(tags)
    meta: dict = {"mode": mode, "build_number": build, "last_commit": last_commit, "previous_tag": "", "compare_url": "",
                  "build_marker_tag": "", "branch": cfg.get("branch", "")}
    if mode == "beta":
        version, _ = stable_base_version(tags, contract)
        betas = [t for t in tags if t.startswith(f"v{version}-beta.")]
        beta_num = 1
        previous = ""
        if betas:
            previous = sorted(betas, key=version_key, reverse=True)[0]
            match = re.search(r"beta\.([0-9]+)", previous)
            beta_num = int(match.group(1)) + 1 if match else 1
        app_version = f"{version}-beta.{beta_num}"
        tag = cfg["tagPattern"].format(version=version, beta=beta_num, build=build)
        meta.update(version=version, app_version=app_version, tag=tag, previous_tag=previous)
        if previous and repo:
            meta["compare_url"] = f"https://github.com/{repo}/compare/{previous}...{tag}"
    elif mode == "stable":
        version, _ = stable_base_version(tags, contract)
        meta.update(version=version, app_version=version, tag=cfg["tagPattern"].format(version=version, build=build))
    elif mode == "tag":
        if not ref_name:
            raise CiError("--ref-name (the pushed tag) is required for tag releases.")
        name = ref_name[len("refs/tags/"):] if ref_name.startswith("refs/tags/") else ref_name
        version = strip_auto_suffix(name[1:] if name.startswith("v") else name)
        meta.update(version=version, app_version=version, tag=name,
                    build_marker_tag=cfg["buildMarkerTagPattern"].format(version=version, build=build))
    else:
        raise CiError(f"Unknown release mode '{mode}'.")
    meta["release_name"] = f"{cfg['releaseNamePrefix']} {meta['tag']}"
    meta["versions_name"] = f"{cfg['releaseNamePrefix']} v{meta['app_version']}-auto({build})"
    meta["prerelease"] = cfg["prerelease"]
    meta["generate_release_notes"] = cfg["generateReleaseNotes"]
    meta["versions_release_type"] = cfg["versionsReleaseType"]
    return meta


def cmd_version(args) -> int:
    contract = load_contract()
    try:
        meta = compute_version(args.mode, list_tags(), contract, ref_name=args.ref_name or "",
                               last_commit=git("log", "-1", "--pretty=%B"), repo=os.environ.get("CI_REPOSITORY", ""))
        path = ROOT / contract["release"]["metadataFile"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"Release tag: {meta['tag']}; app version: {meta['app_version']}; build number: {meta['build_number']}")
    for key in ("tag", "app_version", "build_number"):
        emit(key, meta[key])
    return 0


# --------------------------------------------------------------------------------------------------------------------
# Signing and artifacts
# --------------------------------------------------------------------------------------------------------------------

def cmd_write_signing(_args) -> int:
    contract = load_contract()
    values = {name: os.environ.get(name, "") for name in contract["signing"]["secrets"]}
    missing = [name for name, value in values.items() if not value]
    if missing:
        print(f"Missing signing secret(s): {', '.join(missing)}", file=sys.stderr)
        return 1
    try:
        keystore = base64.b64decode(values["SIGNING_KEY_BASE64"], validate=False)
    except ValueError:
        print("SIGNING_KEY_BASE64 is not valid base64; no value is shown.", file=sys.stderr)
        return 1
    if not keystore:
        print("SIGNING_KEY_BASE64 decoded to an empty keystore.", file=sys.stderr)
        return 1
    keystore_path = ROOT / contract["signing"]["keystoreFile"]
    properties_path = ROOT / contract["signing"]["propertiesFile"]
    keystore_path.write_bytes(keystore)
    properties_path.write_text(
        f"keyAlias={values['KEY_ALIAS']}\nkeyPassword={values['KEY_PASSWORD']}\n"
        f"storePassword={values['STORE_PASSWORD']}\nstoreFile={keystore_path.name}\n", encoding="utf-8")
    for path in (keystore_path, properties_path):
        try:
            path.chmod(0o600)
        except OSError:
            pass
    print("Signing configuration written (values not shown).")
    return 0


def asset_name(contract: dict, variant: str, app_version: str, build: int, ext: str) -> str:
    return contract["release"]["assets"]["namePattern"].format(variant=variant, version=app_version, build=build, ext=ext)


def asset_list(contract: dict, meta: dict) -> list[dict]:
    """Ordered assets: mobile apk, mobile aab, tv apk, tv aab."""
    assets = contract["release"]["assets"]
    result = []
    for variant in assets["variants"]:
        for ext in assets["extensions"]:
            name = asset_name(contract, variant, meta["app_version"], meta["build_number"], ext)
            result.append({"name": name, "variant": variant, "ext": ext, "platform": assets["platformIds"][variant],
                           "path": ROOT / contract["release"]["outputDir"] / name})
    return result


def cmd_stage_artifacts(_args) -> int:
    contract = load_contract()
    try:
        meta = read_meta(contract)
        (ROOT / contract["release"]["outputDir"]).mkdir(parents=True, exist_ok=True)
        staged_root = ROOT / contract["toolchain"]["gradleWrapperDir"] / "build" / "staged"
        for asset in asset_list(contract, meta):
            found = sorted((staged_root / asset["variant"]).rglob(f"*.{asset['ext']}"))
            if not found:
                raise CiError(f"No staged {asset['variant']} .{asset['ext']} found under {staged_root / asset['variant']}.")
            shutil.move(str(found[0]), str(asset["path"]))
            print(f"Staged {asset['name']}")
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


# --------------------------------------------------------------------------------------------------------------------
# GitHub tag and release
# --------------------------------------------------------------------------------------------------------------------

def cmd_create_tag(args, api: GitHubApi | None = None) -> int:
    contract = load_contract()
    try:
        meta = read_meta(contract)
        tag = meta["build_marker_tag"] if meta["mode"] == "tag" else meta["tag"]
        sha = args.sha or git("rev-parse", "HEAD")
        api = api or GitHubApi(os.environ.get("GH_PAT", ""))
        status, _ = api.request("POST", f"{API}/repos/{repository()}/git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
        if status in (200, 201):
            print(f"Created tag {tag} at {sha[:7]}.")
        elif status == 422:
            # 422 also means an invalid sha/ref: an existing tag is accepted only when it points at this commit.
            ref_status, ref = api.request("GET", f"{API}/repos/{repository()}/git/ref/tags/{tag}")
            existing = (ref or {}).get("object", {}).get("sha") if isinstance(ref, dict) else None
            if ref_status != 200 or existing != sha:
                raise CiError(f"Failed to create tag {tag} (HTTP 422): it does not already exist at {sha[:7]}.")
            print(f"Tag {tag} already exists at {sha[:7]}. Skipping tag creation.")
        else:
            raise CiError(f"Failed to create tag {tag} (HTTP {status}).")
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


def release_body(meta: dict) -> str:
    if meta["mode"] == "tag":
        header = f"Automated release build for tag `{meta['tag']}`"
    else:
        header = f"Automated build from branch `{meta['branch']}`"
    return f"{header}\nApp version: `{meta['app_version']}`\n\nWhat's new:\n```\n{meta['last_commit']}\n```"


def publish_github_release(api: GitHubApi, repo: str, meta: dict, assets: list[dict], sha: str | None = None) -> None:
    base = f"{API}/repos/{repo}"
    for asset in assets:
        if not Path(asset["path"]).is_file():
            raise CiError(f"Expected release artifact is missing: {asset['name']}.")
    status, existing = api.request("GET", f"{base}/releases/tags/{meta['tag']}")
    fields = {"name": meta["release_name"], "body": release_body(meta), "prerelease": bool(meta["prerelease"]),
              "make_latest": "legacy"}  # legacy = the previous ncipollo default: date/semver decides, an older hotfix is not Latest
    if status == 200 and isinstance(existing, dict):
        release_id = existing["id"]
        status, _ = api.request("PATCH", f"{base}/releases/{release_id}", fields)
        if status != 200:
            raise CiError(f"Failed to update release {meta['tag']} (HTTP {status}).")
        current = {a["name"]: a["id"] for a in existing.get("assets", [])}
    elif status == 404:
        create = {"tag_name": meta["tag"], **fields}
        if sha:
            create["target_commitish"] = sha  # defense in depth: never let GitHub create the tag at the default branch head
        if meta.get("generate_release_notes"):
            create["generate_release_notes"] = True
        status, created = api.request("POST", f"{base}/releases", create)
        if status not in (200, 201) or not isinstance(created, dict):
            raise CiError(f"Failed to create release {meta['tag']} (HTTP {status}).")
        release_id = created["id"]
        current = {}
    else:
        raise CiError(f"Could not look up release {meta['tag']} (HTTP {status}).")
    for asset in assets:
        if asset["name"] in current:
            api.request("DELETE", f"{base}/releases/assets/{current[asset['name']]}")
        upload = f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name={asset['name']}"
        status, _ = api.request("POST", upload, headers={"Content-Type": "application/octet-stream"},
                                raw=Path(asset["path"]).read_bytes())
        if status not in (200, 201):
            raise CiError(f"Failed to upload {asset['name']} (HTTP {status}).")
        print(f"Uploaded {asset['name']}")


def cmd_publish_release(_args, api: GitHubApi | None = None) -> int:
    contract = load_contract()
    try:
        meta = read_meta(contract)
        api = api or GitHubApi(os.environ.get("GH_PAT", ""))
        publish_github_release(api, repository(), meta, asset_list(contract, meta), sha=git("rev-parse", "HEAD"))
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"Published GitHub release {meta['tag']} (prerelease={meta['prerelease']}).")
    return 0


# --------------------------------------------------------------------------------------------------------------------
# Versions API
# --------------------------------------------------------------------------------------------------------------------

def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_versions_payload(contract: dict, meta: dict, repo: str) -> dict:
    base_url = f"https://github.com/{repo}/releases/download/{meta['tag']}"
    assets = []
    for asset in asset_list(contract, meta):
        path = Path(asset["path"])
        if not path.is_file():
            raise CiError("Expected release artifacts are missing.")
        assets.append({"name": asset["name"], "platform": asset["platform"], "buildNumber": meta["build_number"],
                       "downloadUrl": f"{base_url}/{asset['name']}", "assetType": asset["ext"],
                       "sizeBytes": path.stat().st_size, "contentHash": sha256_of(path)})
    return {"versionNumber": meta["app_version"], "name": meta["versions_name"],
            "releaseType": meta["versions_release_type"], "changelog": meta["last_commit"], "assets": assets}


def versions_request(opener: Callable, method: str, url: str, key: str, body: dict | None = None) -> tuple[int, dict | None]:
    headers = {"X-API-Key": key}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data if data is not None else (b"" if method == "POST" else None),
                                     method=method, headers=headers)
    try:
        with opener(request, timeout=120) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, payload = exc.code, (exc.read() if hasattr(exc, "read") else b"")
    except (urllib.error.URLError, OSError) as exc:
        raise CiError(f"Versions API request failed ({type(exc).__name__}).") from None
    try:
        return status, (json.loads(payload.decode("utf-8")) if payload else None)
    except ValueError:
        return status, None


def cmd_versions_api(_args, opener: Callable = urllib.request.urlopen) -> int:
    contract = load_contract()
    api_cfg = contract["release"]["versionsApi"]
    base = os.environ.get("VERSIONS_API_BASE_URL", "").rstrip("/")
    key = os.environ.get("VERSIONS_API_KEY", "")
    if not base or not key:
        print("VERSIONS_API_BASE_URL (repository variable) and VERSIONS_API_KEY (secret) must be configured.", file=sys.stderr)
        return 1
    try:
        meta = read_meta(contract)
        payload = build_versions_payload(contract, meta, repository())
        status, body = versions_request(opener, "POST", base + api_cfg["createPath"], key, payload)
        if status == api_cfg["conflictStatusMeansAlreadyPublished"]:
            print(f"WARNING: Version {meta['app_version']} build {meta['build_number']} already exists.")
            emit("created", "false")
            return 0
        if status != 200:
            raise CiError(f"Failed to push version. HTTP {status}")
        version_id = (body or {}).get("data", {}).get("id") if isinstance(body, dict) else None
        if not version_id:
            raise CiError("Version created but response does not contain data.id")
        emit("created", "true")
        emit("version_id", version_id)
        languages = api_cfg["autoTranslateLanguages"]
        if not languages:
            print("WARNING: No auto-translate target languages configured.")
        for language in languages:
            url = (base + api_cfg["translatePath"].format(id=version_id, language=language)
                   + f"?sourceLocale={api_cfg['sourceLocale']}")
            print(f"Auto-translating release notes to '{language}' for versionId={version_id}")
            status, _ = versions_request(opener, "POST", url, key)
            if status != 200:
                raise CiError(f"Auto-translate failed for '{language}'. HTTP {status}")
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


# --------------------------------------------------------------------------------------------------------------------
# CI Gate commit status
# --------------------------------------------------------------------------------------------------------------------

def gate_state(job_result: str) -> str | None:
    normalized = job_result.strip().lower()
    if normalized in ("success", "succeeded", "succeededwithissues"):
        return "success"
    if normalized in ("cancelled", "canceled"):
        return "error"
    if normalized in ("skipped", ""):
        return None
    return "failure"


def cmd_ci_gate(args, api: GitHubApi | None = None) -> int:
    contract = load_contract()
    context = contract["pipelines"]["pr"]["status"]["context"]
    state = args.state or gate_state(args.job_result or "")
    if state is None:
        print("Job was skipped; not posting a CI Gate status.")
        return 0
    descriptions = {"pending": "CI running", "success": "CI passed", "failure": "CI failed", "error": "CI cancelled"}
    provider = args.provider
    try:
        sha = args.sha or git("rev-parse", "HEAD")
        api = api or GitHubApi(os.environ.get("GH_PAT", ""))
        status, _ = api.request("POST", f"{API}/repos/{repository()}/statuses/{sha}", {
            "state": state, "context": context, "description": f"{provider.capitalize()} {descriptions[state]}"[:140],
            "target_url": args.target_url or ""})
        if status not in (200, 201):
            raise CiError(f"Could not post the {context} status (HTTP {status}).")
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"Posted {context}={state} for {sha[:7]}.")
    return 0


# --------------------------------------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    guard = sub.add_parser("guard")
    guard.add_argument("--provider", required=True, choices=["github", "azure"])
    guard.add_argument("--snapshot", default=None, help="workflow-start CI_PROVIDER value (github fallback only)")
    guard.add_argument("--max-wait", type=int, default=300)
    guard.add_argument("--poll", type=int, default=15)

    detect = sub.add_parser("detect-changes")
    detect.add_argument("--event", required=True, choices=["push", "manual", "replay", "auto"])
    detect.add_argument("--replay", default="false", help="true when the run is a provider hand-off replay (with --event auto)")
    detect.add_argument("--mode", default=None, choices=["beta", "stable", "tag"])
    detect.add_argument("--before", default=None)
    detect.add_argument("--after", default=None)
    detect.add_argument("--branch", default=None)
    detect.add_argument("--pinned-sha", default=None)

    mode = sub.add_parser("resolve-mode")
    mode.add_argument("--ref", default=None)

    meta = sub.add_parser("pr-metadata")
    meta.add_argument("--sha", default=None)
    sub.add_parser("install-swig")
    sub.add_parser("install-android-sdk")
    gradle = sub.add_parser("gradle")
    gradle.add_argument("task_set")
    rename = sub.add_parser("rename-apks")
    rename.add_argument("--sha", default=None)

    version = sub.add_parser("version")
    version.add_argument("--mode", required=True, choices=["beta", "stable", "tag"])
    version.add_argument("--ref-name", default=None)
    sub.add_parser("write-signing")
    sub.add_parser("stage-artifacts")
    tag = sub.add_parser("create-tag")
    tag.add_argument("--sha", default=None)
    sub.add_parser("publish-release")
    sub.add_parser("versions-api")

    gate = sub.add_parser("ci-gate")
    gate.add_argument("--provider", required=True, choices=["github", "azure"])
    gate.add_argument("--state", default=None, choices=["pending", "success", "failure", "error"])
    gate.add_argument("--job-result", default=None)
    gate.add_argument("--sha", default=None)
    gate.add_argument("--target-url", default=None)
    return parser


COMMANDS = {
    "guard": cmd_guard, "detect-changes": cmd_detect_changes, "resolve-mode": cmd_resolve_mode,
    "pr-metadata": cmd_pr_metadata, "install-swig": cmd_install_swig, "install-android-sdk": cmd_install_android_sdk,
    "gradle": cmd_gradle, "rename-apks": cmd_rename_apks, "version": cmd_version, "write-signing": cmd_write_signing,
    "stage-artifacts": cmd_stage_artifacts, "create-tag": cmd_create_tag, "publish-release": cmd_publish_release,
    "versions-api": cmd_versions_api, "ci-gate": cmd_ci_gate,
}


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return COMMANDS[args.command](args)
    except CiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except Exception as exc:  # never let an unexpected error carry a value into a log
        print(f"Unexpected error in '{args.command}' ({type(exc).__name__}); no value is shown.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
