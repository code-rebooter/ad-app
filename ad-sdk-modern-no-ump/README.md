# Ad SDK 最新依赖版接入说明（No UMP）

适用依赖：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern-no-ump:v1.0.22'
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
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern-no-ump:v1.0.22'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

- `adAppId`：客户自己的广告平台 App ID。
- `adChannelId`：后台分配的渠道 ID。
- 这个版本不包含 UMP/CMP 流程，保留 fllow、GAM/IMA 播放和原公共 API。
- 不要和 `ad-sdk`、`ad-sdk-modern` 同时接入。

### X88 系统 UID 设备的三个 Job 兼容开关

`v1.0.22` 的广告请求、播放器、参数缓存和 UID 存储兼容逻辑基于 `v1.0.11`，
播放器仍在每轮广告结束时释放。新增的系统 Job 守护与本地 X88 验证包一致。
本次只修改 `ad-sdk-modern-no-ump` 的 SDK 功能代码，其他模块保持 `v1.0.20` 的代码。

需要同步验证该兼容处理的客户，在宿主 `AndroidManifest.xml` 的 `<application>` 内添加：

```xml
<meta-data
    android:name="com.smart.android.adsdk.DISABLE_SYSTEM_MAINTENANCE_JOBS"
    android:value="true" />
```

- 仅 Android 7.0 及以上、应用实际运行 UID 为 `1000` 且显式开启时生效，默认关闭。
- 使用系统签名和已有的 `android.uid.system` 配置；初始化和播放调用保持原样。
- SDK 初始化、开机和覆盖安装后会启动守护，每 5 秒检查并取消 `android` 包的
  Job `800`、`801`（后台 Dex 优化）和 `808`（存储 TRIM），同时校验服务名。
- 不会取消其他应用使用相同编号的 Job。无需宿主额外编写 Java 调用或执行 ADB 命令。
- 守护依赖应用进程或其服务运行，不是永久关闭系统维护，也不保证消除所有设备卡死。
- 核对时可查看 `AdSystemJobGuard` 日志；只替换依赖、未添加此开关，不会启用处理。

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
