#!/usr/bin/env bash
# Validates the public endpoint configuration used by CI builds and release publishing.
#
# Reads PRIMARY_SERVERS_URL, FALLBACK_SERVERS_URL and (optionally) VERSIONS_API_BASE_URL from the
# environment. Only public, non-secret URLs are read or printed.
#
# Usage: validate-production-endpoints.sh [--mode strict|warn] [--require-versions-api]
#   strict (default): any violation fails the step.
#   warn:             violations are reported as warnings (pull request builds).
set -uo pipefail

MODE="strict"
REQUIRE_VERSIONS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      if [[ $# -lt 2 ]]; then
        echo "::error::--mode needs a value (strict or warn)"
        exit 2
      fi
      MODE="$2"; shift 2 ;;
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
  authority="${authority%.}"
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

# Backend URLs must be a bare https origin on the canonical host: no port (not even an empty
# trailing ':'), credentials, path, query, fragment or any character outside a safe URL set.
SAFE_ORIGIN_RE='^https://[A-Za-z0-9.-]+/?$'

has_unsafe_chars() {
  case "$1" in
    *[[:space:]]*) return 0 ;;
    *'"'* | *"'"* | *'`'* | *'$'* | *';'* | *'\'*) return 0 ;;
  esac
  return 1
}

check_backend_url() {
  local name="$1" value="${2:-}" host shown
  if [[ -z "$value" ]]; then
    report "$name is not set."
    return
  fi
  printf -v shown '%q' "$value"
  if [[ ! "$value" =~ $SAFE_ORIGIN_RE ]]; then
    report "$name must be a bare https origin (https://host, no port, credentials, path, query or unusual characters), got: $shown"
    return
  fi
  host="${value#https://}"
  host="${host%/}"
  host="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"
  if is_retired "$host"; then
    report "$name points to the retired host '$host'. Use the canonical origin https://$CANONICAL_HOST."
    return
  fi
  if [[ "$host" != "$CANONICAL_HOST" ]]; then
    report "$name host '$host' is not the canonical production host '$CANONICAL_HOST'."
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
# backend and must never be a retired host, the canonical host or any subdomain of it.
fallback="${FALLBACK_SERVERS_URL:-}"
if [[ -z "$fallback" ]]; then
  report "FALLBACK_SERVERS_URL is not set."
elif has_unsafe_chars "$fallback"; then
  printf -v shown '%q' "$fallback"
  report "FALLBACK_SERVERS_URL contains unsafe characters, got: $shown"
elif fallback_host="$(https_host "$fallback")"; then
  if is_retired "$fallback_host" || [[ "$fallback_host" == "$CANONICAL_HOST" || "$fallback_host" == *".$CANONICAL_HOST" ]]; then
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
