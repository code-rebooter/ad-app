# ad-sdk-modern Channel Domain Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route all SDK-owned API calls for `GOOGLE_AD_TV_CVTE` through `https://api.xartek.cc/`, preserve the existing domain for every other channel, and remove the duplicate PreferenceManager class.

**Architecture:** Resolve one Base URL from the manifest channel before constructing runtime clients, then inject the same URL into every existing client. Continue using the official PreferenceManager supplied by the IMA dependency.

**Tech Stack:** Android Java 17, OkHttp, Media3 IMA, Google UMP, Gradle/JitPack

---

### Task 1: Resolve the customer Base URL once

**Files:**
- Modify: `ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/SdkRuntime.java`

- [x] Add fixed constants for the default URL, the CVTE channel, and the CVTE URL.
- [x] Read `channelId` before constructing network clients.
- [x] Resolve the Base URL with an internal case-insensitive channel comparison.
- [x] Build consent endpoints and pass the resolved Base URL to flow-control, authorize/GAM resolve, and reporting clients.

### Task 2: Remove the duplicate AndroidX class

**Files:**
- Delete: `ad-sdk-modern/src/main/java/androidx/preference/PreferenceManager.java`

- [x] Delete the SDK-owned class so the IMA transitive `androidx.preference` implementation is the only packaged definition.

### Task 3: Verify and publish

**Files:**
- Verify: `ad-sdk-modern/build/outputs/aar/ad-sdk-modern-release.aar`

- [x] Run `git diff --check -- ad-sdk-modern` and scan production code for the two Base URLs and duplicate class.
- [x] Run `./gradlew :ad-sdk-modern:assembleRelease :ad-sdk-modern:publishReleasePublicationToMavenLocal -PPUBLISH_GROUP_ID=com.github.code-rebooter.ad-app -PPUBLISH_VERSION=v1.0.12 --console=plain` without running tests.
- [x] Inspect the release AAR to confirm it does not contain `androidx/preference/PreferenceManager.class`.
- [ ] Commit only the intended `ad-sdk-modern` release changes and the two scoped design documents.
- [ ] Push `main`, create and push `v1.0.12`, then trigger and inspect the JitPack build log.
