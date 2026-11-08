# HQ008 Flow SDK 最简接入

2026-11-06 的 2.1.5 版本包含流控/CMP 门禁、动态授权参数、渠道与请求归属、播放期限和持久化上报，以及 init 释放回调内再次初始化的归属修复。Google 接入示例同时修正跳过/失败结算、隐藏模式和版本配置。以下说明针对本次重新生成的 AAR；历史 AAR 不包含全部改动。

## 1. 引入 AAR

将 AAR 放到客户项目 `app/libs/`，并在应用模块添加依赖。文件名按实际 AAR 文件名保持一致：

最低 Android API 24。直接以 `files(...)` 引用 AAR 时，下面三个依赖需要由宿主显式声明，尤其是用于失败补发的 WorkManager；AAR 文件本身不携带传递依赖元数据。

```gradle
dependencies {
    implementation files('libs/hq008-flow-sdk-v2.1.5-release.aar')
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'
    implementation 'com.google.code.gson:gson:2.8.9'
    implementation 'androidx.work:work-runtime:2.10.0'
}
```

## 2. Application 初始化

在 `Application.onCreate()` 里先初始化，不立即启动：

```java
Hq008FlowSdk.initialize(this, Hq008FlowConfig.builder("ADHQ1001")
    .adSdkVersion("2.8.02") // 此例配置 TCL 渠道；Google 纯 IMA 渠道使用 "3.35.1"。
    .consentGate((requestId, channelId, decision) -> {
        // 在宿主 CMP 初始状态就绪且远端决策完成后回传实际结果：
        hostCmp.runWhenDecisionReady(allowed -> decision.onDecision(allowed));
    })
    .build());
```

设置 consentGate 后，`skip_cmp=true` 跳过门禁；否则在 CMP 回调允许后才调用 authorize，决策拒绝或超时结束本轮。SDK 不内嵌宿主 CMP UI；已有 CMP 流程通过此接口衔接。在 start() 前已由宿主管理 CMP 的既有接入可保留 initialize(context, channelId) 入口。

如果是屏保、页面可见性这类场景，再在可展示时调用 `Hq008FlowSdk.start()`，退出时调用 `Hq008FlowSdk.stop()` 和 `Hq008FlowSdk.clearAdCallback()`。

开启系统属性 `persist.sys.ad.log=true` 后，SDK 会按自然日打印本地触发和上报统计，方便调试对账。

每轮广告流程结束时，SDK 会额外调用现有 `/api/v2/ad/report` 上报一次当天汇总；汇总放在 `message` 字段中，不使用 `diagnostic_info`。自然日固定按北京时间（`Asia/Shanghai`）计算，数据使用 `SharedPreferences` 持久化。示例：

```json
{
  "day": "20260806",
  "timezone": "Asia/Shanghai",
  "screensaver_start_total": 12,
  "screensaver_stop_total": 10,
  "authorized_callback_total": 3864,
  "current_final_status": "FAILED",
  "current_final_message": "code=1001,message=TCL_AD_ERROR_1001",
  "final_status_totals": [
    {
      "status": "COMPLETED",
      "message": "COMPLETED",
      "total": 3012
    },
    {
      "status": "FAILED",
      "message": "code=1001,message=TCL_AD_ERROR_1001",
      "total": 32
    },
    {
      "status": "TIMEOUT",
      "message": "TIMEOUT",
      "total": 128
    }
  ]
}
```

其中 `screensaver_start_total`、`screensaver_stop_total`、`authorized_callback_total` 是当天累计次数；`final_status_totals` 按每个终态的 `status + message` 分开累计，最后一次终态由 `current_final_status` 和 `current_final_message` 表示。一次流程只产生一次终态统计上报，重复回调不会重复计数。

## 3. 注册广告回调

在真正能展示广告的组件中注册回调：

```java
private Hq008AdSession currentAdSession;

private final Hq008AdCallback hq008AdCallback = new Hq008AdCallback() {
    @Override
    public void onAdAuthorized(Hq008AdSession session) {
        currentAdSession = session;
        requestScreensaverAd();
    }
};

@Override
public void onDreamingStarted() {
    super.onDreamingStarted();
    Hq008FlowSdk.setAdCallback(hq008AdCallback);
    Hq008FlowSdk.start();
}

@Override
public void onDreamingStopped() {
    finishCurrentSessionAsFailed("SCREENSAVER_STOPPED");
    Hq008FlowSdk.stop();
    Hq008FlowSdk.clearAdCallback();
    super.onDreamingStopped();
}
```

## 4. 回传广告状态

客户原有广告 SDK 回调中转发状态：

```java
private void onAdLoaded() {
    if (currentAdSession != null) {
        currentAdSession.loaded();
    }
}

private void onAdStartPlay() {
    if (currentAdSession != null) {
        currentAdSession.started();
    }
}

private void onAdFinished() {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.completed();
    }
}

private void onAdError(int errorCode, String errorMessage) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.failed(errorCode, errorMessage);
    }
}

private void finishCurrentSessionAsFailed(String reason) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.failed(reason);
    }
}
```


## 与 ad-sdk-tcl 同步接入

每轮使用 `session.getAuthorization()` 读取授权配置：request/channel、hidden_mode、sound_mode、浮窗位置尺寸、callbackTimeoutMs、gam_config 和本轮 popup_log_enabled 均绑定此 session。`ad_callback_timeout_seconds`（兼容 callback_timeout_seconds）有效范围 30..600 秒；无效值使用本地配置，默认 180 秒。轮询默认 1200..1500 秒，服务端间隔有效范围 10..86400 秒，失效时清除旧值；重新开始至少等待初始延迟。轮询按独立周期运行，播放中的轮询跳过请求但继续安排下轮，广告完成不会将周期向后推移。

```java
Hq008Authorization auth = session.getAuthorization();
ExecutionConfig execution = ExecutionConfig.builder(context, session.getRequestId(), session.getChannelId())
    .callbackTimeoutSeconds(auth.getCallbackTimeoutMs() / 1000L)
    .auditEnabled(auth.isPopupLogEnabled())
    .build();

Controller controller = Ad.get().begin(context).lazyLoad().setPlayerType(0).setPlayerViewType(1)
    .setExecutionConfig(execution)
    .setVolume(auth.isSoundEnabled() ? 1f : 0f)
    .setResolvedAd(resolvedInput) // 宿主已取得的完整 TCL 取链响应，也可 setAdTagProvider(provider)
    .listen(new AdStatusListener() {
        @Override public void onAdRequested(java.util.Map<String, Object> fields) { session.requested(fields); }
        @Override public void onAdFilled() { session.filled(); }
        @Override public void onAdLoaded(Controller loaded) { session.loaded(); loaded.start(adContainer); }
        @Override public void onAdStartPlay() { session.started(); }
        @Override public void onAdStartPlay(double seconds) { session.started(seconds); }
        @Override public void onPlaybackDeadline(long deadlineMs) { session.extendPlaybackDeadline(deadlineMs); }
        @Override public void onAdDiagnostics(java.util.Map<String, Object> fields) { session.diagnostics(fields); }
        @Override public void onAdExecutionEvent(String event, java.util.Map<String, Object> fields) {
            session.runtimeEvent(event, fields);
        }
        @Override public void onAdAudit(String json) { session.runtimeAudit(json); }
        @Override public void onAdFinished() { session.completed(); }
        @Override public void onAdError(int code) { session.failed(code, "TCL_AD_ERROR_" + code); }
        @Override public void onAdCancelled() { session.failed("CANCELLED"); }
    }).start();
session.setCancellationHandler(controller::release);
```

授权回调只记录 AD_AUTHORIZED 生命周期，不能作为 TCL 真实请求证明。`session.requested(fields)` 在实际地址 HTTP 响应后调用；各阶段使用独立 event_type：AD_REQUESTED、AD_FILLED、AD_LOADED、AD_STARTED、AD_COMPLETED、AD_ERROR。`started(double)` 是 TCL 的广告串最大时长，记录为 adBreakMaxDurationSeconds。回调去重、终态只结算一次；旧 session 回调不会改动新一轮；终态前已确认、随后排队送达的 REQUESTED 仍按原 request/channel 单独持久化上报。

REQUESTED 与 AD_ERROR 先以完整身份和载荷保存，再由 WorkManager 在网络可用时重试，初始化时恢复待发送项。上报确认后才删除本地记录；大载荷不进入 WorkManager 的 Data。应用需要正常初始化 WorkManager，关闭默认初始化的宿主应自行提供初始化。

`popup_log_enabled=false` 关闭整轮详单与 TCL 运行时审计，REQUESTED/错误及日统计继续工作。终态汇总通过 consent-log-report 发送，保留 TCL_RUNTIME_AUDIT 的完整有界 XML 和时间线。播放期限外预留与 app 一致的 5 秒清理余量，延长流程守卫不会缩短旧期限。流程 stop/超时会调用本轮 cancellationHandler，以释放播放器及在途网络；TCL 的终态诊断和审计在业务终态上传前收集。

## Google 纯 IMA 接入

Google 渠道在初始化阶段显式配置 `.adSdkVersion("3.35.1")`；TCL 渠道配置 `.adSdkVersion("2.8.02")`。Flow 报告的 `sdk_version` 来自该初始化配置，不会从播放器回调自动推断。

若同一 runtime 按轮次选择二合一 SDK 的不同引擎，`adSdkVersion` 应使用与后台约定的 SDK 产品版本，并保留执行诊断中的实际引擎版本；不要在每轮选择引擎时把某一个底层库的版本作为全部轮次的默认。当前 API 不支持逐 session 覆盖版本。若后台要求该字段严格等于底层引擎版本，应在启动流程前确定引擎，并按对应版本配置该流程；不要为改版本号在仍有效的 session 中重新 `initialize()`，这会停止旧流程。

使用二合一依赖中的 `com.smart.android.tclad.ima.*`，将授权参数和回调绑定到本轮 `session`。下面代码放在本轮 `onAdAuthorized(session)` 内：

```java
Hq008Authorization auth = session.getAuthorization();
GoogleImaAdManager manager = new GoogleImaAdManager(
    GoogleImaExecutionConfig.builder(context, session.getRequestId(), session.getChannelId())
        .callbackTimeoutSeconds(auth.getCallbackTimeoutMs() / 1000L)
        .hiddenMode(auth.isHiddenMode())
        .auditEnabled(auth.isPopupLogEnabled())
        .build(),
    new GoogleImaSessionListener() {
        private boolean completedReport;
        private String failureMessage;

        @Override public void onAdReport(String id, String event, String message,
                java.util.Map<String, Object> fields) {
            session.diagnostics(fields);
            session.runtimeEvent(event, fields);
            switch (event) {
                case "AD_REQUESTED": session.requested(fields); break;
                case "AD_FILLED": session.filled(); break;
                case "AD_LOADED": session.loaded(); break;
                case "AD_STARTED": session.started(); break;
                case "AD_COMPLETED": completedReport = true; break;
                case "AD_ERROR":
                    failureMessage = message == null || message.isEmpty() ? "GOOGLE_FAILED" : message;
                    break;
            }
        }
        @Override public void onPlaybackDeadline(String id, long deadlineMs) {
            session.extendPlaybackDeadline(deadlineMs);
        }
        @Override public void onAdAudit(String id, String json) { session.runtimeAudit(json); }
        @Override public void onAdCompleted() {
            if (failureMessage != null) session.failed(failureMessage);
            else if (completedReport) session.completed();
            else session.failed("GOOGLE_FINISHED_WITHOUT_COMPLETION");
        }
        @Override public void onAdFailed() {
            session.failed(failureMessage == null ? "GOOGLE_FAILED" : failureMessage);
        }
        @Override public void onAdCancelled(String reason) { session.failed("CANCELLED:" + reason); }
    });
session.setCancellationHandler(manager::release);
String gamConfigJson = auth.getGamConfigJson();
com.google.gson.JsonElement gamConfig = gamConfigJson == null
    ? null : com.google.gson.JsonParser.parseString(gamConfigJson);
manager.showAd(adContainer, gamConfig, auth.isSoundEnabled());
```

在最终 `onAdCompleted()` / `onAdFailed()` 回调中结算流程，此时运行时审计已经交付。`onAdCompleted()` 也可能表示缺失配置或跳过等执行已结束，不能单独作为播放成功证明；示例先保存业务 `AD_ERROR` / `AD_COMPLETED`，在最终回调中根据实际结果调用 `session.failed()` 或 `session.completed()`，保留跳过和失败的原始审计。

`onAdReport` 的终态会先到达，只记录状态，不在这里立即结算 session，避免过早释放播放器并截断测量收尾。完成、取消回调内重新 `start()/triggerNow()` 或 `initialize()` 时，始终使用对应回调捕获的 `session`，确保旧轮收尾不会覆盖新轮状态。
