# hq008 播放进度上报与无感播放动态控制设计

## 1. 目标

本文档用于说明 `hq008` 渠道当前广告播放能力、可上报的播放进度点，以及“是否无感播放”的后台动态控制方案。

当前目标分为两部分：

1. 设计广告播放进度上报接口与字段
2. 设计“是否无感播放”的后台动态控制接口与字段

---

## 2. 当前客户端真实能力

### 2.1 当前稳定可拿到的播放事件

当前 `hq008` 播放链路基于 TCL 2.8.02 视频广告 SDK，客户端当前稳定可获取到以下事件：

- `onAdLoaded`
- `onAdStartPlay()`
- `onAdStartPlay(progress: Double)`
- `onAdFinished()`
- `onAdError(errorCode: Int)`
- `onContainerSizeError()`

### 2.2 当前稳定可拿到的播放进度能力

SDK 公共接口中，当前确认可用：

- `VideoStateListener.getVideoProgress()`
- `VideoStateListener.isPlaying()`
- `NormalVideoProgress.currentPosition`
- `NormalVideoProgress.duration`

也就是说，客户端当前可以稳定拿到：

- 当前播放位置 `currentPositionMs`
- 当前广告总时长 `durationMs`
- 是否正在播放 `isPlaying`

### 2.3 当前不建议直接依赖的内部事件

SDK 内部存在以下广告事件枚举：

- `STARTED`
- `FIRST_QUARTILE`
- `MIDPOINT`
- `THIRD_QUARTILE`
- `COMPLETED`
- `ALL_ADS_COMPLETED`
- `AD_PROGRESS`
- `AD_BUFFERING`
- `PAUSED`
- `RESUMED`
- `CLICKED`
- `SKIPPED`
- `AD_BREAK_STARTED`
- `AD_BREAK_ENDED`
- `AD_PERIOD_STARTED`
- `AD_PERIOD_ENDED`

但当前公共 `Config` API 没有直接稳定暴露 `AdEventListener` 的注入入口，因此：

- 后台接口设计**不要依赖 SDK 内部枚举事件**
- 推荐基于客户端自己可稳定拿到的回调和进度轮询来上报

---

## 3. 播放进度上报设计

## 3.1 推荐接口

推荐新增独立接口：

`POST /api/v2/ad/playback/report`

不建议继续复用当前的 `api/v2/ad/task/report`，因为：

- 当前 `task/report` 的语义更偏“任务状态”
- 播放进度需要更细粒度的事件模型
- 后续还会涉及 quartile、progress、buffering、pause/resume 等细事件

---

## 3.2 推荐事件模型

推荐 `eventType` 枚举如下：

- `REQUESTED`
- `LOADED`
- `STARTED`
- `PROGRESS`
- `FIRST_QUARTILE`
- `MIDPOINT`
- `THIRD_QUARTILE`
- `COMPLETED`
- `ALL_COMPLETED`
- `PAUSED`
- `RESUMED`
- `ERROR`
- `CONTAINER_ERROR`

说明：

- `REQUESTED`
  - 客户端调用 `Ad.start()` 时上报
- `LOADED`
  - 收到 `onAdLoaded`
- `STARTED`
  - 收到 `onAdStartPlay`
- `PROGRESS`
  - 周期上报当前播放进度
- `FIRST_QUARTILE/MIDPOINT/THIRD_QUARTILE`
  - 客户端根据进度阈值自行推导
- `COMPLETED`
  - 单条广告播放完成
- `ALL_COMPLETED`
  - 整个广告 pod 播放完成
- `PAUSED/RESUMED`
  - 通过 `isPlaying` 状态变化推导
- `ERROR`
  - 收到 `onAdError`
- `CONTAINER_ERROR`
  - 收到 `onContainerSizeError`

---

## 3.3 推荐请求字段

### 3.3.1 基础字段

- `playSessionId`
  - 一次播放会话唯一 ID
  - 每次真正开始广告播放时生成
- `requestId`
  - 广告请求唯一 ID
  - 推荐由后端在 `ad/delivery` 返回
- `packageName`
- `channel`
- `adId`
- `adType`
  - 推荐值：`SPLASH` / `FLOATING`
- `eventType`
- `eventTimeMs`

### 3.3.2 进度字段

- `currentPositionMs`
- `durationMs`
- `progressPercent`
- `isPlaying`

### 3.3.3 展示控制字段

- `hiddenMode`
- `volume`
- `screenX`
- `screenY`
- `containerWidth`
- `containerHeight`

### 3.3.4 设备信息字段

- `deviceId`
  - 当前如果仍然使用 `macAddress`，可继续复用
  - 更推荐统一抽象成 `deviceId`
- `deviceModel`
- `deviceMake`
- `sdkVersion`

### 3.3.5 错误字段

- `errorCode`
- `errorMessage`

### 3.3.6 预留字段

- `extra`
  - JSON 对象
  - 后续可扩展：
    - `creativeId`
    - `adIndex`
    - `adCount`
    - `podIndex`
    - `responseId`

---

## 3.4 推荐请求示例

```json
{
  "playSessionId": "b9a4d8d1-7f6d-44f8-9711-6a6aa0a0f6d0",
  "requestId": "req_20260407_001",
  "packageName": "com.google.android.adcl",
  "channel": "TCL_DEMO",
  "adId": "debug_hq008",
  "adType": "FLOATING",
  "eventType": "PROGRESS",
  "eventTimeMs": 1775549105123,
  "currentPositionMs": 8250,
  "durationMs": 30000,
  "progressPercent": 27,
  "isPlaying": true,
  "hiddenMode": true,
  "volume": 0,
  "screenX": -4000,
  "screenY": -4000,
  "containerWidth": 320,
  "containerHeight": 180,
  "deviceId": "xx:xx:xx:xx:xx:xx",
  "deviceModel": "M2012K11AC",
  "deviceMake": "Xiaomi",
  "sdkVersion": "2.8.02",
  "errorCode": null,
  "errorMessage": null,
  "extra": {
    "creativeId": "886781",
    "adIndex": 1,
    "adCount": 3
  }
}
```

---

## 3.5 客户端实现建议

推荐客户端采用以下策略：

### 3.5.1 上报触发点

- 请求广告时上报一次 `REQUESTED`
- `onAdLoaded` 时上报一次 `LOADED`
- `onAdStartPlay` 时上报一次 `STARTED`
- 播放过程中每 `1000ms` 上报一次 `PROGRESS`
- 当进度穿越 `25% / 50% / 75%` 阈值时，分别上报一次：
  - `FIRST_QUARTILE`
  - `MIDPOINT`
  - `THIRD_QUARTILE`
- 单条广告结束时上报 `COMPLETED`
- 整个广告 pod 结束时上报 `ALL_COMPLETED`
- 错误时上报 `ERROR / CONTAINER_ERROR`

### 3.5.2 quartile 建议

不要依赖 SDK 内部事件，客户端自己按进度算：

- `currentPositionMs / durationMs >= 0.25`
- `currentPositionMs / durationMs >= 0.50`
- `currentPositionMs / durationMs >= 0.75`

每个阈值只上报一次。

### 3.5.3 progress 上报频率

推荐：

- `1000ms` 一次

不建议太密：

- `200ms/500ms` 没必要
- 流量与后台存储压力都会显著增加

---

## 4. 无感播放动态控制设计

## 4.1 背景

当前 `hq008` 客户端是**硬编码强制无感播放**：

- `hiddenMode = true`
- 窗口移到屏幕外
- `alpha = 0`
- `volume = 0`

如果要支持后台动态控制，客户端逻辑需要改成：

- `远程配置 > hq008 默认值 > 本地调试值`

---

## 4.2 推荐接口

推荐新增独立控制接口：

`POST /api/v2/ad/runtime/config`

为什么建议独立接口：

- 即使当前没有广告返回，也可能希望先控制是否无感播放
- 控制面和广告素材投放面解耦更合理
- 便于单独做缓存、版本号和灰度控制

---

## 4.3 推荐请求字段

- `packageName`
- `channel`
- `deviceId`
- `deviceModel`
- `deviceMake`
- `appVersion`
- `flavor`
  - 例如：`hq008`
- `sdkVersion`
- `adType`
  - 可选，如果希望不同广告类型用不同策略

请求示例：

```json
{
  "packageName": "com.google.android.adcl",
  "channel": "TCL_DEMO",
  "deviceId": "xx:xx:xx:xx:xx:xx",
  "deviceModel": "M2012K11AC",
  "deviceMake": "Xiaomi",
  "appVersion": "1.0",
  "flavor": "hq008",
  "sdkVersion": "2.8.02",
  "adType": "FLOATING"
}
```

---

## 4.4 推荐响应字段

- `hiddenMode`
  - `true/false`
- `takeEffect`
  - 推荐值：
    - `IMMEDIATE`
    - `NEXT_AD`
    - `NEXT_REQUEST`
- `ttlSeconds`
  - 配置缓存时长
- `configVersion`
  - 配置版本号
- `updatedAt`
- `reportProgressEnabled`
  - 是否开启进度上报
- `progressIntervalMs`
  - 进度上报周期
- `quartileReportEnabled`
  - 是否上报 25/50/75/100
- `ext`
  - 预留对象

响应示例：

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "hiddenMode": true,
    "takeEffect": "NEXT_AD",
    "ttlSeconds": 3600,
    "configVersion": "2026-04-07-001",
    "updatedAt": "2026-04-07T16:30:00+08:00",
    "reportProgressEnabled": true,
    "progressIntervalMs": 1000,
    "quartileReportEnabled": true,
    "ext": {}
  }
}
```

---

## 4.5 客户端生效优先级建议

建议最终优先级：

1. 后台远程配置值
2. `hq008` 默认值
3. 本地调试值

即：

- 如果后台下发 `hiddenMode=false`，则允许显播
- 如果后台不下发，则 `hq008` 默认继续无感播放

---

## 5. 建议的客户端改造点

## 5.1 进度上报

客户端需要补：

- `playSessionId`
- `requestId`
- `VideoStateListener`
- `1s` progress 轮询
- quartile 阈值上报
- `PAUSED/RESUMED` 检测

## 5.2 动态无感播放控制

客户端需要补：

- 启动时或拉广告前请求 `runtime/config`
- 本地缓存 `hiddenMode`
- 调整当前 `hq008` 的硬编码逻辑，使后台值可覆盖

---

## 6. 当前建议

建议按以下顺序推进：

1. 后台先确认并落地两个接口
   - `POST /api/v2/ad/playback/report`
   - `POST /api/v2/ad/runtime/config`
2. 客户端先做最小实现
   - 先接播放进度上报
   - 再接后台动态无感播放控制
3. 联调时优先验证：
   - `LOADED`
   - `STARTED`
   - `PROGRESS`
   - `FIRST_QUARTILE/MIDPOINT/THIRD_QUARTILE`
   - `COMPLETED`
   - `ERROR`

---

## 7. 结论

当前 `hq008` 客户端已经具备设计这两类能力的基础：

- 播放进度上报
  - 可以稳定获取开始、进度、完成、错误等关键事件
- 无感播放动态控制
  - 当前已有本地和远程配置结构基础
  - 只需要调整 `hq008` 强制隐藏的优先级逻辑即可

这份文档可以直接发给后台做接口评审。
