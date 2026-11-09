#!/bin/bash
# Defaults fit a 1080x2400 phone (Russian-locale debug build); override with env vars, see ../android-memory-profiling.md "Device parameters".
# usage: cycle.sh <serial> <label> <cycles>
# Mobile navigation cycles: drawer -> each destination -> back to Main. Snapshot meminfo post-GC.
S=$1; L=$2; N=${3:-10}
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
gomain() { for k in 1 2 3 4; do [ "$(top)" = "MainActivity" ] && return; adb -s $S shell input keyevent BACK; sleep 1.2; done; }
drawer_open() { adb -s $S exec-out uiautomator dump /dev/tty | grep -q 'design_menu_item_text'; }
tapitem() { gomain; drawer_open || { adb -s $S shell input tap ${HAM:-63 206}; sleep 1; }; adb -s $S shell input tap ${ITEM_X:-300} $1; sleep 2.5; echo -n "$(top) "; gomain; }
gomain
snap start
for i in $(seq 1 $N); do
  for y in ${ITEM_YS:-175 301 427 553 679}; do tapitem $y; done
  echo
  if [ $((i % 5)) -eq 0 ]; then snap cycle$i; fi
done
snap end
