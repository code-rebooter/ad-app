# Google IMA Ad SDK

独立纯 IMA 播放 AAR。宿主负责业务开关、授权和取得基础广告 URL；本 AAR
只负责 Media3 + IMA 3.35.1 的请求、OM 初始化和播放生命周期，不调用 TCL，
也不使用 independent URL 生成器。

宿主可通过 `GoogleImaAdRequest.Builder.setAdUrlResolver()` 处理 Core 已拼接完成的
广告 URL。回调运行在网络工作线程，原生网络和 WebView 回退都会经过该入口；
解析失败会阻止原广告请求，播放器释放时调用 resolver 的 `cancel()`。

本仓库 Google 渠道宿主已接入 `/api/v2/ad/google-gam/patch`，复用现有后台协议，
按本次授权统一后台返回 URL 的 `vpmute` 后用于广告请求，并记录 `AD_GAM_PATCH_REQUEST/READY`、
`AD_SDK_REQUEST.adLog.final_url` 和独立 `FILLED` 状态。Debug 默认也使用授权接口
下发的地址；仅显式设置 `adb shell setprop debug.google.ima.sample omid` 时使用官方
OMID 样本，测试后执行 `adb shell "setprop debug.google.ima.sample ''"` 恢复。
该显式审计开关也启用原有 Debug 流控/CMP/授权放行；默认关闭，Release 不生效。

运行时使用固定版本的受控 IMA、TV Ads、Advertising ID 和 AppSet AAR。IMA 的
`ms` 改由本地同规则引擎生成；GASS、动态 VM/DEX、SignalSdk、GKS 远程链路和
动态 Secure Signals adapter 被阻断。Advertising ID 与 AppSet 的业务取值保留，
独立诊断和客户端 telemetry 被关闭。TV Ads 保留原收集规则，只将补丁涉及的
固件指纹输入统一交给宿主身份策略修正。

Advertising ID 先读取原版 GMS 值；空值、全零或读取失败时，依次复用 independent
方案的现有本地身份存储和 `google_tcl_ad/aid`。三层身份都按 IMA 原版 `adid`
类型进入普通 URL 与 V2 信号。本地兜底生效的本轮不再获取 AppSet，避免同时生成
`rdid` 与 `pvid/pvid_s`。

`preBuild` 会从固定原版 SHA 重新生成四个补丁 AAR，并与
`gradle/google-ima-controlled-maven` 内的实际依赖逐个校验 SHA；两者不一致时
构建直接失败。

```groovy
implementation project(':ad-sdk-google-ima')
```

```java
GoogleImaAdPlayerView player = new GoogleImaAdPlayerView(context);
container.addView(player);
player.play(
    new GoogleImaAdRequest.Builder(adTagUrl)
        .setSoundEnabled(false)
        .setReportedSoundEnabled(false)
        .setAdLoadTimeoutMs(20_000)
        .setAdStartupTimeoutMs(35_000L)
        .build(),
    listener
);
```

宿主销毁广告窗口时必须调用 `release()`。

`setReportedSoundEnabled()` 控制本次请求的 Google 音量上报，默认 `true`；
传 `false` 时上报静音。该值用于请求 `vpmute`、`adWillPlayMuted`、已有播放器音量入口、
ActiveView Native 和 OMID 音量及对应诊断。实际播放音量由 `setSoundEnabled()` 控制。
本仓库宿主从授权响应的整数 `reported_sound_mode` 读取该值：`0` 静音、`1` 非静音，
未下发或字段异常时统一默认非静音。具体下发位置见
[授权接口](../docs/google_ima_tcl_authorize_api.md)。

仅映射 SDK 原流程已经读取或发送的音量值；不主动触发额外音量回调，也不在客户端
VAST 的 `start` 消息中追加音量。当前 Media3 getter、音量变化回调是否实际触发，
应以运行记录为准。

OMID 的设备音量和媒体音量分别处理：原生 `setDeviceVolume` 映射 `deviceVolume`；
在现有 OM `SessionService.start` / `volumeChange` 消息进入测量服务时，仅映射其已有的有效
媒体音量参数，使测量输出的 `mediaPlayerVolume`、`videoPlayerVolume` 跟随本次配置。
不补缺失参数，不发送额外事件，也不修改 Core 虚拟播放器内部状态。

ActiveView 除原生音量外，还会通过 `ima.bridge.getVideoMetadata` / `ima.common.getVideoMetadata` 读取媒体音量。
该读取入口只映射原本存在的有效 `volume`，保留时长、进度等其他返回值，避免原生音量
到达前的早期采样把非静音值计入最大音量。没有主动调用该入口或触发测量事件。
