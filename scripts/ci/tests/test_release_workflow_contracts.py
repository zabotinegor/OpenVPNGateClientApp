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
