# SDK 接入说明

## 1. 添加仓库

在 `settings.gradle` 的仓库列表中添加：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

## 2. 添加依赖和配置

在 App 模块的 `build.gradle` 中配置：

```groovy
android {
    defaultConfig {
        minSdk 23
        manifestPlaceholders += [
            adAppId    : "ca-app-pub-你的AppID~xxxx",
            adChannelId: "分配给你的渠道号"
        ]
    }
    compileOptions {
        coreLibraryDesugaringEnabled true
    }
}

dependencies {
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-fusion:v1.0.20'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

用上述依赖替换旧广告 SDK，勿同时引入。只需填写 App ID 和渠道号，无需额外的广告平台配置。

## 3. 初始化

以下示例使用 `com.smart.android.adsdk.*` 中的 API。应用启动时初始化一次，成功后再请求广告：

```java
AdSdk.initialize(getApplicationContext(), new InitializationListener() {
    @Override public void onInitialized() {
        // 可以请求广告
    }

    @Override public void onError(AdError error) {
        Log.e("AdSdk", error.getMessage(), error.getCause());
    }
});
```

## 4. 请求广告

`adContainer` 为已挂载的 `ViewGroup`，所在窗口需开启硬件加速。将返回的 `AdSession` 保存为成员变量 `adSession`：

```java
adSession = AdSdk.play(adContainer, new AdRequest.Builder().build(),
    new AdListener() {
        @Override public void onLoaded(AdSession session) {}
        @Override public void onStarted(AdSession session) {}

        @Override public void onFinished(AdSession session, AdResult result) {
            Log.i("AdSdk", "status=" + result.getStatus()
                + ", message=" + result.getMessage());
            AdError error = result.getError();
            if (error != null) {
                Log.e("AdSdk", "source=" + error.getSource()
                    + ", code=" + error.getOriginalCode()
                    + ", message=" + error.getMessage());
            }
        }
    });
```

每次请求对应一整轮，`onFinished` 回调一次；`onLoaded`、`onStarted` 各最多一次。下一轮由宿主自行定时调用，本轮结束前不要重复请求。

结束状态：`COMPLETED` 表示本轮有广告完整播放，`SKIPPED` 表示跳过，`ERROR` 表示失败，`CANCELLED` 表示已取消。

页面或容器销毁时释放：

```java
if (adSession != null) {
    adSession.release();
    adSession = null;
}
```

## 5. 开启排查日志

```sh
adb shell setprop persist.sys.ad.log true
adb logcat -v threadtime > ad.log
```
