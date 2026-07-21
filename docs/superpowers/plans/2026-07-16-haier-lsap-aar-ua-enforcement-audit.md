# Haier LSAP AAR UA Enforcement and Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate verified patched LSAP 1.1.12 AARs for three channels, force one canonical UA at every known Java boundary, and upload complete AAR request data to the existing ad report API.

**Architecture:** A deterministic ASM patch task rewrites the original AAR bytecode to call app-owned runtime bridge methods. Channel-scoped runtime code enforces `http.agent` and `LSADWEBUA`, captures shaded OkHttp/URLConnection/WebView/Dex/Titan boundaries, and asynchronously forwards audit JSON through the existing reporting client without blocking advertising.

**Tech Stack:** Android/Kotlin, Gradle 8.13, AGP 8.10.1, ASM 9.x, JUnit 4, shaded OkHttp/Okio from LSAP, Android SharedPreferences and WebView.

---

### Task 1: Runtime UA guard and live reporting snapshot

**Files:**
- Modify: `app/src/main/java/com/smart/android/ad_app/HaierUserAgentInstaller.kt`
- Modify: `app/src/main/java/com/smart/android/ad_app/HaierUserAgentReportCollector.kt`
- Create: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarRuntimeBridge.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/HaierUserAgentInstallerTest.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/HaierUserAgentReportCollectorTest.kt`

- [ ] Write failing tests proving a later bad `http.agent` and bad `LSADWEBUA` are repaired on every guard call, and `webview_ua` is read fresh.
- [ ] Run `./gradlew :app:testHaier_lsapDebugUnitTest --tests '*HaierUserAgent*'` and confirm the new assertions fail.
- [ ] Add a synchronized `ensureEffectiveForCurrentProcess()` result containing observed, effective, changed, and timestamp fields; add bridge methods `getSystemProperty`, `enforceResolvedUa`, `normalizeStoredValue`, and `setWebViewUserAgent`.
- [ ] Update authorize fields to emit `ua_observed`, `ua_aar_cached`, `ua_aar_effective`, drift flags, repair flag, and check time while preserving the three existing fields.
- [ ] Run the focused tests and confirm they pass.

### Task 2: Full request audit model and uploader

**Files:**
- Create: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarNetworkAudit.kt`
- Create: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarAuditUploader.kt`
- Modify: `app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/HaierAarNetworkAuditTest.kt`

- [ ] Write failing tests for raw URL/header/body preservation, gzip+Base64 chunk reconstruction, own-report URL exclusion, and non-recursive upload guards.
- [ ] Run `./gradlew :app:testHaier_lsapDebugUnitTest --tests '*HaierAarNetworkAuditTest'` and confirm failure.
- [ ] Implement an immutable audit event carrying raw request values, UA before/after fields, response metadata, source stack, and coverage.
- [ ] Implement asynchronous uploader batching, immediate critical event upload, gzip+Base64 chunking, SHA-256 metadata, and `api/v2/ad/report` event types.
- [ ] Add `Hq008AdReporter.reportAarAudit(...)` without changing existing ad event behavior.
- [ ] Run the audit tests and confirm they pass.

### Task 3: Deterministic AAR patch generator

**Files:**
- Create: `buildSrc/build.gradle`
- Create: `buildSrc/src/main/groovy/com/smart/lsap/LsapAarPatchTask.groovy`
- Create: `buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy`
- Create: `buildSrc/src/test/groovy/com/smart/lsap/LsapClassPatcherTest.groovy`
- Modify: `app/build.gradle`

- [ ] Write failing patcher tests against copied class fixtures for `System.getProperty`, `d.b.e.b.a(Context)` returns, `LSADWEBUA` writes, WebView UA calls, shaded header builder calls, and RTB body returns.
- [ ] Run `./gradlew :buildSrc:test` and confirm failure.
- [ ] Implement ASM rewrites to route system-property reads, resolver returns, stored UA values, WebView UA calls, shaded headers, RTB JSON and shaded request/response events through `HaierAarRuntimeBridge`.
- [ ] Implement AAR unzip/patch/rezip with fixed entry timestamps, SHA-256 input verification, method-fingerprint verification and patch metadata.
- [ ] Register one generated AAR per target flavor, remove direct runtime use of the three original AARs, and wire generated files as task-built dependencies.
- [ ] Run `./gradlew :buildSrc:test :app:patchLsapAars` and inspect patch metadata and bytecode assertions.

### Task 4: Shaded OkHttp and RTB final enforcement

**Files:**
- Modify: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarRuntimeBridge.kt`
- Modify: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarNetworkAudit.kt`
- Modify: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/HaierAarRtbEnforcementTest.kt`

- [ ] Write failing tests that create a shaded request with bad Header UA and bad RTB `device.ua`, then assert the bridge returns one canonical value and captures the complete raw request.
- [ ] Run the focused test and confirm failure.
- [ ] Implement shaded request-body extraction with shaded Okio Buffer, request rebuilding with canonical `User-Agent`, `/rtb/bid` JSON rewriting, response status/header capture and exception-safe callbacks.
- [ ] Set and clear the active ad request context around `UnifiedAdSdk.requestAd()` and its terminal callbacks.
- [ ] Run the focused tests and confirm they pass.

### Task 5: HttpURLConnection, WebView, Hezi, Titan and Dex boundaries

**Files:**
- Modify: `buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy`
- Modify: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarRuntimeBridge.kt`
- Test: `buildSrc/src/test/groovy/com/smart/lsap/LsapClassPatcherTest.groovy`
- Test: `app/src/test/java/com/smart/android/ad_app/HaierAarBoundaryAuditTest.kt`

- [ ] Add failing bytecode and runtime tests for `d.b.e.h`, `d.a.a.d`, Hezi pre-encryption input, Titan download/load/nativeStart metadata, Dex check/download/class/method/parameter metadata, and WebView visible requests.
- [ ] Run focused tests and confirm failure.
- [ ] Inject request-start/request-finish hooks into both HttpURLConnection helper classes and producer hooks for Hezi/Titan/Dex values.
- [ ] Implement bridge capture methods that preserve every raw field while marking native and dynamic-Dex internal traffic unverified.
- [ ] Run focused tests and confirm they pass.

### Task 6: App integration and regression verification

**Files:**
- Modify: `app/src/main/java/com/smart/android/ad_app/APP.kt`
- Modify: `app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt`
- Modify: `app/proguard-rules.pro`
- Modify: `app/src/test/java/com/smart/android/ad_app/AddyHaierLsapChannelContractTest.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/HaierLsapDebugEntryContractTest.kt`

- [ ] Add failing contract tests requiring runtime bridge initialization in all three flavors, patched AAR task wiring, and R8 keep rules.
- [ ] Run the focused contract tests and confirm failure.
- [ ] Initialize the bridge before SDK initialization, install the `LSADWEBUA` listener, enforce immediately before each SDK request, and add keep rules for all injected bridge entry points.
- [ ] Run `./gradlew :app:testHaier_lsapDebugUnitTest :app:testAddy_hq1002DebugUnitTest :app:testAddy_jamsDebugUnitTest`.
- [ ] Build `assembleHaier_lsapRelease`, `assembleAddy_hq1002Release`, and `assembleAddy_jamsRelease`; inspect the three APKs to confirm only patched AAR bytecode is present.
- [ ] On a connected device, inject bad system and cached UA values, trigger `/rtb/bid`, and compare Header UA, JSON UA, authorize fields and uploaded audit payload byte-for-byte.

