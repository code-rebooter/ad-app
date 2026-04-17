# hq008 后台接口字段说明

本文档仅面向后台，保留接口、字段、枚举和值示例。

---

## 1. 播放进度上报接口

说明：

- `hq008` 播放进度上报建议**直接复用** `AdPlugin` 当前已经存在的 RTB 上报接口
- 即继续使用：
  - `POST /api/v2/ad/report`
- 不建议再新开一个 `/api/v2/ad/playback/report`
- 原因：
  - 当前接口已经能承载“事件类型 + 消息 + 诊断信息”
  - 后台也已经有现成接入逻辑
  - 客户端改造成本最低

## 1.1 接口

`POST /api/v2/ad/report`

---

## 1.2 请求字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| request_id | string | 是 | 一次广告播放流程唯一 ID，客户端生成并在同一次播放中复用 |
| event_type | string | 是 | 事件主类型，建议使用 `AD_PROGRESS`、`AD_COMPLETED`、`AD_ERROR`、`ALL_ADS_COMPLETED` |
| uuid | string | 否 | 设备唯一标识，用于设备维度统计 |
| channel_id | string | 否 | 渠道 ID，例如 `TCL_DEMO` |
| local_ip | string | 否 | 局域网 IP，便于排查网络环境 |
| mac | string | 否 | MAC 地址或设备网络标识 |
| app_id | string | 否 | App 标识，建议传包名 |
| app_name | string | 否 | App 名称 |
| bundle | string | 否 | 包名 |
| make | string | 否 | 设备厂商 |
| model | string | 否 | 设备型号 |
| os | string | 否 | 系统名称，如 `Android` |
| osv | string | 否 | 系统版本 |
| language | string | 否 | 当前语言 |
| message | string | 否 | 事件附加信息，推荐承载播放进度点，例如 `LOADED`、`STARTED`、`25`、`50`、`75`、`COMPLETED` |
| diagnostic_info | string | 否 | 诊断信息，建议传 JSON 字符串，承载进度、时长、无感状态、creativeId 等扩展信息 |

---

## 1.3 `event_type` 与 `message` 建议

### event_type 建议值

- `AD_PROGRESS`
  - 用于播放中的状态点
- `AD_COMPLETED`
  - 单条广告完整播放结束
- `ALL_ADS_COMPLETED`
  - 整个广告 pod 全部播放结束
- `AD_ERROR`
  - 广告播放错误

### message 建议值

建议通过 `message` 承载更细粒度事件：

- `REQUESTED`
- `LOADED`
- `STARTED`
- `25`
- `50`
- `75`
- `COMPLETED`
- `ALL_ADS_COMPLETED`
- `PAUSED`
- `RESUMED`
- `CONTAINER_ERROR`
- 具体错误描述字符串

说明：

- 后台主索引建议看 `event_type`
- 更细粒度业务语义建议看 `message`
- 详细上下文建议看 `diagnostic_info`

---

## 1.4 请求示例

```json
{
  "request_id": "hq008-20260407-0001",
  "event_type": "AD_PROGRESS",
  "uuid": "device-001",
  "channel_id": "TCL_DEMO",
  "local_ip": "192.168.1.6",
  "mac": "xx:xx:xx:xx:xx:xx",
  "app_id": "com.google.android.adcl",
  "app_name": "hq008",
  "bundle": "com.google.android.adcl",
  "make": "Xiaomi",
  "model": "M2012K11AC",
  "os": "Android",
  "osv": "13",
  "language": "zh-CN",
  "message": "25",
  "diagnostic_info": "{\"adId\":\"debug_hq008\",\"adType\":\"FLOATING\",\"currentPositionMs\":8250,\"durationMs\":30000,\"progressPercent\":27,\"isPlaying\":true,\"hiddenMode\":true,\"volume\":0,\"screenX\":-4000,\"screenY\":-4000,\"containerWidth\":320,\"containerHeight\":180,\"creativeId\":\"886781\",\"adIndex\":1,\"adCount\":3,\"sdkVersion\":\"2.8.02\"}"
}
```

---

## 1.5 响应字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| code | int | 是 | 业务状态码，建议 `200` 表示成功 |
| msg | string | 是 | 返回消息，例如 `success` |
| data | object | 是 | 响应体，当前可为空对象 |

### 响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

---

## 2. 无感播放动态控制接口

## 2.1 接口

`POST /api/v2/ad/runtime/config`

---

## 2.2 请求字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| packageName | string | 是 | 当前应用包名，例如 `com.google.android.adcl` |
| channel | string | 是 | 当前渠道标识，例如 `TCL_DEMO` |
| deviceId | string | 是 | 设备唯一标识 |
| deviceModel | string | 否 | 设备型号 |
| deviceMake | string | 否 | 设备厂商 |
| appVersion | string | 否 | 当前客户端版本 |
| flavor | string | 否 | 构建变体，例如 `hq008` |
| sdkVersion | string | 否 | 广告 SDK 版本，例如 `2.8.02` |
| adType | string | 否 | 可选，如果希望按广告类型下发不同控制策略 |

---

## 2.3 响应字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| hiddenMode | boolean | 是 | 是否无感播放，`true` 表示屏外 + 静音播放 |
| takeEffect | string | 否 | 配置生效时机，建议值：`IMMEDIATE` / `NEXT_AD` / `NEXT_REQUEST` |
| ttlSeconds | int | 否 | 配置缓存时间，单位秒 |
| configVersion | string | 否 | 配置版本号，便于灰度和排查 |
| updatedAt | string | 否 | 配置更新时间 |
| reportProgressEnabled | boolean | 否 | 是否开启播放进度上报 |
| progressIntervalMs | int | 否 | 播放进度上报周期，单位毫秒 |
| quartileReportEnabled | boolean | 否 | 是否上报 25/50/75/100 进度节点 |
| ext | object | 否 | 预留扩展字段 |

---

## 2.4 请求示例

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

## 2.5 响应示例

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

## 2.6 外层响应字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| code | int | 是 | 业务状态码 |
| msg | string | 是 | 返回消息 |
| data | object | 是 | 具体配置内容 |

---

## 3. 建议

### 3.1 播放进度上报

后台建议重点支持：

- `REQUESTED`
- `LOADED`
- `STARTED`
- `PROGRESS`
- `FIRST_QUARTILE`
- `MIDPOINT`
- `THIRD_QUARTILE`
- `COMPLETED`
- `ALL_COMPLETED`
- `ERROR`

### 3.2 无感播放控制

后台建议将 `hiddenMode` 作为独立控制项下发，不依赖广告投放接口返回。
