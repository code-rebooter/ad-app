# GAM VAST Ad SDK

This SDK keeps the public API of `ad-sdk-modern` but runs the HQ008 business
gate before playback:

1. `POST /api/v2/ad/sdk/flow-control`
2. `POST /api/v2/ad/sdk/authorize`
3. `POST /api/v2/ad/google-gam/resolve`
4. self-managed VAST / VMAP parsing, tracking, and Media3 playback

It does not include the Google IMA SDK, Google CMP runtime, WebView ad playback,
or Google Mobile Services ad runtime. It also does not schedule repeated ad
requests; the caller decides when to call `AdSdk.play(...)`.

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-gam-vast:<version>'
```

Configure the existing channel metadata:

```groovy
android {
    defaultConfig {
        manifestPlaceholders += [
            adChannelId: "CUSTOMER_CHANNEL"
        ]
    }
}
```

The public package and calls remain unchanged:

```java
AdSdk.initialize(getApplicationContext(), initializationListener);

AdSession session = AdSdk.play(
    adContainer,
    new AdRequest.Builder()
        .setSoundEnabled(true)
        .build(),
    adListener
);
```

Replace the old dependency with this artifact; application code does not need
to change.
