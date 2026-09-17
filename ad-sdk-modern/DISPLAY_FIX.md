# 隐藏与起播显示修复（本地待发布）

本说明对应 `v1.0.16` 之后的本地修改，远程 `v1.0.16` 尚不包含以下修复。

## 已确认的实现问题

`v1.0.16` 使用 `new PlayerView(context)`，Media3 默认创建独立合成的 `SurfaceView`。原隐藏模式只设置其父层 `adRoot.alpha=0`，不足以保证不同 Android 窗口和设备上的实际视频层透明。同时内部背景和播放器起播遮罩使用黑色，存在起播露出黑底的路径。

本地修改将视频显示改为参与普通视图合成的 `TextureView`，隐藏模式同时保持视频视图和广告父层透明；清除内部黑色背景和起播遮罩；可见模式继续等 IMA STARTED 和视频首帧都到达后再渐显，释放前先透明处理。

新增 `AD_DISPLAY_STATE` 和 `AD_FIRST_FRAME` 日志，记录实际视频视图类型、后台隐藏值、各层 alpha、宿主可见性和硬件加速状态。日志仍受 `persist.sys.ad.log` 控制，整轮流程上传仍受 `popup_log_enabled` 控制。

`TextureView` 需要宿主窗口开启硬件加速。宿主容器自身的背景与其他子视图不在这次修改范围内；仍需在客户设备验证隐藏及首帧显示效果。

## 硬件加速配置

本地待发布版本已在 SDK 清单的 `<application>` 上声明 `android:hardwareAccelerated="true"`，通过清单合并提供应用级默认值。常规 Activity 以及通过应用上下文创建的悬浮窗可以继承此配置；它影响应用默认值，不只影响 SDK 广告子视图。`v1.0.16` 尚不包含这项声明。目标 API 14 及以上的应用，Android 本身通常也默认开启硬件加速。

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

容器挂载后，用 `adContainer.isHardwareAccelerated()` 或本地修复新增的 `AD_DISPLAY_STATE` 日志确认实际状态。未挂载时返回 `false` 不能证明宿主关闭了硬件加速。当前没有客户窗口未开启硬件加速的实测证据，这项配置是 `TextureView` 的运行条件。

## 声音 API 的现状

声音 API 保持 `v1.0.16` 的兼容行为：后台返回 `sound_mode` 时优先用于起播；字段缺失时用宿主的 `AdRequest` 设置。播放期间调用 `session.setSoundEnabled()` 可以调整音量，因此后台并未锁定之后的所有音量变更。

完全由后台控制声音的客户无需调用该 API。本次只修复显示实现，没有擅自删除声音接口或改变已经约定的优先级。
