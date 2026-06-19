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


# ---------------------------------------------------------------------------
# mutate boundary: branch create must not stop switch tracking (fix A)
# ---------------------------------------------------------------------------

def test_branch_create_then_switch_then_commit_is_allowed():
    # Regression: 'branch' in mutate boundary caused git branch feature/x &&
    # git switch feature/x && git commit to be denied (switch was ignored).
    cmd = "git branch feature/x && git switch feature/x && git commit -m x"
    assert protected_reason(cmd, "main") == ""

def test_branch_create_then_commit_without_switch_is_blocked():
    # Still on main when commit runs — must be blocked.
    cmd = "git branch feature/x && git commit -m x"
    assert protected_reason(cmd, "main") != ""

def test_reset_before_switch_then_commit_is_allowed():
    # Reset on feature/x, switch to feature/x, commit on feature/x — all allowed.
    cmd = "git reset --hard HEAD && git switch feature/x && git commit -m x"
    assert protected_reason(cmd, "feature/x") == ""


# ---------------------------------------------------------------------------
# branch -f start-point false positive (fix B)
# ---------------------------------------------------------------------------

def test_branch_force_with_protected_start_point_is_allowed():
    # git branch -f feature/new main  — main is the start-point, not the branch being forced
    assert protected_reason("git branch -f feature/new main", "feature/x") == ""

def test_branch_force_with_protected_start_point_develop_is_allowed():
    assert protected_reason("git branch --force feature/new develop", "feature/x") == ""

def test_branch_force_to_main_with_start_point_is_blocked():
    # git branch -f main HEAD^  — main IS the branch being forced
    assert protected_reason("git branch -f main HEAD^", "feature/x") != ""

def test_branch_force_to_dev_with_start_point_is_blocked():
    assert protected_reason("git branch -f dev origin/dev", "feature/x") != ""

def test_branch_force_to_feature_with_main_start_point_multi_flag_is_allowed():
    # git branch -v -f feature/x main  — feature/x is forced, main is start-point
    assert protected_reason("git branch -v -f feature/x main", "feature/x") == ""


# ---------------------------------------------------------------------------
# per-mutation branch tracking: switch after reset must be seen at commit (fix D)
# ---------------------------------------------------------------------------

def test_reset_on_feature_then_switch_to_main_then_commit_is_blocked():
    # Security regression: with the old single-mutate-pos approach, effectiveBranch
    # was frozen at the first mutation (git reset on feature/x) so the later
    # git switch main was ignored and the commit on main was not blocked.
    cmd = "git switch feature/x && git reset --hard HEAD^ && git switch main && git commit -m bad"
    assert protected_reason(cmd, "main") != ""

def test_reset_on_feature_then_commit_on_feature_is_allowed():
    # Reset and commit both on feature/x — no switch to protected branch.
    cmd = "git reset --hard HEAD^ && git commit -m fix"
    assert protected_reason(cmd, "feature/x") == ""


# ---------------------------------------------------------------------------
# flag terminator [;&|]: --hard without trailing space before separator (fix E)
# ---------------------------------------------------------------------------

def test_reset_hard_semicolon_no_space_on_main_is_blocked():
    # --hard;next  — (?:\s|$) would miss the ; without a space; [;&|] catches it.
    assert protected_reason("git reset --hard;git checkout feature/x", "main") != ""

def test_reset_hard_ampersand_no_space_on_main_is_blocked():
    assert protected_reason("git reset --hard&&git checkout feature/x", "main") != ""


# ---------------------------------------------------------------------------
# quoted branch name bypass (fix G)
# ---------------------------------------------------------------------------

def test_branch_force_double_quoted_main_is_blocked():
    # Without strip, m.group(2) = '"main"' which is not in PROTECTED.
    assert protected_reason('git branch -f "main" HEAD^', "feature/x") != ""

def test_branch_force_single_quoted_dev_is_blocked():
    assert protected_reason("git branch -f 'dev' HEAD^", "feature/x") != ""

def test_branch_force_quoted_feature_is_allowed():
    assert protected_reason('git branch -f "feature/new" HEAD^', "main") == ""


# ---------------------------------------------------------------------------
# switch capture: (\S+) can grab shell metacharacters; [^\s;&|]+ stops at ; & | (fix K)
# ---------------------------------------------------------------------------

def test_switch_main_semicolon_then_commit_is_blocked():
    # Old (\S+) captured "main;git" (not in PROTECTED) so the switch was missed.
    # New [^\s;&|]+ captures just "main", effectiveBranch=main at commit -> blocked.
    cmd = "git switch main;git commit -m bad"
    assert protected_reason(cmd, "feature/x") != ""

def test_checkout_main_ampersand_then_commit_is_blocked():
    cmd = "git checkout main&&git commit -m bad"
    assert protected_reason(cmd, "feature/x") != ""

def test_switch_feature_semicolon_then_commit_is_allowed():
    # Switching to a non-protected branch even with tight separators should be allowed.
    cmd = "git switch feature/y;git commit -m ok"
    assert protected_reason(cmd, "feature/x") == ""


# ---------------------------------------------------------------------------
# force-push terminator: --force;next (no space) bypassed (?:\s|$) check (fix L)
# ---------------------------------------------------------------------------

def test_force_push_semicolon_no_space_is_blocked():
    # --force;next — old (?:\s|$) missed the semicolon; [;&|] catches it.
    assert protected_reason("git push --force;git status", "feature/x") != ""

def test_force_push_ampersand_no_space_is_blocked():
    assert protected_reason("git push --force&&git status", "feature/x") != ""

def test_force_push_with_space_still_blocked():
    # Existing behavior unchanged.
    assert protected_reason("git push --force origin feature/x", "feature/x") != ""


# ---------------------------------------------------------------------------
# branch -f per-occurrence: re.search on first match masked later forced cmds (fix M)
# ---------------------------------------------------------------------------

def test_branch_harmless_then_force_main_is_blocked():
    # re.search found "git branch harmless" first (no -f in that segment) and
    # used it as the target; the later "git branch -f main" was ignored.
    cmd = "git branch harmless; git branch -f main HEAD^"
    assert protected_reason(cmd, "feature/x") != ""

def test_branch_force_feature_then_harmless_is_allowed():
    # First command forces a feature branch (allowed), second is harmless.
    cmd = "git branch -f feature/new HEAD^; git branch harmless"
    assert protected_reason(cmd, "main") == ""

def test_branch_harmless_then_force_feature_is_allowed():
    # Neither branch command targets a protected branch.
    cmd = "git branch harmless; git branch -f feature/new HEAD^"
    assert protected_reason(cmd, "main") == ""


# ---------------------------------------------------------------------------
# reset flag scoping: --hard from later reset falsely blocked earlier soft (fix N)
# ---------------------------------------------------------------------------

def test_soft_on_main_then_switch_then_hard_on_feature_is_allowed():
    # git reset --soft HEAD^  (on main — soft, so allowed)
    # git switch feature/x    (now on feature/x)
    # git reset --hard HEAD^  (on feature/x — allowed)
    # The --hard flag must NOT be attributed to the first reset on main.
    cmd = "git reset --soft HEAD^; git switch feature/x; git reset --hard HEAD^"
    assert protected_reason(cmd, "main") == ""

def test_hard_reset_on_main_still_blocked_after_scope_fix():
    # Regression guard: single hard reset on main must still be denied.
    assert protected_reason("git reset --hard HEAD^", "main") != ""

def test_hard_reset_on_main_then_switch_feature_is_blocked():
    # The hard reset happens on main (before the switch) — must be blocked.
    cmd = "git reset --hard HEAD^; git switch feature/x"
    assert protected_reason(cmd, "main") != ""


# ---------------------------------------------------------------------------
# quoted switch bypass: git switch "main" && git commit bypassed (fix P / fix O)
# ---------------------------------------------------------------------------

def test_switch_double_quoted_main_then_commit_is_blocked():
    # _effective_branch_at previously returned '"main"' (with quotes) which was
    # not in PROTECTED, allowing the commit to slip through.
    cmd = 'git switch "main" && git commit -m bad'
    assert protected_reason(cmd, "feature/x") != ""

def test_switch_single_quoted_dev_then_commit_is_blocked():
    cmd = "git switch 'dev' && git commit -m bad"
    assert protected_reason(cmd, "feature/x") != ""

def test_switch_double_quoted_feature_then_commit_is_allowed():
    # Quoted non-protected branch must still be allowed.
    cmd = 'git switch "feature/y" && git commit -m ok'
    assert protected_reason(cmd, "feature/x") == ""


# ---------------------------------------------------------------------------
# git global options prefix: --no-pager, --paginate, etc. must not bypass guard
# ---------------------------------------------------------------------------

def test_no_pager_commit_on_main_is_blocked():
    # git --no-pager commit was not matched by the old GIT_PREFIX_PATTERN.
    assert protected_reason("git --no-pager commit -m bad", "main") != ""

def test_no_pager_push_to_main_is_blocked():
    assert protected_reason("git --no-pager push origin main", "feature/x") != ""

def test_paginate_commit_on_main_is_blocked():
    assert protected_reason("git --paginate commit -m bad", "main") != ""

def test_bare_push_to_dev_is_blocked():
    assert protected_reason("git --bare push origin dev", "feature/x") != ""

def test_no_replace_objects_commit_on_main_is_blocked():
    assert protected_reason("git --no-replace-objects commit -m bad", "main") != ""

def test_no_pager_commit_on_feature_is_allowed():
    assert protected_reason("git --no-pager commit -m ok", "feature/x") == ""

def test_no_pager_push_to_feature_is_allowed():
    assert protected_reason("git --no-pager push origin feature/x", "feature/y") == ""


# ---------------------------------------------------------------------------
# push guard: +<refspec> force prefix, --all, --mirror, --branches bypasses
# ---------------------------------------------------------------------------

def test_push_force_refspec_plus_main_is_blocked():
    assert protected_reason("git push origin +main", "feature/x") != ""

def test_push_force_refspec_plus_dev_is_blocked():
    assert protected_reason("git push origin +dev", "feature/x") != ""

def test_push_force_refspec_plus_refs_heads_main_is_blocked():
    assert protected_reason("git push origin +refs/heads/main", "feature/x") != ""

def test_push_all_is_blocked():
    assert protected_reason("git push --all origin", "feature/x") != ""

def test_push_all_short_form_is_blocked():
    assert protected_reason("git push --all", "feature/x") != ""

def test_push_mirror_is_blocked():
    assert protected_reason("git push --mirror origin", "feature/x") != ""

def test_push_branches_is_blocked():
    assert protected_reason("git push --branches origin", "feature/x") != ""

def test_push_force_refspec_plus_feature_is_allowed():
    assert protected_reason("git push origin +feature/my-branch", "feature/x") == ""

def test_push_force_refspec_plus_non_protected_is_allowed():
    assert protected_reason("git push origin +staging", "feature/x") == ""
