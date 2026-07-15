# Google Video Ad SDK

`google-video-ad-sdk` 是面向 Android TV 客户的 Java AAR，负责：

- 根据客户传入的 `channelId` 请求 GAM 配置。
- 使用 OkHttp 和 Gson 解析后台响应。
- 使用 Media3、ExoPlayer 和 Google IMA 播放 VAST 广告。
- 通过独立 `AdSession` 管理每次播放的状态、声音和资源释放。

## 环境要求

- Android minSdk 23 或更高。
- Java 8 或更高；SDK 本身使用 Java 11 编译。
- 宿主必须能够访问 `https://api.kytira.cc/` 和 GAM/IMA 所需网络地址。

SDK Manifest 已声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 本地 AAR 接入

将 `google-video-ad-sdk-release.aar` 放入宿主的 `app/libs`，然后配置：

```groovy
dependencies {
    implementation files('libs/google-video-ad-sdk-release.aar')

    implementation 'androidx.media3:media3-ui:1.8.0'
    implementation 'androidx.media3:media3-exoplayer:1.8.0'
    implementation 'androidx.media3:media3-exoplayer-ima:1.8.0'

    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.13.2'
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21'
}
```

SDK 代码全部使用 Java。OkHttp 4 本身依赖 Kotlin stdlib，但纯 Java 宿主不需要应用 Kotlin Gradle 插件。

普通 AAR 不会把以上第三方依赖合并进自身。如果后续发布到 Maven，应在 POM 中声明这些运行依赖，让 Gradle 自动传递。

## 初始化

建议在 `Application.onCreate()` 中初始化：

```java
GoogleVideoAds.initialize(
    getApplicationContext(),
    new SdkConfig.Builder()
        .setChannelId("CUSTOMER_CHANNEL")
        .setDebugLogging(false)
        .build(),
    new InitializationListener() {
        @Override
        public void onInitialized() {
            // SDK 可以开始播放广告。
        }

        @Override
        public void onError(AdError error) {
            Log.e("GoogleVideoAds", error.getMessage(), error.getCause());
        }
    }
);
```

`channelId` 会在每次后台配置请求中作为 `channel_id` 发送。

## 播放广告

```java
AdSession session = GoogleVideoAds.play(
    adContainer,
    new AdRequest.Builder()
        .setSoundEnabled(true)
        .build(),
    new AdListener() {
        @Override
        public void onLoaded(AdSession session) {
            // IMA 已加载广告。
        }

        @Override
        public void onStarted(AdSession session) {
            // 广告已经开始播放。
        }

        @Override
        public void onFinished(AdSession session, AdResult result) {
            switch (result.getStatus()) {
                case COMPLETED:
                    break;
                case SKIPPED:
                    Log.i("GoogleVideoAds", "skip=" + result.getReason());
                    break;
                case ERROR:
                    Log.e(
                        "GoogleVideoAds",
                        result.getError().getMessage(),
                        result.getError().getCause()
                    );
                    break;
                case CANCELLED:
                    break;
            }
        }
    }
);
```

每个 `play()` 调用都会立即返回一个独立 `AdSession`。所有监听回调都在 Android 主线程执行，并且每个会话最多收到一次 `onFinished()`。

## 生命周期控制

```java
session.pause();
session.resume();
session.setSoundEnabled(false);
session.release();
```

页面、悬浮窗或广告容器销毁时必须调用 `release()`。提前释放会以 `CANCELLED` 结束；重复释放是安全的。

SDK 只移除自己添加的广告根 View，不会调用 `container.removeAllViews()` 清理客户已有内容。

## 混淆配置

AAR 已携带 `consumer-rules.pro`，通过 Gradle 接入时会自动合并到宿主的 R8/ProGuard 配置。SDK 公共 API 不依赖反射，客户无需额外添加 SDK 类的 `-keep` 规则；Media3、IMA、OkHttp 和 Gson 仍使用各自依赖自带的消费者规则。

## 结果含义

| 状态 | 含义 |
| --- | --- |
| `COMPLETED` | 广告正常播放结束 |
| `SKIPPED` | 后台关闭广告、无广告链接或 IMA 跳过 |
| `ERROR` | 配置网络、响应解析、IMA、播放器或超时失败 |
| `CANCELLED` | 客户主动释放会话 |

错误对象包含 `code`、`stage`、`message` 和可选 `cause`。

## 构建 AAR

在项目根目录执行：

```bash
./gradlew :google-video-ad-sdk:assembleRelease
```

输出文件：

```text
google-video-ad-sdk/build/outputs/aar/google-video-ad-sdk-release.aar
```
