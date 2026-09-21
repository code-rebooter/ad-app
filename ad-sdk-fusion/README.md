# Google + TCL 融合 SDK 接入说明

本模块从 `ad-sdk-modern:v1.0.17` 独立复制开发，保留原来的 `com.smart.android.adsdk.*` API。宿主每次调用一次 `AdSdk.play()`，SDK 先执行 Google 流程，再执行 TCL 流程，最后回调一次整轮结果。调用间隔继续由宿主管理。

当前为本地开发模块，尚未发布新的远程版本。不能把 `0.0.0-local` 当作客户远程依赖版本。

## 接入

在本项目中使用：

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
    implementation project(':ad-sdk-fusion')
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

此依赖替换原来的单家 SDK 依赖。由于保留同一套 API 包名，不能与 `ad-sdk-modern`、`ad-sdk`、`ad-sdk-modern-no-ump`、`ad-sdk-gam-vast` 同时引入。

模块带有三个 TCL 2.8.02 原厂 AAR 子项目，后续远程发布会通过 Maven 传递依赖一起下载。单独复制主 `ad-sdk-fusion-release.aar` 不包含这些依赖，也不包含 Media3、UMP 等 Maven 库。

### TCL 初始化配置

融合版统一内置项目方提供的 TCL 登记配置，宿主继续只配置原来的 `adAppId` 和 `adChannelId`，无需额外传 TCL 包名、签名或初始化 metadata。

| TCL 字段 | 内置值 |
| --- | --- |
| 包名 | `com.google.android.adhq1001` |
| 签名 MD5 | `D2A9B2A8A9E0AF740267C0BC356DC1A4` |
| `partner_name` | `chhkj_1` |
| `project_id` | `213` |
| `tcl_app_key` | 使用项目方提供的项目 213 对应 App Key，内置于 SDK |

TCL 2.8.02 的 base AAR 在构建时打补丁，把授权参数、初始化配置及 TCL BI 包名/签名读取接到 `TclIdentityBridge`。TCL 请求的 `appDomain` 同步使用这套包名。包名、MD5、Key、项目号作为同一套配置维护，不混用其他渠道的登记信息。

Android 的宿主包名、APK 签名、资源和 Manifest 查询仍使用宿主实际信息；Google 分支及我们后台上报的宿主信息保持原有来源。IAB 授权数据仍从宿主自己的 SharedPreferences 读取。

原始 TCL AAR 保留在 `vendor/base/sdk.aar`，实际本地依赖及 Maven 发布都使用构建生成的补丁 AAR，详情见 [TCL AAR 说明](vendor/README.md)。后续调整登记配置只需改 SDK 内部桥接配置，宿主无需重新设计接入参数。

## 渠道与共用域名

| 用途 | 渠道号 |
| --- | --- |
| Google | 宿主配置的 `adChannelId` 原值 |
| TCL | 原值追加 `_TCL` |

例如 `GOOGLE_AD_TV_LOCKSCREEN_HQ002` 自动配对 `GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL`。后台需要事先配置两个渠道。

**两家共用的业务接口域名只按原始渠道选择一次：**

| 原始渠道 | 两家共用的业务域名 |
| --- | --- |
| `GOOGLE_AD_TV_LOCKSCREEN_HQ002` | `https://api.kartna.cc/` |
| `GOOGLE_AD_TV_CVTE` | `https://api.xartek.cc/` |
| 其他 | `https://api.kytira.cc/` |

业务接口包括流程控制、授权、广告事件和整轮日志上报；Google 的 CMP 决策及 GAM 配置也使用该域名。广告平台请求和素材地址由各自平台决定。

## 初始化与播放

```java
AdSdk.initialize(getApplicationContext(), new InitializationListener() {
    @Override public void onInitialized() {}
    @Override public void onError(AdError error) {
        Log.e("AdSdk", error.getMessage(), error.getCause());
    }
});

AdSession session = AdSdk.play(adContainer,
    new AdRequest.Builder().setSoundEnabled(true).build(),
    new AdListener() {
        @Override public void onLoaded(AdSession session) {}
        @Override public void onStarted(AdSession session) {}
        @Override public void onFinished(AdSession session, AdResult result) {
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
    });
```

两家分别经过流程控制与授权。Google 保留原来的 UMP 和 GAM 流程；TCL 授权允许后使用原厂 SDK 请求广告，不调用 Google GAM 配置接口，也不再次运行 Google UMP。TCL 请求携带应用现有的 IAB TCF 授权信号。

单家播放成功、失败或因策略跳过后，都继续下一家。宿主主动释放则结束整轮，不再启动余下渠道。调用期间再次 `play()` 会返回流程占用错误，不并行抢占同一 TCL 播放引擎。

## 回调与结果

| 回调 | 含义 |
| --- | --- |
| `onLoaded` | 本轮第一家加载成功时通知，最多一次 |
| `onStarted` | 本轮第一家开始播放时通知，最多一次 |
| `onFinished` | 两家均结束或宿主取消整轮后通知，恰好一次 |

所有回调返回同一个融合 `AdSession`。如果都没有加载或播放成功，对应的 `onLoaded`、`onStarted` 可以不发生。

整轮结果规则：

- 宿主在整轮结束前 `release()`：`CANCELLED`，即使之前已有一家成功。
- 任意一家完整播放成功：`COMPLETED`。另一家的失败仍保留在它自己的后台上报与日志中。
- 都没有成功：优先返回其中的 `ERROR`；否则保留带错误的 `SKIPPED`；否则使用 Google 的原结果。两家均为同类结果时优先 Google。
- 保留单家原有状态、错误来源、原始码和原始消息，不重新映射平台错误。例如 IMA 303 的 `originalCode` 仍为 `"303"`。TCL `onAdError(int)` 只提供整数，错误码与消息均保留该数值字符串。

`getRequestId()` 与 `getNextRequestSeconds()` 保留 Google 分支的授权信息，TCL 使用自己的独立请求 ID，不覆盖这两个兼容字段。`getNextRequestSeconds()` 只是信息，SDK 不据此定时发起下一轮。

## 超时、声音、显示与释放

每家单独计时，默认总流程超时均为 180 秒，各自后台的合法超时配置（30～600 秒）覆盖各自默认值。Google 耗时不扣除 TCL 的预算；整轮时长因此可能达到两家耗时之和。宿主可继续使用原来的 `SdkConfig` 配置默认值。

两家各自授权响应的 `hidden_mode`、`sound_mode` 分别生效。后台未返回声音配置时使用宿主请求的默认声音值。`session.setSoundEnabled()` 控制当前播放器，并更新后续渠道的声音默认值；后续渠道的明确后台配置仍然优先。

SDK 只管理添加到传入 `ViewGroup` 中的内部广告层。Google 和 TCL 均使用 TextureView，可见模式等待起播与首帧，隐藏模式保持内部层透明；宿主背景、其他子视图和窗口参数仍由宿主管理。SDK 清单提供 `hardwareAccelerated=true` 默认值，实际窗口仍需要支持硬件加速。

```java
session.pause();
session.resume();
session.setSoundEnabled(false);
session.release(); // 容器销毁时调用；取消当前分支和后续分支
```

`pause()` / `resume()` 作用于当前播放器；已经结束的 session 不会重播。下一轮仍由宿主再次调用 `play()`。

## 后台统计与日志

两家使用各自渠道、各自授权请求 ID 上报请求、加载、播放、结束与失败信息。现有事件结构保持兼容，`diagnostic_info.sdkEntry` 分别为 `ima` / `tcl`。后台按 `channel_id` 区分两家统计；授权允许不等于广告加载或播放成功，应继续按各事件统计。

融合层仅合并宿主回调，不额外生成第三条广告完成上报。未引入 HQ008 屏保累计次数、每日计数或自动轮询。

```sh
adb shell setprop persist.sys.ad.log true
adb shell getprop persist.sys.ad.log
adb logcat -v threadtime > ad-fusion.log
```

该开关控制 SDK 自己的业务接口请求/响应、分支切换、显示状态和 `onFinished` 详情日志。`AdSdkFusion` 可关联整轮，单家日志有 `channel` / `requestId`；两家原生播放器事件也转入单家流程日志。TCL 原厂日志开关在 TCL 分支开始时同步读取该属性，原厂能输出的具体内容由其 SDK 决定。后台 `popup_log_enabled` 单独控制该渠道整轮日志上传。
