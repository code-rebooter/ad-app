#!/usr/bin/env bash
set -u
set -o pipefail

ADB_BIN="${ADB:-adb}"
SERIAL="${ADB_SERIAL:-3GV9IVWMTQ}"
DEMO_PACKAGE="${DEMO_PACKAGE:-com.smart.android.adsdk.gamvast.demo}"
TVUI_PACKAGE="${TVUI_PACKAGE:-com.aispeech.tvui}"
STORE_PACKAGE="${STORE_PACKAGE:-com.chihihgs.store}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-2}"
SNAPSHOT_INTERVAL_SECONDS="${SNAPSHOT_INTERVAL_SECONDS:-30}"
STALL_SECONDS="${STALL_SECONDS:-8}"
HOST_COMMAND_TIMEOUT_SECONDS="${HOST_COMMAND_TIMEOUT_SECONDS:-6}"
STATE_HOME="${XDG_STATE_HOME:-$HOME/.local/state}"
OUT_DIR="${X88_COEXISTENCE_OUT:-$STATE_HOME/ad-app/x88-coexistence/$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
ADB_CMD=("$ADB_BIN" -s "$SERIAL")
LOGCAT_PID=""
DMESG_PID=""
HEARTBEAT_FOLLOWER_PID=""
LOGCAT_LAST_STARTED_EPOCH=0
DMESG_LAST_STARTED_EPOCH=0
DMESG_STREAM_SUPPORTED=1

collector_event() {
  local message="$1"
  printf '%s %s\n' "$(date '+%F %T')" "$message" \
    | tee -a "$OUT_DIR/collector-events.log" "$OUT_DIR/monitor.log"
}

start_logcat_stream() {
  "${ADB_CMD[@]}" logcat -T 1 -b all -v threadtime \
    >>"$OUT_DIR/logcat-all.txt" 2>&1 &
  LOGCAT_PID=$!
  LOGCAT_LAST_STARTED_EPOCH=$(date +%s)
  collector_event "logcat collector started pid=$LOGCAT_PID"
}

start_dmesg_stream() {
  "${ADB_CMD[@]}" shell dmesg -w >>"$OUT_DIR/dmesg-live.txt" 2>&1 &
  DMESG_PID=$!
  DMESG_LAST_STARTED_EPOCH=$(date +%s)
  collector_event "dmesg collector started pid=$DMESG_PID"
}

capture_dmesg_snapshot() {
  printf '\n===== host_epoch=%s host_time=%s =====\n' \
    "$(date +%s)" "$(date '+%F %T')" >>"$OUT_DIR/dmesg-live.txt"
  device_append_script "$OUT_DIR/dmesg-live.txt" 'dmesg | tail -n 300'
}

ensure_collectors_running() {
  if [[ -z "$LOGCAT_PID" ]] || ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
    if [[ -n "$LOGCAT_PID" ]]; then
      wait "$LOGCAT_PID" 2>/dev/null || true
      collector_event "logcat collector exited; restarting"
    fi
    start_logcat_stream
  fi
  if (( DMESG_STREAM_SUPPORTED == 1 )) \
    && { [[ -z "$DMESG_PID" ]] || ! kill -0 "$DMESG_PID" 2>/dev/null; }; then
    if [[ -n "$DMESG_PID" ]]; then
      wait "$DMESG_PID" 2>/dev/null || true
      if (( $(date +%s) - DMESG_LAST_STARTED_EPOCH < 5 )); then
        DMESG_STREAM_SUPPORTED=0
        DMESG_PID=""
        collector_event "dmesg stream unavailable; using periodic snapshots"
        return
      fi
      collector_event "dmesg collector exited; restarting"
    fi
    start_dmesg_stream
  fi
}

run_bounded() {
  local output_file="$1"
  local output_mode="$2"
  shift 2

  if [[ "$output_mode" == "append" ]]; then
    "$@" >>"$output_file" 2>&1 &
  else
    "$@" >"$output_file" 2>&1 &
  fi
  local command_pid=$!
  (
    sleep "$HOST_COMMAND_TIMEOUT_SECONDS"
    kill "$command_pid" 2>/dev/null || true
  ) &
  local timer_pid=$!

  wait "$command_pid" 2>/dev/null
  local status=$?
  kill "$timer_pid" 2>/dev/null || true
  wait "$timer_pid" 2>/dev/null || true
  return "$status"
}

device_capture() {
  local output_file="$1"
  shift
  run_bounded "$output_file" truncate \
    "${ADB_CMD[@]}" shell timeout -k 1 5 "$@" || true
}

device_append() {
  local output_file="$1"
  shift
  run_bounded "$output_file" append \
    "${ADB_CMD[@]}" shell timeout -k 1 5 "$@" || true
}

device_capture_script() {
  local output_file="$1"
  local script="$2"
  run_bounded "$output_file" truncate \
    "${ADB_CMD[@]}" shell "timeout -k 1 5 sh -c '$script'" || true
}

device_append_script() {
  local output_file="$1"
  local script="$2"
  run_bounded "$output_file" append \
    "${ADB_CMD[@]}" shell "timeout -k 1 5 sh -c '$script'" || true
}

process_pid() {
  local package_name="$1"
  local pid_file="$OUT_DIR/.pid-${package_name##*.}"
  run_bounded "$pid_file" truncate "${ADB_CMD[@]}" shell pidof "$package_name" || true
  tr -d '\r\n ' <"$pid_file" 2>/dev/null || true
}

capture_process_stacks() {
  local package_name="$1"
  local process_id="$2"
  local stall_dir="$3"
  local safe_name="${package_name//./_}"

  if [[ -z "$process_id" ]]; then
    printf '%s has no running process\n' "$package_name" \
      >"$stall_dir/${safe_name}-not-running.txt"
    return
  fi

  device_capture "$stall_dir/${safe_name}-java.txt" debuggerd -j "$process_id"
  device_capture "$stall_dir/${safe_name}-native.txt" debuggerd -b "$process_id"
  device_capture "$stall_dir/${safe_name}-meminfo.txt" dumpsys meminfo "$package_name"

  local thread_command
  thread_command="for task in /proc/$process_id/task/*; do "
  thread_command+="echo ==== \$(basename \$task) \$(cat \$task/comm 2>/dev/null) ====; "
  thread_command+="echo wchan=\$(cat \$task/wchan 2>/dev/null); "
  thread_command+="cat \$task/stack 2>/dev/null; done"
  device_capture_script "$stall_dir/${safe_name}-kernel-threads.txt" "$thread_command"
}

capture_stall() {
  local epoch="$1"
  local heartbeat_age="$2"
  local stall_dir="$OUT_DIR/stall-$epoch"
  mkdir -p "$stall_dir"
  printf 'host_epoch=%s\nheartbeat_age_seconds=%s\nserial=%s\n' \
    "$epoch" "$heartbeat_age" "$SERIAL" >"$stall_dir/trigger.txt"

  local demo_pid
  local tvui_pid
  local store_pid
  demo_pid="$(process_pid "$DEMO_PACKAGE")"
  tvui_pid="$(process_pid "$TVUI_PACKAGE")"
  store_pid="$(process_pid "$STORE_PACKAGE")"
  printf 'demo_pid=%s\ntvui_pid=%s\nstore_pid=%s\n' \
    "$demo_pid" "$tvui_pid" "$store_pid" \
    >>"$stall_dir/trigger.txt"

  device_capture "$stall_dir/dmesg.txt" dmesg -T
  device_capture_script "$stall_dir/pressure.txt" \
    'for file in /proc/pressure/cpu /proc/pressure/io /proc/pressure/memory; do echo ==== $file ====; cat $file; done; echo ==== diskstats ====; cat /proc/diskstats'
  device_capture "$stall_dir/binder-state.txt" cat /sys/kernel/debug/binder/state
  device_capture "$stall_dir/binder-transactions.txt" cat /sys/kernel/debug/binder/transactions
  device_capture "$stall_dir/power.txt" dumpsys power
  device_capture "$stall_dir/activity.txt" dumpsys activity activities
  device_capture "$stall_dir/window.txt" dumpsys window
  device_capture "$stall_dir/media-player.txt" dumpsys media.player
  device_capture "$stall_dir/media-resource-manager.txt" dumpsys media.resource_manager
  device_capture "$stall_dir/surfaceflinger.txt" dumpsys SurfaceFlinger
  device_capture "$stall_dir/top.txt" top -b -n 1 -m 80
  capture_process_stacks "$DEMO_PACKAGE" "$demo_pid" "$stall_dir"
  capture_process_stacks "$TVUI_PACKAGE" "$tvui_pid" "$stall_dir"
  capture_process_stacks "$STORE_PACKAGE" "$store_pid" "$stall_dir"

  printf '%s stall capture completed: %s\n' "$(date '+%F %T')" "$stall_dir" \
    | tee -a "$OUT_DIR/monitor.log"
}

cleanup() {
  trap - EXIT HUP INT TERM
  for process_id in "$LOGCAT_PID" "$DMESG_PID" "$HEARTBEAT_FOLLOWER_PID"; do
    if [[ -n "$process_id" ]]; then
      kill "$process_id" 2>/dev/null || true
    fi
  done
  pkill -TERM -P "$$" 2>/dev/null || true
  sleep 0.2
  pkill -KILL -P "$$" 2>/dev/null || true
  printf '%s monitor stopped; artifacts: %s\n' "$(date '+%F %T')" "$OUT_DIR" \
    | tee -a "$OUT_DIR/monitor.log"
  exit 0
}
trap cleanup EXIT HUP INT TERM

if ! run_bounded "$OUT_DIR/device-state.txt" truncate "${ADB_CMD[@]}" get-state; then
  printf 'ADB device unavailable: %s\n' "$SERIAL" >&2
  exit 1
fi

{
  printf 'started=%s\n' "$(date '+%F %T')"
  printf 'serial=%s\n' "$SERIAL"
  printf 'demo_package=%s\n' "$DEMO_PACKAGE"
  printf 'tvui_package=%s\n' "$TVUI_PACKAGE"
  printf 'store_package=%s\n' "$STORE_PACKAGE"
  printf 'sample_interval_seconds=%s\n' "$SAMPLE_INTERVAL_SECONDS"
  printf 'stall_seconds=%s\n' "$STALL_SECONDS"
} >"$OUT_DIR/metadata.txt"
device_capture_script "$OUT_DIR/device-properties.txt" \
  'getprop ro.product.model; getprop ro.product.device; getprop ro.build.fingerprint; date; uptime'
device_capture_script "$OUT_DIR/package-state.txt" \
  "for package in $DEMO_PACKAGE $TVUI_PACKAGE $STORE_PACKAGE; do echo ==== \$package ====; dumpsys package \$package | grep -E \"userId=|enabled=\"; done"

: >"$OUT_DIR/logcat-all.txt"
: >"$OUT_DIR/dmesg-live.txt"
: >"$OUT_DIR/collector-events.log"
start_logcat_stream
start_dmesg_stream

touch "$OUT_DIR/heartbeat.log"
tail -n 0 -F "$OUT_DIR/logcat-all.txt" 2>/dev/null | while IFS= read -r line; do
  case "$line" in
    *"HEARTBEAT seq="*)
      printf '%s\n' "$line" >>"$OUT_DIR/heartbeat.log"
      heartbeat_tmp="$OUT_DIR/.heartbeat.last.$$"
      date +%s >"$heartbeat_tmp"
      mv "$heartbeat_tmp" "$OUT_DIR/heartbeat.last"
      ;;
  esac
done &
HEARTBEAT_FOLLOWER_PID=$!

printf '%s monitor started; artifacts: %s\n' "$(date '+%F %T')" "$OUT_DIR" \
  | tee -a "$OUT_DIR/monitor.log"

last_snapshot_epoch=0
stall_active=0
while true; do
  now_epoch=$(date +%s)
  ensure_collectors_running
  printf '\n===== host_epoch=%s host_time=%s =====\n' \
    "$now_epoch" "$(date '+%F %T')" >>"$OUT_DIR/samples.txt"
  device_append_script "$OUT_DIR/samples.txt" \
    "date; uptime; for file in /proc/pressure/cpu /proc/pressure/io /proc/pressure/memory; do echo ==== \$file ====; cat \$file; done; echo ==== diskstats ====; grep -E \"mmcblk2( |p14 )\" /proc/diskstats; for package in $DEMO_PACKAGE $TVUI_PACKAGE $STORE_PACKAGE; do pid=\$(pidof \$package); echo ==== \$package pid=\$pid ====; if test -n \"\$pid\"; then grep -E \"Name:|State:|Threads:|VmRSS:|VmSwap:\" /proc/\$pid/status; cat /proc/\$pid/io; echo fd_count=\$(ls /proc/\$pid/fd 2>/dev/null | wc -l); fi; done"

  if (( now_epoch - last_snapshot_epoch >= SNAPSHOT_INTERVAL_SECONDS )); then
    last_snapshot_epoch=$now_epoch
    printf '\n===== host_epoch=%s =====\n' "$now_epoch" >>"$OUT_DIR/top.txt"
    device_append "$OUT_DIR/top.txt" top -b -n 1 -m 40
    printf '\n===== host_epoch=%s =====\n' "$now_epoch" \
      >>"$OUT_DIR/media-resource-manager.txt"
    device_append "$OUT_DIR/media-resource-manager.txt" dumpsys media.resource_manager
    if (( DMESG_STREAM_SUPPORTED == 0 )); then
      capture_dmesg_snapshot
    fi
  fi

  if [[ -s "$OUT_DIR/heartbeat.last" ]]; then
    last_heartbeat_epoch=$(tr -d '\r\n ' <"$OUT_DIR/heartbeat.last")
    if [[ "$last_heartbeat_epoch" =~ ^[0-9]+$ ]]; then
      heartbeat_age=$((now_epoch - last_heartbeat_epoch))
      printf '%s heartbeat_age_seconds=%s\n' "$now_epoch" "$heartbeat_age" \
        >>"$OUT_DIR/monitor.log"
      if (( now_epoch - LOGCAT_LAST_STARTED_EPOCH < STALL_SECONDS )); then
        printf '%s collector restart grace period active\n' "$now_epoch" \
          >>"$OUT_DIR/monitor.log"
      elif (( heartbeat_age >= STALL_SECONDS )); then
        if (( stall_active == 0 )); then
          stall_active=1
          printf '%s heartbeat stall detected, age=%ss\n' \
            "$(date '+%F %T')" "$heartbeat_age" | tee -a "$OUT_DIR/monitor.log"
          capture_stall "$now_epoch" "$heartbeat_age"
        fi
      else
        if (( stall_active == 1 )); then
          printf '%s heartbeat recovered\n' "$(date '+%F %T')" \
            | tee -a "$OUT_DIR/monitor.log"
        fi
        stall_active=0
      fi
    else
      printf '%s ignored invalid heartbeat timestamp: %s\n' \
        "$now_epoch" "$last_heartbeat_epoch" >>"$OUT_DIR/monitor.log"
    fi
  else
    printf '%s waiting for first HEARTBEAT seq= line\n' "$now_epoch" \
      >>"$OUT_DIR/monitor.log"
  fi

  sleep "$SAMPLE_INTERVAL_SECONDS"
done
