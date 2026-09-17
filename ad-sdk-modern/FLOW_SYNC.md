# 通用 SDK 流程同步说明（v1.0.16）

适用依赖：`com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.16`。`v1.0.15` 不包含这些新增能力。

## 同步范围

本版本同步主项目的公共广告流程能力，以一次 `AdSdk.play()` 对应一轮广告为边界。

| 能力 | 通用 SDK 的处理 |
| --- | --- |
| `popup_log_enabled` | 读取 flow-control 返回值，缺省为 `true`，仅控制本轮整轮流程上传 |
| 整轮日志 | 记录流控、CMP/UMP 决策及执行、授权、GAM 配置、IMA 事件、加载、播放、终态；结束时汇总上传一次 |
| 各阶段耗时 | 上报请求到加载、请求到开始、播放、请求总时长及会话总时长，使用单调时钟 |
| 授权结果 | 授权允许或拒绝时都保留后台 `request_id`、`next_request_seconds`；`client_ip` 用于诊断 |
| 原始错误 | 保留上游错误来源、原始错误码和 message；既有回调不变，详见诊断文档 |
| 每日次数 | 沿用已有广告事件上报供后台汇总，不增加本地每日累计或 `SDK_DAILY_METRIC` |

SDK 没有屏保生命周期，屏保启动/停止统计属于宿主；不增加屏保专用接口。主项目的自动轮询、电视信号门禁、悬浮窗位置和窗口生命周期也由宿主管理。

后台明确返回 `sound_mode` 时按后台设置播放，缺失时仍使用 `AdRequest` 的声音设置。CMP 远端决策失败或未知时仍依据已有 UMP 状态决定是否继续，保留原始错误，不自动将失败转成 `MAYBE_LATER`。这些规则保留通用 SDK 原有接入约定。

## 每轮日志

整轮日志发送到当前渠道域名下的 `/api/v2/ad/consent-log-report`，仍使用主项目的 `channel_id`、`mac`、`ad_version`、`event_type`、`event_message`、`ad_log` 字段。

`ad_log` 为 JSON 字符串，包含 `traceVersion=2`、`requestId`、`status`、最终事件/原因、步骤和诊断字段。每个步骤包含 `index`、`elapsedMs`、`eventType`、`eventMessage`。每个会话独立收集，流控关闭、CMP 阻断、授权拒绝、请求失败、完成、超时和主动释放均有终态。

- `popup_log_enabled=false`：本轮不上传整轮日志，不影响广告事件上报和本地日志。
- `persist.sys.ad.log`：只控制 SDK 本地日志，开启后 SDK 自动打印 `onFinished` 的状态、原文和原始错误码。
- 同一会话终态只上报一次；授权成功时不会提前结束日志收集。
- 最多保留 80 个步骤（首步和最近的步骤），每步文本最多 512 字符；整轮日志里的响应正文副本最多保留 32,000 字符并标记截断；汇总超过 256,000 字符时继续压缩并标记，保持有效 JSON。回调中的原始错误不截断。
- 上传异步执行，不等待服务器响应再通知 `onFinished`；没有离线补传队列。

广告事件继续发送到 `/api/v2/ad/report`。`REQUESTED` 表示已通过授权；`LOADED`、`STARTED` 和终态表示实际进度。后台可按 `request_id` 关联统计，不能把 `REQUESTED` 数量当成宿主调用 `play()` 的总次数。

诊断字段包括 `phase`、`hiddenMode`、`clientIp`、`nextRequestSeconds`、`callbackTimeoutMs`、`adTagHash`、`adTagLength`，上报体补充 `local_ip`。`requestCreatedAtMs/loadedAtMs/startedAtMs/finishedAtMs` 来自单调时钟，`createdAtMs` 为墙上时间；只对同一时钟的字段计算耗时。未到达的阶段不填写时间。

既有超时仍覆盖整轮会话（`timeoutScope=whole_session`），包括流控、CMP、授权和播放；后台合法的 timeout 覆盖值仍以会话开始时刻计算剩余时间。

## 宿主读取授权信息

新增两个只读方法，原有 `AdListener` 接口和播放调用方式无需修改：

```java
@Override
public void onFinished(AdSession session, AdResult result) {
    String requestId = session.getRequestId();
    long nextSeconds = session.getNextRequestSeconds();
    Log.i("AdSdk", "requestId=" + requestId + ", result=" + result);
    // nextSeconds 可供宿主自己的调度逻辑参考。
}
```

`getRequestId()` 初始为本轮客户端 ID，收到授权响应后使用后台 ID（后台未返回时保留客户端 ID）。

`getNextRequestSeconds()` 返回后台合法间隔（10～86400 秒），未返回或非法时为 `0`。授权拒绝时也能读取；SDK 不沿用上一轮间隔，不自动安排下一次广告。宿主按自身业务决定是否以及何时再次调用 `AdSdk.play()`。
