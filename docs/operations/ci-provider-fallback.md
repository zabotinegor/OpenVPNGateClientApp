# CI provider fallback (GitHub Actions / Azure DevOps)

## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [Model in one minute](#model-in-one-minute)
- [Workflow audit and Azure equivalents](#workflow-audit-and-azure-equivalents)
- [Contract, shared scripts and the parity check](#contract-shared-scripts-and-the-parity-check)
- [Configuration and secrets](#configuration-and-secrets)
- [One-time setup (manual, needs credentials)](#one-time-setup-manual-needs-credentials)
- [Operating the switch](#operating-the-switch)
- [Release safety: serialization, hand-off, stale replay](#release-safety-serialization-hand-off-stale-replay)
- [Behaviour that is intentionally different](#behaviour-that-is-intentionally-different)
- [Validation matrix](#validation-matrix)
- [Recovery](#recovery)
- [Local tests](#local-tests)

---

## Model in one minute

GitHub Actions is the default provider for pull request CI and for the dev, main and tag release flows. When GitHub-hosted
minutes are unavailable, one operator command moves **all** of it to Azure DevOps, and on the 1st of every month a scheduled
workflow moves it back.

```powershell
./scripts/ci/pipeline-mode.ps1 status
./scripts/ci/pipeline-mode.ps1 azure          # add -DryRun to preview
./scripts/ci/pipeline-mode.ps1 github
```

- The GitHub repository variable **`CI_PROVIDER`** (`github` | `azure`, unset means `github`) is the single source of truth.
- In `azure` mode every GitHub job is skipped at the job-level `if` (no runner is allocated); in `github` mode both Azure
  pipeline definitions are **disabled** (no hosted agent is allocated) and their first step re-checks the live variable.
- Azure receives GitHub events directly through the Azure Pipelines GitHub integration. No GitHub-hosted router job exists.
- A failing build or test is a build failure. Nothing ever infers "quota exhausted" from one, so a red build never flips the
  provider.

## Workflow audit and Azure equivalents

| GitHub workflow | Trigger | Azure equivalent |
|---|---|---|
| `build-by-pull-request.yml` | `pull_request` (opened, synchronize, reopened, ready_for_review), manual | `azure-pipelines.yml` (PR trigger, `autoCancel: true`) |
| `release-by-dev.yml` | push to `dev` under `src/**` or `media`, manual, replay | `azure-release.yml`, mode `beta` |
| `release-by-main.yml` | push to `main`, manual, replay | `azure-release.yml`, mode `stable` |
| `release-by-tag.yml` | push of a `v*` tag without `-auto` | `azure-release.yml`, mode `tag` |
| `ci-provider-reset.yml` (new) | 01:10 UTC on the 1st, manual | GitHub-only by design: it is the monthly return to GitHub |

The audit found no other workflow that builds, signs, publishes or recovers an Android release. The engine and media
submodules have no workflows here. `azure-release.yml` is one pipeline that picks the release type from the triggering ref
(`android_ci.py resolve-mode`); it never runs for pull requests.

## Contract, shared scripts and the parity check

Nothing is maintained twice. Both providers are thin adapters:

| Piece | Path | Owns |
|---|---|---|
| Contract | `.ci/pipeline-contract.json` | provider state, triggers, branches/tags/paths, JDK, SDK packages, Gradle task sets and memory settings, signing secrets, version and build-number rules, asset naming, Versions API rules, concurrency, secret catalogue and per-pipeline consumers, intentional differences |
| Shared logic | `scripts/ci/android_ci.py` | guard, change detection, stale-replay refusal, version/build number, signing files, Gradle invocations, staging, tag, GitHub Release, Versions API, CI Gate status |
| Loaders | `scripts/ci/load_vault_secrets.py`, `load_repo_variables.py`, `checkout-submodules.sh` | Azure: Key Vault secrets per consumer, allow-listed GitHub variables, private submodules |
| Switch | `scripts/ci/pipeline-mode.ps1` -> `ci-mode.ps1` | lease/lock, ordering, rollback, release-in-flight refusal, destination replay |
| Parity | `scripts/ci/check_pipeline_parity.py` | fails when an adapter drifts from the contract |

The parity check verifies triggers, concurrency and cancel-in-progress, provider gating, the **ordered list of shared-script
invocations** per pipeline and provider (extra or missing steps fail), the absence of inline build/release logic, release
modes, Gradle task sets, JDK, artifact names and retention, which variables and secrets each adapter may reference, that PR
pipelines never receive release/signing/switch credentials, and the Azure bootstrap constants. Change the contract first,
then the adapters, then run `python scripts/ci/check_pipeline_parity.py`. It runs in the tests (`test_pipeline_parity.py`),
which also prove that deliberately introduced drift fails.

## Configuration and secrets

**Non-secret configuration.** GitHub repository variables stay canonical. Azure reads only the allow-listed ones it needs at
runtime (`load_repo_variables.py`); there is no Azure variable group. Derived from the four workflows:

| Variable | Used by |
|---|---|
| `PRIMARY_SERVERS_URL`, `FALLBACK_SERVERS_URL` | PR CI and all releases (Gradle build config) |
| `VERSIONS_API_BASE_URL` | releases (Versions API) |
| `CI_PROVIDER`, `CI_PROVIDER_SWITCHING` | provider state and switch lock (the lock is live-read through the API, never `vars.*`) |
| `AZDO_ORG`, `AZDO_PROJECT`, `AZDO_PR_CI_PIPELINE_ID`, `AZDO_RELEASE_PIPELINE_ID` | the switch and the reset |
| `AZURE_GITHUB_OIDC_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_KEY_VAULT_NAME` | the reset workflow's Azure login and vault |

**Secrets.** GitHub keeps using its Actions secrets (`GH_PAT`, `SIGNING_KEY_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`,
`STORE_PASSWORD`, `VERSIONS_API_KEY`, `GITHUB_TOKEN`). Azure reads the same values from Key Vault through **separate
identities per pipeline**; nothing is placed in Azure pipeline variables or variable groups and nothing is printed (values
are masked before export, a multi-line or empty secret aborts the step).

| Key Vault secret | Env | Loaded by | Purpose and minimum permission |
|---|---|---|---|
| `github-android-pr-ci-pat` | `GH_PAT` | Azure PR CI only | Fine-grained token on this repo and `OpenVPNGateClientMedia`: Metadata read, Contents **read**, Actions Variables **read**, Commit statuses read+write. No Contents write, no Actions write, no Variables write: pull-request-controlled code runs with it |
| `github-android-release-pat` | `GH_PAT` | Azure release only | Contents read+write (tags, releases, private submodules), Actions Variables read |
| `android-signing-keystore-base64` | `SIGNING_KEY_BASE64` | Azure release only | Single-line base64 keystore, identical to the GitHub secret |
| `android-signing-key-alias`, `android-signing-key-password`, `android-signing-store-password` | `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` | Azure release only | Signing credentials |
| `android-versions-api-key` | `VERSIONS_API_KEY` | Azure release only | Versions API key |
| `github-android-provider-switch-pat` | `CI_SWITCH_PAT` | reset workflow / operator | Actions read+write and Variables read+write (mutates `CI_PROVIDER`, dispatches the replay). Never loaded by a pull-request pipeline |
| `azure-devops-android-pipeline-control-pat` | `AZDO_PAT` | reset workflow / operator | Azure DevOps PAT, Build read and execute (enable/disable both pipelines, queue the replay) |

Trust boundaries: `azure-pipelines.yml` (PR code) has no signing, release or switch credential and its Gradle steps do not
even receive the PR token. `azure-release.yml` never runs for pull requests. The GitHub `CI Gate` status job uses no
checkout, so no PR code runs with a status-write token.

The `GH_PAT` GitHub secret must additionally be able to **read Actions variables** (fine-grained: Variables read; classic:
`repo`). The GitHub-side guard reads `CI_PROVIDER` live; if that read is refused it falls back to the workflow-start value
of `vars.CI_PROVIDER` and warns, so a token without the scope cannot block the primary provider. The Azure guard fails closed.

Assumption to confirm: the vault name `openvpngateclient-prd-kv` (the server's vault) and the Azure object names below are
defaults. They live only in `keyVault.bootstrap` of the contract and the two Azure YAML `variables:` blocks (parity-checked);
change all three places together to rename them.

## One-time setup (manual, needs credentials)

None of this is automated by the repository. Do it once, in this order.

1. **Azure DevOps.** In the existing organization create a project (or reuse the server's) and connect it to the GitHub
   repository with the Azure Pipelines GitHub App (no Azure Repos copy). Set `AZDO_ORG` and `AZDO_PROJECT` as GitHub
   repository variables.
2. **Pipelines.** Create two pipelines from existing YAML: `azure-pipelines.yml` (PR CI) and `azure-release.yml` (release).
   Store their numeric ids in the repository variables `AZDO_PR_CI_PIPELINE_ID` and `AZDO_RELEASE_PIPELINE_ID`. Leave both
   **disabled** (Settings > Disable) so GitHub mode consumes no agent; the switch enables them. Submodules stay off in
   the pipeline UI; the scripts fetch them with the token.
3. **Environment lock.** Create the Azure DevOps environment `android-release-build-number` and add an **Exclusive Lock**
   check (Approvals and checks). This, with `lockBehavior: sequential` in `azure-release.yml`, is what serializes releases.
4. **Identities (least privilege, separate from the server's).** Create two Azure managed identities with workload identity
   federation service connections: `openvpn-android-azure-pr-ci` (authorized for `azure-pipelines.yml` only) and
   `openvpn-android-azure-release` (authorized for `azure-release.yml` only). Neither needs an Azure resource role. Grant
   each **Key Vault Secrets User scoped to the individual secrets** it loads (table above), not to the vault. Do not reuse or
   widen the server's connections.
5. **Key Vault secrets.** Create the eight secrets above in the vault. Fine-grained GitHub tokens: see the table; both
   Azure tokens must cover `OpenVPNGateClientApp` and the private `OpenVPNGateClientMedia` submodule (and the engine
   submodule repository if it is private). Copy the keystore/passwords/API key values from the existing GitHub secrets
   (single-line base64 for the keystore).
6. **Monthly reset identity.** Create the GitHub OIDC federated credential (trusts `refs/heads/main` of this repository only)
   for an identity that can read `github-android-provider-switch-pat` and `azure-devops-android-pipeline-control-pat`; set
   `AZURE_GITHUB_OIDC_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_KEY_VAULT_NAME`.
7. **Ruleset (when the plan allows).** Require the `CI Gate` commit status; both providers post it. A skipped GitHub job
   counts as passing, so require the status, never the job.
8. **Verify before relying on it:** run the validation matrix below on a throwaway branch.

## Operating the switch

`CI_PROVIDER` controls PR CI and releases together. Order matters and is fixed by the script: to `azure` it enables both Azure
pipelines and then sets the variable; to `github` it sets the variable and then disables both pipelines. Prior state is
captured first and every applied step is rolled back if one fails. After a real change it queues the destination replay
(next section) and re-pushing open PRs is what makes the new provider build them.

- `status` prints `CI_PROVIDER`, the lock and both pipeline states; they must agree.
- `-DryRun` prints the plan and makes no call.
- The script refuses while the provider being left has a **queued or running release** (PR CI is deliberately not checked),
  names the run, and changes nothing. A state that cannot be read is refused too. `-Force` bypasses only this check, after
  you verified by hand that no release is running (typical when GitHub cannot report its own state).
- Needs: `gh` authenticated with Actions and Variables read/write (an operator account or a locally exported
  `github-android-provider-switch-pat`, never the PR token), `AZDO_PAT`, and the four `AZDO_*` names as repo variables or
  environment variables.

**Monthly reset.** `ci-provider-reset.yml` runs at 01:10 UTC on the 1st, logs in to Azure with OIDC (works from `main` only),
loads the two switch secrets from Key Vault and runs `pipeline-mode.ps1 github`. It can be run by hand (`dry_run` input).

## Release safety: serialization, hand-off, stale replay

- **Serialization.** GitHub: concurrency group `release-build-number`, `cancel-in-progress: false`, shared by dev, main and
  tag. Azure: `lockBehavior: sequential` plus the environment Exclusive Lock; queued runs wait, none is cancelled. An
  in-flight release is never cancelled by a newer push on either provider.
- **Build number and tags.** Build number = highest `-auto(N)` tag + 1, computed after the lock is held; the tag is created
  with a create-only API call. Both providers run the same code, and the switch keeps them from ever running at once, so the
  same number cannot be allocated twice. (An existing tag is tolerated as before; it is not an escape hatch for two live
  providers.)
- **Switch lock.** `CI_PROVIDER_SWITCHING` is created atomically (create-only variable POST) with a per-invocation token
  and deleted in `finally`. Every release and Azure PR run **waits** for it to clear (up to 5 minutes, then fails loudly)
  and then reads `CI_PROVIDER` live, so a push during a hand-off is delayed, not dropped and not run twice.
- **Destination replay.** Before the lock is released, a real provider change queues one release run for `dev` and one for
  `main` on the destination provider, pinned to each branch tip read at that moment (GitHub `workflow_dispatch` with
  `replay=true`, Azure queue with `replay=true`). A push that neither provider evaluated cannot be lost.
- **Stale replay.** When a replay starts it re-reads the live branch tip; if the pinned commit is no longer the tip it skips
  cleanly (`skip_reason=stale-replay`) instead of building a superseded commit. Replay change detection diffs against the
  latest release tag reachable from the commit, so an unchanged branch releases nothing, and an empty range skips.
- **Tag pushes** cannot be replayed automatically. If a `v*` tag was pushed during a hand-off, re-push it or queue
  `azure-release.yml` manually for `refs/tags/<tag>`.

## Behaviour that is intentionally different

Authoritative list: `intentionalDifferences` in the contract. Summary: the PR comment with artifact links is GitHub-only
(the Azure PR token is Commit-statuses-only, so the link travels in the `CI Gate` status URL); JDK/Gradle/SDK installers
are provider-native; Azure has no Gradle cache; artifact retention days are a GitHub setting; Azure derives the push
"before" commit from its previous completed build; the dev path filter is applied by change detection on Azure; Azure has
one release pipeline instead of three. Also intentional consequences of moving release logic into `android_ci.py`: main and
tag releases now use the dev release Gradle profile (`--no-daemon`, 6g heap), and a missing staged artifact stops the
release before anything is published (previously a partial release could be published before the Versions API step failed).

## Validation matrix

| Scenario | How it is covered |
|---|---|
| A. GitHub mode, Azure inactive | Job-level `if` on every root GitHub job and disabled Azure pipelines (parity check); manual: push a PR and a dev commit in github mode, confirm no Azure build |
| B. Azure mode, no GitHub runner, no duplicate | Manual after setup: `pipeline-mode.ps1 azure`, push PR/dev/main/tag; GitHub jobs show *skipped* |
| C. Build parity | Same shared scripts and contract task sets (parity check). Manual: compare APK names, `versionName` and unit test result for one SHA on both providers |
| D. Release parity (beta/stable/tag) | `test_android_ci.py` covers version/build number/tag, release names, prerelease flag, asset names and Versions API payload against the previous shell semantics; manual: one release per type per provider |
| E. Concurrency | Contract-enforced serialization on both providers; switch refusal while a release is in flight (`ci-mode.Tests.ps1`); manual: queue two pushes in Azure and confirm sequential runs |
| F. `github -> azure -> github` | `ci-mode.Tests.ps1` (order, lock, replay, rollback, no-op); manual: run both directions and read `status` |
| G. Stale replay | `test_android_ci.py` (`StaleReplayTests`); manual: queue a replay, push to the branch before it starts |
| H. Parity guard | `test_pipeline_parity.py` introduces drift in triggers, concurrency, steps, secrets, gating and constants and expects failure |

## Recovery

1. `pipeline-mode.ps1 status`, then re-run the intended mode (idempotent, re-applies both sides).
2. PR stuck with `CI Gate` expected in Azure mode: pipeline disabled, the PR identity cannot read
   `github-android-pr-ci-pat`, or the token lacks Commit statuses write. Re-run `azure` and re-push.
3. Duplicate runs: an Azure pipeline is still enabled in GitHub mode. Run `github`.
4. A release run waits or fails with "CI_PROVIDER_SWITCHING has stayed set": a switch was killed before its `finally`.
   After confirming no switch is running: `gh variable delete CI_PROVIDER_SWITCHING`.
5. `pipeline-mode.ps1` reports "another switch is already in progress": wait for it and retry.
6. A failing monthly reset usually means an expired `github-android-provider-switch-pat` or `AZDO_PAT`; renew it and run the
   workflow by hand.

## Local tests

```bash
python -m pip install pyyaml
python -m unittest discover -s scripts/ci/tests        # android_ci.py, loaders, parity check (incl. drift scenarios)
python scripts/ci/check_pipeline_parity.py             # the real adapters against the real contract
```

```powershell
Invoke-Pester scripts/ci/tests    # ci-mode.Tests.ps1 (Pester 5+); do not commit a testResults.xml
```
