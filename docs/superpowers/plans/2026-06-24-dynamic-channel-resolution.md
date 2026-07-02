# Dynamic Channel Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a shared channel resolver that prefers `persist.vendor.ad.channel` and update ad flows to consume it instead of direct `BuildConfig.CHANNEL` reads.

**Architecture:** Add a small resolver object in `src/main` with normalization and property read fallbacks, then replace direct channel lookups in core request/report/scheduling paths. Keep the change narrowly scoped and verify it with contract tests that pin both the resolver behavior and the updated call sites.

**Tech Stack:** Android app, Kotlin, Gradle flavor BuildConfig, JUnit source contract tests

---

### Task 1: Lock the resolver contract in tests

**Files:**
- Create: `app/src/test/java/com/smart/android/ad_app/AdChannelResolverContractTest.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008CmpDecisionClientContractTest.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008CmpConsentReportContractTest.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
assertTrue(resolverSource.contains("persist.vendor.ad.channel"))
assertTrue(resolverSource.contains("BuildConfig.CHANNEL"))
assertTrue(clientSource.contains("\"channel_id\" to AdChannelResolver.currentChannel()"))
assertTrue(adConfigManagerSource.contains("val channel = AdChannelResolver.resolve()"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testHq008DebugUnitTest --tests com.smart.android.ad_app.AdChannelResolverContractTest --tests com.smart.android.ad_app.Hq008CmpDecisionClientContractTest --tests com.smart.android.ad_app.Hq008CmpConsentReportContractTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest`

Expected: FAIL because `AdChannelResolver` does not exist yet and call sites still reference `BuildConfig.CHANNEL`.

### Task 2: Implement shared channel resolution

**Files:**
- Create: `app/src/main/java/com/smart/android/ad_app/AdChannelResolver.kt`

- [ ] **Step 1: Write minimal implementation**

```kotlin
internal object AdChannelResolver {
    private const val CHANNEL_PROPERTY_KEY = "persist.vendor.ad.channel"

    fun currentChannel(): String = resolve().value
}
```

- [ ] **Step 2: Add normalization and fallback behavior**

```kotlin
internal fun normalizeChannel(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }
```

- [ ] **Step 3: Add system property read fallback**

```kotlin
private fun readSystemProperty(key: String): String? {
    return readSystemPropertyReflective(key) ?: readSystemPropertyViaGetprop(key)
}
```

### Task 3: Replace core call sites

**Files:**
- Modify: `app/src/main/java/com/smart/android/ad_app/APP.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/Hq008FloatingFlowGuard.kt`
- Modify: `app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt`
- Modify: `app/src/hq008/java/com/smart/android/ad_app/Hq008ConsentLogReporter.kt`
- Modify: `app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt`
- Modify: `app/src/hq006/java/com/smart/android/ad_app/sdk/AdManager.kt`
- Modify: `app/src/tcl_poly/java/com/smart/android/ad_app/poly/PolyGammaTestEntryActivity.kt`
- Modify: `app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt`

- [ ] **Step 1: Replace direct reads with resolver**

```kotlin
val channel = AdChannelResolver.resolve()
put("channel", channel.value)
```

- [ ] **Step 2: Use resolved channel in schedule and guard defaults**

```kotlin
fun pollingSeconds(channelId: String = AdChannelResolver.currentChannel()): Long
fun tryEnter(channelId: String = AdChannelResolver.currentChannel()): Token?
```

- [ ] **Step 3: Add source-aware logs where it matters**

```kotlin
Log.i(TAG, "广告链路：开始请求广告配置，channel=${channel.value}，channelSource=${channel.source.label}")
```

### Task 4: Verify and clean up

**Files:**
- Test: `app/src/test/java/com/smart/android/ad_app/AdChannelResolverContractTest.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/Hq008CmpDecisionClientContractTest.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/Hq008CmpConsentReportContractTest.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/Hq008DynamicPollingContractTest.kt`

- [ ] **Step 1: Run focused tests**

Run: `./gradlew :app:testHq008DebugUnitTest --tests com.smart.android.ad_app.AdChannelResolverContractTest --tests com.smart.android.ad_app.Hq008CmpDecisionClientContractTest --tests com.smart.android.ad_app.Hq008CmpConsentReportContractTest --tests com.smart.android.ad_app.Hq008DynamicPollingContractTest`

Expected: PASS

- [ ] **Step 2: Search for remaining production call sites**

Run: `rg -n "BuildConfig\\.CHANNEL" app/src/main app/src/hq008 app/src/hq006 app/src/tcl_poly app/src/haier_lsapDebug`

Expected: only allowed display/test leftovers remain, no core request/report path still depends on direct `BuildConfig.CHANNEL`.
