# Google GAM 广告配置接口说明

## 1. 文档目标

本文档用于整理 `google_ad_tv_desktop` 渠道后续从“客户端硬编码 GAM VAST 链接”调整为“后台结构化配置、客户端请求接口获取可播放广告链接”的接口设计。

目标是让后端可以通过后台页面维护客户、渠道、广告位和 GAM 参数；TV 客户端只负责请求接口、校验 URL、补运行时参数并交给 IMA 播放。

---

## 2. 基础概念

### 2.1 GAM

GAM 指 Google Ad Manager。当前 Google 广告链路使用的是 GAM 的 VAST ad tag URL，例如：

```text
https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&...
```

客户端并不是直接播放这个 URL 指向的视频文件，而是把这个 VAST tag 交给 Google IMA SDK。IMA 请求 GAM，GAM 返回 VAST XML，IMA 再解析广告素材、跟踪事件并播放广告。

### 2.2 广告位

广告位是客户端业务上的播放位置，不直接等同于 GAM 的 `iu`。

建议客户端和后端约定稳定的 `slot_id`：

| slot_id | 含义 | 当前状态 |
| --- | --- | --- |
| `tv_desktop_startup` | TV 桌面启动/入口视频广告 | 当前 `google_ad_tv_desktop` 使用 |

后续如果有更多广告位，新增 `slot_id` 即可，例如：

| slot_id | 含义 |
| --- | --- |
| `home_video` | 首页视频广告 |
| `screensaver_video` | 屏保视频广告 |
| `launcher_idle_video` | 桌面空闲视频广告 |

### 2.3 correlator

`correlator` 是 GAM 广告请求的动态关联参数，不是客户 ID、广告位 ID 或固定密钥。

建议规则：

1. 如果后端接口是“每次播放前实时解析”，后端可以直接生成 `correlator`。
2. 如果后端接口是“配置接口”并允许客户端缓存，后端不要写死 `correlator`，可以返回空值或宏。
3. 客户端播放前如果发现 `correlator` 为空或为宏，则补一个当前请求级别的动态值。

---

## 3. 整体设计

### 3.1 职责边界

后端负责：

- 后台页面维护广告配置
- 根据渠道、包名、客户、国家、广告位匹配配置
- 生成 GAM VAST ad tag URL
- 控制广告启停、灰度、测试广告、正式广告
- 保证 GAM 参数格式正确

客户端负责：

- 请求广告配置接口
- 传入当前渠道、包名、版本、广告位等上下文
- 校验返回的 URL 是否安全可用
- 补运行时参数，例如 `correlator`
- 缓存配置并在接口失败时兜底
- 调用 IMA 播放广告

### 3.2 推荐方案

推荐后端提供一个“运行时广告解析接口”，客户端每次需要播放广告前按 `slot_id` 请求接口。

接口返回：

- 广告是否启用
- 本次广告的配置版本
- 可播放的 GAM VAST URL
- 缓存时间
- 可选的超时配置

客户端拿到 `ad_tag_url` 后，只做安全校验和运行时参数补全，然后直接播放。

---

## 4. 接口一：广告配置解析

### 4.1 接口地址

```text
POST /api/v2/ad/google-gam/resolve
```

接口地址为建议值，后端可以按现有路由规范调整，但语义建议保持为“resolve”，表示本次请求返回一个客户端可播放的广告配置。

### 4.2 请求格式

数据格式：`JSON`

### 4.3 请求参数

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `channel_id` | string | 是 | 当前渠道，客户端取 `BuildConfig.CHANNEL`，例如 `GOOGLE_AD_TV_DESKTOP` |
| `package_name` | string | 是 | 当前应用包名，例如 `io.android.launcher.tv.desktop` |
| `version_code` | int | 是 | 当前 App 版本号 |
| `version_name` | string | 是 | 当前 App 版本名称 |
| `slot_id` | string | 是 | 客户端业务广告位，例如 `tv_desktop_startup` |
| `request_id` | string | 是 | 客户端本次广告请求 ID，用于日志串联 |
| `device_country` | string | 否 | 设备国家或服务端识别国家，例如 `US`、`HK` |
| `language` | string | 否 | 系统语言，例如 `en-US` |
| `timezone` | string | 否 | 设备时区，例如 `Asia/Shanghai` |
| `device_id` | string | 否 | 后端已有设备标识时传；没有则不传 |
| `debug` | boolean | 否 | 是否测试模式，默认 `false` |

### 4.4 请求示例

```json
{
  "channel_id": "GOOGLE_AD_TV_DESKTOP",
  "package_name": "io.android.launcher.tv.desktop",
  "version_code": 6,
  "version_name": "1.0.5",
  "slot_id": "tv_desktop_startup",
  "request_id": "google_ad_tv_desktop_1720500000000",
  "device_country": "US",
  "language": "en-US",
  "timezone": "Asia/Shanghai",
  "debug": false
}
```

### 4.5 外层响应字段

接口响应沿用当前项目后端常见的 `code`、`msg`、`data` 包裹结构。

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | int | 是 | 业务状态码；建议 `100000` 表示成功 |
| `msg` | string | 是 | 返回消息；成功时建议为 `success` |
| `data` | object | 否 | 具体广告配置内容；无可用配置或异常时可为 `null` |

### 4.6 data 字段

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `enabled` | boolean | 是 | 当前广告位是否启用 |
| `provider` | string | 是 | 广告服务商，当前固定为 `google_gam` |
| `slot_id` | string | 是 | 返回命中的广告位 |
| `config_version` | string | 是 | 后端广告配置版本，用于日志和问题追踪 |
| `ttl_seconds` | int | 是 | 客户端缓存时间，建议 300 到 3600 秒 |
| `ad_tag_url` | string | 否 | 可播放的 GAM VAST URL；`enabled=true` 时必填 |
| `ad_load_timeout_ms` | int | 否 | IMA 广告加载超时时间；不传则客户端使用本地默认值 |
| `ad_startup_timeout_ms` | int | 否 | 广告启动超时时间；不传则客户端使用本地默认值 |
| `reason` | string | 否 | 当 `enabled=false` 或无可用配置时返回原因 |

### 4.7 成功响应示例

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "enabled": true,
    "provider": "google_gam",
    "slot_id": "tv_desktop_startup",
    "config_version": "20260709-001",
    "ttl_seconds": 600,
    "ad_tag_url": "https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&description_url=https%3A%2F%2Fghtfor.cc&tfcd=0&npa=0&ad_type=audio_video&sz=1x1%7C300x250%7C320x480%7C400x300%7C640x360%7C640x430%7C640x480&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&plcmt=1&vpmute=0&app_package=io.android.launcher.tv.desktop&correlator=",
    "ad_load_timeout_ms": 20000,
    "ad_startup_timeout_ms": 35000
  }
}
```

说明：

- `data.ad_tag_url` 可以保留 `correlator=` 空值，客户端播放前补动态值。
- 如果后端每次实时生成最终 URL，也可以直接返回非空 `correlator`。
- `description_url` 必须由后端保证 URL encode 正确，例如 `https://ghtfor.cc` 应编码为 `https%3A%2F%2Fghtfor.cc`。

### 4.8 关闭广告响应示例

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "enabled": false,
    "provider": "google_gam",
    "slot_id": "tv_desktop_startup",
    "config_version": "20260709-001",
    "ttl_seconds": 300,
    "reason": "SLOT_DISABLED"
  }
}
```

客户端收到 `code=100000` 且 `data.enabled=false` 时，本次不播放广告，按广告跳过或无广告逻辑处理，并记录日志。

### 4.9 异常响应示例

```json
{
  "code": 500001,
  "msg": "NO_MATCHED_CONFIG",
  "data": null
}
```

客户端收到 `code != 100000` 或 `data=null` 时，按接口失败处理，优先使用缓存或本地兜底配置。

---

## 5. 后台页面配置字段建议

### 5.1 基础匹配字段

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `enabled` | boolean | 是 | 是否启用 |
| `channel_id` | string | 是 | 渠道，例如 `GOOGLE_AD_TV_DESKTOP` |
| `package_name` | string | 是 | 包名，例如 `io.android.launcher.tv.desktop` |
| `slot_id` | string | 是 | 客户端广告位 |
| `customer_id` | string | 否 | 客户 ID，没有则为空 |
| `country_allowlist` | string[] | 否 | 允许国家，例如 `US`、`HK` |
| `country_blocklist` | string[] | 否 | 屏蔽国家 |
| `min_version_code` | int | 否 | 最低 App 版本 |
| `max_version_code` | int | 否 | 最高 App 版本 |
| `priority` | int | 是 | 多条配置命中时优先级，数字越大优先级越高 |
| `config_version` | string | 是 | 配置版本 |

### 5.2 GAM 配置字段

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `gam_host` | string | 是 | 固定建议为 `pubads.g.doubleclick.net` |
| `ad_unit_path` | string | 是 | GAM 广告单元，对应 URL 参数 `iu` |
| `description_url` | string | 是 | 原始 URL，后端负责 encode |
| `sizes` | string[] | 是 | 广告尺寸列表，例如 `640x480` |
| `tfcd` | string | 是 | 当前示例为 `0` |
| `npa` | string | 是 | 当前示例为 `0` |
| `ad_type` | string | 是 | 当前示例为 `audio_video` |
| `plcmt` | string | 是 | 当前示例为 `1` |
| `vpmute` | string | 是 | 当前示例为 `0` |
| `extra_params` | object | 否 | 预留 GAM 扩展参数 |

### 5.3 当前 google_ad_tv_desktop 示例配置

```json
{
  "enabled": true,
  "channel_id": "GOOGLE_AD_TV_DESKTOP",
  "package_name": "io.android.launcher.tv.desktop",
  "slot_id": "tv_desktop_startup",
  "country_allowlist": ["US", "HK"],
  "priority": 100,
  "provider": "google_gam",
  "gam_host": "pubads.g.doubleclick.net",
  "ad_unit_path": "/23334778486/TVDesktop/video-1",
  "description_url": "https://ghtfor.cc",
  "sizes": ["1x1", "300x250", "320x480", "400x300", "640x360", "640x430", "640x480"],
  "tfcd": "0",
  "npa": "0",
  "ad_type": "audio_video",
  "plcmt": "1",
  "vpmute": "0",
  "config_version": "20260709-001"
}
```

---

## 6. URL 生成规则

后端生成 `ad_tag_url` 时建议固定以下规则：

1. 使用 HTTPS。
2. Host 固定为 `pubads.g.doubleclick.net`。
3. Path 固定为 `/gampad/ads`。
4. `iu` 使用后台配置的 `ad_unit_path`。
5. `description_url` 必须 URL encode。
6. `sz` 由后台配置的 `sizes` 使用 `|` 拼接后再 URL encode。
7. `app_package` 优先使用请求里的 `package_name`。
8. `correlator` 可以留空，由客户端播放前补；也可以后端实时生成。
9. 必须包含 `output=vast`、`env=vp`、`impl=s`。

当前推荐生成结果：

```text
https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&description_url=https%3A%2F%2Fghtfor.cc&tfcd=0&npa=0&ad_type=audio_video&sz=1x1%7C300x250%7C320x480%7C400x300%7C640x360%7C640x430%7C640x480&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&plcmt=1&vpmute=0&app_package=io.android.launcher.tv.desktop&correlator=
```

---

## 7. 客户端处理规则

客户端拿到接口响应后按以下流程处理：

1. 如果接口失败、`code != 100000` 或 `data=null`，优先使用本地缓存的上一次成功配置。
2. 如果没有缓存，使用 APK 内置兜底配置。
3. 如果响应 `data.enabled=false`，本次不播放广告。
4. 校验 `data.ad_tag_url`：
   - scheme 必须是 `https`
   - host 必须是 `pubads.g.doubleclick.net`
   - path 必须是 `/gampad/ads`
   - 必须包含 `iu`
   - 必须包含 `output=vast`
5. 如果 `correlator` 不存在或为空，客户端补当前请求动态值。
6. 如果 `app_package` 不存在或为空，客户端补当前包名。
7. 调用 IMA 播放最终 URL。
8. 播放请求、加载、开始、完成、失败都带上 `request_id`、`slot_id`、`config_version` 上报。

客户端不需要理解或修改以下 GAM 业务参数：

- `iu`
- `description_url`
- `sz`
- `tfcd`
- `npa`
- `ad_type`
- `plcmt`
- `vpmute`
- 后端扩展的 GAM 参数

---

## 8. 缓存和兜底

### 8.1 缓存

客户端按响应的 `ttl_seconds` 缓存配置。

建议：

| 场景 | ttl_seconds |
| --- | --- |
| 测试广告频繁调整 | 300 |
| 正式稳定广告 | 1800 到 3600 |
| 灰度发布 | 300 到 600 |

### 8.2 兜底优先级

客户端获取广告配置失败时：

1. 使用未过期缓存。
2. 使用已过期但最近一次成功缓存，同时记录接口失败日志。
3. 使用 APK 内置默认 GAM 配置。
4. 如果没有任何配置，则跳过本次广告。

---

## 9. 日志和排查字段

客户端广告相关日志建议至少包含：

| 字段名 | 说明 |
| --- | --- |
| `request_id` | 本次客户端广告请求 ID |
| `slot_id` | 客户端广告位 |
| `provider` | 当前为 `google_gam` |
| `config_version` | 后端配置版本 |
| `channel_id` | 当前渠道 |
| `package_name` | 当前包名 |
| `ad_unit_path` | GAM 广告单元 |
| `correlator` | 本次最终请求使用的 correlator |
| `stage` | 当前阶段，例如 `request_config`、`request_ad`、`vast_player` |
| `error` | 错误信息 |

不建议长期在正式日志里完整打印 `ad_tag_url`。如果需要排查，可只在 debug 包或受控开关下打印。

---

## 10. reason 建议

外层 `code` 继续沿用当前项目后端业务状态码规范。广告配置没有命中或被关闭这类业务原因，建议放在 `data.reason` 或异常响应的 `msg` 中。

| reason/msg | 含义 | 客户端行为 |
| --- | --- | --- |
| `NO_MATCHED_CONFIG` | 没有匹配到配置 | 使用缓存或本地兜底 |
| `SLOT_DISABLED` | 广告位关闭 | 本次跳过广告 |
| `INVALID_PACKAGE` | 包名不允许 | 跳过广告并上报 |
| `COUNTRY_BLOCKED` | 国家不允许 | 跳过广告并上报 |
| `CONFIG_INVALID` | 后台配置格式错误 | 使用缓存或本地兜底 |
| `PROVIDER_UNAVAILABLE` | 广告服务不可用 | 使用缓存或本地兜底 |

错误响应示例：

```json
{
  "code": 500001,
  "msg": "NO_MATCHED_CONFIG",
  "data": null
}
```

---

## 11. 安全要求

客户端必须拒绝以下 URL：

- 非 HTTPS URL
- host 不是 `pubads.g.doubleclick.net`
- path 不是 `/gampad/ads`
- 缺少 `iu`
- 缺少 `output=vast`
- 包含非预期 scheme，例如 `javascript:`、`file:`、`intent:`
- URL 长度明显异常

后端也需要在保存配置时做同样的校验，避免错误配置直接下发到线上设备。

---

## 12. 后续扩展

### 12.1 多广告位

新增广告位时只需要增加 `slot_id` 和后端配置，不需要新增客户端渠道包。

### 12.2 多客户

后端通过 `customer_id`、`channel_id`、`package_name`、`country_allowlist` 匹配不同客户配置。

客户端仍然只传上下文，不写客户判断逻辑。

### 12.3 多广告服务商

当前 `provider=google_gam`。

如果后续接入其他广告服务商，可以增加：

| provider | 含义 |
| --- | --- |
| `google_gam` | Google Ad Manager VAST |
| `lsap` | LSAP SDK |
| `custom_vast` | 自定义 VAST URL |

客户端按 `provider` 选择对应播放器或 SDK。

---

## 13. 客户端验收点

后端接口联调时，客户端重点验证：

1. 能请求到 `enabled=true` 的配置。
2. 返回 URL 能被 IMA 正常加载。
3. `description_url` 编码正确。
4. `app_package` 等于当前包名。
5. `correlator` 最终不为空。
6. 接口失败时能走缓存或本地兜底。
7. `enabled=false` 时客户端不播放广告。
8. 多国家、多客户、多广告位命中规则符合预期。
9. 日志能通过 `request_id` 和 `config_version` 串起来。
