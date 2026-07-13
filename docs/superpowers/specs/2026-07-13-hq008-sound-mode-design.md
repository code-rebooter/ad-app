# hq008-family 请求级声音控制设计

## 目标

所有走 `POST /api/v2/ad/sdk/authorize` 的客户端请求解析后台现有字段 `sound_mode`，并让支持音量控制的广告播放器按本次请求决定是否播放声音。

字段语义：

- `sound_mode=true`：有声音。
- `sound_mode=false`：静音。
- 字段缺失或为 `null`：静音。

声音策略与 `hidden_mode` 相互独立。`hidden_mode` 只控制广告画面和窗口位置，不参与音量计算。

## 本次范围

本次实现：

- hq008-family 中使用 TCL 广告播放器的渠道。
- `google_ad_tv_desktop`。
- 公共 `authorize` DTO、广告配置和播放调用链。

暂不改变播放行为：

- `haier_lsap`。
- `addy_hq1002`。
- `addy_jams`。

原因是当前海尔 LSAP 1.1.10 和 ADDY 1.1.11 AAR 没有公开声音或音量控制 API。公共链路仍可解析和携带 `sound_mode`，但这些播放器本次不消费该字段。

## 数据流

1. `Hq008SdkAuthorizeClient` 将 `sound_mode` 解析为可空布尔值。
2. `AdConfigManager` 使用 `dto.sound_mode == true` 计算本次请求的 `soundEnabled`，因此任何缺失、空值或 `false` 都会安全回退到静音。
3. `soundEnabled` 写入本次 `AdConfigDto`，随 `AdRenderer`、`TvAdFloatingWindow` 和 `IAdManager.showAd` 显式传递。
4. 播放器不得从全局持久化状态读取声音配置，避免上一轮有声请求污染下一轮缺失字段的请求。

## 播放器行为

### TCL

TCL 请求构建时使用：

```kotlin
.setVolume(if (soundEnabled) 1f else 0f)
```

### Google VAST

Google 播放器在 `prepare()` 前使用：

```kotlin
volume = if (soundEnabled) 1f else 0f
```

GAM URL 的 `vpmute` 同步反映初始播放状态：

- 有声：`vpmute=0`
- 静音：`vpmute=1`

### Haier LSAP 和 ADDY

本次保持 SDK 当前播放行为，不使用系统媒体音量、反射或替换 SDK 内部监听器。待供应商提供播放器级声音接口后再接入。

## 兼容性和日志

- 老后台不返回 `sound_mode` 时始终静音。
- 每次授权回调、广告请求和播放开始日志记录本次 `soundEnabled`，便于核对后台返回与播放器状态。
- 非 hq008-family 的旧广告接口调用使用默认值 `false`，不改变现有默认静音预期。

## 测试

- 验证 `sound_mode=true`、`false`、缺失和 `null` 的解析与默认值。
- 验证声音配置随本次广告请求显式传递。
- 验证 TCL 音量为 `1f/0f`。
- 验证 Google ExoPlayer 音量与 GAM `vpmute` 一致。
- 编译 TCL、Google 及受公共接口变更影响的主要渠道变体。
