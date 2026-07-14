# Google Video Ad SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the `google_ad_tv_desktop` GAM/IMA playback flow into a customer-facing, pure-Java Android Library that builds a reusable AAR and is consumed by the existing channel.

**Architecture:** A static `GoogleVideoAds` facade owns immutable initialization config, while every `play()` call creates an isolated `AdSession`. The session resolves the GAM URL with OkHttp/Gson, delegates rendering to a Media3/IMA player adapter, emits main-thread callbacks, and guarantees one terminal `AdResult` before idempotent cleanup.

**Tech Stack:** Java 11, Android Library/AGP 8.10.1, OkHttp 4.12.0, Gson 2.13.2, Media3 1.8.0, Google IMA, Kotlin stdlib 2.2.21 as an OkHttp runtime dependency, JUnit 4.

---

### Task 1: Scaffold the Android Library and define Java API contracts

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle`
- Modify: `settings.gradle`
- Create: `google-video-ad-sdk/build.gradle`
- Create: `google-video-ad-sdk/src/main/AndroidManifest.xml`
- Create: `google-video-ad-sdk/consumer-rules.pro`
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/PublicApiContractTest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/SdkConfig.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdRequest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdState.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdResultStatus.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdErrorCode.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdErrorStage.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdError.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdResult.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdSession.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/AdListener.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/InitializationListener.java`

- [ ] **Step 1: Add the library plugin alias and module scaffolding**

Add `android-library` to the version catalog, apply it with `apply false` in the root build, include `:google-video-ad-sdk`, and configure the module with namespace `com.smart.android.googlevideoad`, min SDK 23, compile SDK 36, Java 11, consumer rules, and the pinned runtime/test dependencies.

- [ ] **Step 2: Write the failing public API contract test**

```java
@Test
public void sdkConfigRequiresTrimmedChannelId() {
    SdkConfig config = new SdkConfig.Builder()
        .setChannelId("  GOOGLE_AD_TV_DESKTOP  ")
        .setDebugLogging(true)
        .build();

    assertEquals("GOOGLE_AD_TV_DESKTOP", config.getChannelId());
    assertTrue(config.isDebugLogging());
}

@Test(expected = IllegalArgumentException.class)
public void sdkConfigRejectsBlankChannelId() {
    new SdkConfig.Builder().setChannelId("   ").build();
}

@Test
public void adRequestDefaultsToMutedPlayback() {
    assertFalse(new AdRequest.Builder().build().isSoundEnabled());
}
```

- [ ] **Step 3: Run the test and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.PublicApiContractTest --console=plain`

Expected: compilation fails because the public API classes do not exist.

- [ ] **Step 4: Implement the minimal immutable Java contracts**

Implement builder-based `SdkConfig` and `AdRequest`, enums for state/result/error, immutable `AdError` and `AdResult`, and the following interfaces:

```java
public interface AdSession {
    AdState getState();
    void pause();
    void resume();
    void setSoundEnabled(boolean enabled);
    void release();
}

public interface AdListener {
    void onLoaded(AdSession session);
    void onStarted(AdSession session);
    void onFinished(AdSession session, AdResult result);
}

public interface InitializationListener {
    void onInitialized();
    void onError(AdError error);
}
```

- [ ] **Step 5: Run the public API test and verify GREEN**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.PublicApiContractTest --console=plain`

Expected: all `PublicApiContractTest` tests pass.

### Task 2: Implement GAM configuration parsing and HTTP resolution

**Files:**
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/internal/GamConfigParserTest.java`
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/internal/GamConfigClientTest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/GamPlaybackConfig.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/GamResolveResult.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/GamConfigParser.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/GamConfigResolver.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/GamConfigClient.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/Cancellable.java`

- [ ] **Step 1: Write failing parser tests for wrapped, direct, and no-ad responses**

```java
@Test
public void parsesWrappedPlaybackConfig() throws Exception {
    String json = "{\"code\":100000,\"data\":{\"enabled\":true," +
        "\"ad_tag_url\":\"https://pubads.g.doubleclick.net/test\"," +
        "\"ad_load_timeout_ms\":12000,\"ad_startup_timeout_ms\":25000}}";

    GamResolveResult result = parser.parse(json);

    assertTrue(result.hasAd());
    assertEquals(12000, result.getConfig().getAdLoadTimeoutMs());
    assertEquals(25000L, result.getConfig().getAdStartupTimeoutMs());
}

@Test
public void disabledConfigProducesSkipResult() throws Exception {
    GamResolveResult result = parser.parse("{\"code\":100000,\"data\":{\"enabled\":false}}");
    assertFalse(result.hasAd());
    assertEquals("CONFIG_DISABLED", result.getSkipReason());
}
```

- [ ] **Step 2: Run parser tests and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.GamConfigParserTest --console=plain`

Expected: compilation fails because parser classes do not exist.

- [ ] **Step 3: Implement parser with Gson and safe timeout defaults**

Use defaults `20_000` ms for media load and `35_000L` ms for startup. Accept the production envelope (`code/data`) and a direct object for resilience; blank URLs produce `NO_AD_TAG`, disabled responses produce `CONFIG_DISABLED`, and malformed JSON throws a parser exception consumed by the client.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.GamConfigParserTest --console=plain`

Expected: parser tests pass.

- [ ] **Step 5: Write failing OkHttp recording-interceptor tests**

```java
@Test
public void postsChannelIdAndReturnsParsedConfig() throws Exception {
    client.resolve("CHANNEL_A", callback);

    Request request = interceptor.getRecordedRequest();
    assertEquals("/api/v2/ad/google-gam/resolve", request.url().encodedPath());
    assertTrue(readRequestBody(request).contains("\"channel_id\":\"CHANNEL_A\""));
    assertTrue(callback.await().hasAd());
}
```

- [ ] **Step 6: Run HTTP tests and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.GamConfigClientTest --console=plain`

Expected: compilation fails because `GamConfigClient` is missing.

- [ ] **Step 7: Implement asynchronous OkHttp resolution and cancellation**

POST JSON `{ "channel_id": channelId }`, use an OkHttp call timeout of 20 seconds, map non-2xx/transport/parser errors to `AdError` with `CONFIG_HTTP_ERROR`, `CONFIG_NETWORK_ERROR`, or `CONFIG_PARSE_ERROR`, and return a `Cancellable` backed by `Call.cancel()`.

- [ ] **Step 8: Run HTTP tests and verify GREEN**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.GamConfigClientTest --console=plain`

Expected: recording-interceptor tests pass.

### Task 3: Implement the isolated AdSession state machine

**Files:**
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/internal/AdSessionImplTest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/CallbackDispatcher.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/MainThreadDispatcher.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/AdPlayer.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/AdPlayerFactory.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/AdSessionImpl.java`

- [ ] **Step 1: Write failing happy-path and terminal-once tests**

```java
@Test
public void sessionResolvesLoadsStartsAndCompletes() {
    AdSessionImpl session = fixture.createSession();
    session.start();
    fixture.resolver.succeed(GamPlaybackConfig.create("https://example.test/vast", 20000, 35000));
    fixture.player.emitLoaded();
    fixture.player.emitStarted();
    fixture.player.emitCompleted();

    assertEquals(Arrays.asList("loaded", "started", "finished:COMPLETED"), fixture.events);
    assertEquals(AdState.FINISHED, session.getState());
}

@Test
public void competingTerminalCallbacksNotifyOnlyOnce() {
    AdSessionImpl session = fixture.startedSession();
    fixture.player.emitError(new RuntimeException("boom"));
    fixture.player.emitCompleted();
    session.release();
    assertEquals(1, fixture.finishedCount());
}
```

- [ ] **Step 2: Run state-machine tests and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.AdSessionImplTest --console=plain`

Expected: compilation fails because session dependencies do not exist.

- [ ] **Step 3: Implement minimal session orchestration**

State transitions are `RESOLVING_CONFIG -> LOADING -> PLAYING <-> PAUSED -> FINISHED`. A synchronized terminal guard ensures only one `COMPLETED`, `SKIPPED`, `ERROR`, or `CANCELLED` result. All listener calls use `CallbackDispatcher`; cleanup cancels the network call, releases the player, clears its reference, and is idempotent.

- [ ] **Step 4: Add failing control tests**

Test that `pause`, `resume`, `setSoundEnabled`, and early `release` delegate to the player only when legal, and that early release produces `CANCELLED` exactly once.

- [ ] **Step 5: Implement control delegation and verify GREEN**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.AdSessionImplTest --console=plain`

Expected: all state-machine tests pass.

### Task 4: Implement the Media3/Google IMA player adapter

**Files:**
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/internal/PlaybackEventGateTest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/PlaybackEventGate.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/ImaAdPlayer.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/ImaAdPlayerFactory.java`

- [ ] **Step 1: Write the failing event-gate test**

```java
@Test
public void loadedStartedAndTerminalSignalsAreDeduplicated() {
    PlaybackEventGate gate = new PlaybackEventGate();
    assertTrue(gate.markLoaded());
    assertFalse(gate.markLoaded());
    assertTrue(gate.markStarted());
    assertFalse(gate.markStarted());
    assertTrue(gate.markTerminal());
    assertFalse(gate.markTerminal());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.internal.PlaybackEventGateTest --console=plain`

Expected: compilation fails because `PlaybackEventGate` is missing.

- [ ] **Step 3: Implement the event gate and verify GREEN**

Run the same command and expect it to pass.

- [ ] **Step 4: Implement `ImaAdPlayer` using the proven current channel behavior**

Create a `PlayerView`, `ImaAdsLoader`, `ExoPlayer`, `AdsMediaSource`, and silent content source. Keep the container transparent until both IMA `STARTED` and ExoPlayer first-frame events occur; map IMA `LOADED`, `STARTED`, `COMPLETED`, `ALL_ADS_COMPLETED`, `SKIPPED`, and error callbacks through `AdPlayer.Listener`; enforce startup timeout; and release loader/player/views on every terminal path.

- [ ] **Step 5: Compile the library adapter**

Run: `./gradlew :google-video-ad-sdk:compileDebugJavaWithJavac --console=plain`

Expected: Java compilation succeeds with no missing Media3/IMA symbols.

### Task 5: Implement the `GoogleVideoAds` facade

**Files:**
- Create: `google-video-ad-sdk/src/test/java/com/smart/android/googlevideoad/GoogleVideoAdsTest.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/GoogleVideoAds.java`
- Create: `google-video-ad-sdk/src/main/java/com/smart/android/googlevideoad/internal/SdkRuntime.java`

- [ ] **Step 1: Write failing initialization/play contract tests**

```java
@Test
public void initializeStoresChannelAndReportsSuccess() {
    GoogleVideoAds.initialize(context, new SdkConfig.Builder().setChannelId("CHANNEL_A").build(), listener);
    assertEquals(1, listener.successCount);
}

@Test
public void playBeforeInitializationFinishesWithInitError() {
    AdSession session = GoogleVideoAds.play(container, new AdRequest.Builder().build(), adListener);
    assertEquals(AdErrorCode.INIT_NOT_CALLED, adListener.awaitResult().getError().getCode());
}
```

- [ ] **Step 2: Run facade tests and verify RED**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --tests com.smart.android.googlevideoad.GoogleVideoAdsTest --console=plain`

Expected: compilation fails because the facade/runtime is missing.

- [ ] **Step 3: Implement synchronized initialization and session creation**

Use application context, endpoint `https://api.kytira.cc/api/v2/ad/google-gam/resolve`, one shared OkHttp client, Gson parser, main-thread dispatcher, and `ImaAdPlayerFactory`. `play()` validates non-null arguments and returns a session immediately; uninitialized calls return a failed session whose terminal callback is dispatched on the main thread.

- [ ] **Step 4: Run facade and full module tests**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest --console=plain`

Expected: all SDK unit tests pass.

### Task 6: Replace the in-app Google channel implementation with the SDK

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt`
- Delete: `app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdVastPlayerView.kt`
- Delete: `app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdTvDesktopVastConfig.kt`
- Delete: `app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleGamAdConfigClient.kt`
- Modify: `app/src/test/java/com/smart/android/ad_app/GoogleAdTvDesktopFlavorContractTest.kt`
- Create: `google-video-ad-sdk/README.md`

- [ ] **Step 1: Update the channel contract test first**

Require `google_ad_tv_desktopImplementation project(':google-video-ad-sdk')`, calls to `GoogleVideoAds.initialize`/`GoogleVideoAds.play`, result mapping, and absence of the three old flavor-local player/config files.

- [ ] **Step 2: Run the channel contract test and verify RED**

Run: `./gradlew :app:testGoogle_ad_tv_desktopDebugUnitTest --tests com.smart.android.ad_app.GoogleAdTvDesktopFlavorContractTest --console=plain`

Expected: the updated assertions fail because the channel still uses local Media3/IMA code.

- [ ] **Step 3: Adapt the channel manager and remove duplicated implementation**

Keep hq008 reporting in the app adapter. Map SDK `onLoaded` and `onStarted` to existing reporting/callbacks; map `COMPLETED` and `SKIPPED` to `adComplete`, `ERROR` to `adError`, and `CANCELLED` to cancellation logging without duplicate host callbacks. Release the previous `AdSession` before starting a new one.

- [ ] **Step 4: Document Java customer integration**

Document local-AAR dependencies, Maven behavior, manifest permissions, initialize/play/session examples, result semantics, lifecycle cleanup, and consumer ProGuard behavior.

- [ ] **Step 5: Run channel and SDK tests**

Run: `./gradlew :google-video-ad-sdk:testDebugUnitTest :app:testGoogle_ad_tv_desktopDebugUnitTest --console=plain`

Expected: all tests pass.

- [ ] **Step 6: Build release artifacts and affected app variants**

Run: `./gradlew :google-video-ad-sdk:assembleRelease :app:assembleGoogle_ad_tv_desktopDebug :app:assembleGoogle_ad_tv_desktopRelease --console=plain`

Expected: builds succeed and `google-video-ad-sdk/build/outputs/aar/google-video-ad-sdk-release.aar` exists.

- [ ] **Step 7: Inspect the AAR contents**

Run: `unzip -l google-video-ad-sdk/build/outputs/aar/google-video-ad-sdk-release.aar`

Expected: public SDK classes, manifest, resources, and consumer ProGuard rules are present; Media3, IMA, OkHttp, and Gson dependency classes are not duplicated into the normal AAR.
