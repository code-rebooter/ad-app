# Ad SDK 最新依赖版接入说明

适用依赖：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.0'
```

## 1. 仓库

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

## 2. App 配置

```groovy
android {
    defaultConfig {
        minSdk 23
        manifestPlaceholders += [
            adAppId    : "ca-app-pub-客户自己的~xxxx",
            adChannelId: "CUSTOMER_CHANNEL"
        ]
    }

    compileOptions {
        coreLibraryDesugaringEnabled true
    }
}

dependencies {
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.0'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

- `adAppId`：客户自己的广告平台 App ID。
- `adChannelId`：后台分配的渠道 ID。
- 不要和 `ad-sdk` 同时接入。

## 3. 初始化

API 包名：`com.smart.android.adsdk.*`

```java
AdSdk.initialize(
    getApplicationContext(),
    new InitializationListener() {
        @Override
        public void onInitialized() {
        }

        @Override
        public void onError(AdError error) {
            Log.e("AdSdk", error.getMessage(), error.getCause());
        }
    }
);
```

## 4. 播放

```java
AdSession session = AdSdk.play(
    adContainer,
    new AdRequest.Builder()
        .setSoundEnabled(true)
        .build(),
    new AdListener() {
        @Override
        public void onLoaded(AdSession session) {
        }

        @Override
        public void onStarted(AdSession session) {
        }

        @Override
        public void onFinished(AdSession session, AdResult result) {
            if (result.getStatus() == AdResultStatus.ERROR) {
                Log.e("AdSdk", result.getError().getMessage(), result.getError().getCause());
            }
        }
    }
);
```

## 5. 释放

```java
session.pause();
session.resume();
session.setSoundEnabled(false);
session.release();
```

广告容器销毁时必须调用 `release()`。
