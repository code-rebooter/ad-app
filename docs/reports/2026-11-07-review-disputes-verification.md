# 2026-11-07 三项争议复核

> 后续状态：用户又一轮修改后的当前结论见[第二轮修复复审](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-app-tcl-round2-review.md)。本文保留修改前的复现证据，不再代表 IMA 重入等问题的最新状态。

已按当前工作树重新编译并验证。此次只交付定位、可运行探针和结果，不改生产业务逻辑。

## 1. 网络总超时：确切问题在 GoogleGamClient，不是 GoogleGamPatchClient

问题类：`com.smart.android.tclad.GoogleGamClient`。

文件：[GoogleGamClient.java:56](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleGamClient.java#L56)，正文异常分支在 80 行：

```java
@Override public void onFailure(Call ignored, IOException error) {
    if (!call.isCanceled()) callback.onError(301);
}
```

OkHttp 已经进入 onFailure，但总超时也会把 isCanceled 设为 true，因此宿主 callback 被过滤。不是宿主忘接 onFailure，也不是线程一直在请求。

用户贴出的 `call.timeout()` 和无条件 `onResult` 位于另一条 patch 路径：

- [app GoogleGamPatchClient.kt:43–46](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleGamPatchClient.kt#L43)
- [SDK runtime GoogleGamPatchClient.kt:38–41](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/runtime/GoogleGamPatchClient.kt#L38)

该 patch 路径有回调，TclBackendPatch 也有等待超时兜底，不能把 GoogleGamClient 的问题泛化到它们。当前 app 主源码未直接调用 GoogleGamClient；SDK demo 有调用。

本轮控制组：readTimeout 正确回调 error:301；显式取消不回调符合预期；headers/body 两种 callTimeout 均丢失宿主回调。默认构造器的 readTimeout 为 10 秒，因此完全无响应通常先走 readTimeout，不应笼统说默认无响应一定等 20 秒后静默。

另使用真实默认构造器，不注入短 timeout：本地服务立即返回响应头、正文每 3 秒返回 1 字节，避免 readTimeout，实际触发 20 秒总超时：

```text
requestReceived=true
configuredCallTimeoutMs=20000
configuredReadTimeoutMs=10000
elapsedMs=20208
transportIsCanceledAtEnd=true
sdkCallback=NONE
runningCalls=0
```

复现与完整结果：[timeout-verification](/Users/zy/Documents/Codex/ad-app/build/review-20261107/timeout-verification/README.md)。

## 2. CMP：原厂 HQ008 有补报；Google UMP 没有这份补报队列

两份同名 Hq008CmpManager 属于不同 sourceSet：

- [原厂 HQ008:2673](/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt#L2673)：有 KEY_PENDING_CONSENT_REPORT_STATE / consumePendingConsentReportIfNeeded，失败保留，成功清理。
- [Google UMP:254–260](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt#L254)：本地操作成功后先清 pending action，再发 report，忽略上报错误；下一轮 accepted action 分支跳过，仍会丢报。
- [sourceSet:7–11](/Users/zy/Documents/Codex/ad-app/app/gradle/google-url-tcl.gradle#L7)：Google 构建明确排除原厂 HQ008 manager。

当前 [reportConsentResult:69–91](/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt#L69) 的请求体仅含 consent_action 和渠道、MAC、版本、Android API、androidid 等元数据，不发送 TCString 或 consent_payload。因此原厂只保存 action，并不意味着漏发了协议要求的 consent 内容；也不会在补报时读取新的 consent 串入旧报告。cycle_key 仅供本地使用，不进入该 HTTP 请求体。

实编译验证：

```text
Google first pass: popupCalls=1 reports=1
Google retry pass: popupCalls=1 reports=1
native failure retains pending: {"cycle_key":"cycle-A","report_action":"SAVE_SETTINGS"}
PASS native frozen state: new Context rebuilt SAVE_SETTINGS, retry succeeded, pending cleared
```

结论应限定为“Google UMP 结果失败后没有持久补报”，而不是所有 CMP 永久丢失。当前协议无需新增完整 consent 字符串。原厂重试会重读渠道/版本等元数据，不等于逐字冻结原 HTTP 请求；若需求是严格重放原请求，应冻结现有协议 requestBody，而非扩展出当前协议未发送的 consent 字段。

复现与完整结果：[cmp-verification](/Users/zy/Documents/Codex/ad-app/build/review-20261107/cmp-verification/README.md)。Google 探针实编译完整生产 manager；原厂探针从当前源码抽取补报 helpers 原文编译，验证状态保存与消费。

## 3. SDK IMA 重入误取消：已有防护未覆盖活动 owner 的取消回调

位置：[GoogleImaAdManager.kt:81–83](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaAdManager.kt#L81)。

无需并发夹具，单个主线程同步顺序即可：

```text
A.showAd()
B.showAd()                 // 外层 B
  A.destroyAd()            // 第81行
    A.onAdCancelled()
      B.showAd()           // 内层 B，新一代请求，建立新 B 会话
  B.destroyAd()            // 第82行，误取消内层的新 B
  generation 检查         // 第83行，已经太晚
```

28–30 行只保护 pendingOwner.cancelPendingShow 引起的回调；本例 A 是活动 owner，不是 pendingOwner。81 行的 it !== this 只是排除 owner=B，本例 owner=A 所以仍会进入；它不是 generation 防护。需要在跨 owner 的 destroyAd 返回后、调用自身 destroyAd 前核对 generation。

使用当前统一重编译的 production manager、正常公开配置 builder 和公开 manager 构造器，无 Unsafe、无反射写 manager/session。已观测：

```text
baseline.bActive=true
reentry.expected=bActive:true,bCancelled:false
reentry.actual=bActive:false,bCancelled:true
reentry.issueReproduced=true
```

复现与完整结果：[reentry-verification](/Users/zy/Documents/Codex/ad-app/build/review-20261107/reentry-verification/README.md)。Handler/Context/ViewGroup 为确定性 JVM 边界；ViewGroup.post 保留到下一消息循环，不执行播放器启动，因此验证同步替换逻辑而非真实播放。

## 一键运行

```bash
bash /Users/zy/Documents/Codex/ad-app/build/review-20261107/run-verification.sh
```

该入口先编译当前 SDK，再执行三组探针。每组目录包含源码、运行器、结果日志及对应源码/类摘要；本轮实际编译日志位于 build/review-20261107/current-sdk-compile.log。
