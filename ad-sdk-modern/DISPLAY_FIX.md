# 隐藏与起播显示修复（v1.0.17）

适用依赖：`com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.17`。`v1.0.16` 不包含以下修复。

## 已确认的实现问题

`v1.0.16` 使用 `new PlayerView(context)`，Media3 默认创建独立合成的 `SurfaceView`。原隐藏模式只设置其父层 `adRoot.alpha=0`，不足以保证不同 Android 窗口和设备上的实际视频层透明。同时内部背景和播放器起播遮罩使用黑色，存在起播露出黑底的路径。

`v1.0.17` 将视频显示改为参与普通视图合成的 `TextureView`，隐藏模式同时保持视频视图和广告父层透明；清除内部黑色背景和起播遮罩；可见模式继续等 IMA STARTED 和视频首帧都到达后再渐显，释放前先透明处理。

新增 `AD_DISPLAY_STATE` 和 `AD_FIRST_FRAME` 日志，记录实际视频视图类型、后台隐藏值、各层 alpha、宿主可见性和硬件加速状态。日志仍受 `persist.sys.ad.log` 控制，整轮流程上传仍受 `popup_log_enabled` 控制。

`TextureView` 需要宿主窗口开启硬件加速。宿主容器自身的背景与其他子视图不在这次修改范围内；本地设备验证结果见下文；客户设备与其容器背景仍需结合实际日志判断。

## 硬件加速配置

`v1.0.17` 在 SDK 清单的 `<application>` 上声明 `android:hardwareAccelerated="true"`，通过清单合并提供应用级默认值。常规 Activity 以及通过应用上下文创建的悬浮窗可以继承此配置；它影响应用默认值，不只影响 SDK 广告子视图。`v1.0.16` 尚不包含这项声明。目标 API 14 及以上的应用，Android 本身通常也默认开启硬件加速。

宿主没有显式关闭时，通常无需额外操作。若宿主 `<application>` 显式设置了 `false`，与 SDK 的 `true` 可能产生清单合并冲突，需要宿主统一配置；SDK 不通过 `tools:replace` 强制覆盖。某个 Activity 单独配置 `false` 时，也需要在该 Activity 开启硬件加速。

普通 Activity 可以在宿主清单中设置：

```xml
<application android:hardwareAccelerated="true" ...>
    <activity android:name=".AdHostActivity"
        android:hardwareAccelerated="true" ... />
</application>
```

对于 `FloatingAdService` 通过 `WindowManager` 创建的悬浮窗，可以在原有窗口参数上添加标志，保留其他 flags：

```java
params.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
windowManager.addView(rootView, params);
```

标志必须在 `addView()` 之前设置。Activity 也可在 `setContentView()` 之前调用 `getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)`。SDK 收到已经挂载的 `ViewGroup` 后，不能通过 `setLayerType(View.LAYER_TYPE_HARDWARE, null)` 把软件渲染窗口切换为硬件加速；SDK 不会拆卸或重建宿主窗口。

容器挂载后，用 `adContainer.isHardwareAccelerated()` 或 `v1.0.17` 新增的 `AD_DISPLAY_STATE` 日志确认实际状态。未挂载时返回 `false` 不能证明宿主关闭了硬件加速。当前没有客户窗口未开启硬件加速的实测证据，这项配置是 `TextureView` 的运行条件。

## 声音 API 的现状

声音 API 保持 `v1.0.16` 的兼容行为：后台返回 `sound_mode` 时优先用于起播；字段缺失时用宿主的 `AdRequest` 设置。播放期间调用 `session.setSoundEnabled()` 可以调整音量，因此后台并未锁定之后的所有音量变更。

完全由后台控制声音的客户无需调用该 API。本次只修复显示实现，没有擅自删除声音接口或改变已经约定的优先级。

## 本地设备验证（2026-09-17）

在 mstar（API 29、1280×720）上，以本地 release AAR 运行验证；未使用 X88。验证应用分别提供 Activity 普通布局容器和应用上下文创建的 `TYPE_APPLICATION_OVERLAY` 悬浮窗容器，覆盖两种接入方式，不据此推断客户使用哪一种。

流程控制、授权、广告配置和上报请求接入本地测试服务，分别返回 `hidden_mode=true/false`、`sound_mode=true/false` 及声音字段缺失。SDK 通过实际 HTTP 客户端和解析器处理这些响应；测试配置只存在于独立验证应用，正式 SDK 的渠道域名和线上后台配置未修改。广告请求直接使用 Google 官方 Single Inline Linear 测试标签，实际播放带 Google Ad Manager 标识和动态倒计时的 10 秒 Preroll 广告。CMP 使用接口 `skip_cmp=true`，本次不验证 UMP 弹窗。

七项连续验证全部通过，同一进程中六轮广告均完成，随后一轮空 VAST 返回 IMA 303：

- Activity 与悬浮窗各验证隐藏和可见模式，隐藏模式保持宿主背景，可见模式显示官方广告内容并正常结束。
- 再次在悬浮窗中隐藏播放成功；整个过程中没有清除应用数据。
- 后台声音值覆盖与其相反的宿主默认值；字段缺失时使用宿主默认值。六轮中均检查了播放器音量以及 API 切换、恢复，共 18 次。此项验证播放器音量状态，不是扬声器声学测量。
- 每轮只回调一次 `onFinished`，完成时 `message=COMPLETED`；空 VAST 原样返回 IMA `303` 和 `No Ads VAST response after one or more Wrappers`。
- 248 次全屏像素采样未发现广告区域黑帧或隐藏模式露出；录屏验证区间的 1602 帧也未检出广告中心区域黑帧。结束后 SDK 子视图均被移除。宿主未额外声明硬件加速或添加窗口硬件标志，两个容器均记录 `hardwareAccelerated=true`。

前置纯色视频只用于辅助检测显隐和黑帧，不作为官方广告画面验证的依据。测试过程中处理了设备失效代理；一次高频截图同时录屏的运行被系统 `lowmemorykiller` 中断，降低采集负担后完整验证通过。测试设备网络与日志属性已恢复。以上结果覆盖本设备、测试接口配置和官方测试广告，不代表已核实客户正式授权响应或所有设备兼容性。
