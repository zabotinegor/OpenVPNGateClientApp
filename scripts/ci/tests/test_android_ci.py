"""Unit tests for scripts/ci/android_ci.py. Run: python -m unittest discover -s scripts/ci/tests -t ."""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_ci as ci  # noqa: E402

CONTRACT = ci.load_contract()


class FakeApi:
    """Scripted GitHub API: routes maps (method, url-substring) -> (status, body) or a list of them (consumed in order)."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def request(self, method, url, body=None, headers=None, raw=None):
        self.calls.append((method, url, body))
        for (m, needle), response in self.routes.items():
            if m == method and needle in url:
                if isinstance(response, list):
                    return response.pop(0) if len(response) > 1 else response[0]
                return response
        return 404, None


def init_repo(path: Path) -> None:
    subprocess.run(["git", "init", "-q", str(path)], check=True)
    for key, value in (("user.email", "t@example.com"), ("user.name", "t"), ("commit.gpgsign", "false")):
        subprocess.run(["git", "-C", str(path), "config", key, value], check=True)


def commit(path: Path, files: dict, message="c") -> str:
    for name, text in files.items():
        target = path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    subprocess.run(["git", "-C", str(path), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(path), "commit", "-q", "-m", message], check=True)
    return subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()


class VersioningTests(unittest.TestCase):
    def test_version_key_sorts_numerically(self):
        tags = ["v1.1.9", "v1.1.10", "v1.1.2"]
        self.assertEqual(sorted(tags, key=ci.version_key, reverse=True)[0], "v1.1.10")

    def test_strip_auto_suffix(self):
        self.assertEqual(ci.strip_auto_suffix("1.1.5-auto(12)"), "1.1.5")
        self.assertEqual(ci.strip_auto_suffix("1.1.5(3)"), "1.1.5")
        self.assertEqual(ci.strip_auto_suffix("1.2.0"), "1.2.0")

    def test_next_build_number_is_highest_plus_one(self):
        self.assertEqual(ci.next_build_number([]), 1)
        self.assertEqual(ci.next_build_number(["v1.1.1-auto(4)", "v1.1.2-beta.1-auto(9)", "v1.0.0", "tagbuild-v1.1.3-auto(7)"]), 10)

    def test_beta_seed_when_no_stable_tag(self):
        meta = ci.compute_version("beta", [], CONTRACT)
        self.assertEqual((meta["app_version"], meta["tag"], meta["build_number"]), ("1.1.0-beta.1", "v1.1.0-beta.1-auto(1)", 1))

    def test_beta_increments_after_stable_and_existing_beta(self):
        tags = ["v1.1.4-auto(20)", "v1.1.5-beta.1-auto(21)", "v1.1.5-beta.2-auto(22)", "v1.1.3-auto(10)"]
        meta = ci.compute_version("beta", tags, CONTRACT, repo="o/r")
        self.assertEqual(meta["app_version"], "1.1.5-beta.3")
        self.assertEqual(meta["tag"], "v1.1.5-beta.3-auto(23)")
        self.assertEqual(meta["previous_tag"], "v1.1.5-beta.2-auto(22)")
        self.assertTrue(meta["compare_url"].endswith("v1.1.5-beta.2-auto(22)...v1.1.5-beta.3-auto(23)"))
        self.assertEqual((meta["prerelease"], meta["versions_release_type"], meta["generate_release_notes"]), (True, 1, True))

    def test_stable_ignores_beta_tags(self):
        tags = ["v1.1.4-auto(20)", "v1.1.5-beta.1-auto(21)"]
        meta = ci.compute_version("stable", tags, CONTRACT)
        self.assertEqual((meta["version"], meta["tag"], meta["build_number"]), ("1.1.5", "v1.1.5-auto(22)", 22))
        self.assertEqual((meta["prerelease"], meta["versions_release_type"]), (False, 0))
        self.assertEqual(meta["release_name"], "Release v1.1.5-auto(22)")

    def test_tag_mode_uses_pushed_tag_and_build_marker(self):
        meta = ci.compute_version("tag", ["v1.1.4-auto(20)"], CONTRACT, ref_name="v2.0.0")
        self.assertEqual((meta["app_version"], meta["tag"], meta["build_marker_tag"]), ("2.0.0", "v2.0.0", "tagbuild-v2.0.0-auto(21)"))
        self.assertEqual(meta["versions_name"], "Release v2.0.0-auto(21)")
        with self.assertRaises(ci.CiError):
            ci.compute_version("tag", [], CONTRACT)

    def test_release_body_differs_for_tag_mode(self):
        base = {"mode": "stable", "branch": "main", "tag": "v1-auto(1)", "app_version": "1", "last_commit": "msg"}
        self.assertIn("from branch `main`", ci.release_body(base))
        self.assertIn("for tag `v1`", ci.release_body({**base, "mode": "tag", "tag": "v1"}))

    def test_asset_names_match_existing_convention(self):
        meta = {"app_version": "1.1.5-beta.3", "build_number": 23}
        names = [a["name"] for a in ci.asset_list(CONTRACT, meta)]
        self.assertEqual(names, ["OpenVPNGateClient_mobile_1.1.5-beta.3_build23.apk", "OpenVPNGateClient_mobile_1.1.5-beta.3_build23.aab",
                                 "OpenVPNGateClient_tv_1.1.5-beta.3_build23.apk", "OpenVPNGateClient_tv_1.1.5-beta.3_build23.aab"])


class ChangeDetectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        init_repo(self.repo)
        self.pattern = CONTRACT["changeDetection"]["codePathRegex"]

    def tearDown(self):
        self.tmp.cleanup()

    def test_code_and_media_pointer_changes_proceed(self):
        first = commit(self.repo, {"README.md": "a"})
        second = commit(self.repo, {"src/app/A.kt": "x"})
        self.assertTrue(ci.decide_changes("push", first, second, self.pattern, self.repo)[0])
        self.assertTrue(ci.is_code_change(["media"], self.pattern))
        self.assertTrue(ci.is_code_change(["media/icon.png"], self.pattern))
        self.assertFalse(ci.is_code_change(["docs/a.md", "mediafoo/x"], self.pattern))

    def test_docs_only_range_skips(self):
        first = commit(self.repo, {"src/a": "1"})
        second = commit(self.repo, {"docs/x.md": "1"})
        proceed, reason, _ = ci.decide_changes("push", first, second, self.pattern, self.repo)
        self.assertFalse(proceed)
        self.assertIn("non-code", reason)

    def test_manual_always_proceeds(self):
        head = commit(self.repo, {"docs/x.md": "1"})
        self.assertTrue(ci.decide_changes("manual", head, head, self.pattern, self.repo)[0])

    def test_zero_before_lists_history_and_empty_diff_proceeds_for_push(self):
        head = commit(self.repo, {"docs/x.md": "1"})
        self.assertFalse(ci.decide_changes("push", ci.ZERO_SHA, head, self.pattern, self.repo)[0])
        self.assertTrue(ci.decide_changes("push", head, head, self.pattern, self.repo)[0])

    def test_replay_uses_latest_release_tag_baseline_and_skips_when_unchanged(self):
        base = commit(self.repo, {"src/a": "1"})
        subprocess.run(["git", "-C", str(self.repo), "tag", "v1.1.1-auto(5)", base], check=True)
        head = commit(self.repo, {"docs/x.md": "1"})
        self.assertFalse(ci.decide_changes("replay", None, head, self.pattern, self.repo)[0])
        code = commit(self.repo, {"src/b": "2"})
        self.assertTrue(ci.decide_changes("replay", None, code, self.pattern, self.repo)[0])
        # No new commit since the tag: an empty replay range releases nothing.
        subprocess.run(["git", "-C", str(self.repo), "tag", "v1.1.2-auto(6)", code], check=True)
        proceed, reason, _ = ci.decide_changes("replay", None, code, self.pattern, self.repo)
        self.assertFalse(proceed)
        self.assertIn("Replay", reason)

    def test_azure_previous_build_lookup_is_used_when_before_missing(self):
        first = commit(self.repo, {"src/a": "1"})
        second = commit(self.repo, {"docs/x.md": "1"})
        proceed, _, _ = ci.decide_changes("push", None, second, self.pattern, self.repo, previous_lookup=lambda: first)
        self.assertFalse(proceed)
        # An unknown previous commit is ignored rather than trusted: the whole history is inspected (fail-open).
        proceed, _, _ = ci.decide_changes("push", None, second, self.pattern, self.repo, previous_lookup=lambda: "f" * 40)
        self.assertTrue(proceed)

    def _tag(self, name, sha):
        subprocess.run(["git", "-C", str(self.repo), "tag", name, sha], check=True)

    def test_azure_replay_ignores_the_previous_azure_build_and_uses_the_latest_release_tag(self):
        # Azure released X, GitHub later released Y (tag). A replay at Y must find an empty range and skip.
        azure_built = commit(self.repo, {"src/a": "1"})
        released_elsewhere = commit(self.repo, {"src/b": "2"})
        self._tag("v1.1.2-auto(6)", released_elsewhere)
        head = released_elsewhere
        proceed, reason, _ = ci.decide_changes("replay", None, head, self.pattern, self.repo, previous_lookup=lambda: azure_built)
        self.assertFalse(proceed)
        self.assertIn("Replay", reason)

    def test_azure_push_prefers_the_latest_release_tag_when_it_is_newer_than_the_previous_build(self):
        azure_built = commit(self.repo, {"src/a": "1"})
        released_elsewhere = commit(self.repo, {"src/b": "2"})
        self._tag("v1.1.2-auto(6)", released_elsewhere)
        head = commit(self.repo, {"docs/x.md": "1"})
        # Baseline azure_built would list src/b and release again; the tag baseline sees a docs-only change.
        proceed, reason, files = ci.decide_changes("push", None, head, self.pattern, self.repo, previous_lookup=lambda: azure_built)
        self.assertFalse(proceed)
        self.assertEqual(files, ["docs/x.md"])

    def test_azure_push_keeps_the_previous_build_when_it_is_newer_than_the_release_tag(self):
        old_release = commit(self.repo, {"src/a": "1"})
        self._tag("v1.1.1-auto(5)", old_release)
        azure_built = commit(self.repo, {"src/b": "2"})
        head = commit(self.repo, {"docs/x.md": "1"})
        proceed, _, files = ci.decide_changes("push", None, head, self.pattern, self.repo, previous_lookup=lambda: azure_built)
        self.assertFalse(proceed)
        self.assertEqual(files, ["docs/x.md"])

    def test_push_with_a_real_before_sha_is_not_overridden_by_a_tag(self):
        base = commit(self.repo, {"src/a": "1"})
        tagged = commit(self.repo, {"src/b": "2"})
        self._tag("v1.1.2-auto(6)", tagged)
        head = commit(self.repo, {"src/c": "3"})
        _, _, files = ci.decide_changes("push", base, head, self.pattern, self.repo)
        self.assertEqual(sorted(files), ["src/b", "src/c"])


class StaleReplayTests(unittest.TestCase):
    def test_pinned_sha_that_is_no_longer_tip_is_stale(self):
        api = FakeApi({("GET", "/commits/dev"): (200, {"sha": "b" * 40})})
        stale, live = ci.stale_replay("dev", "a" * 40, api, "o/r")
        self.assertTrue(stale)
        self.assertEqual(live, "b" * 40)
        self.assertFalse(ci.stale_replay("dev", "b" * 40, api, "o/r")[0])

    def test_unreadable_tip_is_an_error_not_a_pass(self):
        with self.assertRaises(ci.CiError):
            ci.stale_replay("dev", "a" * 40, FakeApi({("GET", "/commits/dev"): (500, None)}), "o/r")

    def test_detect_changes_skips_a_stale_replay_cleanly(self):
        api = FakeApi({("GET", "/commits/main"): (200, {"sha": "b" * 40})})
        args = argparse.Namespace(event="replay", replay="false", mode=None, before=None, after="a" * 40, branch="main", pinned_sha="a" * 40)
        out = io.StringIO()
        with mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}, clear=False), mock.patch("sys.stdout", out):
            self.assertEqual(ci.cmd_detect_changes(args, api=api), 0)
        self.assertIn("has_code_changes=false", out.getvalue())
        self.assertIn("skip_reason=stale-replay", out.getvalue())

    def test_detect_changes_fails_when_replay_lacks_pinned_sha(self):
        args = argparse.Namespace(event="replay", replay="false", mode=None, before=None, after="a" * 40, branch="main", pinned_sha="")
        with mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}, clear=False), mock.patch("sys.stderr", io.StringIO()):
            self.assertEqual(ci.cmd_detect_changes(args, api=FakeApi({})), 1)

    def test_tag_mode_never_runs_change_detection(self):
        args = argparse.Namespace(event="auto", replay="false", mode="tag", before=None, after="a" * 40, branch=None, pinned_sha=None)
        out = io.StringIO()
        with mock.patch("sys.stdout", out):
            self.assertEqual(ci.cmd_detect_changes(args), 0)
        self.assertIn("has_code_changes=true", out.getvalue())

    def test_auto_event_resolution(self):
        with mock.patch.dict(os.environ, {"BUILD_REASON": "Manual"}):
            self.assertEqual(ci.resolve_event("auto", "false"), "manual")
            self.assertEqual(ci.resolve_event("auto", "True"), "replay")
        with mock.patch.dict(os.environ, {"BUILD_REASON": "IndividualCI"}):
            self.assertEqual(ci.resolve_event("auto", "False"), "push")
        self.assertEqual(ci.resolve_event("push", "true"), "push")


class ModeResolutionTests(unittest.TestCase):
    def test_refs_map_to_release_modes(self):
        self.assertEqual(ci.resolve_mode_from_ref("refs/heads/dev", CONTRACT), "beta")
        self.assertEqual(ci.resolve_mode_from_ref("refs/heads/main", CONTRACT), "stable")
        self.assertEqual(ci.resolve_mode_from_ref("refs/tags/v1.2.3", CONTRACT), "tag")
        self.assertIsNone(ci.resolve_mode_from_ref("refs/tags/v1.2.3-beta.1-auto(5)", CONTRACT))
        self.assertIsNone(ci.resolve_mode_from_ref("refs/tags/tagbuild-v1.2.3-auto(5)", CONTRACT))
        self.assertIsNone(ci.resolve_mode_from_ref("refs/heads/feature/x", CONTRACT))


class GuardTests(unittest.TestCase):
    def args(self, provider="azure", snapshot=None):
        return argparse.Namespace(provider=provider, snapshot=snapshot, max_wait=30, poll=1)

    def run_guard(self, api, args, clock=None):
        out = io.StringIO()
        sleeps = []
        with mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}, clear=False), mock.patch("sys.stdout", out), mock.patch("sys.stderr", io.StringIO()):
            code = ci.cmd_guard(args, api=api, sleep=sleeps.append, clock=clock or (lambda: 0))
        return code, out.getvalue(), sleeps

    def test_active_when_provider_matches(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): (404, None), ("GET", "actions/variables/CI_PROVIDER"): (200, {"value": "azure"})})
        code, out, _ = self.run_guard(api, self.args("azure"))
        self.assertEqual(code, 0)
        self.assertIn("active=true", out)

    def test_unset_provider_means_github_so_azure_is_inactive(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): (404, None), ("GET", "actions/variables/CI_PROVIDER"): (404, None)})
        code, out, _ = self.run_guard(api, self.args("azure"))
        self.assertEqual(code, 0)
        self.assertIn("active=false", out)

    def test_waits_for_the_switch_lock_then_reads_provider_live(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): [(200, {"value": "tok"}), (200, {"value": "tok"}), (404, None)],
                       ("GET", "actions/variables/CI_PROVIDER"): (200, {"value": "github"})})
        code, out, sleeps = self.run_guard(api, self.args("github"))
        self.assertEqual((code, len(sleeps)), (0, 2))
        self.assertIn("active=true", out)

    def test_stuck_lock_times_out_loudly(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): (200, {"value": "tok"})})
        ticks = iter(range(0, 1000, 20))
        code, _, _ = self.run_guard(api, self.args("github", snapshot="github"), clock=lambda: next(ticks))
        self.assertEqual(code, 1)

    def test_azure_fails_closed_when_variable_unreadable(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): (404, None), ("GET", "actions/variables/CI_PROVIDER"): (403, None)})
        code, _, _ = self.run_guard(api, self.args("azure"))
        self.assertEqual(code, 1)

    def test_github_falls_back_to_workflow_snapshot_when_read_is_refused(self):
        api = FakeApi({("GET", "CI_PROVIDER_SWITCHING"): (403, None)})
        code, out, _ = self.run_guard(api, self.args("github", snapshot=""))
        self.assertEqual(code, 0)
        self.assertIn("active=true", out)
        code, out, _ = self.run_guard(api, self.args("github", snapshot="azure"))
        self.assertIn("active=false", out)


class SigningAndGradleTests(unittest.TestCase):
    def test_write_signing_creates_files_without_printing_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "src").mkdir()
            env = {"SIGNING_KEY_BASE64": base64.b64encode(b"KEYSTORE").decode(), "KEY_ALIAS": "alias", "KEY_PASSWORD": "kpw-secret",
                   "STORE_PASSWORD": "spw-secret"}
            out = io.StringIO()
            with mock.patch.object(ci, "ROOT", Path(tmp)), mock.patch.dict(os.environ, env), mock.patch("sys.stdout", out):
                self.assertEqual(ci.cmd_write_signing(None), 0)
            self.assertEqual((Path(tmp) / "src" / "keystore.jks").read_bytes(), b"KEYSTORE")
            props = (Path(tmp) / "src" / "keystore.properties").read_text()
            self.assertIn("keyAlias=alias", props)
            self.assertIn("storeFile=keystore.jks", props)
            for secret in ("kpw-secret", "spw-secret", env["SIGNING_KEY_BASE64"]):
                self.assertNotIn(secret, out.getvalue())

    def test_write_signing_names_missing_secret(self):
        env = {k: "" for k in CONTRACT["signing"]["secrets"]}
        err = io.StringIO()
        with mock.patch.dict(os.environ, env), mock.patch("sys.stderr", err):
            self.assertEqual(ci.cmd_write_signing(None), 1)
        self.assertIn("SIGNING_KEY_BASE64", err.getvalue())

    def test_gradle_command_uses_contract_profile_and_maps_build_config(self):
        env = {"PRIMARY_SERVERS_URL": "https://p.example", "FALLBACK_SERVERS_URL": "https://f.example"}
        with mock.patch.dict(os.environ, env):
            command, out_env = ci.gradle_command(CONTRACT, "debug-apks")
        self.assertEqual(command[1:], ["--no-daemon", "--max-workers=2", "--stacktrace", ":mobile:assembleDebug", ":tv:assembleDebug"])
        self.assertEqual(out_env["GRADLE_OPTS"], CONTRACT["gradle"]["profiles"]["pr"]["gradleOpts"])
        self.assertEqual(out_env["ORG_GRADLE_PROJECT_PRIMARY_SERVERS_URL"], "https://p.example")

    def test_gradle_requires_build_configuration(self):
        with mock.patch.dict(os.environ, {"PRIMARY_SERVERS_URL": "", "FALLBACK_SERVERS_URL": ""}):
            with self.assertRaises(ci.CiError):
                ci.gradle_command(CONTRACT, "unit-tests")
        with self.assertRaises(ci.CiError):
            ci.gradle_command(CONTRACT, "nope")


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)
        self.meta = {"mode": "beta", "app_version": "1.1.5-beta.1", "build_number": 7, "tag": "v1.1.5-beta.1-auto(7)",
                     "release_name": "Beta Release v1.1.5-beta.1-auto(7)", "versions_name": "Beta Release v1.1.5-beta.1-auto(7)",
                     "prerelease": True, "generate_release_notes": True, "versions_release_type": 1, "branch": "dev",
                     "last_commit": "Fix things", "build_marker_tag": ""}
        self.assets = ci.asset_list(CONTRACT, self.meta)
        for asset in self.assets:
            asset["path"] = self.out / asset["name"]
            asset["path"].write_bytes(b"data-" + asset["name"].encode())

    def tearDown(self):
        self.tmp.cleanup()

    def test_release_is_created_as_prerelease_with_generated_notes(self):
        api = FakeApi({("GET", "/releases/tags/"): (404, None), ("POST", "/releases"): (201, {"id": 99}), ("POST", "uploads.github.com"): (201, {})})
        ci.publish_github_release(api, "o/r", self.meta, self.assets)
        create = next(c for c in api.calls if c[0] == "POST" and c[1].endswith("/releases"))
        self.assertTrue(create[2]["prerelease"])
        self.assertTrue(create[2]["generate_release_notes"])
        self.assertEqual(create[2]["tag_name"], self.meta["tag"])
        self.assertEqual(len([c for c in api.calls if "uploads.github.com" in c[1]]), 4)

    def test_existing_release_is_updated_and_assets_replaced(self):
        existing = {"id": 5, "assets": [{"name": self.assets[0]["name"], "id": 77}]}
        api = FakeApi({("GET", "/releases/tags/"): (200, existing), ("PATCH", "/releases/5"): (200, {}), ("DELETE", "/releases/assets/77"): (204, None),
                       ("POST", "uploads.github.com"): (201, {})})
        ci.publish_github_release(api, "o/r", self.meta, self.assets)
        methods = [c[0] for c in api.calls]
        self.assertIn("PATCH", methods)
        self.assertIn("DELETE", methods)
        self.assertNotIn("generate_release_notes", json.dumps([c[2] for c in api.calls if c[0] == "PATCH"]))

    def test_missing_artifact_blocks_publication(self):
        self.assets[1]["path"].unlink()
        with self.assertRaises(ci.CiError):
            ci.publish_github_release(FakeApi({}), "o/r", self.meta, self.assets)

    def test_tag_creation_tolerates_existing_tag_but_not_other_errors(self):
        for status, expected in ((201, 0), (422, 0), (403, 1)):
            api = FakeApi({("POST", "/git/refs"): (status, None), ("GET", "/git/ref/tags/"): (200, {"object": {"sha": "a" * 40}})})
            with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ci, "ROOT", Path(tmp)), \
                    mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}), mock.patch("sys.stdout", io.StringIO()), mock.patch("sys.stderr", io.StringIO()):
                meta_path = Path(tmp) / CONTRACT["release"]["metadataFile"]
                meta_path.parent.mkdir(parents=True)
                meta_path.write_text(json.dumps(self.meta))
                self.assertEqual(ci.cmd_create_tag(argparse.Namespace(sha="a" * 40), api=api), expected)
                self.assertEqual(api.calls[0][2]["ref"], f"refs/tags/{self.meta['tag']}")

    def test_422_for_a_tag_at_a_different_commit_or_an_unreadable_ref_fails(self):
        for ref_response in ((200, {"object": {"sha": "b" * 40}}), (404, None)):
            api = FakeApi({("POST", "/git/refs"): (422, None), ("GET", "/git/ref/tags/"): ref_response})
            with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ci, "ROOT", Path(tmp)),                     mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}), mock.patch("sys.stdout", io.StringIO()), mock.patch("sys.stderr", io.StringIO()):
                meta_path = Path(tmp) / CONTRACT["release"]["metadataFile"]
                meta_path.parent.mkdir(parents=True)
                meta_path.write_text(json.dumps(self.meta))
                self.assertEqual(ci.cmd_create_tag(argparse.Namespace(sha="a" * 40), api=api), 1)

    def test_release_keeps_the_legacy_latest_rule_and_pins_the_target_commit(self):
        api = FakeApi({("GET", "/releases/tags/"): (404, None), ("POST", "/releases"): (201, {"id": 99}), ("POST", "uploads.github.com"): (201, {})})
        ci.publish_github_release(api, "o/r", self.meta, self.assets, sha="c" * 40)
        create = next(c for c in api.calls if c[0] == "POST" and c[1].endswith("/releases"))
        self.assertEqual(create[2]["make_latest"], "legacy")
        self.assertEqual(create[2]["target_commitish"], "c" * 40)

    def test_release_update_also_keeps_the_legacy_latest_rule(self):
        api = FakeApi({("GET", "/releases/tags/"): (200, {"id": 5, "assets": []}), ("PATCH", "/releases/5"): (200, {}), ("POST", "uploads.github.com"): (201, {})})
        ci.publish_github_release(api, "o/r", self.meta, self.assets)
        patch = next(c for c in api.calls if c[0] == "PATCH")
        self.assertEqual(patch[2]["make_latest"], "legacy")

    def test_build_marker_tag_is_used_in_tag_mode(self):
        meta = {**self.meta, "mode": "tag", "tag": "v2.0.0", "build_marker_tag": "tagbuild-v2.0.0-auto(8)"}
        api = FakeApi({("POST", "/git/refs"): (201, None)})
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ci, "ROOT", Path(tmp)), mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}), \
                mock.patch("sys.stdout", io.StringIO()):
            meta_path = Path(tmp) / CONTRACT["release"]["metadataFile"]
            meta_path.parent.mkdir(parents=True)
            meta_path.write_text(json.dumps(meta))
            ci.cmd_create_tag(argparse.Namespace(sha="a" * 40), api=api)
        self.assertEqual(api.calls[0][2]["ref"], "refs/tags/tagbuild-v2.0.0-auto(8)")

    def _run_versions(self, statuses):
        calls = []

        class Resp:
            def __init__(self, status, payload):
                self.status, self._payload = status, payload

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return json.dumps(self._payload).encode()

        queue = list(statuses)

        def opener(request, timeout=0):
            calls.append((request.get_method(), request.full_url, request.headers.get("X-api-key")))
            status, payload = queue.pop(0)
            if status >= 400:
                import urllib.error
                raise urllib.error.HTTPError(request.full_url, status, "err", {}, io.BytesIO(json.dumps(payload).encode()))
            return Resp(status, payload)

        env = {"VERSIONS_API_BASE_URL": "https://api.example/", "VERSIONS_API_KEY": "sekrit", "CI_REPOSITORY": "o/r"}
        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ci, "ROOT", Path(tmp)), mock.patch.dict(os.environ, env), \
                mock.patch("sys.stdout", out), mock.patch("sys.stderr", err):
            (Path(tmp) / "output").mkdir()
            for asset in self.assets:
                (Path(tmp) / "output" / asset["name"]).write_bytes(asset["path"].read_bytes())
            meta_path = Path(tmp) / CONTRACT["release"]["metadataFile"]
            meta_path.parent.mkdir(parents=True)
            meta_path.write_text(json.dumps(self.meta))
            code = ci.cmd_versions_api(None, opener=opener)
        return code, calls, out.getvalue() + err.getvalue()

    def test_versions_api_creates_then_translates_every_language(self):
        code, calls, text = self._run_versions([(200, {"data": {"id": "v-1"}})] + [(200, {})] * 3)
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 4)
        self.assertTrue(calls[0][1].endswith("/api/v1/versions"))
        self.assertTrue(all(c[2] == "sekrit" for c in calls))
        self.assertTrue(calls[1][1].endswith("/api/v1/versions/v-1/localizations/en/auto-translate?sourceLocale=en"))
        self.assertNotIn("sekrit", text)

    def test_versions_api_conflict_means_already_published_and_skips_translation(self):
        code, calls, text = self._run_versions([(409, {})])
        self.assertEqual((code, len(calls)), (0, 1))
        self.assertIn("created=false", text)

    def test_versions_api_real_failure_is_a_failure(self):
        code, calls, _ = self._run_versions([(500, {})])
        self.assertEqual(code, 1)

    def test_versions_api_payload_matches_existing_shape(self):
        with mock.patch.object(ci, "asset_list", return_value=self.assets):
            payload = ci.build_versions_payload(CONTRACT, self.meta, "o/r")
        self.assertEqual([a["platform"] for a in payload["assets"]], [0, 0, 1, 1])
        self.assertEqual([a["assetType"] for a in payload["assets"]], ["apk", "aab", "apk", "aab"])
        self.assertEqual(payload["releaseType"], 1)
        self.assertTrue(payload["assets"][0]["downloadUrl"].startswith("https://github.com/o/r/releases/download/v1.1.5-beta.1-auto(7)/"))
        self.assertEqual(len(payload["assets"][0]["contentHash"]), 64)


class GateTests(unittest.TestCase):
    def test_job_results_map_to_commit_states(self):
        self.assertEqual(ci.gate_state("success"), "success")
        self.assertEqual(ci.gate_state("Succeeded"), "success")
        self.assertEqual(ci.gate_state("SucceededWithIssues"), "success")
        self.assertEqual(ci.gate_state("failure"), "failure")
        self.assertEqual(ci.gate_state("Failed"), "failure")
        self.assertEqual(ci.gate_state("cancelled"), "error")
        self.assertEqual(ci.gate_state("Canceled"), "error")
        self.assertIsNone(ci.gate_state("skipped"))

    def test_gate_posts_the_contract_context(self):
        api = FakeApi({("POST", "/statuses/"): (201, {})})
        args = argparse.Namespace(provider="azure", state="pending", job_result=None, sha="c" * 40, target_url="https://x")
        with mock.patch.dict(os.environ, {"CI_REPOSITORY": "o/r"}), mock.patch("sys.stdout", io.StringIO()):
            self.assertEqual(ci.cmd_ci_gate(args, api=api), 0)
        self.assertEqual(api.calls[0][2]["context"], "CI Gate")
        self.assertEqual(api.calls[0][2]["state"], "pending")


if __name__ == "__main__":
    unittest.main()
