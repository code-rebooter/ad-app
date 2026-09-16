# Ad SDK 最新依赖版接入说明

适用依赖：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.15'
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
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.15'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

- `adAppId`：客户自己的广告平台 App ID。
- `adChannelId`：后台分配的渠道 ID。
- 这个版本包含 Google UMP/CMP 流程。
- 不要和 `ad-sdk`、`ad-sdk-modern-no-ump`、`ad-sdk-gam-vast` 同时接入。

### HQ002 锁屏渠道配置

使用 `v1.0.15` 或以上版本，将上面 `adChannelId` 的值设为：

```groovy
adChannelId: "GOOGLE_AD_TV_LOCKSCREEN_HQ002"
```

SDK 会自动使用 `https://api.kartna.cc/` 作为广告流程接口域名，覆盖流程控制、UMP/CMP 决策与结果上报、广告配置请求和广告事件上报，无需额外配置域名。广告素材地址仍由后台返回。

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
