"""Fail-closed tests for the Android PR CI change classifier."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import classify_pr_changes as ci  # noqa: E402


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=repo, capture_output=True,
        check=True, text=True, encoding="utf-8"
    ).stdout.strip()


def commit(repo: Path, msg: str) -> str:
    git(repo, "add", "-A")
    git(repo, "-c", "user.name=CI Test", "-c", "user.email=test@invalid",
        "commit", "-q", "-m", msg)
    return git(repo, "rev-parse", "HEAD")


class ClassifierTests(unittest.TestCase):
    def test_only_explicit_docs_paths_are_skippable(self):
        for name in (
            "README.md", "CHANGELOG.md", "LICENSE", "docs/guide.md",
            "docs/operations/android.rst", "docs/qa/notes.txt"
        ):
            with self.subTest(doc=name):
                self.assertTrue(ci.is_documentation(name))
        for name in (
            "src/core/src/main/java/Main.kt", "src/build.gradle.kts",
            "src/gradle/libs.versions.toml", "src/external/OpenVPNEngine",
            ".gitmodules", ".github/workflows/ci-pr.yml",
            "scripts/ci/tests/test_classify_pr_changes.py",
            "docs/fixtures/probe.py", "docs/architecture.json",
            "docs/README.md.sh", "src/core/README.md", ".github/README.md",
            "docs", "somefile", "README.md.ps1"
        ):
            with self.subTest(relevant=name):
                self.assertFalse(ci.is_documentation(name))

    def test_combination_and_empty_set_fail_closed(self):
        self.assertEqual(ci.classify(["README.md", "docs/notes.md"]), (False, []))
        self.assertEqual(ci.classify(["docs/notes.md", "src/core/File.kt"]),
                         (True, ["src/core/File.kt"]))
        self.assertEqual(ci.classify(["docs/notes.md", ".github/workflows/ci-pr.yml"]),
                         (True, [".github/workflows/ci-pr.yml"]))
        self.assertEqual(ci.classify([]), (True, []))

    def test_diff_of_docs_only_vs_rename_out_of_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            git(root, "init", "-q", "-b", "dev")
            (root / "src").mkdir()
            (root / "src" / "Main.kt").write_text("fun main() = Unit\n", encoding="utf-8")
            (root / "docs").mkdir()
            (root / "docs" / "setup.md").write_text("Initial\n", encoding="utf-8")
            base = commit(root, "base")

            (root / "docs" / "setup.md").write_text("Changed\n", encoding="utf-8")
            doc_head = commit(root, "docs")
            paths = ci.changed_files(root, base, doc_head)
            self.assertEqual(paths, ["docs/setup.md"])
            self.assertEqual(ci.classify(paths), (False, []))

            # Git might detect a rename; --no-renames must keep the
            # original executable deletion, forcing full CI.
            (root / "src" / "Main.kt").rename(root / "docs" / "Main.kt")
            head = commit(root, "move code to docs")
            paths = ci.changed_files(root, doc_head, head)
            self.assertIn("src/Main.kt", paths)
            self.assertIn("docs/Main.kt", paths)
            self.assertTrue(ci.classify(paths)[0])

    def test_invalid_sha_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                ci.changed_files(Path(tmp), "main", "deadbeef")

    def test_cli_writes_boolean_and_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            git(root, "init", "-q", "-b", "dev")
            (root / "README.md").write_text("A\n", encoding="utf-8")
            base = commit(root, "base")
            (root / "README.md").write_text("B\n", encoding="utf-8")
            head = commit(root, "docs")
            out = root / "ci-outputs"
            summary = root / "ci-summary"
            self.assertEqual(ci.main([
                "--repo", str(root), "--base", base, "--head", head,
                "--output-file", str(out), "--summary-file", str(summary)
            ]), 0)
            self.assertIn("run_heavy_ci=false\n", out.read_text(encoding="utf-8"))
            self.assertIn("changed_files=1\n", out.read_text(encoding="utf-8"))
            self.assertIn("docs-only; skip Gradle", summary.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
