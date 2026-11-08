# 2026-11-06 SDK 模块复审

> 后续状态：下列七项历史发现已修复并完成定向回归，见 [修复与验证报告](2026-11-06-sdk-review-fixes-validation.md)。以下保留原始复审结论及复现证据。

本次只审查，未修改 SDK 源码或重新生成交付包。确认 4 项运行时缺陷和 3 项接入示例问题，其中 2 项 P1、4 项 P2、1 项 P3。前次真机结果仍代表已执行场景的实际结果，但未覆盖下列组合边界。

## 1. [P1] IMA 排队请求在 release 后仍会启动

位置：`ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaAdManager.kt:53`；对应 `app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaAdManager.kt:87` 同样缺少延迟请求的取消登记。

在旧轮 A 的完成测量结算期，调用 B.showAd() 会直接 postDelayed 重试，此时 B.current 仍为空。随后调用 B.release() 在 current 为空处返回，不能撤销 Runnable；A 结算后 B 仍进入创建会话和发起广告的路径。宿主退出或 Flow stop 因此无法可靠取消等待中的下一轮。应将排队请求纳入可取消的会话/代次，并在延迟任务执行前复核所有权和取消状态。

运行客户 Maven 仓库 AAR 的真实 manager 方法，Android Handler/Looper/SystemClock 用 JVM 桩：

```text
Actual compiled manager pending retries after B.release(): 1
Released manager resumed new-request path (sentinel execution is null): ...GoogleImaAdManager.showAd(GoogleImaAdManager.kt:68)
```

复现证据：`/tmp/review-ima-repro/src/Repro.java`、`/tmp/review-ima-repro/evidence.txt`。末尾 NPE 是用于证明已进入新会话分支的测试哨兵，不是生产环境崩溃结论。

## 2. [P1] Google 接入示例将跳过/拒播记为广告完成

位置：`hq008-flow-sdk/README.md:213`，配合 `ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaAdManager.kt:473`。

Google manager 对 `gam_config={}`、配置不可用或外部拒播会先发 AD_ERROR，再以 skipped=true 调用 onAdCompleted() 结束宿主窗口；二合一说明文档也明确此语义。Flow 示例的 onAdReport 未处理 AD_ERROR，onAdCompleted 却无条件 session.completed()。客户照抄后，未播放广告也会产生 Flow AD_COMPLETED、成功日统计和成功终态审计。

应先保存本轮错误/成功事实，在测量/审计收尾后的流程结束回调中选择 completed 或 failed；不要在较早的 AD_ERROR 中直接提前释放，从而再次截断审计。此项通过实际源码调用链核验，本轮未另做真机复现。

## 3. [P2] Google 在取消后继续补发开始回调

位置：`ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaAdManager.kt:390`。

宿主在同步 onAdReport(AD_STARTED) 内调用 release()，会完成取消并收到 onAdCancelled；原调用栈返回后仍无条件调用 session.onStart，形成取消后的 onAdStarted。直接消费二合一 SDK 的宿主可能因此重新打开窗口或恢复已结束业务状态。应在每次可重入宿主回调返回后复核会话活性，而不只检查 hostStartDelivered。

客户交付 AAR 真实方法的最小复现结果：

```text
[AD_STARTED report -> release(), onAdCancelled, onAdStarted after release]
```

复现：`/tmp/review-ima-repro/src/Reentry.java`。FlowRuntime 自身有迟到阶段回调过滤，因此该过滤可以挡住 Flow 的部分业务影响，不能替代 SDK 对直接宿主的保证。

## 4. [P2] TCL 在取消后继续补发 FILLED 回调

位置：`ad-sdk-tcl/src/main/java/com/smart/android/tclad/runtime/TclExecution.kt:124`。

onEvent 先同步调用 onAdExecutionEvent，再继续执行事件分支。宿主在 onAdExecutionEvent("filled", ...) 内 release 或替换广告，会先完成 onAdCancelled，返回后旧调用栈仍发 onAdFilled，造成旧轮终态后的填充事件。应在宿主回调返回后复核 isClosed；started 中宿主回调后的 deadline 扩展也需检查同类重入。

使用原始生产 TclExecution、播放状态/预算代码与临时 Android/bridge 桩实编译运行：

```text
[event:filled, cancelled, filled]
CONFIRMED: onAdFilled delivered after cancellation
```

复现：`/tmp/tcl-review-reentrant/build.py`；结果：`/tmp/tcl-review-reentrant/result.log`。

## 5. [P2] Flow 便捷 init 在重入后覆盖后来者回调

位置：`hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/Hq008FlowSdk.java:31`。

外层 init(B, callbackB) 的 initialize 会同步停止旧轮；若旧轮 cancellationHandler 内再次 initialize(C) 并注册 callbackC，外层返回后通过全局 setAdCallback/start 操作的已是 C。结果为配置 C 配上 callbackB，并意外启动本来只初始化的 C。无 callback 的 init 也存在意外 start 后来者的问题。初始化后续操作应绑定本次创建的 runtime，并在被替换后停止后续操作。

原始 Hq008FlowSdk.java 加最小 Context/FlowRuntime 桩的复现：

```text
current config=newer; newer callback retained=false; outer callback installed=true; starts=1
```

复现：`/tmp/flow-init-reentry-review/src/com/smart/android/hq008flow/Review.java`。实际 FlowRuntime.stop → cancelAd → handler.run() 为同步调用，符合该触发条件。

## 6. [P2] Google 接入示例未透传授权隐藏模式

位置：`hq008-flow-sdk/README.md:193`。

示例获取了 auth，却未设置 `.hiddenMode(auth.isHiddenMode())`。GoogleImaExecutionConfig 默认 hiddenMode=false，播放器在首帧/开始时会将容器 alpha 动画到 1。因此后台要求 hidden_mode=true 的授权，客户照抄示例会变成可见播放，报告也写成可见。应明确传入本轮授权隐藏模式；仅读取授权对象并不会自动应用。

## 7. [P3] Google 示例仍默认上报 TCL 版本

位置：`hq008-flow-sdk/README.md:25`，默认值位于 `hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/Hq008FlowConfig.java:92`。

初始化与 Google 接入示例未设置 `.adSdkVersion("3.35.1")`，配置默认值仍是 2.8.02。照抄后 IMA 广告虽可播放，Flow 顶层 sdk_version 与诊断 adSdkVersion 会写成 TCL 版本。此前测试宿主显式按引擎配置版本，所以没有覆盖此照抄示例路径。

## 范围和验证限制

复审覆盖 TCL facade/runtime/provider、Google 迁入模块与共享 core、Flow runtime/持久化/授权解析、原厂恢复/隔离补丁、Gradle/受控依赖和交付示例。没有足够证据确认 transport 登记、WebView 延迟销毁、原厂异常恢复或正常唯一 request_id 下的报告持久化还有其他新增缺陷；这不等于证明其没有问题。

本轮未改设备环境、未重复完整真机矩阵。四个运行时问题采用真实交付类或生产源码的最小 JVM 复现；原厂播放器、网络和 OMID 实际测量未在这些复现中运行。接入示例问题通过实际源码调用路径确认。SDK 源码与上一轮交付 ZIP 均保持原状，发现的问题目前尚未修复。
