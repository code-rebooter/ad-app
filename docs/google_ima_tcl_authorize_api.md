# Google IMA → TCL：授权响应合并广告配置

2026-10-23 后续更新：完整 URL 在本地公共修正之后调用 [google-gam/patch](google_gam_patch_api.md)，不再执行旧开关。以下授权合并取链协议继续有效。

2026-10-23 后续收拢：现已覆盖应用内全部 12 个 Google flavor，包括原 Google 桌面/锁屏渠道和历史 tcl_test 构建名称。共同实现和清单见 `app/src/google_ad_tv_desktop/README.md`。

授权接口 `POST /api/v2/ad/sdk/authorize` 同时返回基础广告链接及加载配置。当前应用的全部 Google 渠道均移除单独的 `google-gam/resolve` 请求，不在缺少链接时回退旧接口。其他独立 SDK 和已发布旧版本不在本次源码合并范围内。

## 授权返回示例

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "authorized": true,
    "request_id": "this-request-id",
    "reported_sound_mode": 0,
    "gam_config": {
      "ad_tag_url": "https://pubads.g.doubleclick.net/gampad/ads?iu=%2Fyour%2Fad_unit&output=vast&env=vp&gdfp_req=1&correlator=",
      "ad_load_timeout_ms": 20000,
      "ad_startup_timeout_ms": 35000
    }
  }
}
```

授权返回原来的声音、下次请求间隔、位置和宿主回调超时等字段仍按原流程解析。本次没有迁移通用 SDK 的 key/config 接口或计时策略；同日补充的 androidid、版本字段、授权 rdid 和日志 request_id 见 [请求字段同步](google-ad-backend-fields-20261023.md)。

## 解析与传递规则

1. 存在 `data.gam_config` 时，广告链接和超时只从该对象读取，不与响应平铺字段混用。音量上报开关支持下面的独立规则。
2. 为兼容通用 SDK 支持的旧授权响应格式，未提供 `gam_config` 时可直接读取 `data.ad_tag_url`、`data.ad_load_timeout_ms`、`data.ad_startup_timeout_ms`。
3. 明确返回 `gam_config: null`、非对象、空对象或无可用 `ad_tag_url` 时，本轮跳过；不恢复平铺旧值、不复用上一轮配置、不请求 resolve。
4. 加载/启播超时缺失或非正数时，分别沿用 20000/35000 毫秒。格式无法解析的广告配置只使本轮缺少可用链接。
5. 旧 ima_tcl_url_patch 字段已停用；返回旧值不会触发本地 TCL 专用调整。
6. 每次授权配置随本轮 `AdConfigDto`、浮窗和播放器调用传递，不使用进程级“上一次配置”缓存。授权未通过时仍由原授权门禁终止流程。

## Google 音量上报开关（2026-11-03）

在 `data.reported_sound_mode` 下发整数：`0` 上报静音，`1` 上报非静音；
兼容字符串 `"0"`、`"1"`。只有有效的 `0` 才启用静音上报，缺失、`null`、布尔值、
其他数字、非法字符串、对象、数组或解析异常均默认非静音。
也可放在 `data.gam_config.reported_sound_mode`，对象内的值优先。每次授权独立生效，
不会沿用上一轮的值。原 `sound_mode` 继续控制实际播放音量。

纯 IMA 运行时统一控制 `vpmute`（非静音 `0`、静音 `1`）、
`AdsRequest.adWillPlayMuted`、已有播放器百分比入口（`100`/`0`，仅在原流程调用时生效）、
ActiveView Native 音量（`1.0`/`0.0`）、OMID `setDeviceVolume`（`1.0`/`0.0`），
以及已有 OM 测量消息中的媒体音量参数，并同步相关诊断日志。
`AdsRequestImpl.setAdWillPlayMuted()` 的底层 AAR 补丁也直接读取本次配置，
按 `MUTED` / `UNMUTED` 写入请求，避免底层固定值覆盖宿主设置。

只替换 SDK 原流程已经读取或发送的音量值，不主动新增音量事件、调用回调或修改
客户端 VAST 的 `start` 消息结构。当前版本 Media3 的 getter 或音量变化回调未触发时，
不会人为补发。

OM 测量的 `mediaPlayerVolume` 与 `deviceVolume` 是独立输入。除设备音量入口外，
在原有 OM `SessionService.start` 的第二个参数、`SessionService.volumeChange` 的第一个参数
进入 OM 服务时，只替换原本存在且有效的数值音量；保持原消息标识、版本、时长、会话和发送者。
没有参数或参数非法时原样通过。由此控制 OM 输出的 `mediaPlayerVolume` 和 `videoPlayerVolume`，
不新增 OM 事件，不修改 native 客户端起播载荷，也不宣称已改写 Core 内部播放器状态。
实际播放音量继续由 `sound_mode` 控制。

ActiveView 原有 `ima.bridge.getVideoMetadata` / `ima.common.getVideoMetadata` 读取返回中，已有且有效的 `volume` 也按本次字段映射，
其余元数据不变；避免原生音量到达之前的早期采样被计入错误的最大音量 `mv`。
ActiveView 的 `a`（当前音量）、`mv`（最大音量）、`nv`（最小音量）应分别取证。
`mut` 在本次 Core 中是测量时长统计，不是静音布尔值，不能按 `0/1` 静音规则改写。

independent 运行时按该字段设置 URL 生成器的 `adWillPlayMuted` 和 patch 前 URL 的
`vpmute`；未下发或字段异常时也默认非静音。两种运行时都会在
`google-gam/patch` 请求中携带本次解析后的整数 `reported_sound_mode`（`0`/`1`），后台生成最终 URL
时应保留对应的 `vpmute`。客户端在交给 IMA/TCL 前也会按本次配置统一最终 URL 的
`vpmute`，其余 URL 内容保持后台结果。

注意：业务字段 `reported_sound_mode=0` 表示静音，对应 Google 的 `vpmute=1`；
`reported_sound_mode=1` 表示非静音，对应 `vpmute=0`，两者取值相反。

## 之后的 URL 流程

`authorize.data.gam_config → independent 本地生成完整 URL → 公共 URL 修正及空 rdid 兜底 → google-gam/patch → 后台 URL 原样交 TCL`

旧开关及本地 TCL 字段调整已移除，详见 [后台 patch 协议](google_gam_patch_api.md)。最终 URL 日志和 FILLED 状态保持原链路。当前仅编译完整独立 AAR，不再提供 IMA 构建或运行时回退；配置见 `app/src/google_ad_tv_desktop/README.md`。

## 接口接入时的历史验证（渠道合并之前）

当前授权配置解析（含旧开关忽略、授权渠道本地绑定）10 项通过；连同 patch HTTP、公共 URL 修正、空 ID 兜底和批次日志，共 24 项定点检查通过。四个渠道 Release Kotlin 编译及旧 Google/纯 TCL 实验分支的共享入口 Debug 编译通过。未请求线上授权、patch 或广告，也未运行设备。原始记录保存在仓库外研究归档中。

## 同日新增请求字段

当前全部 Google 渠道的授权请求携带 `androidid`、`ad_version_name`、`sdk_version` 和 `rdid`。`sdk_version` 采用实际 TCL 执行 SDK 版本 `2.8.02`；rdid 复用现有本地 UID。当前流程接口的共同字段和日志关联方式见 [请求字段同步](google-ad-backend-fields-20261023.md)。
