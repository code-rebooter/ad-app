# X88 Coexistence Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve enough app, process, Binder, media, Surface, IO, and kernel evidence to locate the first blocked layer when the X88 runs `com.aispeech.tvui` beside the GAM VAST Demo.

**Architecture:** Add Logcat-only instrumentation to the Demo and player, then run a Mac-hosted monitor that watches the Demo main-thread heartbeat. The monitor continuously streams logs and takes bounded snapshots; on a heartbeat stall it captures both applications' Java/native stacks and system service state without changing the ad flow.

**Tech Stack:** Android Java, Media3 ExoPlayer, Bash, ADB, debuggerd, JUnit 4 source-contract tests.

---

### Task 1: Diagnostic Contract

**Files:**
- Create: `ad-sdk-gam-vast/src/test/java/com/smart/android/adsdk/internal/X88DiagnosticsContractTest.java`

- [x] **Step 1: Write the failing source-contract tests**

Add tests that require `HEARTBEAT seq=`, the two-second main-handler heartbeat, `PLAYER_OP` and `PLAYER_EVENT` markers, Surface lifecycle markers, and host-monitor commands for `debuggerd -j`, `debuggerd -b`, pressure, Binder, and MediaCodec snapshots.

- [x] **Step 2: Run the focused test and verify failure**

Run:

```bash
./gradlew :ad-sdk-gam-vast:testDebugUnitTest --tests '*X88DiagnosticsContractTest'
```

Expected: failure because the heartbeat, detailed player markers, and X88 monitor script do not exist yet.

### Task 2: App-Side Logcat Diagnostics

**Files:**
- Modify: `ad-sdk-gam-vast-demo/src/main/java/com/smart/android/adsdk/gamvast/demo/GamVastDemoActivity.java`
- Modify: `ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java`

- [x] **Step 1: Add the Demo heartbeat**

Use a main-handler runnable that logs every two seconds without updating the UI or writing files:

```java
Log.i(TAG, "HEARTBEAT seq=" + heartbeatSequence
    + ", uptimeMs=" + SystemClock.elapsedRealtime()
    + ", request=" + requestSequence
    + ", inFlight=" + requestInFlight
    + ", state=" + (session == null ? null : session.getState()));
```

Start it after `buildUi()` and remove it in `onDestroy()`.

- [x] **Step 2: Add player operation and event markers**

Log begin/end and elapsed time around player creation, player-view attachment, media item setup, prepare, stop, release, player detachment, and root-view removal. Log playback states, first frame, errors, and Surface create/change/destroy events. Include controller identity, media index/type, state, thread, and elapsed time, but never include the ad URL.

- [x] **Step 3: Run the focused test**

Run:

```bash
./gradlew :ad-sdk-gam-vast:testDebugUnitTest --tests '*X88DiagnosticsContractTest'
```

Expected: only the host-monitor assertions remain failing.

### Task 3: Host Stall Monitor

**Files:**
- Create: `scripts/monitor_x88_coexistence.sh`

- [x] **Step 1: Implement continuous host-side collection**

The script must stream all Logcat buffers and `dmesg -w`, follow heartbeat lines, sample `/proc/pressure`, `/proc/diskstats`, process status/IO/thread wait channels, and collect periodic top/Binder/media snapshots into a timestamped Mac directory.

- [x] **Step 2: Implement bounded stall capture**

After eight seconds without a heartbeat, run each diagnostic under the device's `timeout` command. Capture `debuggerd -j` and `debuggerd -b` for both packages, `/proc` thread stacks, Binder state, `dumpsys power`, `activity`, `window`, media services, SurfaceFlinger, memory, and final kernel state. Trigger once per stall and re-arm after heartbeat recovery.

- [x] **Step 3: Verify tests and shell syntax**

Run:

```bash
bash -n scripts/monitor_x88_coexistence.sh
./gradlew :ad-sdk-gam-vast:testDebugUnitTest --tests '*X88DiagnosticsContractTest'
```

Expected: shell syntax passes and focused tests pass.

### Task 4: Install and Start Isolated Coexistence Test

**Files:**
- Runtime artifacts only under `artifacts/x88-coexistence/`

- [x] **Step 1: Build the X88-signed Demo**

```bash
./gradlew :ad-sdk-gam-vast-demo:assembleDebug
```

- [x] **Step 2: Stop old monitors, install the Demo, and preserve power settings**

Stop only our existing `x88_isolation_*` screen sessions. Uninstall and reinstall only `com.smart.android.adsdk.gamvast.demo`. Do not change screen timeout, stay-awake, Dream settings, network, or backend configuration.

- [x] **Step 3: Establish the single-variable package state**

Verify `com.chihihgs.store` and `com.screensaver.android.box` remain disabled, then enable only `com.aispeech.tvui` and verify the package state before launching the Demo.

- [x] **Step 4: Start monitoring and launch the Demo**

Run the monitor in a detached `screen` session, launch the Demo, and verify heartbeat, both process IDs, `uid=1000`, player operation markers, and non-empty host artifacts.
