"""Tests for scripts/ci/check_pipeline_parity.py and the Key Vault / repository-variable loaders.

Scenario H: an intentionally introduced contract/provider mismatch must make the parity check fail.
"""
from __future__ import annotations

import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_pipeline_parity as parity  # noqa: E402
import load_repo_variables as repo_vars  # noqa: E402
import load_vault_secrets as vault  # noqa: E402

REAL_ROOT = Path(__file__).resolve().parents[3]
COPIED = [".ci", ".github/workflows", "azure-pipelines.yml", "azure-release.yml", "scripts/ci"]


class ParityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for item in COPIED:
            src = REAL_ROOT / item
            dst = self.root / item
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, dst, ignore=shutil.ignore_patterns("__pycache__", "tests"))
            else:
                shutil.copy2(src, dst)

    def tearDown(self):
        self.tmp.cleanup()

    def edit(self, relative: str, old: str, new: str, count: int = 1):
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text, f"test setup: '{old}' not found in {relative}")
        path.write_text(text.replace(old, new, count), encoding="utf-8")

    def errors(self):
        return parity.check(self.root)

    def assertViolation(self, fragment: str):
        errors = self.errors()
        self.assertTrue(any(fragment in e for e in errors), f"expected a violation containing '{fragment}', got: {errors}")

    def test_repository_adapters_match_the_contract(self):
        self.assertEqual(parity.check(REAL_ROOT), [])
        self.assertEqual(self.errors(), [])

    def test_cli_exit_codes(self):
        self.assertEqual(parity.main(["--root", str(self.root)]), 0)
        self.edit("azure-release.yml", "lockBehavior: sequential", "lockBehavior: runAll")
        self.assertEqual(parity.main(["--root", str(self.root)]), 1)

    def test_azure_dropping_serialization_fails(self):
        self.edit("azure-release.yml", "lockBehavior: sequential", "lockBehavior: runLatest")
        self.assertViolation("lockBehavior")

    def test_azure_missing_environment_lock_fails(self):
        self.edit("azure-release.yml", "environment: android-release-build-number", "environment: something-else")
        self.assertViolation("exclusive lock")

    def test_github_release_cancel_in_progress_drift_fails(self):
        self.edit(".github/workflows/release-by-main.yml", "cancel-in-progress: false", "cancel-in-progress: true")
        self.assertViolation("release-by-main.yml: concurrency")

    def test_github_trigger_branch_drift_fails(self):
        self.edit(".github/workflows/release-by-dev.yml", "- 'dev'", "- 'develop'")
        self.assertViolation("push branches")

    def test_github_paths_drift_fails(self):
        self.edit(".github/workflows/release-by-dev.yml", "      - 'media'\n", "")
        self.assertViolation("push paths")

    def test_azure_tag_exclusion_drift_fails(self):
        self.edit("azure-release.yml", "- '*-auto*'", "- '*-nightly*'")
        self.assertViolation("trigger.tags.exclude")

    def test_removing_a_shared_step_from_one_provider_fails(self):
        self.edit(".github/workflows/release-by-tag.yml", "run: python3 scripts/ci/android_ci.py versions-api", "run: echo skipped")
        self.assertViolation("versions-api")

    def test_reordering_shared_steps_fails(self):
        self.edit("azure-release.yml", "android_ci.py stage-artifacts", "android_ci.py create-tag")
        self.assertTrue(self.errors())

    def test_inline_build_logic_in_an_adapter_fails(self):
        self.edit(".github/workflows/build-by-pull-request.yml", "run: python3 scripts/ci/android_ci.py gradle unit-tests",
                  "run: ./gradlew testDebugUnitTestApp")
        self.assertViolation("inline Gradle")

    def test_unknown_gradle_task_set_fails(self):
        self.edit("azure-pipelines.yml", "android_ci.py gradle release-sanity", "android_ci.py gradle release-everything")
        self.assertTrue(self.errors())

    def test_wrong_release_mode_fails(self):
        self.edit(".github/workflows/release-by-main.yml", "version --mode stable", "version --mode beta")
        self.assertViolation("--mode stable")

    def test_pr_pipeline_receiving_a_release_secret_fails(self):
        self.edit("azure-pipelines.yml", "GH_PAT: $(GH_PAT)\n      PR_HEAD_SHA", "GH_PAT: $(GH_PAT)\n      VERSIONS_API_KEY: $(VERSIONS_API_KEY)\n      PR_HEAD_SHA")
        self.assertViolation("must not receive")

    def test_pr_pipeline_loading_the_release_secret_consumer_fails(self):
        self.edit("azure-pipelines.yml", "--consumer azure-pr-ci", "--consumer azure-release")
        self.assertViolation("secret consumer")

    def test_gradle_step_receiving_the_github_token_fails(self):
        self.edit("azure-pipelines.yml",
                  "    displayName: Run Unit Tests (app)\n    condition: eq(variables['guard.active'], 'true')\n    env:\n",
                  "    displayName: Run Unit Tests (app)\n    condition: eq(variables['guard.active'], 'true')\n    env:\n      GH_PAT: $(GH_PAT)\n")
        self.assertViolation("Gradle step must not receive GH_PAT")

    def test_release_pipeline_must_not_run_for_pull_requests(self):
        self.edit("azure-release.yml", "\npr: none\n", "\npr:\n  branches:\n    include:\n      - '*'\n")
        self.assertViolation("pr: none")

    def test_github_job_without_azure_gate_fails(self):
        self.edit(".github/workflows/release-by-dev.yml", "    if: vars.CI_PROVIDER != 'azure'\n", "")
        self.assertViolation("must be skipped while CI_PROVIDER=azure")

    def test_unlisted_repository_variable_fails(self):
        self.edit(".github/workflows/release-by-main.yml", "vars.VERSIONS_API_BASE_URL", "vars.SOMETHING_NEW")
        self.assertViolation("SOMETHING_NEW")

    def test_unlisted_secret_fails(self):
        self.edit(".github/workflows/release-by-main.yml", "secrets.VERSIONS_API_KEY", "secrets.OTHER_KEY")
        self.assertViolation("OTHER_KEY")

    def test_bootstrap_constant_drift_fails(self):
        self.edit("azure-release.yml", "AZURE_RELEASE_SERVICE_CONNECTION: openvpn-android-azure-release", "AZURE_RELEASE_SERVICE_CONNECTION: some-other-connection")
        self.assertViolation("releaseServiceConnection")

    def test_jdk_version_drift_fails(self):
        self.edit(".github/workflows/release-by-main.yml", "java-version: '17'", "java-version: '21'")
        self.assertViolation("setup-java version")

    def test_contract_change_without_adapter_change_fails(self):
        path = self.root / ".ci" / "pipeline-contract.json"
        contract = json.loads(path.read_text(encoding="utf-8"))
        contract["pipelines"]["release-main"]["triggers"]["branches"] = ["release"]
        path.write_text(json.dumps(contract), encoding="utf-8")
        self.assertTrue(self.errors())

    def test_pr_secret_catalogue_must_not_expand_to_release_credentials(self):
        path = self.root / ".ci" / "pipeline-contract.json"
        contract = json.loads(path.read_text(encoding="utf-8"))
        contract["secretConsumers"]["azure-pr-ci"]["secrets"].append("android-signing-key-password")
        path.write_text(json.dumps(contract), encoding="utf-8")
        self.assertViolation("azure-pr-ci")

    def test_reset_must_use_the_monthly_schedule(self):
        self.edit(".github/workflows/ci-provider-reset.yml", '"10 1 1 * *"', '"10 1 2 * *"')
        self.assertViolation("schedule")

    def test_pr_artifact_name_drift_fails(self):
        self.edit(".github/workflows/build-by-pull-request.yml", "name: apk-tv-debug_", "name: tv-debug-apk_")
        self.assertViolation("apk-tv-debug")


class LoaderTests(unittest.TestCase):
    contract = json.loads((REAL_ROOT / ".ci" / "pipeline-contract.json").read_text(encoding="utf-8"))

    def test_vault_loader_exports_only_the_consumer_secrets_and_masks_azure_values(self):
        seen = []

        def runner(command):
            name = command[command.index("--name") + 1]
            seen.append(name)
            return 0, json.dumps({"value": f"value-of-{name}", "enabled": True})

        out = io.StringIO()
        code = vault.main(["--provider", "azure", "--consumer", "azure-pr-ci", "--vault", "kv"], runner=runner, out=out)
        self.assertEqual(code, 0)
        self.assertEqual(seen, ["github-android-pr-ci-pat"])
        self.assertIn("##vso[task.setvariable variable=GH_PAT;isSecret=true]value-of-github-android-pr-ci-pat", out.getvalue())

    def test_pr_identity_cannot_read_release_secrets(self):
        names = vault.select_secrets(self.contract, "azure-pr-ci")
        self.assertEqual(names, ["github-android-pr-ci-pat"])
        envs = {self.contract["keyVault"]["secrets"][n]["env"] for n in names}
        self.assertTrue(envs.isdisjoint({"SIGNING_KEY_BASE64", "KEY_PASSWORD", "STORE_PASSWORD", "KEY_ALIAS", "VERSIONS_API_KEY", "CI_SWITCH_PAT", "AZDO_PAT"}))

    def test_vault_loader_refuses_multiline_and_missing_secrets_without_emitting(self):
        out = io.StringIO()
        code = vault.main(["--provider", "azure", "--consumer", "azure-release", "--vault", "kv"],
                          runner=lambda c: (0, json.dumps({"value": "a\nb", "enabled": True})), out=out)
        self.assertEqual(code, 1)
        self.assertNotIn("setvariable", out.getvalue())
        code = vault.main(["--provider", "azure", "--consumer", "azure-release", "--vault", "kv"], runner=lambda c: (1, ""), out=io.StringIO())
        self.assertEqual(code, 1)

    def test_vault_loader_refuses_disabled_secret(self):
        with self.assertRaises(vault.VaultError):
            vault.read_secret("kv", "x", lambda c: (0, json.dumps({"value": "v", "enabled": False})))

    def test_repo_variable_selection_is_allow_listed_and_required(self):
        available = {"PRIMARY_SERVERS_URL": "https://p", "FALLBACK_SERVERS_URL": "https://f", "UNRELATED": "x", "VERSIONS_API_BASE_URL": "https://v"}
        self.assertEqual(repo_vars.select(self.contract, "pr", available), {"PRIMARY_SERVERS_URL": "https://p", "FALLBACK_SERVERS_URL": "https://f"})
        self.assertNotIn("VERSIONS_API_BASE_URL", repo_vars.select(self.contract, "pr", available))
        with self.assertRaises(repo_vars.LoaderError) as raised:
            repo_vars.select(self.contract, "release-common", {"PRIMARY_SERVERS_URL": "https://p"})
        self.assertIn("FALLBACK_SERVERS_URL", str(raised.exception))
        self.assertIn("VERSIONS_API_BASE_URL", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
