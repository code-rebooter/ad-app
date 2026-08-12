# GAM VAST Ad SDK

This SDK keeps the public API of `ad-sdk-modern` but uses a self-managed GAM VAST
request, parser, tracker, and Media3 player path. It does not depend on Google
IMA, UMP, WebView, or Google Mobile Services.

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-gam-vast:v1.0.4'
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
