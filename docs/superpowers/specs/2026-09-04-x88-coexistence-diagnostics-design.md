# X88 Coexistence Diagnostics

## Goal

Identify the first blocked layer when `com.aispeech.tvui` and the GAM VAST Demo run together as `uid=1000`, without changing ad requests, VAST parsing, tracking, playback order, or power behavior.

## App Instrumentation

- Emit a main-thread heartbeat from the Demo every two seconds.
- Log entry, exit, elapsed time, thread, player state, media type, and Surface lifecycle around player creation, media preparation, first frame, stop, release, player detachment, and view removal.
- Do not log or modify the ad URL.
- Keep instrumentation in Logcat; do not add app-side file or preference writes.

## Host Monitor

- Stream all Logcat buffers and kernel messages to the Mac.
- Sample both app processes, system pressure, storage counters, Binder state, MediaCodec-related processes, and top processes.
- Detect a missing Demo main-thread heartbeat for eight seconds.
- On the first stall, collect Java and native backtraces for the Demo and `com.aispeech.tvui`, plus power, activity, window, media, SurfaceFlinger, Binder, memory, and kernel snapshots.
- Apply timeouts to diagnostic commands so one blocked service cannot stop the monitor.

## Test Isolation

- Keep `com.chihihgs.store` and `com.screensaver.android.box` disabled.
- Enable only `com.aispeech.tvui` as the changed variable.
- Keep the current GAM VAST build, one-minute request interval, normal screen timeout, and system color dream behavior.

## Limits

A kernel hard lock can prevent final state collection. Continuous host-side Logcat, dmesg, heartbeat timestamps, and pressure samples should still preserve the events immediately preceding the lock.
