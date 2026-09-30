# Remove ad-sdk-modern System UID Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all `uid=1000` customer-specific behavior from `ad-sdk-modern` without changing `ad-sdk-modern-no-ump`.

**Architecture:** Remove the compatibility utility and restore each affected IMA, UMP, and preference-storage path to its UID-agnostic behavior. Protect the module with a source-level contract that rejects reintroduction of system-UID branches.

**Tech Stack:** Android library, Java 17, Gradle, JUnit 4, Media3 IMA, Google UMP

---

### Task 1: Add the removal contract

**Files:**
- Create: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/SystemUidCompatibilityRemovalTest.java`
- Delete: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/SystemUidStorageCompatTest.java`
- Delete: `ad-sdk-modern/src/test/java/androidx/preference/PreferenceManagerStorageCompatTest.java`

- [x] Add a JUnit test that recursively reads `ad-sdk-modern/src/main` and rejects `SYSTEM_UID`, `Process.myUid()`, and `SystemUidStorageCompat`.
- [x] Run `./gradlew :ad-sdk-modern:testDebugUnitTest --tests com.smart.android.adsdk.internal.SystemUidCompatibilityRemovalTest` and verify it fails because compatibility code still exists.

### Task 2: Remove production compatibility branches

**Files:**
- Delete: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/SystemUidStorageCompat.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentResolver.java`
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentManager.java`
- Modify: `ad-sdk-modern/src/main/java/androidx/preference/PreferenceManager.java`

- [x] Remove the WebView preparation and Google SDK context routing calls.
- [x] Use `context` directly for IMA, Media3 data sources, and ExoPlayer.
- [x] Remove system-UID UMP bypasses and consent preference bypasses.
- [x] Restore `PreferenceManager` to application-context SharedPreferences without UID or storage-mode switching.
- [x] Delete the compatibility utility.

### Task 3: Verify the standard module

**Files:**
- Test: `ad-sdk-modern/src/test/java/com/smart/android/adsdk/internal/SystemUidCompatibilityRemovalTest.java`

- [x] Run the removal contract and verify it passes.
- [x] Search `ad-sdk-modern/src/main` for `SYSTEM_UID`, `Process.myUid()`, `SystemUidStorageCompat`, and explicit `uid=1000` handling; expect no matches.
- [x] Run `./gradlew :ad-sdk-modern:compileDebugJavaWithJavac` and expect success.
- [x] Run `./gradlew :ad-sdk-modern:testDebugUnitTest` and compare any failures with the recorded baseline.
