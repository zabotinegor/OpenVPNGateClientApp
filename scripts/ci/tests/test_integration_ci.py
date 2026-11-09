"""Tests for scripts/ci/integration_ci.py: real temporary git repositories for `prepare`, a fake API transport for `publish`."""
from __future__ import annotations

import contextlib
import io
import os
import re
import shutil
import subprocess
import tempfile
import sys
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "scripts" / "ci"))

import integration_ci as ci  # noqa: E402

TOKEN = "ghs_testtokenvalue1234567890"
REPO = "owner/repo"
RUN_ID = "424242"


def git(cwd: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "user.name=test", "-c", "user.email=test@invalid", *args],
        cwd=str(cwd), capture_output=True, text=True, check=True)
    return result.stdout.strip()


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


class PrepareFixture:
    """A bare origin, a dev history and a pull request head published as refs/pull/<n>/head."""

    def __init__(self, test: unittest.TestCase, *, conflict: bool = False) -> None:
        self.root = Path(tempfile.mkdtemp())
        test.addCleanup(shutil.rmtree, str(self.root), True)
        # Isolate from the machine's git configuration (autocrlf, credential helpers) so line endings stay byte-exact.
        empty_config = self.root / "empty.gitconfig"
        empty_config.write_text("", encoding="utf-8")
        patcher = mock.patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(empty_config), "GIT_CONFIG_NOSYSTEM": "1"})
        patcher.start()
        test.addCleanup(patcher.stop)
        self.origin = self.root / "origin.git"
        seed = self.root / "seed"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "dev", str(self.origin)], check=True)
        subprocess.run(["git", "init", "-q", "-b", "dev", str(seed)], check=True)
        write(seed / "shared.txt", "line\n")
        write(seed / "main-only.txt", "a\n")
        git(seed, "add", "-A")
        git(seed, "commit", "-q", "-m", "initial")
        git(seed, "remote", "add", "origin", str(self.origin))
        git(seed, "checkout", "-q", "-b", "feature")
        write(seed / "feature.txt", "pr\n")
        if conflict:
            write(seed / "shared.txt", "pr side\n")
        git(seed, "add", "-A")
        git(seed, "commit", "-q", "-m", "pr change")
        self.head = git(seed, "rev-parse", "HEAD")
        git(seed, "checkout", "-q", "dev")
        write(seed / "main-only.txt", "b\n")
        if conflict:
            write(seed / "shared.txt", "main side\n")
        git(seed, "add", "-A")
        git(seed, "commit", "-q", "-m", "main advances")
        self.base = git(seed, "rev-parse", "HEAD")
        git(seed, "push", "-q", "origin", "dev")
        git(seed, "push", "-q", "origin", f"{self.head}:refs/pull/7/head")
        self.seed = seed
        self.work = self.root / "work"
        subprocess.run(["git", "clone", "-q", str(self.origin), str(self.work)], check=True)
        self.output = self.root / "github_output"
        self.output.write_text("", encoding="utf-8")

    def env(self, **overrides: str) -> dict[str, str]:
        env = {
            "PR": "7", "HEAD_SHA": self.head, "BASE_SHA": self.base, "GITHUB_TOKEN": TOKEN,
            "GITHUB_OUTPUT": str(self.output), "GITHUB_SHA": self.base, "GITHUB_REF": "refs/heads/dev",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
        }
        env.update(overrides)
        return env

    def prepare(self, **overrides: str) -> str:
        with contextlib.redirect_stdout(io.StringIO()):
            return ci.prepare(self.env(**overrides), cwd=str(self.work))

    def refs(self) -> str:
        return git(self.work, "for-each-ref", "--format=%(refname) %(objectname)")


class RecordingGit:
    def __init__(self) -> None:
        self.calls: list[tuple[list[str], dict[str, str]]] = []

    def __call__(self, args, cwd, extra_env=None):
        self.calls.append((list(args), dict(extra_env or {})))
        return ci.run_git(args, cwd, extra_env)

    def names(self) -> list[str]:
        return [call[0][0] for call in self.calls]


class PrepareTests(unittest.TestCase):
    def assert_fails(self, fixture: PrepareFixture, reason: str, **overrides: str) -> RecordingGit:
        recorder = RecordingGit()
        with self.assertRaises(ci.CiError) as raised, contextlib.redirect_stdout(io.StringIO()):
            ci.prepare(fixture.env(**overrides), cwd=str(fixture.work), git=recorder)
        self.assertEqual(raised.exception.reason, reason)
        self.assertEqual(fixture.output.read_text(encoding="utf-8"), "", "no tree output on failure")
        return recorder

    def test_invalid_inputs_fail_before_any_git_call(self):
        fixture = PrepareFixture(self)
        sha = "a" * 40
        cases = [
            {"PR": "0"}, {"PR": "01"}, {"PR": "1a"}, {"PR": ""}, {"PR": "7\n"},
            {"HEAD_SHA": "A" * 40}, {"HEAD_SHA": "a" * 39}, {"HEAD_SHA": sha + "\n"}, {"HEAD_SHA": "g" * 40},
            {"BASE_SHA": "B" * 40}, {"BASE_SHA": ""},
        ]
        for overrides in cases:
            with self.subTest(overrides=overrides):
                recorder = self.assert_fails(fixture, "input-invalid", **overrides)
                self.assertEqual(recorder.calls, [])

    def test_wrong_dispatch_context_fails_before_any_git_call(self):
        fixture = PrepareFixture(self)
        for reason, overrides in (
            ("sha-not-base", {"GITHUB_SHA": "c" * 40}),
            ("ref-not-base", {"GITHUB_REF": "refs/heads/feature"}),
            ("ref-not-base", {"GITHUB_REF": "refs/pull/7/merge"}),
            ("event-not-dispatch", {"GITHUB_EVENT_NAME": "pull_request"}),
        ):
            with self.subTest(overrides=overrides):
                self.assertEqual(self.assert_fails(fixture, reason, **overrides).calls, [])

    def test_checked_out_commit_must_be_the_base(self):
        fixture = PrepareFixture(self)
        git(fixture.work, "checkout", "-q", "--detach", f"{fixture.base}~1")
        self.assert_fails(fixture, "sha-not-base")

    def test_base_drift_fails_before_the_merge(self):
        fixture = PrepareFixture(self)
        write(fixture.seed / "later.txt", "x\n")
        git(fixture.seed, "add", "-A")
        git(fixture.seed, "commit", "-q", "-m", "main advances again")
        git(fixture.seed, "push", "-q", "origin", "dev")
        recorder = self.assert_fails(fixture, "base-drift")
        self.assertNotIn("merge", recorder.names())

    def test_head_drift_fails_before_the_merge(self):
        fixture = PrepareFixture(self)
        git(fixture.seed, "checkout", "-q", "feature")
        write(fixture.seed / "late.txt", "x\n")
        git(fixture.seed, "add", "-A")
        git(fixture.seed, "commit", "-q", "-m", "pr moves")
        git(fixture.seed, "push", "-q", "origin", "HEAD:refs/pull/7/head", "--force")
        recorder = self.assert_fails(fixture, "head-drift")
        self.assertNotIn("merge", recorder.names())

    def test_missing_pull_ref_fails_to_fetch(self):
        fixture = PrepareFixture(self)
        self.assert_fails(fixture, "fetch-failed", PR="8")

    def test_conflict_fails_without_tree_and_restores_the_base_workspace(self):
        fixture = PrepareFixture(self, conflict=True)
        self.assert_fails(fixture, "merge-conflict")
        self.assertEqual(git(fixture.work, "rev-parse", "HEAD"), fixture.base)
        self.assertEqual(git(fixture.work, "status", "--porcelain"), "")
        self.assertFalse((fixture.work / "feature.txt").exists(), "no pull request file may remain after a failed merge")
        self.assertEqual((fixture.work / "shared.txt").read_text(encoding="utf-8"), "main side\n")

    def test_clean_merge_emits_the_merge_tree_and_writes_nothing(self):
        fixture = PrepareFixture(self)
        before_refs = fixture.refs()
        recorder = RecordingGit()
        with contextlib.redirect_stdout(io.StringIO()):
            tree = ci.prepare(fixture.env(), cwd=str(fixture.work), git=recorder)

        # Reference merge in a separate clone with the same tool, then compare trees.
        reference = fixture.root / "reference"
        subprocess.run(["git", "clone", "-q", str(fixture.origin), str(reference)], check=True)
        git(reference, "fetch", "-q", "origin", "refs/pull/7/head")
        git(reference, "merge", "-q", "--no-ff", "-m", "ref", fixture.head)
        self.assertEqual(tree, git(reference, "rev-parse", "HEAD^{tree}"))
        self.assertRegex(tree, r"^[0-9a-f]{40}$")
        self.assertEqual(fixture.output.read_text(encoding="utf-8"), f"tree={tree}\n")

        self.assertEqual(git(fixture.work, "rev-parse", "HEAD"), fixture.base, "no commit is created")
        self.assertEqual(git(fixture.work, "rev-parse", "--verify", "MERGE_HEAD"), fixture.head, "merge left uncommitted for the build")
        self.assertTrue((fixture.work / "feature.txt").exists())
        after_refs = fixture.refs()
        added = set(after_refs.splitlines()) - set(before_refs.splitlines())
        self.assertEqual({line.split()[0] for line in added}, {f"refs/remotes/pr/7"}, "only the fetched pull ref is added")
        self.assertEqual(git(fixture.work, "tag", "--list"), "")

    def test_token_never_reaches_argv_or_git_config(self):
        fixture = PrepareFixture(self)
        recorder = RecordingGit()
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            ci.prepare(fixture.env(), cwd=str(fixture.work), git=recorder)
        for args, _ in recorder.calls:
            self.assertFalse(any(TOKEN in arg for arg in args))
        config = (fixture.work / ".git" / "config").read_text(encoding="utf-8")
        self.assertNotIn(TOKEN, config)
        self.assertNotIn("extraheader", config)
        fetch_env = [env for args, env in recorder.calls if args[0] == "fetch"][0]
        self.assertEqual(fetch_env["GIT_CONFIG_COUNT"], "1")
        self.assertEqual(fetch_env["GIT_CONFIG_KEY_0"], "http.https://github.com/.extraheader")
        self.assertTrue(fetch_env["GIT_CONFIG_VALUE_0"].startswith("AUTHORIZATION: basic "))
        self.assertTrue(all(not env for args, env in recorder.calls if args[0] != "fetch"), "only the fetch gets the header")
        self.assertIn("::add-mask::", stdout.getvalue())
        self.assertNotIn(TOKEN, stdout.getvalue())

    def test_merge_runs_with_rerere_off_and_no_commit(self):
        fixture = PrepareFixture(self)
        recorder = RecordingGit()
        with contextlib.redirect_stdout(io.StringIO()):
            ci.prepare(fixture.env(), cwd=str(fixture.work), git=recorder)
        merge = [args for args, _ in recorder.calls if "merge" in args][0]
        self.assertIn("rerere.enabled=false", merge)
        self.assertIn("--no-commit", merge)
        self.assertIn("--no-ff", merge)
        self.assertEqual(merge[-1], fixture.head)

    def test_main_reports_failures_as_annotations_without_echoing_inputs(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = ci.main(["prepare"], env={"PR": "1; echo pwned", "HEAD_SHA": "x", "BASE_SHA": "y"})
        self.assertEqual(code, 1)
        self.assertIn("::error title=input-invalid::", stdout.getvalue())
        self.assertNotIn("pwned", stdout.getvalue())


BASE = "b" * 40
OLD_BASE = "0" * 40
HEAD = "a" * 40
TREE = "c" * 40
MERGE = "d" * 40


class FakeApi:
    """Routes GitHub REST calls to scripted responses. A list value is consumed one entry per call (the last repeats)."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None]] = []
        self.routes: dict[tuple[str, str], object] = {}
        self.sleeps: list[float] = []
        self.route("GET", f"repos/{REPO}/git/ref/heads/dev", (200, {"object": {"sha": BASE}}))
        self.route("GET", f"repos/{REPO}/pulls/5", (200, pull()))
        self.route("GET", f"repos/{REPO}/git/commits/{MERGE}", (200, commit([BASE, HEAD], TREE)))
        self.route("POST", f"repos/{REPO}/statuses/{HEAD}", (201, {"id": 1}))

    def route(self, method: str, path: str, response: object) -> None:
        self.routes[(method, path)] = response

    def __call__(self, method, url, token, body=None):
        assert token == TOKEN
        path = url.split("https://api.github.com/", 1)[1]
        self.calls.append((method, path, body))
        response = self.routes[(method, path)]
        if isinstance(response, list):
            return response.pop(0) if len(response) > 1 else response[0]
        if isinstance(response, BaseException):
            raise response
        return response

    def posts(self) -> list[tuple[str, str, dict | None]]:
        return [call for call in self.calls if call[0] == "POST"]

    def count(self, method: str, path: str) -> int:
        return sum(1 for call in self.calls if call[:2] == (method, path))


def pull(*, mergeable=True, merge_sha=MERGE, state="open", base_ref="dev", head=HEAD) -> dict:
    return {"state": state, "mergeable": mergeable, "merge_commit_sha": merge_sha,
            "base": {"ref": base_ref}, "head": {"sha": head}}


def commit(parents: list[str], tree: str) -> dict:
    return {"parents": [{"sha": p} for p in parents], "tree": {"sha": tree}}


def publish_env(**overrides: str) -> dict[str, str]:
    env = {
        "PR": "5", "HEAD_SHA": HEAD, "BASE_SHA": BASE, "TREE": TREE, "GITHUB_TOKEN": TOKEN,
        "GITHUB_REPOSITORY": REPO, "GITHUB_RUN_ID": RUN_ID, "GITHUB_SHA": BASE, "GITHUB_REF": "refs/heads/dev",
        "GITHUB_EVENT_NAME": "workflow_dispatch",
    }
    env.update(overrides)
    return env


class PublishTests(unittest.TestCase):
    def run_publish(self, api: FakeApi, **overrides: str) -> str:
        with contextlib.redirect_stdout(io.StringIO()):
            return ci.publish(publish_env(**overrides), http=api, sleep=api.sleeps.append)

    def assert_no_post(self, api: FakeApi, reason: str, **overrides: str) -> None:
        with self.assertRaises(ci.CiError) as raised:
            self.run_publish(api, **overrides)
        self.assertEqual(raised.exception.reason, reason)
        self.assertEqual(api.posts(), [], "no status may be posted on failure")

    def test_happy_path_posts_exactly_one_success_status(self):
        api = FakeApi()
        description = self.run_publish(api)
        self.assertEqual(len(api.posts()), 1)
        _, path, body = api.posts()[0]
        self.assertEqual(path, f"repos/{REPO}/statuses/{HEAD}")
        self.assertEqual(body, {
            "state": "success",
            "context": "Integration CI Gate",
            "description": f"pr=5 base={BASE} tree={TREE}",
            "target_url": f"https://github.com/{REPO}/actions/runs/{RUN_ID}",
        })
        self.assertEqual(description, body["description"])
        self.assertRegex(body["description"], r"^pr=[1-9][0-9]* base=[0-9a-f]{40} tree=[0-9a-f]{40}$")
        self.assertTrue(body["target_url"].endswith(f"/actions/runs/{RUN_ID}"))
        self.assertNotIn("/attempts/", body["target_url"])
        self.assertEqual(api.sleeps, [])
        self.assertEqual({call[0] for call in api.calls}, {"GET", "POST"})

    def test_invalid_tree_output_fails_before_any_api_call(self):
        for tree in ("", "C" * 40, "c" * 39, "c" * 40 + "\n", "xyz"):
            with self.subTest(tree=tree):
                api = FakeApi()
                self.assert_no_post(api, "tree-invalid", TREE=tree)
                self.assertEqual(api.calls, [])

    def test_wrong_context_or_inputs_fail_before_any_api_call(self):
        for reason, overrides in (
            ("sha-not-base", {"GITHUB_SHA": OLD_BASE}),
            ("ref-not-base", {"GITHUB_REF": "refs/heads/feature"}),
            ("event-not-dispatch", {"GITHUB_EVENT_NAME": "push"}),
            ("input-invalid", {"PR": "05"}),
            ("input-invalid", {"GITHUB_RUN_ID": "12/../3"}),
            ("input-invalid", {"GITHUB_REPOSITORY": "owner/repo/extra"}),
        ):
            with self.subTest(overrides=overrides):
                api = FakeApi()
                self.assert_no_post(api, reason, **overrides)
                self.assertEqual(api.calls, [])

    def test_tree_mismatch_fails(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/git/commits/{MERGE}", (200, commit([BASE, HEAD], "e" * 40)))
        self.assert_no_post(api, "tree-mismatch")

    def test_stale_merge_commit_retries_then_posts_once(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/git/commits/{MERGE}", [(200, commit([OLD_BASE, HEAD], TREE)), (200, commit([BASE, HEAD], TREE))])
        self.run_publish(api)
        self.assertEqual(len(api.posts()), 1)
        self.assertEqual(api.sleeps, [10])

    def test_parent_order_is_enforced(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/git/commits/{MERGE}", (200, commit([HEAD, BASE], TREE)))
        self.assert_no_post(api, "merge-commit-stale")

    def test_extra_or_missing_parents_are_stale(self):
        for parents in ([BASE], [BASE, HEAD, OLD_BASE]):
            with self.subTest(parents=parents):
                api = FakeApi()
                api.route("GET", f"repos/{REPO}/git/commits/{MERGE}", (200, commit(parents, TREE)))
                self.assert_no_post(api, "merge-commit-stale")

    def test_stale_for_the_whole_budget_fails_closed_with_bounded_sleeps(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/git/commits/{MERGE}", (200, commit([OLD_BASE, HEAD], TREE)))
        self.assert_no_post(api, "merge-commit-stale")
        self.assertEqual(api.count("GET", f"repos/{REPO}/pulls/5"), ci.MAX_READS)
        self.assertEqual(len(api.sleeps), ci.MAX_READS - 1)
        self.assertLess(sum(api.sleeps), 180)

    def test_mergeable_null_for_the_whole_budget_fails_closed(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", (200, pull(mergeable=None, merge_sha=None)))
        self.assert_no_post(api, "mergeability-timeout")
        self.assertEqual(api.count("GET", f"repos/{REPO}/pulls/5"), ci.MAX_READS)
        self.assertLess(sum(api.sleeps), 180)

    def test_mergeable_null_then_ready_posts_once(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", [(200, pull(mergeable=None, merge_sha=None)), (200, pull())])
        self.run_publish(api)
        self.assertEqual(len(api.posts()), 1)
        self.assertEqual(api.sleeps, [10])

    def test_malformed_merge_commit_sha_is_not_trusted(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", (200, pull(merge_sha="../../x")))
        self.assert_no_post(api, "mergeability-timeout")
        self.assertEqual(api.count("GET", f"repos/{REPO}/git/commits/{MERGE}"), 0)

    def test_mergeable_false_is_a_merge_conflict(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", (200, pull(mergeable=False)))
        self.assert_no_post(api, "merge-conflict")
        self.assertEqual(api.sleeps, [])

    def test_dev_moved_at_the_first_check_fails(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/git/ref/heads/dev", (200, {"object": {"sha": OLD_BASE}}))
        self.assert_no_post(api, "base-drift")

    def test_head_moved_at_the_first_check_fails(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", (200, pull(head="9" * 40)))
        self.assert_no_post(api, "head-drift")

    def test_dev_moving_before_the_final_check_fails(self):
        api = FakeApi()
        # First read agrees, the re-check right before the POST sees a newer tip.
        api.route("GET", f"repos/{REPO}/git/ref/heads/dev", [(200, {"object": {"sha": BASE}}), (200, {"object": {"sha": OLD_BASE}})])
        self.assert_no_post(api, "base-drift")

    def test_head_moving_before_the_final_check_fails(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/5", [(200, pull()), (200, pull(head="9" * 40))])
        self.assert_no_post(api, "head-drift")

    def test_closed_pull_request_or_wrong_base_branch_fails(self):
        for reason, response in (("pr-not-open", pull(state="closed")), ("pr-base-not-dev", pull(base_ref="release"))):
            with self.subTest(reason=reason):
                api = FakeApi()
                api.route("GET", f"repos/{REPO}/pulls/5", (200, response))
                self.assert_no_post(api, reason)

    def test_ambiguous_or_failing_api_responses_fail_closed(self):
        targets = (f"repos/{REPO}/git/ref/heads/dev", f"repos/{REPO}/pulls/5", f"repos/{REPO}/git/commits/{MERGE}")
        for path in targets:
            for response in ((500, {}), (403, {"message": "rate limited"}), (404, None), (200, None), (200, ["list"]), OSError("boom")):
                with self.subTest(path=path, response=response):
                    api = FakeApi()
                    api.route("GET", path, response)
                    self.assert_no_post(api, "api-error")

    def test_non_201_post_response_fails(self):
        for status in (200, 202, 403, 422, 500):
            with self.subTest(status=status):
                api = FakeApi()
                api.route("POST", f"repos/{REPO}/statuses/{HEAD}", (status, {}))
                with self.assertRaises(ci.CiError) as raised:
                    self.run_publish(api)
                self.assertEqual(raised.exception.reason, "status-post-failed")
                self.assertEqual(len(api.posts()), 1, "the single attempt is not retried")

    def test_post_network_failure_fails(self):
        api = FakeApi()
        api.route("POST", f"repos/{REPO}/statuses/{HEAD}", OSError("reset"))
        with self.assertRaises(ci.CiError) as raised:
            self.run_publish(api)
        self.assertEqual(raised.exception.reason, "status-post-failed")

    def test_enterprise_server_url_is_used_for_the_target(self):
        api = FakeApi()
        self.run_publish(api, GITHUB_SERVER_URL="https://github.example.com/")
        self.assertEqual(api.posts()[0][2]["target_url"], f"https://github.example.com/{REPO}/actions/runs/{RUN_ID}")

    def test_description_format_matches_the_gate_for_large_pull_numbers(self):
        api = FakeApi()
        api.route("GET", f"repos/{REPO}/pulls/123456", (200, pull()))
        description = self.run_publish(api, PR="123456")
        self.assertRegex(description, ci.DESCRIPTION_PATTERN)
        self.assertEqual(re.fullmatch(ci.DESCRIPTION_PATTERN, f"pr=0 base={BASE} tree={TREE}"), None)

    def test_main_exits_non_zero_on_invalid_handover_without_network(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = ci.main(["publish"], env=publish_env(TREE="nope"))
        self.assertEqual(code, 1)
        self.assertIn("::error title=tree-invalid::", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
