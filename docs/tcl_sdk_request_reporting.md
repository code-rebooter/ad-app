# TCL REQUESTED 上报时机

适用于全部 `AD_CHANNEL_TYPE=TCL` 应用渠道。请求使用独立的 `AD_REQUESTED / REQUESTED` 状态，将触发点从宿主进入 `showAd` 改为原厂 SDK 收到广告取链接口的 HTTP 响应。授权接口仍负责授权与调度，不再用进入 `showAd` 作为 SDK 请求已经发生的证明。

## 接口与统计口径

接口仍为 `POST /api/v2/ad/report`。收到响应时，每个宿主 `request_id` 只生成一次 `event_type=AD_REQUESTED`、`message=REQUESTED`。

共用上报器的生命周期事件均使用独立类型，message 保持原值：

| event_type | message | 阶段 |
| --- | --- | --- |
| AD_REQUESTED | REQUESTED | 请求 |
| AD_FILLED | FILLED | 填充 |
| AD_LOADED | LOADED | 加载 |
| AD_STARTED | STARTED | 起播 |
| AD_COMPLETED | COMPLETED | 完成 |
| AD_ERROR | 具体错误或 TIMEOUT/CANCELLED | 失败、超时、取消 |

REQUESTED、LOADED、STARTED 不再归入 AD_PROGRESS。此类型调整覆盖共用上报器的 TCL、Google 和 LSAP；本次请求响应确认时机的调整仅作用于 TCL。SDK 内部自己的 AD_PROGRESS 进度事件不受影响。

| 情况 | 上报 | 计入 REQUESTED 请求数 |
| --- | --- | --- |
| 原厂取链收到响应，包括业务拒绝、无广告、空/无效响应体、HTTP 4xx/5xx | REQUESTED；后续失败另报 AD_ERROR | 是 |
| 已进入原厂 HTTP 执行，但结束时没有响应，如 DNS、连接失败、超时 | 原有 AD_ERROR，诊断 sdkRequestOutcome=NO_RESPONSE | 否 |
| 初始化失败、容器错误或提前取消，原厂取链 HTTP 尚未执行 | AD_ERROR，诊断 sdkRequestOutcome=NOT_SENT | 否 |

只按原厂 AddressLoader 的请求归属标记观察取链请求，不按“任意 POST”判断，不包含初始化、后台 patch、VAST/Wrapper、素材下载及 tracking。HTTP 重定向已有响应、后续跳转连接失败时，仍上报 REQUESTED。诊断抓取、JSON 解析或后续播放失败不能撤销已经确认的请求。

TCL 业务码 `100000` 是取链成功条件之一，不是请求计数条件。是否返回有效 VAST 视频素材仍由独立 `AD_FILLED / FILLED` 表示。NO_RESPONSE 不能解释为服务端一定没有收到请求，客户端无法据此完全复现 TCL 后台请求报表。

请求示例：

```json
{
  "request_id": "本轮授权关联ID",
  "event_type": "AD_REQUESTED",
  "message": "REQUESTED",
  "channel_id": "TCL_FFA",
  "diagnostic_info": "{\"sdkRequestOutcome\":\"REQUESTED\",\"sdkRequestResponded\":true,\"sdkRequestAttempted\":true,\"sdkRequestAttempts\":1,\"sdkRequestHttpStatus\":503}"
}
```

其他设备、版本和应用字段沿用原上报协议。`diagnostic_info` 还可包含：

- `sdkRequestBusinessCode`：实际返回的业务码，无法解析时省略；不用于是否计数的判断。
- `sdkRequestStartedAtMs` / `sdkRequestRespondedAtMs`：设备 elapsedRealtime 时间，用于本次请求内的耗时关联。
- `sdkRequestError` / `sdkRequestTerminalReason`：网络/响应体错误及终态原因。

收到响应后尽快生成 REQUESTED；若响应之后先进入错误、超时、替换或销毁路径，在关闭会话时同步兜底补发已确认的 REQUESTED。没有响应则不制造 REQUESTED，错误、超时及取消通过 AD_ERROR 闭环，取消使用 message=CANCELLED。上述上报不受“当前会话”过滤与详细日志开关影响。终态诊断携带请求结果快照，NO_RESPONSE 和 NOT_SENT 只是诊断字段，不是新增的独立状态。晚到的旧会话响应不会归到新请求。

进入 showAd 的汇总日志改为 AD_SHOW_REQUESTED；日志中的 AD_REQUESTED 也移动到实际响应确认处。已有 AD_SDK_REQUEST.adLog.final_url 仍表示 patch 成功后的链接记录，不作为请求计数凭据。

## 保存、重试与后台去重

TCL 的 REQUESTED 和终态 AD_ERROR 都在发送前保存到本地，再使用带网络约束的 WorkManager 任务发送。接口失败或 60 秒内未确认时保留数据，按从 30 秒开始的指数退避重试；收到原接口成功回调后才删除。TCL 初始化或新展示入口会再次调度尚未完成的保存记录，覆盖保存后尚未调度就退出的情况。重试使用保存的 request_id、channel_id、ad_version 等原始字段，不重新读取新一轮配置。

**后台按 `(request_id, event_type, message)` 幂等处理。** 服务端处理成功、成功回包丢失时会重发；直接按 HTTP 上报次数累加会重复计数。新版本请求统计使用 `event_type=AD_REQUESTED`，message 仍为 REQUESTED；兼容旧版时继续按旧协议识别历史 AD_PROGRESS / REQUESTED，不能把同一请求重复计数。REQUESTED 可能因重试晚于 AD_ERROR 或 AD_COMPLETED 到达，用 request_id 关联，不依赖到达顺序。
