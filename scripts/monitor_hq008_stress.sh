#!/usr/bin/env bash
set -u

ADB_BIN="${ADB:-adb}"
SERIAL="${ADB_SERIAL:-192.168.0.110:36783}"
PACKAGE="${HQ008_PACKAGE:-com.google.android.adffa}"
ACTIVITY="${HQ008_ACTIVITY:-com.google.android.adffa/com.smart.android.ad_app.Hq008SdkStressActivity}"
INTERVAL="${MONITOR_INTERVAL_SECONDS:-2}"
OUT_DIR="${HQ008_STRESS_OUT:-artifacts/hq008-stress/$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$OUT_DIR"
ADB_CMD=("$ADB_BIN" -s "$SERIAL")

if ! "${ADB_CMD[@]}" get-state >/dev/null 2>&1; then
  echo "ADB device unavailable: $SERIAL" >&2
  exit 1
fi

echo "collecting HQ008 stress data from $SERIAL"
echo "output: $OUT_DIR"
"${ADB_CMD[@]}" logcat -c || true
"${ADB_CMD[@]}" shell am force-stop "$PACKAGE" || true
"${ADB_CMD[@]}" shell am start -n "$ACTIVITY" >"$OUT_DIR/launch.txt" 2>&1 || true

"${ADB_CMD[@]}" logcat -v threadtime -s Hq008SdkStress:V AndroidRuntime:E '*:S' \
  >"$OUT_DIR/logcat.txt" 2>&1 &
LOGCAT_PID=$!

cleanup() {
  trap - EXIT INT TERM
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
  echo "stopped; artifacts in $OUT_DIR"
  exit 0
}
trap cleanup EXIT INT TERM

last_heartbeat=0
heartbeat_count=0
stall_count=0
echo "epoch,pid,fd,threads,rss" >"$OUT_DIR/proc.csv"
while true; do
  now=$(date +%s)
  pid=$("${ADB_CMD[@]}" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  if [[ -n "$pid" ]]; then
    fd_count=$("${ADB_CMD[@]}" shell "run-as $PACKAGE sh -c 'ls /proc/$pid/fd 2>/dev/null | wc -l'" | tr -d '\r ')
    thread_count=$("${ADB_CMD[@]}" shell "run-as $PACKAGE sh -c 'ls /proc/$pid/task 2>/dev/null | wc -l'" | tr -d '\r ')
    rss_kb=$("${ADB_CMD[@]}" shell "awk '/^VmRSS:/ {print \$2}' /proc/$pid/status 2>/dev/null" | tr -d '\r ')
    echo "$now,$pid,$fd_count,$thread_count,$rss_kb" >>"$OUT_DIR/proc.csv"
    if (( now % 30 == 0 )); then
      "${ADB_CMD[@]}" shell dumpsys meminfo "$PACKAGE" >"$OUT_DIR/meminfo-$now.txt" 2>&1 || true
      "${ADB_CMD[@]}" exec-out screencap -p >"$OUT_DIR/screen-$now.png" 2>/dev/null || true
    fi
  fi

  if [[ -f "$OUT_DIR/logcat.txt" ]]; then
    current_count=$(rg -c 'HEARTBEAT seq=' "$OUT_DIR/logcat.txt" || true)
    current_count=${current_count:-0}
    if (( current_count > heartbeat_count )); then
      heartbeat_count=$current_count
      last_heartbeat=$now
    fi
  fi
  if [[ -n "$pid" ]] && (( last_heartbeat > 0 && now - last_heartbeat > 8 )); then
    stall_count=$((stall_count + 1))
    if (( stall_count == 1 )); then
      echo "possible main-thread stall detected at $now (last heartbeat $last_heartbeat)" | tee -a "$OUT_DIR/monitor.log"
      "${ADB_CMD[@]}" shell kill -3 "$pid" >>"$OUT_DIR/monitor.log" 2>&1 || true
      "${ADB_CMD[@]}" shell debuggerd -b "$pid" >"$OUT_DIR/debuggerd-$now.txt" 2>&1 || true
      "${ADB_CMD[@]}" shell dumpsys meminfo "$PACKAGE" >"$OUT_DIR/meminfo-stall-$now.txt" 2>&1 || true
    fi
  else
    stall_count=0
  fi
  sleep "$INTERVAL"
done
