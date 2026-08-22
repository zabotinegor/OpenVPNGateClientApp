#!/bin/sh
# Tests for .githooks/pre-push - the protected-branch push guard.
#
# Focus: the dev-recreation archive-existence requirement (round 6 fix).
# Round 6 found the archive-existence check was confined to the deletion
# path; a push recreating dev from scratch (remote dev absent) passed with
# only a tip-match check against origin/main, even with no archive/archive-
# dev-* ref anywhere - turning the narrow release-flow exception into
# blanket permission to recreate dev whenever it happened to be missing.
#
# Fixtures are a throwaway git repo in a temp dir with fabricated
# refs/remotes/origin/* refs (no real remote/push needed - the hook only
# ever reads local refs and stdin). Run: sh test-pre-push-dev-archive-guard.sh
# Exits 0 on all pass, 1 on any failure.

set -eu

pass=0
fail=0

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
hook="$repo_root/.githooks/pre-push"

if [ ! -f "$hook" ]; then
  echo "FAIL  .githooks/pre-push not found at $hook"
  exit 1
fi

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT

repo="$sandbox/repo"
mkdir -p "$repo"
git -C "$repo" -c init.defaultBranch=main init -q
git -C "$repo" -c user.email=t@example.com -c user.name=T commit -q --allow-empty -m main1
main_sha=$(git -C "$repo" rev-parse HEAD)

git -C "$repo" checkout -q -b dev-old
git -C "$repo" -c user.email=t@example.com -c user.name=T commit -q --allow-empty -m dev1
dev_old_sha=$(git -C "$repo" rev-parse HEAD)

git -C "$repo" checkout -q -b dev-other
git -C "$repo" -c user.email=t@example.com -c user.name=T commit -q --allow-empty -m dev2
dev_other_sha=$(git -C "$repo" rev-parse HEAD)

git -C "$repo" checkout -q main
git -C "$repo" update-ref refs/remotes/origin/main "$main_sha"

zero=0000000000000000000000000000000000000000

run_hook() {
  # Prints the hook's exit code. Written with '|| rc=$?' so a non-zero hook
  # exit does not itself trip this script's `set -e`.
  rc=0
  printf '%s\n' "$1" | sh "$hook" >"$sandbox/out" 2>&1 || rc=$?
  echo "$rc"
}

reset_dev_and_archive_refs() {
  git -C "$repo" update-ref -d refs/remotes/origin/dev >/dev/null 2>&1 || true
  for r in $(git -C "$repo" for-each-ref --format='%(refname)' 'refs/remotes/origin/archive/archive-dev-*'); do
    git -C "$repo" update-ref -d "$r" >/dev/null 2>&1 || true
  done
}

assert_blocked() {
  label="$1"
  line="$2"
  rc=$(cd "$repo" && run_hook "$line")
  if [ "$rc" != "0" ]; then
    echo "  PASS  $label"
    pass=$((pass + 1))
  else
    echo "  FAIL  $label (expected block, hook allowed it)"
    fail=$((fail + 1))
  fi
}

assert_allowed() {
  label="$1"
  line="$2"
  rc=$(cd "$repo" && run_hook "$line")
  if [ "$rc" = "0" ]; then
    echo "  PASS  $label"
    pass=$((pass + 1))
  else
    echo "  FAIL  $label (expected allow, hook blocked it: $(cat "$sandbox/out"))"
    fail=$((fail + 1))
  fi
}

echo "== Regression: direct push to main is still blocked =="
reset_dev_and_archive_refs
assert_blocked "push to refs/heads/main is blocked" \
  "refs/heads/main $main_sha refs/heads/main $main_sha"

echo "== Regression: in-place update to dev is still blocked =="
reset_dev_and_archive_refs
git -C "$repo" update-ref refs/remotes/origin/dev "$dev_old_sha"
assert_blocked "in-place non-zero-to-non-zero update to dev is blocked" \
  "refs/heads/dev $dev_other_sha refs/heads/dev $dev_old_sha"

echo "== Regression: deleting dev with an archive at its tip is allowed =="
reset_dev_and_archive_refs
git -C "$repo" update-ref refs/remotes/origin/dev "$dev_old_sha"
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-08-01 "$dev_old_sha"
assert_allowed "delete dev allowed when an archive ref matches the current dev tip" \
  "refs/heads/dev $zero refs/heads/dev $dev_old_sha"

echo "== Regression: deleting dev without a matching archive is blocked =="
reset_dev_and_archive_refs
git -C "$repo" update-ref refs/remotes/origin/dev "$dev_old_sha"
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-08-01 "$dev_other_sha"
assert_blocked "delete dev blocked when no archive ref matches the current dev tip" \
  "refs/heads/dev $zero refs/heads/dev $dev_old_sha"

echo "== Fix: recreating dev with zero archive refs anywhere is now blocked =="
reset_dev_and_archive_refs
assert_blocked "recreate dev (remote absent) is blocked when no archive-dev-* ref exists at all" \
  "refs/heads/dev $main_sha refs/heads/dev $zero"

echo "== Fix: recreating dev is allowed once an archive exists and the tip matches main =="
reset_dev_and_archive_refs
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-07-01 "$dev_old_sha"
assert_allowed "recreate dev (remote absent) is allowed once any archive-dev-* ref exists and local tip matches origin/main" \
  "refs/heads/dev $main_sha refs/heads/dev $zero"

echo "== Regression: recreating dev still requires the tip to match origin/main =="
reset_dev_and_archive_refs
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-07-01 "$dev_old_sha"
assert_blocked "recreate dev is still blocked when local tip != origin/main, even with an archive present" \
  "refs/heads/dev $dev_other_sha refs/heads/dev $zero"

echo "== Round 7 fix: dev-deletion archive check uses the stdin remote_sha, not a stale local tracking ref =="

# Round 7 P1: the archive-existence check for deleting dev used to read the
# LOCAL refs/remotes/origin/dev tracking ref (only refreshed on fetch) to
# find "the current dev tip", instead of the remote_sha the hook actually
# receives on stdin for the ref being deleted (per githooks(5): "<local ref>
# <local sha> <remote ref> <remote sha>"). Reproduce the exact bug shape: the
# local origin/dev tracking ref is stale and still points at an OLD commit
# that IS archived, while stdin reports the REAL current remote dev tip is a
# NEWER commit that has NO archive - deleting that newer, unarchived tip must
# still be blocked even though the stale local ref alone would look archived.
reset_dev_and_archive_refs
git -C "$repo" checkout -q dev-old
git -C "$repo" -c user.email=t@example.com -c user.name=T commit -q --allow-empty -m dev-newer-unarchived
dev_newer_sha=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" checkout -q main
# Stale local tracking ref: still points at the OLD (archived) commit, not
# the newer unarchived tip the "remote" actually has.
git -C "$repo" update-ref refs/remotes/origin/dev "$dev_old_sha"
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-08-01 "$dev_old_sha"
assert_blocked "deleting dev is blocked when stdin's remote_sha (newer, unarchived) has no archive, even though the stale local origin/dev tracking ref (older) does" \
  "refs/heads/dev $zero refs/heads/dev $dev_newer_sha"

echo "== Round 7 fix: dev-deletion archive check still allows deletion when remote_sha itself is archived =="
reset_dev_and_archive_refs
# Local tracking ref deliberately left stale/absent (never updated) to prove
# the decision now comes from stdin's remote_sha alone, not the local cache.
git -C "$repo" update-ref refs/remotes/origin/archive/archive-dev-2026-08-01 "$dev_old_sha"
assert_allowed "deleting dev is allowed when stdin's remote_sha matches an archive ref, independent of the (absent/stale) local origin/dev tracking ref" \
  "refs/heads/dev $zero refs/heads/dev $dev_old_sha"

echo "== Round 7 fix: dev-related remote lookups use the actual remote argument, not a hardcoded origin assumption =="
reset_dev_and_archive_refs
git -C "$repo" update-ref "refs/remotes/upstream/main" "$main_sha"
git -C "$repo" update-ref "refs/remotes/upstream/archive/archive-dev-2026-08-01" "$dev_old_sha"
run_hook_remote() {
  # Same as run_hook but passes $1/$2 (remote name/URL) the way git actually
  # invokes pre-push, so remote_name resolves to "upstream" instead of the
  # "${1:-origin}" default.
  rc=0
  printf '%s\n' "$2" | sh "$hook" "$1" "https://example.invalid/upstream.git" >"$sandbox/out" 2>&1 || rc=$?
  echo "$rc"
}
rc=$(cd "$repo" && run_hook_remote "upstream" "refs/heads/dev $zero refs/heads/dev $dev_old_sha")
if [ "$rc" = "0" ]; then
  echo "  PASS  delete dev against remote 'upstream' is allowed using upstream/archive/* refs, not origin/*"
  pass=$((pass + 1))
else
  echo "  FAIL  delete dev against remote 'upstream' is allowed using upstream/archive/* refs, not origin/* (expected allow, hook blocked it: $(cat "$sandbox/out"))"
  fail=$((fail + 1))
fi
rc=$(cd "$repo" && run_hook_remote "upstream" "refs/heads/dev $main_sha refs/heads/dev $zero")
if [ "$rc" = "0" ]; then
  echo "  PASS  recreate dev against remote 'upstream' is allowed using upstream/main and upstream/archive/*"
  pass=$((pass + 1))
else
  echo "  FAIL  recreate dev against remote 'upstream' is allowed using upstream/main and upstream/archive/* (expected allow, hook blocked it: $(cat "$sandbox/out"))"
  fail=$((fail + 1))
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo "Results: $pass passed, 0 failed"
  exit 0
fi
echo "Results: $pass passed, $fail failed"
exit 1
