"""Tests for protect-agent-git-command.py — push false-positive (#2) and missing-command coverage (#8)."""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "protect_agent_git_command",
    Path(__file__).parent.parent / "scripts" / "protect-agent-git-command.py",
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
protected_reason = _mod.protected_reason


# ---------------------------------------------------------------------------
# push — feature branches whose name starts with a protected prefix (#2)
# ---------------------------------------------------------------------------

def test_push_dev_slash_feature_is_allowed():
    assert protected_reason("git push origin dev/my-feature", "feature/x") == ""

def test_push_main_hyphen_feature_is_allowed():
    assert protected_reason("git push origin main-feature", "feature/x") == ""

def test_push_develop_slash_branch_is_allowed():
    assert protected_reason("git push origin develop/issue-1", "feature/x") == ""

def test_push_master_dot_branch_is_allowed():
    assert protected_reason("git push origin master.backup", "feature/x") == ""

# push to exact protected names must still be blocked
def test_push_to_dev_is_blocked():
    assert protected_reason("git push origin dev", "feature/x") != ""

def test_push_to_main_is_blocked():
    assert protected_reason("git push origin main", "feature/x") != ""

def test_push_to_master_is_blocked():
    assert protected_reason("git push origin master", "feature/x") != ""

def test_push_refs_heads_dev_slash_feature_is_allowed():
    assert protected_reason("git push origin refs/heads/dev/sub", "feature/x") == ""

def test_push_refs_heads_main_is_blocked():
    assert protected_reason("git push origin refs/heads/main", "feature/x") != ""

def test_delete_dev_slash_feature_is_allowed():
    assert protected_reason("git push origin --delete dev/old", "feature/x") == ""

def test_delete_dev_is_blocked():
    assert protected_reason("git push origin --delete dev", "feature/x") != ""


# ---------------------------------------------------------------------------
# reset --hard / --keep / --merge on protected branches (#8)
# ---------------------------------------------------------------------------

def test_reset_hard_on_main_is_blocked():
    assert protected_reason("git reset --hard HEAD^", "main") != ""

def test_reset_hard_on_dev_is_blocked():
    assert protected_reason("git reset --hard HEAD~3", "dev") != ""

def test_reset_hard_on_master_is_blocked():
    assert protected_reason("git reset --hard origin/master", "master") != ""

def test_reset_keep_on_main_is_blocked():
    assert protected_reason("git reset --keep HEAD^", "main") != ""

def test_reset_merge_on_dev_is_blocked():
    assert protected_reason("git reset --merge HEAD^", "dev") != ""

def test_reset_hard_on_feature_is_allowed():
    assert protected_reason("git reset --hard HEAD^", "feature/x") == ""

def test_reset_soft_on_main_is_allowed():
    assert protected_reason("git reset --soft HEAD^", "main") == ""

def test_reset_mixed_on_main_is_allowed():
    assert protected_reason("git reset HEAD^", "main") == ""


# ---------------------------------------------------------------------------
# git branch -f / --force targeting protected branches (#8)
# ---------------------------------------------------------------------------

def test_branch_force_short_to_main_is_blocked():
    assert protected_reason("git branch -f main HEAD^", "feature/x") != ""

def test_branch_force_long_to_dev_is_blocked():
    assert protected_reason("git branch --force dev HEAD~1", "feature/x") != ""

def test_branch_force_to_master_is_blocked():
    assert protected_reason("git branch -f master abc123", "feature/x") != ""

def test_branch_force_to_feature_is_allowed():
    assert protected_reason("git branch -f feature/new HEAD^", "main") == ""

def test_branch_force_to_dev_slash_sub_is_allowed():
    assert protected_reason("git branch -f dev/sub HEAD^", "feature/x") == ""

def test_branch_create_without_force_is_allowed():
    assert protected_reason("git branch new-branch", "main") == ""

def test_branch_delete_protected_via_push_is_blocked():
    assert protected_reason("git push origin --delete main", "feature/x") != ""
