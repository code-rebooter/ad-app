# AD_YTX01 Foreground Application Gate Design

## Goal

Prevent the `ad_ytx01` channel from requesting or continuing a floating-ad flow while a configured video application is in the foreground. The foreground check runs inside the dynamically downloaded plugin APK and does not depend on plugin Android components.

## Scope

- Enable the gate only for the `ad_ytx01` flavor.
- Keep every other flavor's scheduling, requests, and playback behavior unchanged.
- Initially block only Android TV YouTube: `com.google.android.youtube.tv`.
- Keep the blocked packages in one set so the plugin developer can add packages later.
- Remove the previous audio-session and codec-resource observer sample from the plugin handoff.

## Dynamic Plugin Entry

The plugin exposes a Java entry class that the host invokes through its existing `DexClassLoader`:

```java
AdPlaybackForegroundPlugin.start(hostContext);
AdPlaybackForegroundPlugin.stop();
```

`start(Context)` is idempotent and stores only `context.getApplicationContext()`. It immediately checks the foreground package and then checks once per second on a private single-thread executor. `stop()` cancels the executor and publishes a denied state because the foreground state is no longer being monitored.

The plugin APK is not installed. It therefore does not declare an `Application`, receiver, service, permission, or `<queries>` entry. The code runs with the host application's UID and permissions. The host must be able to call `ActivityManager.getRunningTasks(1)` and invoke `start(Context)` after loading the plugin.

## Foreground Decision

The plugin reads `topActivity.packageName` from `ActivityManager.getRunningTasks(1)` and compares it with the configured set using exact package-name matching.

- YouTube in the foreground: `canPlayAd=false`.
- Any successfully identified package outside the set: `canPlayAd=true`.
- Query exception, empty task list, or missing package name: `canPlayAd=false`.

The plugin publishes the initial decision, every decision change, and a heartbeat of the unchanged decision every 30 seconds. The heartbeat restores the current state if the ad application process restarts while the foreground package remains unchanged.

## Broadcast Contract

The plugin sends an explicit broadcast to `com.google.android.adytx01`:

- Action: `io.permission.AD_PLAYBACK_STATUS`
- Boolean extra: `canPlayAd`
- String extra: `reason`
- Long extra: `eventTime`, based on `SystemClock.elapsedRealtime()`

Reasons distinguish `blocked_foreground_app:<package>`, `foreground_app_allowed:<package>`, `foreground_query_failed`, and `foreground_monitor_stopped`.

## Ad Application Behavior

The state starts as unknown. For `ad_ytx01`, unknown and denied states both fail closed; only an explicit allowed state permits a new floating-ad flow.

When a denied state arrives:

1. Invalidate the current HQ008 floating-flow token so asynchronous callbacks cannot continue.
2. Stop and destroy the current floating-ad window on the main thread.
3. Let the existing window teardown release the TCL controller.
4. Do not resume the interrupted ad when an allowed state later arrives. The normal scheduler may start a new flow on its next interval.

The gate is checked before entering `flow-control` and at asynchronous continuation points before CMP, authorize, and final ad dispatch.

## Failure Handling

- Ignore broadcasts that omit `canPlayAd`.
- Deny playback before the plugin sends its first valid status.
- Deny playback when foreground detection fails.
- Treat duplicate denied broadcasts as idempotent.
- Reject stale asynchronous callbacks after denial.
- Let all flavors except `ad_ytx01` bypass this gate.

## Handoff Contents

The plugin handoff contains only the foreground plugin entry source and a README showing the `DexClassLoader` invocation. It does not contain the obsolete AIDL resource observers, boot receiver, plugin `Application`, or plugin Manifest.

## Verification

- Contract-test the plugin entry, YouTube-only set, one-second polling, failure denial, state changes, and heartbeat.
- Unit-test advertising state transitions and flavor isolation.
- Contract-test the broadcast receiver declaration, pre-flow gate, callback checks, and active-ad teardown.
- Build the `ad_ytx01Debug` APK and inspect its merged manifest.
- Package the plugin reference source as a separate ZIP for the plugin developer.
