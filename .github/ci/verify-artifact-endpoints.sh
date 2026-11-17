#!/usr/bin/env bash
# Verifies that built release artifacts embed the canonical API origin and no retired host.
#
# Usage: verify-artifact-endpoints.sh <expected-origin> <artifact> [<artifact>...]
# Each artifact is an APK or AAB. The dex files inside are scanned for the expected origin string
# and for the retired public hostnames.
set -uo pipefail

if [[ $# -lt 2 ]]; then
  echo "::error::usage: verify-artifact-endpoints.sh <expected-origin> <artifact>..."
  exit 2
fi

EXPECTED="${1%/}"
shift
RETIRED_HOSTS=("openvpngateclient.azurewebsites.net")

if ! command -v unzip >/dev/null 2>&1; then
  echo "::error::unzip is required to inspect release artifacts."
  exit 2
fi

failed=0
for artifact in "$@"; do
  if [[ ! -f "$artifact" ]]; then
    echo "::error::Artifact not found: $artifact"
    failed=1
    continue
  fi

  dex_entries="$(unzip -Z1 "$artifact" | grep -E '(^|/)classes[0-9]*\.dex$' || true)"
  if [[ -z "$dex_entries" ]]; then
    echo "::error::No dex files found in $(basename "$artifact")."
    failed=1
    continue
  fi

  found_expected=0
  found_retired=""
  while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    content="$(unzip -p "$artifact" "$entry" | tr -c '[:print:]' '\n')"
    if grep -qF "$EXPECTED" <<<"$content"; then
      found_expected=1
    fi
    for retired in "${RETIRED_HOSTS[@]}"; do
      if grep -qF "$retired" <<<"$content"; then
        found_retired="$retired"
      fi
    done
  done <<<"$dex_entries"

  name="$(basename "$artifact")"
  if [[ -n "$found_retired" ]]; then
    echo "::error::$name embeds the retired host '$found_retired'."
    failed=1
  elif [[ "$found_expected" != "1" ]]; then
    echo "::error::$name does not embed the expected API origin $EXPECTED."
    failed=1
  else
    echo "$name embeds $EXPECTED and no retired host."
  fi
done

exit "$failed"
