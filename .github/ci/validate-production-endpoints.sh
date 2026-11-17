#!/usr/bin/env bash
# Validates the public endpoint configuration used by CI builds and release publishing.
#
# Reads PRIMARY_SERVERS_URL, FALLBACK_SERVERS_URL and (optionally) VERSIONS_API_BASE_URL from the
# environment. Only public, non-secret URLs are read or printed.
#
# Usage: validate-production-endpoints.sh [--mode strict|warn] [--require-versions-api]
#   strict (default): any violation fails the step.
#   warn:             violations are reported as warnings (PR and integration builds).
set -uo pipefail

MODE="strict"
REQUIRE_VERSIONS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    --require-versions-api) REQUIRE_VERSIONS=1; shift ;;
    *) echo "::error::Unknown argument: $1"; exit 2 ;;
  esac
done
if [[ "$MODE" != "strict" && "$MODE" != "warn" ]]; then
  echo "::error::--mode must be strict or warn"
  exit 2
fi

# Retired public hostnames that must never be embedded in a build or used to publish metadata.
RETIRED_HOSTS=("openvpngateclient.azurewebsites.net")
# The canonical public origin. Backend failover between Azure and RackNerd happens behind it.
CANONICAL_HOST="openvpngateclient.com"

failures=0
report() {
  if [[ "$MODE" == "strict" ]]; then
    echo "::error::$1"
  else
    echo "::warning::$1"
  fi
  failures=$((failures + 1))
}

# Prints the lowercase host of an https URL, or nothing when the URL is not a plain https origin/URL.
https_host() {
  local url="$1" rest authority
  [[ "$url" == https://* ]] || return 1
  rest="${url#https://}"
  authority="${rest%%[/?#]*}"
  [[ "$authority" == *@* ]] && return 1
  authority="${authority%%:*}"
  [[ -n "$authority" ]] || return 1
  printf '%s' "$authority" | tr 'A-Z' 'a-z'
}

is_retired() {
  local host="$1" retired
  for retired in "${RETIRED_HOSTS[@]}"; do
    [[ "$host" == "$retired" ]] && return 0
  done
  return 1
}

check_backend_url() {
  local name="$1" value="${2:-}" host
  if [[ -z "$value" ]]; then
    report "$name is not set."
    return
  fi
  if ! host="$(https_host "$value")"; then
    report "$name must be an https URL without credentials."
    return
  fi
  if is_retired "$host"; then
    report "$name points to the retired host '$host'. Use the canonical origin https://$CANONICAL_HOST."
    return
  fi
  if [[ "$host" != "$CANONICAL_HOST" ]]; then
    report "$name host '$host' is not the canonical production host '$CANONICAL_HOST'."
    return
  fi
  local rest="${value#https://}" path
  path="${rest#"${rest%%[/?#]*}"}"
  if [[ "$rest" != *[/?#]* ]]; then path=""; fi
  if [[ -n "${path%/}" ]]; then
    report "$name must be the bare origin (no path, query or fragment)."
    return
  fi
  echo "$name OK: https://$host"
}

check_backend_url "PRIMARY_SERVERS_URL" "${PRIMARY_SERVERS_URL:-}"

if [[ -n "${VERSIONS_API_BASE_URL:-}" || "$REQUIRE_VERSIONS" == "1" ]]; then
  check_backend_url "VERSIONS_API_BASE_URL" "${VERSIONS_API_BASE_URL:-}"
  if [[ -n "${PRIMARY_SERVERS_URL:-}" && -n "${VERSIONS_API_BASE_URL:-}" ]]; then
    if [[ "${PRIMARY_SERVERS_URL%/}" != "${VERSIONS_API_BASE_URL%/}" ]]; then
      report "PRIMARY_SERVERS_URL and VERSIONS_API_BASE_URL must use the same origin."
    fi
  fi
fi

# The fallback is the independent third-party VPN Gate CSV feed. It must stay independent of the
# backend and must never be a retired or canonical backend host.
fallback="${FALLBACK_SERVERS_URL:-}"
if [[ -z "$fallback" ]]; then
  report "FALLBACK_SERVERS_URL is not set."
elif fallback_host="$(https_host "$fallback")"; then
  if is_retired "$fallback_host" || [[ "$fallback_host" == "$CANONICAL_HOST" ]]; then
    report "FALLBACK_SERVERS_URL must remain the independent VPN Gate CSV source, not '$fallback_host'."
  else
    echo "FALLBACK_SERVERS_URL OK: https://$fallback_host"
  fi
else
  report "FALLBACK_SERVERS_URL must be an https URL without credentials."
fi

if [[ "$failures" -gt 0 ]]; then
  if [[ "$MODE" == "strict" ]]; then
    echo "Endpoint validation failed with $failures problem(s)."
    exit 1
  fi
  echo "Endpoint validation reported $failures warning(s) (warn mode)."
fi
exit 0
