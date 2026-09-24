#!/usr/bin/env python3
"""Fails when a provider adapter drifts from .ci/pipeline-contract.json.

Checks the GitHub workflows (.github/workflows/build-by-pull-request.yml, release-by-dev.yml, release-by-main.yml,
release-by-tag.yml, ci-provider-reset.yml) and the Azure pipelines (azure-pipelines.yml, azure-release.yml) for:
  * triggers (branches, tags, paths, PR types, manual/replay inputs) and concurrency/serialization,
  * provider gating (every GitHub job is skipped while CI_PROVIDER=azure; Azure runs a live guard),
  * the ordered list of shared-script invocations (the anti-drift core) and no unlisted extras,
  * no inline build/release logic in YAML (gradle, git tag, curl, apt-get, sdkmanager, base64 ...),
  * release modes, Gradle task sets, JDK version, artifact names and retention,
  * repository variables and secrets each adapter may reference (least privilege, PR pipelines never see release,
    signing or provider-switch credentials),
  * Azure bootstrap constants (Key Vault, service connections, environment) equal keyVault.bootstrap.

Usage: python3 scripts/ci/check_pipeline_parity.py [--root PATH]
Exit 0 when the adapters match the contract, 1 with one line per violation otherwise.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("PyYAML is required: python3 -m pip install pyyaml", file=sys.stderr)
    raise SystemExit(2)

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN_INLINE = [
    (r"\./gradlew|\bgradlew\b", "inline Gradle invocation (use android_ci.py gradle <task-set>)"),
    (r"\bgit\s+tag\b|\bgit\s+push\b", "inline git tagging/pushing"),
    (r"\bcurl\b|\bwget\b", "inline HTTP call"),
    (r"\bapt-get\b|\bsdkmanager\b", "inline toolchain installation"),
    (r"base64\s+(-d|--decode)", "inline keystore decoding"),
    (r"keystore\.properties|keyPassword=|storePassword=", "inline signing configuration"),
    (r"ndk;|cmake;|build-tools;|platforms;android", "hard-coded Android SDK package list (contract owns it)"),
    (r"releases/download|api/v1/versions", "inline release/Versions API logic"),
]
PR_FORBIDDEN_SECRET_ENVS = {"SIGNING_KEY_BASE64", "KEY_ALIAS", "KEY_PASSWORD", "STORE_PASSWORD", "VERSIONS_API_KEY",
                            "CI_SWITCH_PAT", "AZDO_PAT"}


def load_yaml(path: Path):
    with open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def on_block(doc: dict) -> dict:
    # PyYAML parses the bare key `on` as boolean True.
    block = doc.get("on", doc.get(True))
    return block or {}


def invocations(script: str) -> list[str]:
    """Shared-script invocations in a shell snippet, in order, as contract-style labels."""
    found = []
    for match in re.finditer(r"scripts/ci/([A-Za-z0-9_.\-]+)[\x22']?((?:\s+[a-z][a-z\-]*){0,2})", script):
        name, rest = match.group(1), match.group(2).split()
        label = name
        if name == "android_ci.py" and rest:
            label = f"{name} {rest[0]}"
            if rest[0] == "gradle" and len(rest) > 1:
                label += f" {rest[1]}"
        found.append(label)
    return found


def github_steps(doc: dict) -> list[dict]:
    steps = []
    for job in (doc.get("jobs") or {}).values():
        steps.extend(job.get("steps") or [])
    return steps


def github_invocations(doc: dict) -> list[str]:
    result: list[str] = []
    for step in github_steps(doc):
        if "run" in step:
            result.extend(invocations(str(step["run"])))
    return result


def azure_steps(doc: dict) -> list[dict]:
    steps = list(doc.get("steps") or [])
    for job in doc.get("jobs") or []:
        strategy = (job.get("strategy") or {}).get("runOnce", {}).get("deploy", {})
        steps.extend(strategy.get("steps") or job.get("steps") or [])
    return steps


def azure_step_script(step: dict) -> str:
    for key in ("bash", "script", "powershell", "pwsh"):
        if key in step:
            return str(step[key])
    inputs = step.get("inputs") or {}
    return str(inputs.get("inlineScript", ""))


def azure_invocations(doc: dict) -> list[str]:
    result: list[str] = []
    for step in azure_steps(doc):
        result.extend(invocations(azure_step_script(step)))
    return result


def check_sequence(errors: list[str], where: str, actual: list[str], shared: list[str], extras: list[str]) -> None:
    position = 0
    for expected in shared:
        try:
            position = actual.index(expected, position) + 1
        except ValueError:
            errors.append(f"{where}: shared step '{expected}' is missing or out of order (contract order: {shared}).")
            return
    allowed = set(shared) | set(extras)
    for label in dict.fromkeys(actual):
        if label not in allowed:
            errors.append(f"{where}: invokes '{label}', which the contract does not list for this provider.")
    for label in extras:
        if label not in actual:
            errors.append(f"{where}: provider step '{label}' required by the contract is missing.")


def check_inline(errors: list[str], where: str, scripts: list[str]) -> None:
    for script in scripts:
        for pattern, reason in FORBIDDEN_INLINE:
            if re.search(pattern, script):
                errors.append(f"{where}: {reason}: adapters must call scripts/ci/ instead.")


def github_scripts(doc: dict) -> list[str]:
    # github-script blocks (`with.script`) are JavaScript status/comment posters, not build logic.
    return [str(s["run"]) for s in github_steps(doc) if "run" in s]


def text_refs(text: str, kind: str) -> set[str]:
    return set(re.findall(rf"\b{kind}\.([A-Z][A-Z0-9_]*)", text))


def check_github_common(errors, name, path, doc, contract, gated=True):
    text = path.read_text(encoding="utf-8")
    for var in text_refs(text, "vars"):
        if var not in contract["repositoryVariables"]:
            errors.append(f"{name}: uses vars.{var}, which is not in the contract's repositoryVariables.")
    if "vars.CI_PROVIDER_SWITCHING" in text:
        errors.append(f"{name}: must not read the reserved live lock variable CI_PROVIDER_SWITCHING through vars.*.")
    allowed_secrets = set(contract["gitHubSecrets"])
    for secret in text_refs(text, "secrets"):
        if secret not in allowed_secrets:
            errors.append(f"{name}: references secrets.{secret}, which is not in the contract's gitHubSecrets.")
    for job_name, job in (doc.get("jobs") or {}).items():
        # Jobs that depend on another job inherit its skip; every root job must gate itself.
        if gated and not job.get("needs") and "CI_PROVIDER != 'azure'" not in str(job.get("if", "")):
            errors.append(f"{name}: job '{job_name}' must be skipped while CI_PROVIDER=azure "
                          "(job-level `if: vars.CI_PROVIDER != 'azure'`).")
    check_inline(errors, name, github_scripts(doc))
    for step in github_steps(doc):
        uses = str(step.get("uses", ""))
        if uses.startswith("actions/setup-java"):
            java = step.get("with", {})
            if str(java.get("java-version")) != contract["toolchain"]["jdkVersion"]:
                errors.append(f"{name}: setup-java version {java.get('java-version')} != contract {contract['toolchain']['jdkVersion']}.")
            if java.get("distribution") != contract["toolchain"]["jdkDistribution"]:
                errors.append(f"{name}: setup-java distribution {java.get('distribution')} != contract.")


def check_github_pr(errors, root, contract):
    cfg = contract["pipelines"]["pr"]
    path = root / cfg["github"]["file"]
    name = cfg["github"]["file"]
    if not path.exists():
        errors.append(f"{name}: file is missing.")
        return
    doc = load_yaml(path)
    check_github_common(errors, name, path, doc, contract)
    check_github_pr_trust(errors, name, path, doc, cfg["github"])
    trig = on_block(doc)
    if sorted((trig.get("pull_request") or {}).get("types", [])) != sorted(cfg["triggers"]["githubPullRequestTypes"]):
        errors.append(f"{name}: pull_request types differ from the contract.")
    if "workflow_dispatch" not in trig:
        errors.append(f"{name}: workflow_dispatch trigger is missing.")
    conc = doc.get("concurrency") or {}
    if not str(conc.get("group", "")).startswith(cfg["concurrency"]["github"]["group"]) \
            or bool(conc.get("cancel-in-progress")) != cfg["concurrency"]["github"]["cancelInProgress"]:
        errors.append(f"{name}: concurrency does not match the contract.")
    check_sequence(errors, name, github_invocations(doc), cfg["sharedSteps"], cfg["providerSteps"]["github"])
    uploads = [s for s in github_steps(doc) if str(s.get("uses", "")).startswith("actions/upload-artifact")]
    for artifact in cfg["artifacts"]["names"]:
        prefix = artifact.split("{")[0]
        matching = [s for s in uploads if str(s["with"].get("name", "")).startswith(prefix)]
        if not matching:
            errors.append(f"{name}: no upload-artifact step publishes '{artifact}'.")
        for step in matching:
            if step["with"].get("retention-days") != cfg["artifacts"]["githubRetentionDays"]:
                errors.append(f"{name}: '{artifact}' retention differs from the contract.")
    report = cfg["artifacts"]["unitTestReport"]
    if not any(s["with"].get("name") == report["name"] and s["with"].get("retention-days") == report["githubRetentionDays"]
               and "failure()" in str(s.get("if", "")) for s in uploads):
        errors.append(f"{name}: unit-test-report artifact must upload on failure only with the contract retention.")
    if cfg["status"]["context"] not in path.read_text(encoding="utf-8"):
        errors.append(f"{name}: the '{cfg['status']['context']}' commit status is not posted.")


def check_github_pr_trust(errors, name, path, doc, gh):
    """PR-controlled code must not see the release PAT, must not keep credentials in .git/config, and only the
    checkout-free gate job may hold statuses: write."""
    text = path.read_text(encoding="utf-8")
    if "secrets.GH_PAT" in text:
        errors.append(f"{name}: must never reference secrets.GH_PAT (the release PAT); use secrets.{gh['checkoutSecret']}.")
    if "statuses" in (doc.get("permissions") or {}):
        errors.append(f"{name}: statuses must not be granted at workflow level.")
    jobs = doc.get("jobs") or {}
    build, gate = jobs.get(gh["buildJob"]) or {}, jobs.get(gh["gateJob"]) or {}
    if build.get("permissions") != gh["buildPermissions"]:
        errors.append(f"{name}: job '{gh['buildJob']}' permissions must be exactly {gh['buildPermissions']} (no statuses).")
    if gate.get("permissions") != gh["gatePermissions"]:
        errors.append(f"{name}: job '{gh['gateJob']}' permissions must be exactly {gh['gatePermissions']}.")
    if any(str(s.get("uses", "")).startswith("actions/checkout") for s in gate.get("steps") or []):
        errors.append(f"{name}: the gate job must not check out code.")
    checkouts = [s for s in github_steps(doc) if str(s.get("uses", "")).startswith("actions/checkout")]
    if not checkouts:
        errors.append(f"{name}: no checkout step found.")
    for step in checkouts:
        with_ = step.get("with") or {}
        if with_.get("persist-credentials") is not False:
            errors.append(f"{name}: actions/checkout must set persist-credentials: false.")
        if with_.get("token") != "${{ secrets." + gh["checkoutSecret"] + " }}":
            errors.append(f"{name}: actions/checkout must use secrets.{gh['checkoutSecret']} (read-only).")


def check_github_release(errors, root, contract, key):
    common = contract["pipelines"]["release-common"]
    cfg = contract["pipelines"][key]
    path = root / cfg["github"]["file"]
    name = cfg["github"]["file"]
    if not path.exists():
        errors.append(f"{name}: file is missing.")
        return
    doc = load_yaml(path)
    check_github_common(errors, name, path, doc, contract)
    trig = on_block(doc)
    push = trig.get("push") or {}
    expected = cfg["triggers"]
    if sorted(push.get("branches", [])) != sorted(expected.get("branches", [])):
        errors.append(f"{name}: push branches {push.get('branches')} != contract {expected.get('branches', [])}.")
    if sorted(push.get("tags", [])) != sorted(expected.get("tags", [])):
        errors.append(f"{name}: push tags {push.get('tags')} != contract {expected.get('tags', [])}.")
    if sorted(push.get("paths", [])) != sorted(expected.get("paths", [])):
        errors.append(f"{name}: push paths {push.get('paths')} != contract {expected.get('paths', [])}.")
    if bool("workflow_dispatch" in trig) != bool(expected.get("manual")):
        errors.append(f"{name}: workflow_dispatch presence differs from the contract (manual={expected.get('manual')}).")
    if expected.get("replay"):
        inputs = (trig.get("workflow_dispatch") or {}).get("inputs", {}) or {}
        if "replay" not in inputs or "replay_sha" not in inputs:
            errors.append(f"{name}: workflow_dispatch must declare the replay and replay_sha inputs.")
    conc = doc.get("concurrency") or {}
    want = common["concurrency"]["github"]
    if conc.get("group") != want["group"] or bool(conc.get("cancel-in-progress")) != want["cancelInProgress"]:
        errors.append(f"{name}: concurrency must be group '{want['group']}' with cancel-in-progress {want['cancelInProgress']}.")
    omit = set(cfg.get("omitSteps", {}).get("github", []))
    shared = [s for s in common["sharedSteps"] if s not in omit]
    actual = github_invocations(doc)
    check_sequence(errors, name, actual, shared, common["providerSteps"]["github"])
    text = path.read_text(encoding="utf-8")
    if not re.search(rf"android_ci\.py version --mode {cfg['releaseType']}\b", text):
        errors.append(f"{name}: 'version' must run with --mode {cfg['releaseType']}.")
    if "detect-changes" in " ".join(shared) and expected.get("branches"):
        if f"--branch {expected['branches'][0]}" not in re.sub(r"\s+", " ", text):
            errors.append(f"{name}: detect-changes must pass --branch {expected['branches'][0]} for the stale-replay guard.")
    for secret in text_refs(text, "secrets"):
        if secret not in set(common["secrets"]) | {"GITHUB_TOKEN"}:
            errors.append(f"{name}: secrets.{secret} is not a release secret in the contract.")


def check_azure_conditions(errors, name, steps):
    """A custom `condition:` replaces Azure's implicit succeeded(): every conditional step must state its failure
    semantics (succeeded(), failed(), always() or succeededOrFailed()), otherwise it keeps running after a failed step."""
    for step in steps:
        condition = step.get("condition")
        if condition is None:
            continue
        if not re.search(r"\b(succeeded|failed|always|succeededOrFailed)\(\)", str(condition)):
            label = step.get("displayName") or step.get("name") or azure_step_script(step)[:40]
            errors.append(f"{name}: step '{label}' has condition {condition!r} without succeeded()/failed()/always(): "
                          "it would keep running after an earlier step failed (use and(succeeded(), ...)).")


def azure_job_steps(doc: dict, job_name: str) -> list[dict]:
    for job in doc.get("jobs") or []:
        if job.get("job") == job_name or job.get("deployment") == job_name:
            return list(job.get("steps") or [])
    return []


def check_azure_common(errors, name, text, contract, variable_secrets):
    for var in re.findall(r"\$\(repo\.([A-Z][A-Z0-9_]*)\)", text):
        if var not in contract["repositoryVariables"]:
            errors.append(f"{name}: uses repo.{var}, which is not in the contract's repositoryVariables.")
    boot = contract["keyVault"]["bootstrap"]
    if not re.search(rf"^\s+AZURE_KEY_VAULT_NAME:\s*{re.escape(boot['vaultName'])}\s*$", text, re.M):
        errors.append(f"{name}: AZURE_KEY_VAULT_NAME must equal keyVault.bootstrap.vaultName ({boot['vaultName']}).")
    for secret_name in re.findall(r"\$\(([A-Z][A-Z0-9_]*)\)", text):
        if secret_name in PR_FORBIDDEN_SECRET_ENVS and secret_name not in variable_secrets:
            errors.append(f"{name}: references $({secret_name}), which this pipeline's identity must not receive.")


def check_azure_pr(errors, root, contract):
    cfg = contract["pipelines"]["pr"]
    name = cfg["azure"]["file"]
    path = root / name
    if not path.exists():
        errors.append(f"{name}: file is missing.")
        return
    text = path.read_text(encoding="utf-8")
    doc = load_yaml(path)
    check_azure_common(errors, name, text, contract, set())
    check_azure_conditions(errors, name, azure_steps(doc))
    if doc.get("trigger") not in (None, "none"):
        errors.append(f"{name}: trigger must be 'none' (PR pipeline).")
    pr = doc.get("pr") or {}
    if pr.get("autoCancel") != cfg["concurrency"]["azure"]["autoCancel"]:
        errors.append(f"{name}: pr.autoCancel must be {cfg['concurrency']['azure']['autoCancel']} (cancel-in-progress parity).")
    if sorted((pr.get("branches") or {}).get("include", [])) != sorted(cfg["triggers"]["azurePullRequestBranches"]):
        errors.append(f"{name}: pr.branches.include differs from the contract.")
    check_sequence(errors, name, azure_invocations(doc), cfg["sharedSteps"], cfg["providerSteps"]["azure"])
    check_inline(errors, name, [azure_step_script(s) for s in azure_steps(doc)])
    variables = doc.get("variables") or {}
    boot = contract["keyVault"]["bootstrap"]
    if variables.get("AZURE_PR_CI_SERVICE_CONNECTION") != boot["prServiceConnection"]:
        errors.append(f"{name}: AZURE_PR_CI_SERVICE_CONNECTION must equal keyVault.bootstrap.prServiceConnection.")
    if str(variables.get("JDK_VERSION")) != contract["toolchain"]["jdkVersion"]:
        errors.append(f"{name}: JDK_VERSION must equal the contract jdkVersion.")
    if variables.get("AZURE_PR_GATE_SERVICE_CONNECTION") != boot["prGateServiceConnection"]:
        errors.append(f"{name}: AZURE_PR_GATE_SERVICE_CONNECTION must equal keyVault.bootstrap.prGateServiceConnection.")
    build_steps = azure_job_steps(doc, cfg["azure"]["jobs"]["build"])
    gate_steps = azure_job_steps(doc, cfg["azure"]["jobs"]["gate"])
    if not build_steps or not gate_steps:
        errors.append(f"{name}: jobs '{cfg['azure']['jobs']['build']}' and '{cfg['azure']['jobs']['gate']}' must both exist "
                      "(the CI Gate is posted by a separate job).")
    build_text = json.dumps(build_steps)
    gate_text = json.dumps(gate_steps)
    for job_label, job_text, want in (("build", build_text, cfg["azure"]["secretConsumer"]),
                                      ("gate", gate_text, cfg["azure"]["gateSecretConsumer"])):
        found = re.findall(r"--consumer\s+([A-Za-z0-9_-]+)", job_text)
        if found != [want]:
            errors.append(f"{name}: the {job_label} job must load exactly the '{want}' secret consumer, found {found}.")
    if "ci-gate" in build_text or "PR_GATE_SERVICE_CONNECTION" in build_text or cfg["azure"]["gateSecretConsumer"] in build_text:
        errors.append(f"{name}: the build job must not post the CI Gate or use the statuses identity "
                      "(pull-request-controlled code runs there).")
    if "ci-gate" not in gate_text:
        errors.append(f"{name}: the gate job must post the CI Gate.")
    if "PR_CI_SERVICE_CONNECTION" in gate_text:
        errors.append(f"{name}: the gate job must not use the build identity.")
    for step in build_steps + gate_steps:
        script = azure_step_script(step)
        if re.search(r"load_vault_secrets\.py|android_ci\.py\s+ci-gate", script) and "/trusted/scripts/ci/" not in script:
            errors.append(f"{name}: the vault loader and ci-gate must run from the trusted target-branch copy "
                          "($(Agent.TempDirectory)/trusted/scripts/ci/), not from the pull-request tree.")
    if not all("HEAD^1" in json.dumps(steps) for steps in (build_steps, gate_steps)):
        errors.append(f"{name}: both jobs must stage the trusted scripts from the merge commit's first parent (HEAD^1).")
    for artifact in cfg["artifacts"]["names"]:
        prefix = artifact.split("{")[0]
        if not any(str((s.get("inputs") or {}).get("artifact", "")).startswith(prefix) for s in azure_steps(doc)):
            errors.append(f"{name}: no PublishPipelineArtifact step publishes '{artifact}'.")
    if not any((s.get("inputs") or {}).get("artifact") == cfg["artifacts"]["unitTestReport"]["name"] and s.get("condition") == "failed()"
               for s in azure_steps(doc)):
        errors.append(f"{name}: unit-test-report must be published on failure only.")
    for step in azure_steps(doc):
        script = azure_step_script(step)
        if "android_ci.py gradle" in script and "GH_PAT" in json.dumps(step.get("env") or {}):
            errors.append(f"{name}: the Gradle step must not receive GH_PAT: pull-request-controlled code runs there.")
        if "android_ci.py gradle" in script:
            missing = [v for v in cfg["configVariables"] if f"$(repo.{v})" not in json.dumps(step.get("env") or {})]
            if missing:
                errors.append(f"{name}: a Gradle step is missing configuration variables {missing}.")
    if cfg["status"]["context"] not in text and "ci-gate" not in text:
        errors.append(f"{name}: the CI Gate status is not posted.")


def check_azure_release(errors, root, contract):
    common = contract["pipelines"]["release-common"]
    name = common["azure"]["file"]
    path = root / name
    if not path.exists():
        errors.append(f"{name}: file is missing.")
        return
    text = path.read_text(encoding="utf-8")
    doc = load_yaml(path)
    release_secret_envs = set(common["secrets"]) - {"GH_PAT"}
    check_azure_common(errors, name, text, contract, release_secret_envs)
    check_azure_conditions(errors, name, azure_steps(doc))
    types = contract["release"]["types"]
    trigger = doc.get("trigger") or {}
    want_branches = sorted(cfg["triggers"]["branches"][0] for cfg in
                           (contract["pipelines"][k] for k in ("release-dev", "release-main")))
    if sorted((trigger.get("branches") or {}).get("include", [])) != want_branches:
        errors.append(f"{name}: trigger.branches.include must be {want_branches}.")
    tag_cfg = contract["pipelines"]["release-tag"]["triggers"]
    if sorted((trigger.get("tags") or {}).get("include", [])) != sorted(tag_cfg["tags"]):
        errors.append(f"{name}: trigger.tags.include must be {tag_cfg['tags']}.")
    if sorted((trigger.get("tags") or {}).get("exclude", [])) != sorted(tag_cfg["excludeTags"]):
        errors.append(f"{name}: trigger.tags.exclude must be {tag_cfg['excludeTags']} (tags created by the branch flows are ignored).")
    if doc.get("pr") not in ("none", None) and doc.get("pr") is not None:
        errors.append(f"{name}: pr must be 'none': the release identity must never run pull-request code.")
    if str(doc.get("pr")) != "none":
        errors.append(f"{name}: pr: none must be declared explicitly.")
    if doc.get("lockBehavior") != common["concurrency"]["azure"]["lockBehavior"]:
        errors.append(f"{name}: lockBehavior must be {common['concurrency']['azure']['lockBehavior']} (build-number serialization).")
    jobs = doc.get("jobs") or []
    if not any(j.get("environment") == common["concurrency"]["azure"]["environment"] for j in jobs):
        errors.append(f"{name}: a deployment job must use environment {common['concurrency']['azure']['environment']} (exclusive lock).")
    if not any(p.get("name") == "replay" for p in doc.get("parameters") or []):
        errors.append(f"{name}: the 'replay' parameter is missing.")
    check_sequence(errors, name, azure_invocations(doc), common["sharedSteps"], common["providerSteps"]["azure"])
    check_inline(errors, name, [azure_step_script(s) for s in azure_steps(doc)])
    variables = doc.get("variables") or {}
    boot = contract["keyVault"]["bootstrap"]
    if variables.get("AZURE_RELEASE_SERVICE_CONNECTION") != boot["releaseServiceConnection"]:
        errors.append(f"{name}: AZURE_RELEASE_SERVICE_CONNECTION must equal keyVault.bootstrap.releaseServiceConnection.")
    if str(variables.get("JDK_VERSION")) != contract["toolchain"]["jdkVersion"]:
        errors.append(f"{name}: JDK_VERSION must equal the contract jdkVersion.")
    consumers = re.findall(r"--consumer\s+(\S+)", text)
    if consumers != [common["azure"]["secretConsumer"]]:
        errors.append(f"{name}: must load exactly the '{common['azure']['secretConsumer']}' secret consumer, found {consumers}.")
    if not re.search(r"version --mode \"\$\(mode\.mode\)\"", text):
        errors.append(f"{name}: 'version' must take its mode from the resolve-mode step.")
    for step in azure_steps(doc):
        if "write-signing" in azure_step_script(step):
            env_keys = set((step.get("env") or {}).keys())
            if env_keys != set(contract["signing"]["secrets"]):
                errors.append(f"{name}: write-signing env {sorted(env_keys)} != contract signing secrets.")
        if "android_ci.py gradle" in azure_step_script(step):
            missing = [v for v in common["configVariables"] if v in ("PRIMARY_SERVERS_URL", "FALLBACK_SERVERS_URL")
                       and f"$(repo.{v})" not in json.dumps(step.get("env") or {})]
            if missing:
                errors.append(f"{name}: the Gradle step is missing configuration variables {missing}.")
    for mode in types:
        cfg_key = {"beta": "release-dev", "stable": "release-main", "tag": "release-tag"}[mode]
        if contract["pipelines"][cfg_key]["azure"]["mode"] != mode:
            errors.append(f"contract: pipelines.{cfg_key}.azure.mode must be '{mode}'.")


def check_reset(errors, root, contract):
    state = contract["providerState"]
    path = root / state["resetWorkflow"]
    if not path.exists():
        errors.append(f"{state['resetWorkflow']}: file is missing.")
        return
    doc = load_yaml(path)
    text = path.read_text(encoding="utf-8")
    crons = [s.get("cron") for s in (on_block(doc).get("schedule") or [])]
    if crons != [state["resetCron"]]:
        errors.append(f"{state['resetWorkflow']}: schedule {crons} != contract {state['resetCron']}.")
    if "--consumer ci-provider-reset" not in text:
        errors.append(f"{state['resetWorkflow']}: must load the ci-provider-reset consumer.")
    if state["switchCommand"] not in text:
        errors.append(f"{state['resetWorkflow']}: must reset through {state['switchCommand']}.")
    if "github" not in re.findall(r"Mode\s*=\s*'(\w+)'", text):
        errors.append(f"{state['resetWorkflow']}: must reset the provider to github.")
    check_github_common(errors, state["resetWorkflow"], path, doc, contract, gated=False)


def check_contract(errors, root, contract):
    catalogue = contract["keyVault"]["secrets"]
    for consumer, entry in contract["secretConsumers"].items():
        for secret in entry["secrets"]:
            if secret not in catalogue:
                errors.append(f"contract: consumer '{consumer}' lists '{secret}', which is not in the Key Vault catalogue.")
    pr_secrets = {catalogue[s]["env"] for s in contract["secretConsumers"]["azure-pr-ci"]["secrets"] if s in catalogue}
    leaked = pr_secrets & PR_FORBIDDEN_SECRET_ENVS
    if leaked:
        errors.append(f"contract: azure-pr-ci must not load {sorted(leaked)} (PR-controlled code runs with it).")
    if set(contract["secretConsumers"]["azure-release"]["secrets"]) & set(contract["secretConsumers"]["azure-pr-ci"]["secrets"]):
        errors.append("contract: azure-pr-ci and azure-release must not share a Key Vault secret.")
    reset_envs = {catalogue[s]["env"] for s in contract["secretConsumers"]["ci-provider-reset"]["secrets"]}
    if reset_envs != {"CI_SWITCH_PAT", "AZDO_PAT"}:
        errors.append("contract: ci-provider-reset must load exactly CI_SWITCH_PAT and AZDO_PAT.")
    if set(contract["secretConsumers"]["azure-pr-gate"]["secrets"]) & set(contract["secretConsumers"]["azure-pr-ci"]["secrets"]):
        errors.append("contract: azure-pr-ci (build) and azure-pr-gate must not share a Key Vault secret.")
    gate_envs = {catalogue[s]["env"] for s in contract["secretConsumers"]["azure-pr-gate"]["secrets"] if s in catalogue}
    if gate_envs & (PR_FORBIDDEN_SECRET_ENVS - {"GH_PAT"}):
        errors.append(f"contract: azure-pr-gate must not load {sorted(gate_envs & PR_FORBIDDEN_SECRET_ENVS)}.")
    for consumer in ("azure-pr-ci", "azure-pr-gate", "azure-release"):
        if reset_envs & {catalogue[s]["env"] for s in contract["secretConsumers"][consumer]["secrets"]}:
            errors.append(f"contract: {consumer} must never load the provider-switch credentials.")
    for name in contract["pipelines"]["pr"]["gradleTaskSets"]:
        if name not in contract["gradle"]["taskSets"]:
            errors.append(f"contract: unknown Gradle task set '{name}'.")
    for var in contract["pipelines"]["pr"]["configVariables"] + contract["pipelines"]["release-common"]["configVariables"]:
        if var not in contract["repositoryVariables"]:
            errors.append(f"contract: configVariable '{var}' is not in repositoryVariables.")
    if not contract["intentionalDifferences"]:
        errors.append("contract: intentionalDifferences must document the provider-specific differences.")
    shared_dir = root / "scripts" / "ci"
    for label in set(contract["pipelines"]["pr"]["sharedSteps"] + contract["pipelines"]["release-common"]["sharedSteps"]
                     + contract["pipelines"]["pr"]["providerSteps"]["azure"] + contract["pipelines"]["release-common"]["providerSteps"]["azure"]):
        script = label.split()[0]
        if not (shared_dir / script).exists():
            errors.append(f"contract: shared script scripts/ci/{script} does not exist.")


def check(root: Path = DEFAULT_ROOT) -> list[str]:
    errors: list[str] = []
    contract_path = root / ".ci" / "pipeline-contract.json"
    if not contract_path.exists():
        return [".ci/pipeline-contract.json is missing."]
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    check_contract(errors, root, contract)
    check_github_pr(errors, root, contract)
    for key in ("release-dev", "release-main", "release-tag"):
        check_github_release(errors, root, contract, key)
    check_reset(errors, root, contract)
    check_azure_pr(errors, root, contract)
    check_azure_release(errors, root, contract)
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    args = parser.parse_args(argv)
    errors = check(Path(args.root))
    if errors:
        for line in errors:
            print(f"PARITY VIOLATION: {line}", file=sys.stderr)
        return 1
    print("Pipeline parity OK: GitHub and Azure adapters match .ci/pipeline-contract.json.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
