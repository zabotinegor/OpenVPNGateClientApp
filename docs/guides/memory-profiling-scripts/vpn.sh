#!/bin/bash
# Coordinates (drawer, buttons) are for a 1080x2400 phone running the Russian-locale debug build; adjust per device (see ../android-memory-profiling.md).
# usage: vpn.sh <serial> <label> <cycles>   (connect attempt -> disconnect, main + :openvpn processes)
S=$1; L=$2; N=${3:-10}
PKG=com.yahorzabotsin.openvpnclientgate
OUT=${OUT:-./mem-out}; mkdir -p "$OUT"
snap() {
  for P in $PKG $PKG:openvpn; do
    PID=$(adb -s $S shell pgrep -f "^$P\$" | head -1)
    [ -n "$PID" ] && adb -s $S shell run-as $PKG kill -10 $PID >/dev/null 2>&1
  done
  sleep 3
  for P in $PKG $PKG:openvpn; do
    T=$(echo $P | tr ':' '_')
    adb -s $S shell dumpsys meminfo $P > $OUT/$L-$1-$T.txt
    echo "$1 [$P]: $(grep -E 'Activities:|Views:|ViewRootImpl:|AppContexts:|Services:' $OUT/$L-$1-$T.txt | tr -s ' ' | tr '\n' ' ') $(grep -E 'TOTAL PSS|Java Heap:' $OUT/$L-$1-$T.txt | head -2 | tr -s ' ' | tr '\n' ' ')"
  done
}
snap start
for i in $(seq 1 $N); do
  adb -s $S shell input tap 540 2270; sleep 8
  adb -s $S shell input tap 540 2270; sleep 5
  if [ $((i % 5)) -eq 0 ]; then snap cycle$i; fi
done
snap end
echo "service-destroyed count: $(adb -s $S logcat -d | grep -c 'Service destroyed and listener removed')"
