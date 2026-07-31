# Ad SDK 接入说明

本文档面向 Android TV 宿主应用，说明如何接入 `ad-sdk-release.aar` 并请求广告。

## 环境要求

- Android minSdk 23 或更高。
- Java 8 或更高。
- 宿主应用需要能访问 `https://api.kytira.cc/` 以及广告播放所需网络地址。

## 接入配置

宿主工程需要能访问 `google()` 和 `mavenCentral()` 仓库。如果工程已经统一配置过，可跳过本段。

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

将 `ad-sdk-release.aar` 放入宿主 `app/libs` 目录，然后在 `app/build.gradle` 中声明本地 AAR：

```groovy
android {
    defaultConfig {
        manifestPlaceholders += [
            adAppId    : "ca-app-pub-客户自己的~xxxx",
            adChannelId: "CUSTOMER_CHANNEL"
        ]
    }
}

dependencies {
    implementation files('libs/ad-sdk-release.aar')
}
```

`adAppId` 是客户自己的广告平台 App ID。
`adChannelId` 是后台分配给当前客户或当前包的渠道 ID，会作为 `channel_id` 参与后台请求。

## 外部依赖

由于本地 AAR 方式不会自动带上传递依赖，宿主应用需要同时声明以下外部依赖：

```groovy
dependencies {
    implementation 'com.google.android.exoplayer:exoplayer:2.17.1'
    implementation 'com.google.android.exoplayer:exoplayer-ui:2.17.1'
    implementation 'com.google.android.exoplayer:extension-ima:2.17.1'

    implementation 'com.squareup.okhttp3:okhttp:3.14.9'
    implementation 'com.google.code.gson:gson:2.13.2'
    implementation 'com.google.android.ump:user-messaging-platform:4.0.0'
}
```

如果宿主工程已经接入了同名依赖，可以复用现有版本，但需要保证运行时包含以上组件。

SDK 的 AAR 会自动合并网络权限、渠道配置和内部透明页面。客户不需要接入 CMP 代码，也不需要自己处理同意弹窗流程。

## 初始化

建议在 `Application.onCreate()` 中初始化：

```java
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdSdk;
import com.smart.android.adsdk.InitializationListener;
import com.smart.android.adsdk.SdkConfig;

AdSdk.initialize(
    getApplicationContext(),
    new InitializationListener() {
        @Override
        public void onInitialized() {
            // SDK 可以开始播放广告。
        }

        @Override
        public void onError(AdError error) {
            Log.e("AdSdk", error.getMessage(), error.getCause());
        }
    }
);
```

如果需要调整整轮广告超时，可传入可选配置：

```java
AdSdk.initialize(
    getApplicationContext(),
    new SdkConfig.Builder()
        .setAdCallbackTimeoutMs(180_000L)
        .build(),
    listener
);
```

默认整轮广告会话超时为 180 秒。超时后本次广告会以 `TIMEOUT` 错误结束并释放资源。

## 播放广告

```java
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdResultStatus;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdSdk;

AdSession session = AdSdk.play(
    adContainer,
    new AdRequest.Builder()
        .setRequestId("host-request-id")
        .setSoundEnabled(true)
        .build(),
    new AdListener() {
        @Override
        public void onLoaded(AdSession session) {
            // 广告资源已加载。
        }

        @Override
        public void onStarted(AdSession session) {
            // 广告已经开始展示。
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

`requestId` 是可选字段，用于串联宿主侧授权、配置和播放日志。

每次 `play()` 都会返回一个独立 `AdSession`。监听回调会在 Android 主线程执行，并且每个会话最多收到一次 `onFinished()`。

## 生命周期

```java
session.pause();
session.resume();
session.setSoundEnabled(false);
session.release();
```

页面、悬浮窗或广告容器销毁时必须调用 `release()`。提前释放会以 `CANCELLED` 结束；重复释放是安全的。

SDK 只移除自己添加的广告 View，不会清理客户已有内容。

## 结果含义

| 状态 | 含义 |
| --- | --- |
| `COMPLETED` | 广告正常展示结束 |
| `SKIPPED` | 本次无可展示广告或播放链路跳过 |
| `ERROR` | 网络、配置、播放器或超时失败 |
| `CANCELLED` | 客户主动释放会话 |

错误对象包含 `code`、`stage`、`message` 和可选 `cause`。
