#!/usr/bin/env bash
# Regression tests for the CI endpoint validation and artifact verification scripts.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATE="$DIR/validate-production-endpoints.sh"
VERIFY="$DIR/verify-artifact-endpoints.sh"
CSV="https://www.vpngate.net/api/iphone/"
CANON="https://openvpngateclient.com"
AZURE="https://openvpngateclient.azurewebsites.net"

pass=0
fail=0
expect() {
  local name="$1" want="$2"; shift 2
  "$@" >/dev/null 2>&1
  local rc=$?
  if [[ "$rc" == "$want" ]]; then
    pass=$((pass + 1)); echo "PASS $name"
  else
    fail=$((fail + 1)); echo "FAIL $name (rc=$rc, expected $want)"
  fi
}
run_validate() { env -i PATH="$PATH" "$@" bash "$VALIDATE" $VALIDATE_ARGS; }

VALIDATE_ARGS="--require-versions-api"
expect "canonical config passes" 0 run_validate PRIMARY_SERVERS_URL=$CANON VERSIONS_API_BASE_URL=$CANON/ FALLBACK_SERVERS_URL=$CSV
expect "azure primary fails" 1 run_validate PRIMARY_SERVERS_URL=$AZURE VERSIONS_API_BASE_URL=$CANON FALLBACK_SERVERS_URL=$CSV
expect "azure versions base fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON VERSIONS_API_BASE_URL=$AZURE FALLBACK_SERVERS_URL=$CSV
expect "missing versions base fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON FALLBACK_SERVERS_URL=$CSV
expect "origin mismatch fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON VERSIONS_API_BASE_URL=https://example.com FALLBACK_SERVERS_URL=$CSV
expect "canonical fallback fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON VERSIONS_API_BASE_URL=$CANON FALLBACK_SERVERS_URL=$CANON/x
expect "azure fallback fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON VERSIONS_API_BASE_URL=$CANON FALLBACK_SERVERS_URL=$AZURE/x

VALIDATE_ARGS=""
expect "http primary fails" 1 run_validate PRIMARY_SERVERS_URL=http://openvpngateclient.com FALLBACK_SERVERS_URL=$CSV
expect "credentials in primary fail" 1 run_validate PRIMARY_SERVERS_URL=https://u:p@openvpngateclient.com FALLBACK_SERVERS_URL=$CSV
expect "path in primary fails" 1 run_validate PRIMARY_SERVERS_URL=$CANON/api FALLBACK_SERVERS_URL=$CSV
expect "uppercase host passes" 0 run_validate PRIMARY_SERVERS_URL=https://OpenVPNGateClient.com FALLBACK_SERVERS_URL=$CSV
expect "trailing slash passes" 0 run_validate PRIMARY_SERVERS_URL=$CANON/ FALLBACK_SERVERS_URL=$CSV

VALIDATE_ARGS="--mode warn"
expect "warn mode never fails" 0 run_validate PRIMARY_SERVERS_URL=$AZURE FALLBACK_SERVERS_URL=$CSV

# Artifact verification against synthetic dex payloads.
PY="$(command -v python3 || command -v python || true)"
if command -v zip >/dev/null 2>&1 || [[ -n "$PY" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  mk() { # name payload
    local d="$tmp/$1.d"; mkdir -p "$d"
    printf 'dex\n035\0\0%s\0\0' "$2" > "$d/classes.dex"
    if command -v zip >/dev/null 2>&1; then
      (cd "$d" && zip -q "../$1.apk" classes.dex)
    else
      "$PY" -I -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"w",zipfile.ZIP_DEFLATED); z.write(sys.argv[2],"classes.dex"); z.close()' "$tmp/$1.apk" "$d/classes.dex"
    fi
  }
  mk good "$CANON"
  mk azure "$AZURE"
  mk both "$CANON $AZURE"
  mk none "https://example.com"
  expect "artifact with canonical origin passes" 0 bash "$VERIFY" "$CANON" "$tmp/good.apk"
  expect "artifact with retired host fails" 1 bash "$VERIFY" "$CANON" "$tmp/azure.apk"
  expect "artifact with both fails" 1 bash "$VERIFY" "$CANON" "$tmp/both.apk"
  expect "artifact without origin fails" 1 bash "$VERIFY" "$CANON" "$tmp/none.apk"
  expect "missing artifact fails" 1 bash "$VERIFY" "$CANON" "$tmp/absent.apk"
else
  echo "SKIP artifact tests: zip not available"
fi

echo "passed=$pass failed=$fail"
[[ "$fail" == "0" ]]
