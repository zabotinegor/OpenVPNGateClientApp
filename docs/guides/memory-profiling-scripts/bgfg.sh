#!/bin/bash
# Coordinates (drawer, buttons) are for a 1080x2400 phone running the Russian-locale debug build; adjust per device (see ../android-memory-profiling.md).
# usage: bgfg.sh <serial> <label> <cycles>  (HOME -> relaunch; SSE foreground-only connection)
S=$1; L=$2; N=${3:-10}
PKG=com.yahorzabotsin.openvpnclientgate
OUT=${OUT:-./mem-out}; mkdir -p "$OUT"
snap() {
  PID=$(adb -s $S shell pidof $PKG | awk '{print $1}')
  adb -s $S shell run-as $PKG kill -10 $PID >/dev/null 2>&1
  sleep 3
  adb -s $S shell dumpsys meminfo $PKG > $OUT/$L-$1.txt
  echo "$1: $(grep -E 'Activities:|Views:|ViewRootImpl:' $OUT/$L-$1.txt | tr -s ' ' | tr '\n' ' ') $(grep -E 'TOTAL PSS|Java Heap:' $OUT/$L-$1.txt | head -2 | tr -s ' ' | tr '\n' ' ')"
}
adb -s $S logcat -c
snap start
for i in $(seq 1 $N); do
  adb -s $S shell input keyevent HOME; sleep 3
  adb -s $S shell am start -n $PKG/.mobile.SplashActivity >/dev/null; sleep 5
done
snap end
adb -s $S logcat -d | grep -i "SseServerEventsClient" > $OUT/$L-sse.log
echo "SSE log lines: $(wc -l < $OUT/$L-sse.log)"
cut -c19-140 $OUT/$L-sse.log | sed -E 's/[0-9]+/N/g' | sort | uniq -c | sort -rn | head -8
