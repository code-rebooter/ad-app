# IMA → TCL：最终 URL 日志与 FILLED 状态

2026-11-03：填充状态改用独立 `event_type=AD_FILLED`，继续携带 `message=FILLED`，覆盖共用状态上报器的 Google 和原厂 TCL 渠道。

2026-10-23 当前说明：完整 URL 取后台 google-gam/patch 的返回结果，旧开关字段已删除；仍在 AD_SDK_REQUEST.adLog.final_url 中记录，随终态一次汇总。后面的原开关示例仅为历史记录，当前流程见 [patch 协议](google_gam_patch_api.md)。

适用渠道：`google_ad_tv_desktop_v260904_1`、`google_ad_tv_desktop_v260904_x98k`、`google_ad_tv_desktop_localcalendar`、`google_ad_tv_desktop_kaneshort`。

沿用现有接口补充以下两项，不增加逐阶段日志请求。

## 1. 最终 URL 追加到原来的汇总日志

接口仍为 `POST /api/v2/ad/consent-log-report`。在本次广告流程完成、失败、取消或超时后，沿用原有的一次汇总上传；URL 就绪时只记录到当前批次，不立即上传日志。

完整 URL 放在这次汇总请求 `ad_log` 的结构化内容里：

```text
steps[ eventType = "AD_SDK_REQUEST" ].adLog.final_url
```

该 step 的 adLog 示例：

```json
{
  "request_id": "本次请求ID",
  "final_url": "交给TCL.setVastUrl的完整URL",
  "ima_tcl_url_patch": 0
}
```

`final_url` 已完成开关选择和公共补丁修正（1.0.2 起包含空 rdid 的持久 UID 兜底），与 setVastUrl 的输入完全相同；不会截成 512 字符的 event_message，也不只保留哈希。TCL 自己随后加的 requestId 不属于这个交接 URL。日志压缩时将 AD_SDK_REQUEST 视为应保留的结构化内容，日志开关及终态汇总方式继续沿用现有逻辑。

## 2. 现有状态接口新增 FILLED

接口仍为 `POST /api/v2/ad/report`，沿用原来的字段和本次 request_id：

```json
{
  "request_id": "本次请求ID",
  "event_type": "AD_FILLED",
  "message": "FILLED",
  "diagnostic_info": "包含原有诊断信息及 filled、filledAtMs、fillSource 等字段的JSON字符串"
}
```

其他 device/channel/app 字段与原接口一致。后台按 `event_type=AD_FILLED` 识别“已收到广告素材”，继续保留 `message=FILLED`。当前共用上报器中的 REQUESTED、LOADED、STARTED 也分别使用 AD_REQUESTED、AD_LOADED、AD_STARTED，message 不变，接口路径不变。状态请求独立于汇总日志，在发现填充时发起，不等待广告播放结束；HTTP 回调的到达顺序不保证和事件触发顺序相同，后台用 request_id 关联已有的事件记录。

### 触发位置

观察 TCL 本来就拉取到的 XML，在该字符串进入 TCL XmlConverter 正式解析之前，做一次独立的轻量 XML 识别。识别到完整 InLine 线性视频广告且 MediaFile 存在 HTTP(S) 素材地址时，上报一次 FILLED。

只看到 Wrapper 跳转、空 VAST、空字符串、无法识别的 XML，不上报 FILLED；Wrapper 后续响应由原 TCL 自己继续拉取，同一原厂 request ID 沿用到最终响应。一个宿主请求最多上报一次 FILLED，取消后的旧响应不计入新请求。没有另发广告请求，没有改变原厂解析、播放或 XML 监测。

FILLED 代表“广告响应已返回视频素材链接”，不代表 TCL 已成功解析、视频能播放、已曝光或产生收益。后续 LOADED、STARTED、COMPLETED、ERROR 继续照常上报；后续错误的诊断中仍保留已经拿到填充的事实，不把它改成无填充。

## 交测结果（2026-10-17）

- 官方测试广告：REQUESTED → FILLED → LOADED → STARTED → COMPLETED，FILLED 发起在 TCL loaded 回调之前；日志只请求一次。
- 空 VAST：没有 FILLED，沿用错误状态；日志只请求一次。
- Wrapper → 返回素材 → TCL 后续失败：只上报一次 FILLED，且发生在最终响应到达之后；随后错误继续保留，日志只请求一次。
- 真实管理器入口：本次无填充，未误报 FILLED；汇总日志里的完整 URL 与管理器交接 URL 一致。
- 本次观察到的 FILLED 状态请求及汇总日志请求，接口回调均成功。
- 15 项针对性测试通过，包括大于 512 字符的完整 URL、日志压缩后 URL 保留、一个终态收口、空/跳转响应不报填充、重复/取消响应隔离。

原始证据已迁移至仓库外研究归档。Release 构建、静态校验、交付包及本轮模拟器 Debug 验证记录均不纳入版本控制。

2026-10-23：最终 URL 现取 google-gam/patch 成功返回的 data.ad_tag_url，记录到原 AD_SDK_REQUEST.adLog.final_url；旧 ima_tcl_url_patch 日志字段已移除。仍在原终态一次汇总上传，FILLED 观察与状态上报时机不变。完整协议见 [后台 patch](google_gam_patch_api.md)。
