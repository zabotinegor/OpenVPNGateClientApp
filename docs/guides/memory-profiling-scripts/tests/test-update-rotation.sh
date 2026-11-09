#!/usr/bin/env bash
# Offline regression: run the update profiler with mocked ADB and no iterations.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/settings" "$WORK/output"

cat > "$WORK/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-s" ]]; then shift 2; fi
if [[ "${1:-}" == "shell" && "${2:-}" == "settings" ]]; then
  case "${3:-}" in
    get) cat "$FAKE_SETTINGS_DIR/$5";;
    put) printf '%s\n' "$6" > "$FAKE_SETTINGS_DIR/$5";;
  esac
elif [[ "${1:-}" == "shell" && "${2:-}" == "pidof" ]]; then
  echo 123
elif [[ "${1:-}" == "shell" && "${2:-}" == "dumpsys" && "${3:-}" == "meminfo" ]]; then
  printf 'Activities: 1\nViews: 1\nJava Heap: 1\nTOTAL PSS: 1\n'
fi
MOCK_ADB
printf '#!/bin/bash\nexit 0\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/adb" "$WORK/bin/sleep"

run_update() {
  PATH="$WORK/bin:$PATH" FAKE_SETTINGS_DIR="$WORK/settings" OUT="$WORK/output" \
    bash "$SCRIPT_DIR/update.sh" fake-device rotation-test 0 complete
}
for auto in 0 1; do
  for rotation in 0 1 2 3; do
    printf '%s\n' "$auto" > "$WORK/settings/accelerometer_rotation"
    printf '%s\n' "$rotation" > "$WORK/settings/user_rotation"
    run_update > /dev/null
    [[ $(cat "$WORK/settings/accelerometer_rotation") == "$auto" ]]
    [[ $(cat "$WORK/settings/user_rotation") == "$rotation" ]]
  done
done
printf 'null\n' > "$WORK/settings/accelerometer_rotation"
printf '2\n' > "$WORK/settings/user_rotation"
if run_update > /dev/null 2>&1; then
  echo "Invalid initial rotation setting should abort" >&2
  exit 1
fi
[[ $(cat "$WORK/settings/accelerometer_rotation") == "null" ]]
[[ $(cat "$WORK/settings/user_rotation") == "2" ]]
echo "PASS: all 8 initial rotation combinations restored; invalid settings left untouched"
