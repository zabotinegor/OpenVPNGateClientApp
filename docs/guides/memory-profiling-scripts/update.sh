#!/bin/bash
# Defaults fit a 1080x2400 phone (Russian-locale debug build); override with env vars, see ../android-memory-profiling.md "Device parameters".
# usage: update.sh <serial> <label> <iterations> <mode: complete|rotate>
S=$1; L=$2; N=${3:-5}; MODE=${4:-complete}
PKG=com.yahorzabotsin.openvpnclientgate
OUT=${OUT:-./mem-out}; mkdir -p "$OUT"
snap() {
  PID=$(adb -s $S shell pidof $PKG | awk '{print $1}')
  adb -s $S shell run-as $PKG kill -10 $PID >/dev/null 2>&1
  sleep 3
  adb -s $S shell dumpsys meminfo $PKG > $OUT/$L-$1.txt
  echo "$1: $(grep -E 'Activities:|Views:|ViewRootImpl:|AppContexts:' $OUT/$L-$1.txt | tr -s ' ' | tr '\n' ' ') $(grep -E 'TOTAL PSS|Java Heap:' $OUT/$L-$1.txt | head -2 | tr -s ' ' | tr '\n' ' ')"
}
top() { adb -s $S shell dumpsys activity activities | grep -m1 "${RESUMED:- topResumedActivity}" | grep -o "[A-Za-z]*Activity" | tail -1; }
gomain() { for k in 1 2 3 4 5; do [ "$(top)" = "MainActivity" ] && return; adb -s $S shell input keyevent BACK; sleep 1.2; done; }
drawer_open() { adb -s $S exec-out uiautomator dump /dev/tty | grep -q 'design_menu_item_text'; }
trap 'adb -s $S shell settings put system accelerometer_rotation 1' EXIT
adb -s $S shell settings put system accelerometer_rotation 0
adb -s $S shell settings put system user_rotation 0
gomain
snap start
for i in $(seq 1 $N); do
  adb -s $S shell "run-as $PKG sh -c 'rm -f cache/updates/*'"
  gomain
  drawer_open || { adb -s $S shell input tap ${HAM:-63 206}; sleep 1; }
  adb -s $S shell input tap ${ITEM_X:-300} ${UPDATE_ITEM_Y:-805}; sleep 4
  adb -s $S shell input tap ${UPDATE_BTN:-888 1378}
  if [ "$MODE" = "rotate" ]; then
    sleep 1; adb -s $S shell settings put system user_rotation 1; sleep 2
    adb -s $S shell settings put system user_rotation 0; sleep 2
  fi
  # wait for installer or completion
  for k in $(seq 1 40); do [ "$(top)" = "PackageInstallerActivity" ] && break; sleep 1; done
  echo "iter $i end-top=$(top) apk=$(adb -s $S shell run-as $PKG ls cache/updates | tr '\n' ' ')"
  gomain
  snap iter$i
done
snap end
