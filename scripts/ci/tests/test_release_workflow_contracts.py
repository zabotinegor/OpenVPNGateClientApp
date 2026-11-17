"""Structural tests for GitHub Actions release signing and cache contracts.

Only GitHub Actions is in scope. No GitHub API, secrets or Android SDK are
required: the tests read the workflow and Gradle files as text.
"""
from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[3]

RELEASE_WORKFLOWS = (
    ".github/workflows/release-by-dev.yml",
    ".github/workflows/release-by-main.yml",
    ".github/workflows/release-by-tag.yml",
)
PULL_REQUEST_WORKFLOWS = (
    ".github/workflows/ci-pr.yml",
    ".github/workflows/build-by-pull-request.yml",
)


def read(path: str) -> str:
    return (REPO / path).read_text(encoding="utf-8")


def step(text: str, name: str) -> str:
    """Return the body of the step named `name` (up to the next step)."""
    marker = f"- name: {name}"
    assert marker in text, f"step '{name}' not found"
    return text.split(marker, 1)[1].split("\n      - name:", 1)[0]


class ReleaseSigningContractTests(unittest.TestCase):
    def test_release_workflows_decode_keystore_from_signing_secret(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                self.assertEqual(text.count("- name: Decode Keystore"), 1)
                decode = step(text, "Decode Keystore")
                self.assertIn("secrets.SIGNING_KEY_BASE64", decode)
                self.assertIn("base64 --decode > keystore.jks", decode)
                for secret in ("KEY_ALIAS", "KEY_PASSWORD", "STORE_PASSWORD"):
                    self.assertIn(f"secrets.{secret}", decode)
                self.assertIn("storeFile=keystore.jks", decode)
                self.assertIn("working-directory: ./src", decode)

    def test_release_build_runs_after_keystore_decode_in_same_directory(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                self.assertLess(text.index("- name: Decode Keystore"),
                                text.index("- name: Stage Artifacts"))
                stage = step(text, "Stage Artifacts")
                self.assertIn("./gradlew", stage)
                self.assertIn("stageReleaseArtifacts", stage)
                self.assertIn("working-directory: ./src", stage)

    def test_gradle_signs_release_with_keystore_properties(self):
        # The decoded keystore.properties lives in src/ (the Gradle root).
        for path in ("src/mobile/build.gradle.kts", "src/tv/build.gradle.kts"):
            with self.subTest(module=path):
                text = read(path)
                self.assertIn('rootProject.file("keystore.properties")', text)
                self.assertIn('signingConfig = signingConfigs.getByName("release")', text)

    def test_pull_request_workflows_do_not_decode_keystore(self):
        for path in PULL_REQUEST_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                self.assertNotIn("Decode Keystore", text)
                self.assertNotIn("SIGNING_KEY_BASE64", text)
                self.assertNotIn("keystore.jks", text)
                self.assertNotIn("keystore.properties", text)
                for secret in ("KEY_ALIAS", "KEY_PASSWORD", "STORE_PASSWORD"):
                    self.assertNotIn(f"secrets.{secret}", text)

    def test_pull_request_workflows_are_pull_request_triggered(self):
        self.assertIn("on:\n  pull_request:", read(PULL_REQUEST_WORKFLOWS[0]))
        self.assertIn("workflow_call:", read(PULL_REQUEST_WORKFLOWS[1]))


class CanonicalEndpointContractTests(unittest.TestCase):
    def test_release_workflows_validate_endpoints_before_building(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                self.assertEqual(text.count("- name: Validate production endpoint configuration"), 1)
                validate = step(text, "Validate production endpoint configuration")
                self.assertIn("validate-production-endpoints.sh --mode strict --require-versions-api", validate)
                for name in ("PRIMARY_SERVERS_URL", "FALLBACK_SERVERS_URL", "VERSIONS_API_BASE_URL"):
                    self.assertIn(f"{name}: ${{{{ vars.{name} }}}}", validate)
                self.assertNotIn("secrets.", validate)
                self.assertLess(text.index("- name: Validate production endpoint configuration"),
                                text.index("- name: Stage Artifacts"))

    def test_release_workflows_verify_artifacts_before_tagging_and_publishing(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                name = "- name: Verify release artifacts embed the canonical API origin"
                self.assertEqual(text.count(name), 1)
                verify = step(text, "Verify release artifacts embed the canonical API origin")
                self.assertIn("verify-artifact-endpoints.sh", verify)
                self.assertIn("EXPECTED_ORIGIN: ${{ vars.PRIMARY_SERVERS_URL }}", verify)
                run_line = next(l for l in verify.splitlines() if l.strip().startswith("run:"))
                self.assertIn('verify-artifact-endpoints.sh "$EXPECTED_ORIGIN"', run_line)
                self.assertNotIn("${{", run_line)
                self.assertIn("output/*.apk output/*.aab", verify)
                self.assertGreater(text.index(name), text.index("- name: Stage Artifacts"))
                for later in ("- name: Push unified version release", "- name: Publish"):
                    if later in text:
                        self.assertLess(text.index(name), text.index(later))

    def test_release_workflows_use_the_same_endpoint_variables(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                text = read(path)
                self.assertIn("ORG_GRADLE_PROJECT_PRIMARY_SERVERS_URL: ${{ vars.PRIMARY_SERVERS_URL }}", text)
                self.assertIn("ORG_GRADLE_PROJECT_FALLBACK_SERVERS_URL: ${{ vars.FALLBACK_SERVERS_URL }}", text)
                self.assertIn("VERSIONS_API_BASE_URL: ${{ vars.VERSIONS_API_BASE_URL }}", text)
                self.assertIn("VERSIONS_API_KEY: ${{ secrets.VERSIONS_API_KEY }}", text)

    def test_pull_request_build_only_warns_about_endpoint_configuration(self):
        build = read(PULL_REQUEST_WORKFLOWS[1])
        check = step(build, "Check endpoint scripts and configuration")
        self.assertIn("validate-production-endpoints.sh --mode warn", check)
        self.assertNotIn("--mode strict", check)

    def test_endpoint_scripts_hardcode_only_the_retired_and_canonical_hosts(self):
        for path in (".github/ci/validate-production-endpoints.sh",
                     ".github/ci/verify-artifact-endpoints.sh"):
            with self.subTest(script=path):
                self.assertIn("openvpngateclient.azurewebsites.net", read(path))

    def test_gradle_rejects_retired_host_for_release_builds(self):
        text = read("src/core/build.gradle.kts")
        self.assertIn("openvpngateclient.azurewebsites.net", text)
        self.assertIn("isRetiredPublicHost", text)


class GradleCacheContractTests(unittest.TestCase):
    def test_release_workflows_set_cache_read_only_false_explicitly(self):
        for path in RELEASE_WORKFLOWS:
            with self.subTest(workflow=path):
                setup = step(read(path), "Setup Gradle")
                self.assertIn("gradle/actions/setup-gradle@v3", setup)
                self.assertRegex(setup, r"(?m)^\s+cache-read-only: false\s*$")

    def test_pull_request_build_sets_cache_read_only_true_explicitly(self):
        setup = step(read(PULL_REQUEST_WORKFLOWS[1]), "Setup Gradle")
        self.assertRegex(setup, r"(?m)^\s+cache-read-only: true\s*$")


if __name__ == "__main__":
    unittest.main()
