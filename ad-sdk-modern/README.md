# Ad SDK 最新依赖版接入说明

适用依赖：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.17'
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
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.17'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

- `adAppId`：客户自己的广告平台 App ID。
- `adChannelId`：后台分配的渠道 ID。
- 这个版本包含 Google UMP/CMP 流程。
- 不要和 `ad-sdk`、`ad-sdk-modern-no-ump`、`ad-sdk-gam-vast` 同时接入。

`v1.0.17` 使用 `TextureView` 修复隐藏模式和起播黑底，并在 SDK 清单中声明默认开启硬件加速。宿主没有显式关闭时通常无需额外设置。`FloatingAdService` 悬浮窗创建参数、Activity 配置与实际状态确认方式见 [显示修复与硬件加速说明](DISPLAY_FIX.md#硬件加速配置)。

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
            Log.i("AdSdk", "onFinished requestId=" + session.getRequestId()
                + ", status=" + result.getStatus()
                + ", message=" + result.getMessage());
            AdError error = result.getError();
            if (error != null) {
                Log.e("AdSdk", "source=" + error.getSource()
                    + ", originalCode=" + error.getOriginalCode()
                    + ", message=" + error.getMessage(), error.getCause());
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

一次 `AdSdk.play()` 处理一轮广告，`onFinished` 表示本轮结束，状态可能是 `COMPLETED`、`SKIPPED`、`ERROR` 或 `CANCELLED`。需要下一轮广告时，由接入方按业务间隔再次调用 `AdSdk.play()`；已结束的 session 不能通过 `resume()` 重新播放。

## 6. v1.0.16 流程与诊断增强

`v1.0.16` 新增系统属性日志、`onFinished` 自动输出结束详情和原始错误码回传，并补齐整轮流程上报、各阶段耗时及授权信息读取。原始 IMA 错误码通过 `getOriginalCode()` 获取，例如 IMA 返回 303 时得到字符串 `"303"`。

开启 SDK 本地日志：

```sh
adb shell setprop persist.sys.ad.log true
adb shell getprop persist.sys.ad.log
```

确认属性为 `true` 后生效，无需修改客户回调。完整抓取命令及接口日志范围见 [诊断日志说明](DIAGNOSTICS.md)。后台 `popup_log_enabled` 单独控制整轮日志上传，不影响本地日志开关。

`AdSession.getRequestId()` 和 `getNextRequestSeconds()` 可读取本轮后台授权信息，授权拒绝时也保留；SDK 不自动安排下一轮。具体字段见 [通用 SDK 流程同步说明](FLOW_SYNC.md)。

## 7. 播放控制边界

- 总流程默认超时为 180 秒，从 `play()` 开始计时；后台合法值（30～600 秒）可覆盖。宿主无需额外配置，也可通过 `SdkConfig.Builder.setAdCallbackTimeoutMs()` 提供默认值。
- 后台 `sound_mode` 决定本轮播放声音；未返回时使用 `AdRequest` 设置。播放过程中可调用 `session.setSoundEnabled()`。
- 后台 `hidden_mode` 控制 SDK 创建的内部广告层显隐，不直接改变传入的整个 `ViewGroup`。宿主容器自身的背景和其他子视图由宿主管理，隐藏模式不会自动静音。
- CMP 远端决策失败或未知时，仍按现有 UMP 状态决定是否继续。未增加屏保专用接口、每日累计统计或自动轮询。

## 8. v1.0.17 显示修复

隐藏模式同时隐藏内部广告层和视频纹理；可见模式在 IMA STARTED 与视频首帧都到达后渐显。播放器背景和起播遮罩改为透明，释放前先隐藏内部广告层。新增 `AD_DISPLAY_STATE` 与 `AD_FIRST_FRAME` 诊断，仍受 `persist.sys.ad.log` 控制。声音 API 和后台声音优先级保持兼容。硬件加速接入方式与设备验证范围见 [显示修复说明](DISPLAY_FIX.md)。
