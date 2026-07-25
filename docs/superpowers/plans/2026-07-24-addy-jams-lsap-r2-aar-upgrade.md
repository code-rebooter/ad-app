# ADDY JAMS LSAP 1.1.12 R2 AAR Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the customer-reissued `addy_jams` LSAP 1.1.12 AAR as a side-by-side R2 artifact, regenerate the existing bytecode patch, and produce a signed release APK for device testing.

**Architecture:** The old input AAR remains available for rollback. Gradle's existing `lsapPatchedAarSpecs.addy_jams` entry points to the R2 input and a distinct generated patched output; the application continues to depend only on `lsapPatchedAars.addy_jams`.

**Tech Stack:** Android Gradle Plugin, Groovy Gradle configuration, Kotlin/JUnit contract tests, ASM AAR patcher, JADX/APK ZIP inspection.

---

### Task 1: Lock the R2 artifact contract

**Files:**
- Modify: `app/src/test/java/com/smart/android/ad_app/AddyHaierLsapChannelContractTest.kt`
- Test: `app/src/test/java/com/smart/android/ad_app/AddyHaierLsapChannelContractTest.kt`

- [ ] **Step 1: Write the failing contract test**

Add a test that asserts both old and R2 AAR files exist, the `addy_jams` patch input/output use the R2 names, the configured SHA is `b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30`, and `addy_jamsImplementation` consumes `lsapPatchedAars.addy_jams`.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :app:testAddy_jamsDebugUnitTest --tests 'com.smart.android.ad_app.AddyHaierLsapChannelContractTest'
```

Expected: FAIL because the R2 file and Gradle configuration do not exist yet.

### Task 2: Add and select the R2 input

**Files:**
- Create: `app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar`
- Modify: `app/build.gradle`
- Test: `app/src/test/java/com/smart/android/ad_app/AddyHaierLsapChannelContractTest.kt`

- [ ] **Step 1: Copy the customer AAR under the R2 name**

Copy the supplied binary without modifying the existing 1.1.12 artifact, then verify its SHA-256 is exactly `b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30`.

- [ ] **Step 2: Update only the `addy_jams` patch specification**

Set:

```groovy
input : "libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar"
output: "generated/lsap-patched/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2-patched.aar"
sha256: "b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30"
```

- [ ] **Step 3: Increment the `addy_jams` application version**

Set versionCode to `8` and versionName to `1.0.8` so the R2 APK upgrades the previous `7 / 1.0.7` release.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 command. Expected: PASS.

### Task 3: Regenerate and audit the bytecode patch

**Files:**
- Generate: `app/build/generated/lsap-patched/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2-patched.aar`

- [ ] **Step 1: Run the existing patch task**

```bash
./gradlew :app:patchAddyJamsLsapAar
```

Expected: BUILD SUCCESSFUL with 39 modified classes and all 11 network-surface categories. If a hard gate fails, stop and diagnose the changed bytecode rather than weakening the gate.

- [ ] **Step 2: Verify generated metadata**

```bash
unzip -p app/build/generated/lsap-patched/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2-patched.aar META-INF/lsap-ua-audit.properties
```

Expected: patch version `lsap-full-network-audit-2`, the R2 original SHA, `targetFlavor=addy_jams`, SDK version 1.1.12, 39 modified classes, and all required categories.

### Task 4: Update maintenance records

**Files:**
- Modify: `docs/lsap-aar-patching-maintenance-guide.md`

- [ ] **Step 1: Record the active R2 input and rollback artifact**

Update the current AAR table and explain that `addy_jams` has two supplier-identical version labels distinguished by local `-r2` naming and SHA.

- [ ] **Step 2: Run repository whitespace validation**

```bash
git diff --check
```

Expected: no output and exit code 0.

### Task 5: Build and inspect the release candidate

**Files:**
- Generate: `app/build/outputs/apk/addy_jams/release/app-addy_jams-release-<timestamp>.apk`

- [ ] **Step 1: Run the full flavor unit tests**

```bash
./gradlew :app:testAddy_jamsDebugUnitTest
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Build the signed release APK**

```bash
./gradlew :app:assembleAddy_jamsRelease
```

Expected: BUILD SUCCESSFUL and a timestamped APK in the `addy_jams/release` output directory.

- [ ] **Step 3: Inspect the packaged implementation**

Use JADX to verify the APK contains the R2 `UnifiedAdSdk` terminal guards/main-thread detach behavior and `HaierAarRuntimeBridge`. Verify the APK signature and record the APK SHA-256.

- [ ] **Step 4: Commit the integration**

Stage only the R2 AAR, Gradle/test/documentation changes, review the staged diff and artifact hash, then commit with:

```bash
git commit -m "fix: 升级 addy_jams LSAP 播放器释放修订包"
```
