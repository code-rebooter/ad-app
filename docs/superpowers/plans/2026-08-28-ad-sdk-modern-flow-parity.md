# ad-sdk-modern Flow Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Synchronize pending CMP action retries and per-authorization callback timeouts into `ad-sdk-modern`.

**Architecture:** Keep consent retry state inside `AdConsentResolver` using SDK-owned SharedPreferences. Parse the authorize timeout into `FlowAuthorizedConfig`, then let `AdSessionImpl` replace its default timer with the remaining server-defined deadline.

**Tech Stack:** Android Java library, Google UMP, Gson, OkHttp, JUnit 4, Gradle

---

### Task 1: Pending CMP action retry

**Files:**
- Create: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdConsentPendingActionContractTest.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentResolver.java`

- [x] Add a contract test requiring pending-action lookup before `requestRemoteDecision`, persistence before `AdConsentManager.requestConsent`, and clearing after error-free local success.
- [x] Run the single test and confirm it fails because pending-action storage is absent.
- [x] Add SDK-owned SharedPreferences helpers and the three terminal-action lifecycle calls.
- [x] Run the single test and confirm it passes.

### Task 2: Authorize timeout parsing

**Files:**
- Create: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdCallbackTimeoutPolicy.java`
- Create: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdCallbackTimeoutPolicyTest.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/FlowAuthorizedConfig.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/RemoteAdConfigClient.java`
- Modify: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/RemoteAdConfigClientTest.java`

- [x] Add policy tests for 30 and 600 seconds plus missing and out-of-range values.
- [x] Add authorize-flow tests for `ad_callback_timeout_seconds` and its compatibility alias.
- [x] Run those tests and confirm they fail because no timeout override is exposed.
- [x] Implement range normalization, alias parsing, and `FlowAuthorizedConfig.getAdCallbackTimeoutMs()`.
- [x] Run those tests and confirm they pass.

### Task 3: Preserve the total-session deadline

**Files:**
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/TimeoutScheduler.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/MainThreadTimeoutScheduler.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdSessionImpl.java`
- Modify: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdSessionImplTest.java`

- [x] Add tests showing a 240-second authorize override received after 10 seconds schedules 230 seconds, while a missing override keeps the original 180-second timer.
- [x] Run the tests and confirm they fail because authorize callbacks do not reschedule timeouts.
- [x] Expose a monotonic scheduler clock and reschedule the timer from the original session start.
- [x] Run the tests and confirm they pass.

### Task 4: Regression verification

**Files:**
- Test: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdConsentPendingActionContractTest.java`
- Test: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdCallbackTimeoutPolicyTest.java`
- Test: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/RemoteAdConfigClientTest.java`
- Test: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/AdSessionImplTest.java`

- [x] Run targeted tests and compile `ad-sdk-modern`.
- [x] Run `git diff --check` and confirm `ad-sdk-modern-no-ump` has no differences.
- [x] Run the full `ad-sdk-modern` unit suite and compare failures with the recorded two-test baseline.
