#!/usr/bin/env bash
# Azure only: checks out the PR head commit (when PR_HEAD_SHA is set, matching the GitHub PR workflow which builds the
# head, not the merge commit) and initializes the private submodules after a checkout that did not persist credentials.
# Env: GH_PAT (from Key Vault), PR_HEAD_SHA (optional).
set -euo pipefail
: "${GH_PAT:?GH_PAT is required}"
auth=$(printf 'x-access-token:%s' "$GH_PAT" | base64 -w0)
echo "##vso[task.setsecret]${auth}"
# The header goes through git's environment config so it never appears on a command line.
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="http.https://github.com/.extraheader"
export GIT_CONFIG_VALUE_0="AUTHORIZATION: basic ${auth}"
if [ -n "${PR_HEAD_SHA:-}" ]; then
  git checkout --detach "$PR_HEAD_SHA"
fi
git submodule update --init --recursive
