# Google GAM patch 客户端接入（2026-10-23）

2026-11-02：纯 IMA 运行时也已接入本协议。顺序为
`authorize.data.gam_config → IMA Core 拼接完整 URL → google-gam/patch → 后台 URL 原样用于 IMA 广告 HTTP → IMA 解析、播放和测量`。
原生网络及 WebView 回退共享本轮一次 patch，使用绑定的 request_id/channel_id、
本轮剩余超时和独立本地 rdid；patch 失败不回退原链接，播放器释放时取消请求。
纯 IMA 的 `sdk_version` 为实际使用的 `3.35.1`。其 REQUEST/READY、最终 URL 日志和
FILLED 状态沿用下述业务含义；TCL 专用的末端 URL 修正规则不额外施加到 IMA 请求。

适用渠道：`google_ad_tv_desktop_v260904_1`、`google_ad_tv_desktop_v260904_x98k`、`google_ad_tv_desktop_localcalendar`、`google_ad_tv_desktop_kaneshort`。

协议依据为本次提供的 `google-gam-patch.md`。其中 `rdid` 是用户在文档之外明确补充的请求字段，客户端一并提交。

## 固定顺序

`flow-control / CMP → sdk/authorize.data.gam_config → independent 本地生成完整 URL → 本地公共 URL 修正及空 rdid 兜底 → google-gam/patch → 后台返回 URL → TCL.setVastUrl → TCL 请求、解析、播放和跟踪`

已移除客户端 `ima_tcl_url_patch` 开关及其控制的 TCL 专用字段删改实现；服务器仍返回旧字段时不再生效。本地公共修正规则仍执行，但只发生在调用 patch 之前。

客户端把后台返回的链接原样传给 `setVastUrl`，不再追加公共修正、ID 兜底、信号拼接或本地 TCL 适配。TCL 接手后自身的原厂请求行为保持原样。

## 请求

`POST /api/v2/ad/google-gam/patch`，JSON，使用当前渠道的既有自有后台域名。

```json
{
  "request_id": "same-as-this-authorization",
  "channel_id": "same-channel-used-for-authorization",
  "ad_tag_url": "https://pubads.g.doubleclick.net/gampad/ads?full=locally_corrected_url",
  "rdid": "existing-local-persisted-uuid",
  "mac": "00:11:22:33:44:55",
  "androidid": "actual-android-id",
  "ad_version": 12,
  "ad_version_name": "actual-host-version-name",
  "sdk_version": "2.8.02"
}
```

- `request_id` 沿用本次授权的最终请求 ID；授权响应没给 ID 时，沿用发起授权时的本地 ID。
- `channel_id` 在授权时绑定并随本轮传到播放器，不在 patch 回调时重读可变渠道。
- `ad_tag_url` 是已经完成本地公共修正和空 ID 兜底的完整链接，保留编码、重复参数及信号串。
- 单独的 `rdid` 直接读取 `google_tcl_ad/aid`：已有值复用，没有才生成并持久保存。它与授权请求复用同一存储，不能从 URL 的 rdid 反取；URL 内已有 GMS 广告 ID 不因此被换成本地 UID。
- `mac`、`androidid` 与前一轮字段同步规则一致；ANDROID_ID 读取失败传空字符串。
- 版本字段继续描述实际宿主和本链路使用的 TCL 2.8.02 执行 SDK。

## 响应与错误

必须同时满足 HTTP 200、JSON `code=200`，并且 `data.ad_tag_url` 是非空字符串：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "ad_tag_url": "https://pubads.g.doubleclick.net/gampad/ads?final=backend_result"
  }
}
```

只把返回 URL 传给 TCL。请求失败、HTTP/API 错误、JSON 无效或返回空链接时，本轮报告失败，不回退到本地链接发广告请求。

patch 使用本轮宿主超时的剩余时间，不重新延长总时限。取消、替换、窗口销毁和总超时会取消在途 patch；过期回调不能进入新会话。客户端不自动重试该 POST。

## 日志

`AD_GAM_PATCH_REQUEST` 和 `AD_GAM_PATCH_READY` 是原流程日志中的步骤，普通消息只记录 URL 哈希和长度。真正交给 TCL 的完整后台结果仍写入 `AD_SDK_REQUEST.adLog.final_url`，随原终态日志一次汇总上报。已移除日志中的旧开关字段。FILLED 仍在 TCL 收到填充响应且正式解析前报告。

## 验证范围

定点检查使用拦截的 OkHttp 请求验证 JSON、成功码、错误、取消和 URL 原值；同时覆盖授权配置、本地公共修正、空 ID 兜底与最终 URL 汇总日志。没有请求线上 patch/广告，也没有做设备播放验证。构建和定点原始记录保存在仓库外研究归档中。
