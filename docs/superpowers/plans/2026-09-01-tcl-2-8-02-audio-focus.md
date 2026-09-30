# TCL 2.8.02 Concurrent Video Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent every TCL 2.8.02 ad flavor from requesting audio focus or blocking the video output of a concurrently playing third-party application.

**Architecture:** Extend the existing SHA-pinned AAR bytecode patch pipeline. Keep the audio-focus getter patch, and map only TCL's `SurfaceView` request (`0`) to `TextureView` (`1`) at `setSurfaceType(IZ)V`; keep runtime code, other surface types, decoder selection, and `sound_mode` volume handling unchanged.

**Tech Stack:** Android Gradle Plugin, Groovy, ASM tree API, JUnit 4, Kotlin contract tests.

---

### Task 1: Add A Failing Bytecode Regression Test

**Files:**
- Modify: `buildSrc/build.gradle`
- Create: `buildSrc/src/test/groovy/com/smart/lsap/LsapClassPatcherAudioFocusTest.groovy`

- [ ] **Step 1: Add JUnit 4 to buildSrc tests**

```groovy
testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Generate the TCL config class shape and assert the patch disables its getter**

```groovy
@Test
void 'hq008 patch disables TCL 2_8_02 audio focus config getter only'() {
    byte[] original = configClassWithBooleanGetters(true)
    assertEquals(ICONST_1, constantReturnedBy(original, 'w'))

    byte[] patched = LsapClassPatcher.patchHq008Parameters(
        'com/tcl/uniplayer/tuniplayer/b.class',
        original
    )

    assertEquals(ICONST_0, constantReturnedBy(patched, 'w'))
    assertEquals(ICONST_1, constantReturnedBy(patched, 'q'))
}
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
./gradlew -p buildSrc test --tests com.smart.lsap.LsapClassPatcherAudioFocusTest
```

Expected: FAIL because `w()Z` still returns `ICONST_1`.

### Task 2: Disable TCL Audio Focus In The Existing AAR Patch

**Files:**
- Modify: `buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy`
- Modify: `buildSrc/src/main/groovy/com/smart/lsap/Hq008AarPatchTask.groovy`

- [ ] **Step 1: Route each HQ008 method through the focused audio policy patch**

```groovy
if (patchHq008AudioFocusPolicy(entryName, method)) {
    changed = true
}
```

- [ ] **Step 2: Replace only TCL 2.8.02's audio-focus getter**

```groovy
private static boolean patchHq008AudioFocusPolicy(String entryName, MethodNode method) {
    if (entryName == 'com/tcl/uniplayer/tuniplayer/b.class' &&
        method.name == 'w' && method.desc == '()Z') {
        replaceMethodBodyWithConstantBoolean(method, false)
        return true
    }
    return false
}
```

- [ ] **Step 3: Record the policy in generated AAR metadata**

```groovy
'patchVersion=hq008-parameter-normalization-2',
'audioFocusPolicy=disabled',
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
./gradlew -p buildSrc test --tests com.smart.lsap.LsapClassPatcherAudioFocusTest
```

Expected: PASS.

### Task 3: Lock Flavor Coverage And Verify Artifacts

**Files:**
- Modify: `app/src/test/java/com/smart/android/ad_app/Hq008AdSdkDependencyIsolationTest.kt`

- [ ] **Step 1: Add all TCL 2.8.02 flavors to the dependency contract**

```kotlin
listOf(
    "hq008Implementation patchedAar",
    "tcl_aishangImplementation patchedAar",
    "ad_ytx01Implementation patchedAar",
    "ad_album_101_001Implementation patchedAar",
    "hq008NoneuImplementation patchedAar",
    "hq008Noneuc2Implementation patchedAar",
    "tcl_polyImplementation patchedAar",
    "hq008XHSXImplementation patchedAar"
).forEach { dependency -> assertTrue(buildGradle.contains(dependency)) }
```

- [ ] **Step 2: Run the focused app contract test**

Run:

```bash
./gradlew :app:testHq008DebugUnitTest --tests com.smart.android.ad_app.Hq008AdSdkDependencyIsolationTest
```

Expected: PASS.

- [ ] **Step 3: Generate the patched player AAR**

Run:

```bash
./gradlew :app:patchHq008XhsxPlayerAar
```

Expected: task succeeds and reports at least one modified class.

- [ ] **Step 4: Inspect the generated player class**

Extract `classes.jar` from
`app/build/generated/hq008XHSX-patched/adsdk_overseas_player-2.8.02-patched.aar`
and run:

```bash
javap -p -c com.tcl.uniplayer.tuniplayer.b
```

Expected: `public final boolean w()` consists of `iconst_0; ireturn`.

- [ ] **Step 5: Build every TCL 2.8.02 debug variant**

Run:

```bash
./gradlew :app:assembleHq008Debug :app:assembleHq008XHSXDebug \
  :app:assembleTcl_aishangDebug :app:assembleAd_ytx01Debug \
  :app:assembleAd_album_101_001Debug :app:assembleHq008NoneuDebug \
  :app:assembleHq008Noneuc2Debug :app:assembleTcl_polyDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Perform the device regression check**

Start YouTube playback, trigger a muted TCL ad, and capture logcat.

Expected: no `MediaFocusControl requestAudioFocus()` entry from the ad package,
no `AUDIOFOCUS_LOSS` delivered to YouTube, and YouTube remains playing.

### Task 4: Add A Failing Concurrent Render Policy Test

**Files:**
- Create: `buildSrc/src/test/groovy/com/smart/lsap/LsapClassPatcherSurfaceTypeTest.groovy`

- [ ] **Step 1: Generate the actual obfuscated TCL player-base class shape**

Generate `com/tcl/ff/component/uniplayer/f/k.class` with
`setSurfaceType(IZ)V` and a getter for the stored surface type.

- [ ] **Step 2: Assert the exact value mapping**

Execute the patched generated class and verify `0 -> 1`, `1 -> 1`, and
`-1 -> -1`. Also verify that a matching method in an unrelated class is not
modified.

- [ ] **Step 3: Run the focused test and verify RED**

```bash
./gradlew -p buildSrc test --tests com.smart.lsap.LsapClassPatcherSurfaceTypeTest
```

Expected: FAIL because input `0` is still stored as `0`.

### Task 5: Map TCL SurfaceView Requests To TextureView

**Files:**
- Modify: `buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy`
- Modify: `buildSrc/src/main/groovy/com/smart/lsap/Hq008AarPatchTask.groovy`

- [ ] **Step 1: Add the exact ASM method-entry patch**

For `com/tcl/ff/component/uniplayer/f/k.class#setSurfaceType(IZ)V`, insert a
method-entry guard that replaces local parameter `1` with `1` only when its
original value is `0`.

- [ ] **Step 2: Update generated AAR diagnostics metadata**

Increment `patchVersion` and record the TextureView render policy.

- [ ] **Step 3: Verify GREEN and existing regression coverage**

Run both buildSrc policy tests and the app flavor dependency contract test.

- [ ] **Step 4: Inspect the generated player AAR**

Generate the player AAR and use `javap` to confirm the exact
`setSurfaceType(IZ)V` method-entry guard and the existing `w()Z` false return.

- [ ] **Step 5: Build the customer test artifact**

Run `./gradlew :app:assembleAd_ytx01Debug`, then report the APK path, package,
version, file size, and SHA-256. Device behavior remains a test-team check.
