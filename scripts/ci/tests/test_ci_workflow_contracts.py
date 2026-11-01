"""Structural tests for Android CI Gate and trusted integration fallback.

No GitHub API or Android SDK is required.
"""
from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"


def read(path: str) -> str:
    return (REPO / path).read_text(encoding="utf-8")


class AndroidCiGateWorkflowTests(unittest.TestCase):
    def test_only_ci_pr_receives_pr_events(self):
        entry = read(".github/workflows/ci-pr.yml")
        build = read(".github/workflows/build-by-pull-request.yml")
        self.assertIn("on:\n  pull_request:", entry)
        self.assertIn("branches: [dev, main]", entry)
        self.assertIn("on:\n  # Called by ci-pr.yml", build)
        self.assertIn("  workflow_call:", build)
        self.assertNotRegex(build.split("permissions:", 1)[0], r"(?m)^  pull_request:")
        self.assertIn("uses: ./.github/workflows/build-by-pull-request.yml", entry)
        self.assertIn("secrets: inherit", entry)

    def test_gate_is_published_from_same_pr_run_only_on_success(self):
        entry = read(".github/workflows/ci-pr.yml")
        self.assertIn("needs: build", entry)
        self.assertIn("if: ${{ always() && github.event_name == 'pull_request' }}", entry)
        self.assertIn("statuses: write", entry)
        self.assertIn("context: 'CI Gate'", entry)
        self.assertIn("context.payload.pull_request.head.sha", entry)
        self.assertIn("result === 'success'", entry)
        self.assertIn("state: ok ? 'success' : 'failure'", entry)
        self.assertIn("actions/runs/", entry)

    def test_integration_is_dispatch_only_and_separates_trust(self):
        entry = read(".github/workflows/ci-integration.yml")
        start = entry.split("permissions: {}", 1)[0]
        self.assertRegex(start, r"(?m)^  workflow_dispatch:")
        self.assertNotRegex(start, r"(?m)^  pull_request:")
        self.assertIn("needs: integration-build", entry)
        self.assertIn("if: ${{ success() }}", entry)
        self.assertIn("python3 -I scripts/ci/integration_ci.py prepare", entry)
        self.assertIn("python3 -I scripts/ci/integration_ci.py publish", entry)
        self.assertIn("statuses: write", entry)
        build = entry.split("  integration-build:", 1)[1].split("  publish-evidence:", 1)[0]
        self.assertNotIn("statuses: write", build)
        self.assertIn("persist-credentials: false", build)
        self.assertEqual(build.count("secrets.GH_PAT"), 1)
        self.assertIn("submodules: recursive", build)
        self.assertIn("cache-disabled: true", build)
        self.assertIn("testDebugUnitTestApp", build)
        self.assertIn("assembleDebugApp", build)
        self.assertIn("assembleReleaseApp", build)
        self.assertIn("bundleReleaseApp", build)
        self.assertIn("TREE: ${{ needs.integration-build.outputs.tree }}", entry)

    def test_integration_validates_trusted_wrapper_before_engine_checkout(self):
        entry = read(".github/workflows/ci-integration.yml")
        build = entry.split("  integration-build:", 1)[1].split("  publish-evidence:", 1)[0]
        initial_checkout = build.index("- name: Checkout trusted dev without submodules")
        wrapper_validation = build.index("- name: Set up Gradle without caching PR code")
        trusted_submodules = build.index("- name: Initialize trusted dev private submodules")
        merge_preparation = build.index("- name: Validate dispatch and prepare exact merge tree")
        merged_wrapper_guard = build.index("- name: Check exact merge retains validated application wrapper")
        submodule_verification = build.index("- name: Verify merged submodule pointers")
        builds = build.index("- name: Android unit tests, mobile and TV debug/release builds")
        self.assertTrue(
            initial_checkout < wrapper_validation < trusted_submodules <
            merge_preparation < merged_wrapper_guard < submodule_verification < builds
        )
        self.assertIn("submodules: false", build[initial_checkout:wrapper_validation])
        self.assertIn("validate-wrappers: true", build[wrapper_validation:trusted_submodules])
        self.assertIn("submodules: recursive", build[trusted_submodules:merge_preparation])
        self.assertEqual(build.count("secrets.GH_PAT"), 1)
        self.assertEqual(build.count("persist-credentials: false"), 2)
        self.assertIn('git diff --quiet "$BASE_SHA" -- src/gradle/wrapper/gradle-wrapper.jar',
                      build[merged_wrapper_guard:submodule_verification])
        self.assertIn("cache-disabled: true", build[wrapper_validation:trusted_submodules])

    def test_verifier_pins_dev_live_base_and_merge_tree(self):
        code = read("scripts/ci/integration_ci.py")
        self.assertIn('TARGET_BASE_BRANCH = "dev"', code)
        self.assertIn('TARGET_BASE_REF = "refs/heads/dev"', code)
        self.assertIn('CONTEXT = "Integration CI Gate"', code)
        self.assertIn('description = f"pr={pr} base={base} tree={tree}"', code)
        self.assertIn('parents != [base, head]', code)
        self.assertIn('merge_tree.get("sha") != tree', code)
        self.assertIn('state": "success"', code)
        self.assertEqual(len(re.findall(r'CONTEXT = "Integration CI Gate"', code)), 1)


if __name__ == "__main__":
    unittest.main()
