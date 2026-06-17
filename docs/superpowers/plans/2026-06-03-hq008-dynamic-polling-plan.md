# hq008 Dynamic Polling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `hq008` family channels use `sdk/authorize.next_request_seconds` as the primary floating-ad polling interval, while preserving a local randomized fallback and keeping the behavior isolated per channel.

**Architecture:** Keep the existing `sdk/authorize` contract as the source of dynamic polling. Persist the effective interval in `Hq008LocalSchedulePolicy`, keyed by channel, and have `ScheduleManagerImpl` read from that policy for `hq008` family channels. On every authorize callback, validate and save the server-provided interval when present, otherwise retain or fall back to the existing local randomized 20-25 minute window. Apply the new interval to the live scheduler through `HandlerAdTaskScheduler`.

**Tech Stack:** Kotlin, Android SharedPreferences, existing `HandlerAdTaskScheduler`, JUnit4 contract tests

---

### Task 1: Lock the desired behavior in tests

**Files:**
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008LocalSchedulePolicyTest.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008AuthorizeSerializationContractTest.kt`
- Create: `app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt`

- [ ] **Step 1: Write the failing test for policy fallback and channel isolation**

```kotlin
@Test
fun `hq008 policy should prefer persisted server interval and fall back to local randomized value`() {
    val policySource = readProjectFile("app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt")

    assertTrue(policySource.contains("serverPollingSeconds"))
    assertTrue(policySource.contains("BuildConfig.CHANNEL"))
    assertTrue(policySource.contains("next_request_seconds"))
}
```

- [ ] **Step 2: Write the failing test for authorize callback applying scheduler updates**

```kotlin
@Test
fun `hq008 authorize callback should apply next_request_seconds to local scheduler`() {
    val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")

    assertTrue(adConfigManagerSource.contains("Hq008LocalSchedulePolicy.updateServerPollingSeconds"))
    assertTrue(adConfigManagerSource.contains("HandlerAdTaskScheduler.startOrUpdateTask"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest --tests com.smart.android.ad_app.Hq008LocalSchedulePolicyTest
```

Expected: FAIL because the new server-driven interval handling does not exist yet.

- [ ] **Step 4: Extend existing contract expectations to match the new direction**

Update `Hq008LocalSchedulePolicyTest` so it no longer asserts that `next_request_seconds` must be ignored, and instead asserts:

```kotlin
assertTrue(
    "ScheduleManagerImpl 应该继续通过 Hq008LocalSchedulePolicy 统一读取 hq008 轮询间隔",
    scheduleManagerSource.contains("Hq008LocalSchedulePolicy.pollingSeconds()")
)
assertTrue(
    "AdConfigManager 应该在 authorize 回调里接入 next_request_seconds",
    adConfigManagerSource.contains("dto.next_request_seconds")
)
```

- [ ] **Step 5: Commit the red tests**

```bash
git add app/src/test/java/com/smart/android/ad_app/Hq008LocalSchedulePolicyTest.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008AuthorizeSerializationContractTest.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt
git commit -m "test: lock hq008 dynamic polling contracts"
```

### Task 2: Persist server-controlled polling intervals with local fallback

**Files:**
- Modify: `app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt`

- [ ] **Step 1: Add failing test coverage for persistence API names**

Add to `Hq008DynamicPollingContractTest`:

```kotlin
assertTrue(policySource.contains("fun updateServerPollingSeconds"))
assertTrue(policySource.contains("fun clearServerPollingSeconds"))
assertTrue(policySource.contains("fun resolveEffectivePollingSeconds"))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest
```

Expected: FAIL because these methods do not exist yet.

- [ ] **Step 3: Implement the minimal persistence API**

Update `Hq008LocalSchedulePolicy.kt` to:

```kotlin
private const val KEY_SERVER_POLLING_SECONDS_PREFIX = "server_polling_seconds_"
private const val MIN_SERVER_POLLING_SECONDS = 30L
private const val MAX_SERVER_POLLING_SECONDS = 24 * 60 * 60L

fun pollingSeconds(channelId: String): Long {
    return resolveEffectivePollingSeconds(channelId) ?: randomizedPollingSeconds
}

fun updateServerPollingSeconds(channelId: String, nextRequestSeconds: Long) {
    val normalized = normalizeServerPollingSeconds(nextRequestSeconds) ?: return
    val context = appContext ?: return
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_SERVER_POLLING_SECONDS_PREFIX + channelId, normalized)
        .apply()
}
```

Also add:

```kotlin
fun clearServerPollingSeconds(channelId: String)
internal fun resolveEffectivePollingSeconds(channelId: String): Long?
internal fun normalizeServerPollingSeconds(nextRequestSeconds: Long): Long?
```

- [ ] **Step 4: Route `ScheduleManagerImpl` through the channel-aware policy**

Update `ScheduleManagerImpl.kt` minimal branch:

```kotlin
override fun handlerScheduleTime(): Long {
    return if (BuildFlavor.isHq008Family()) {
        Hq008LocalSchedulePolicy.pollingSeconds(BuildConfig.CHANNEL)
    } else {
        BuildConfig.HANDLER_SCHEDULE_SECONDS
    }
}
```

- [ ] **Step 5: Run the focused tests and verify they pass**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest --tests com.smart.android.ad_app.Hq008LocalSchedulePolicyTest
```

Expected: PASS

- [ ] **Step 6: Commit the policy change**

```bash
git add app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt \
        app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008LocalSchedulePolicyTest.kt
git commit -m "feat: persist hq008 polling interval by channel"
```

### Task 3: Apply `next_request_seconds` from authorize callbacks to the live scheduler

**Files:**
- Modify: `app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/HandlerAdTaskScheduler.kt`

- [ ] **Step 1: Add the failing contract for live scheduler updates**

Add to `Hq008DynamicPollingContractTest`:

```kotlin
assertTrue(adConfigManagerSource.contains("val nextPollingSeconds"))
assertTrue(adConfigManagerSource.contains("Hq008LocalSchedulePolicy.updateServerPollingSeconds(BuildConfig.CHANNEL"))
assertTrue(adConfigManagerSource.contains("HandlerAdTaskScheduler.startOrUpdateTask(nextPollingSeconds)"))
```

- [ ] **Step 2: Run the contract test to verify it fails**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest
```

Expected: FAIL because the authorize callback still only logs the value.

- [ ] **Step 3: Implement the minimal authorize callback update path**

In `AdConfigManager.requestHq008Authorize()` add, immediately after the authorize callback log:

```kotlin
val nextPollingSeconds = Hq008LocalSchedulePolicy.normalizeServerPollingSeconds(dto.next_request_seconds)
if (nextPollingSeconds != null) {
    Hq008LocalSchedulePolicy.updateServerPollingSeconds(BuildConfig.CHANNEL, nextPollingSeconds)
    HandlerAdTaskScheduler.startOrUpdateTask(nextPollingSeconds)
} else {
    Hq008LocalSchedulePolicy.clearServerPollingSeconds(BuildConfig.CHANNEL)
    HandlerAdTaskScheduler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
}
```

If you prefer to preserve the last valid server value instead of clearing on invalid values, keep that behavior explicit in code and tests.

- [ ] **Step 4: Keep `HandlerAdTaskScheduler` behavior unchanged except for accepting live interval updates**

Only adjust `HandlerAdTaskScheduler` if testing shows that mid-flight `startOrUpdateTask()` does not apply the new interval cleanly. Avoid unrelated refactors.

- [ ] **Step 5: Run focused tests and verify they pass**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest --tests com.smart.android.ad_app.Hq008LocalSchedulePolicyTest
```

Expected: PASS

- [ ] **Step 6: Commit the authorize-driven reschedule change**

```bash
git add app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt \
        app/src/main/java/com/smart/android/ad_app/HandlerAdTaskScheduler.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt
git commit -m "feat: apply hq008 authorize polling interval"
```

### Task 4: Verify hq008-family behavior and regression boundaries

**Files:**
- Modify: `app/src/test/java/com/smart/android/ad_app/TclPolyFlavorContractTest.kt` (only if needed)
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008Noneuc2FlavorContractTest.kt` (only if needed)

- [ ] **Step 1: Add regression assertions only if current tests miss channel isolation**

If missing, add assertions that:

```kotlin
assertTrue(BuildFlavor.isHq008Noneu("tcl_poly"))
assertTrue(buildGradle.contains("channel             : \"TCL_POLY\""))
```

No need to add redundant assertions if current tests already cover them.

- [ ] **Step 2: Run the full focused hq008-family unit test set**

Run:

```bash
./gradlew testHq008NoneuDebugUnitTest \
  --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest \
  --tests com.smart.android.ad_app.Hq008LocalSchedulePolicyTest \
  --tests com.smart.android.ad_app.TclPolyFlavorContractTest \
  --tests com.smart.android.ad_app.Hq008Noneuc2FlavorContractTest
```

Expected: PASS

- [ ] **Step 3: Run one build proving the app still assembles**

Run:

```bash
./gradlew assembleHq008polyDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit verification-only test adjustments if any**

```bash
git add app/src/test/java/com/smart/android/ad_app/TclPolyFlavorContractTest.kt \
        app/src/test/java/com/smart/android/ad_app/Hq008Noneuc2FlavorContractTest.kt
git commit -m "test: cover hq008 dynamic polling isolation"
```
