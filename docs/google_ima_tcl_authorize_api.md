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

1. 存在 `data.gam_config` 时，只从该对象读取广告字段，不与响应平铺字段混用。
2. 为兼容通用 SDK 支持的旧授权响应格式，未提供 `gam_config` 时可直接读取 `data.ad_tag_url`、`data.ad_load_timeout_ms`、`data.ad_startup_timeout_ms`。
3. 明确返回 `gam_config: null`、非对象、空对象或无可用 `ad_tag_url` 时，本轮跳过；不恢复平铺旧值、不复用上一轮配置、不请求 resolve。
4. 加载/启播超时缺失或非正数时，分别沿用 20000/35000 毫秒。格式无法解析的广告配置只使本轮缺少可用链接。
5. 旧 ima_tcl_url_patch 字段已停用；返回旧值不会触发本地 TCL 专用调整。
6. 每次授权配置随本轮 `AdConfigDto`、浮窗和播放器调用传递，不使用进程级“上一次配置”缓存。授权未通过时仍由原授权门禁终止流程。

## 之后的 URL 流程

`authorize.data.gam_config → independent 本地生成完整 URL → 公共 URL 修正及空 rdid 兜底 → google-gam/patch → 后台 URL 原样交 TCL`

旧开关及本地 TCL 字段调整已移除，详见 [后台 patch 协议](google_gam_patch_api.md)。最终 URL 日志和 FILLED 状态保持原链路。当前仅编译完整独立 AAR，不再提供 IMA 构建或运行时回退；配置见 `app/src/google_ad_tv_desktop/README.md`。

## 接口接入时的历史验证（渠道合并之前）

当前授权配置解析（含旧开关忽略、授权渠道本地绑定）10 项通过；连同 patch HTTP、公共 URL 修正、空 ID 兜底和批次日志，共 24 项定点检查通过。四个渠道 Release Kotlin 编译及旧 Google/纯 TCL 实验分支的共享入口 Debug 编译通过。未请求线上授权、patch 或广告，也未运行设备。原始记录保存在仓库外研究归档中。

## 同日新增请求字段

当前全部 Google 渠道的授权请求携带 `androidid`、`ad_version_name`、`sdk_version` 和 `rdid`。`sdk_version` 采用实际 TCL 执行 SDK 版本 `2.8.02`；rdid 复用现有本地 UID。当前流程接口的共同字段和日志关联方式见 [请求字段同步](google-ad-backend-fields-20261023.md)。
