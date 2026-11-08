# 2026-11-07 app / ad-sdk-tcl 第三轮修复复审

> 后续状态：再次修改后的最新结论见[第四轮修复复审](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-app-tcl-round4-review.md)。本页保留第三轮证据，取消、CMP 和 R8 问题已在后续验证关闭。

## 当前结论

本轮相关 Gradle 构建和 **833 次现有模块单测全部通过**；针对本轮改动的定向验证仍确认 **4 项 P2 问题**。本轮只审查、执行验证及保存证据，没有修改生产代码或既有测试。

重点检查本轮变化的 GoogleGamClient、Google CMP、WebKit consumer rules、SDK/app RDID 初始化以及 TCL 测试/Debug 夹具，并回归上轮涉及的 IMA 所有权及 independent 启动计时；不将本轮称为重新逆向所有原厂二进制。

## 已修复 / 不再按原问题报告

| 上轮项目 | 当前状态 |
|---|---|
| 正文 callTimeout 漏回调 | **已修复**。短总超时和真实默认 20 秒慢正文均有一次错误回调。 |
| Google CMP accepted 快速路径不补报 | **该路径已修复**。ACCEPT_ALL/SAVE_SETTINGS 在正式入口与冻结 preferences 恢复后可补报。另一分支见问题 2。 |
| 旧 REJECT ACK 删除新 ACCEPT_ALL pending | **已修复**。不同 action 比较能够挡住原反例。相同 action 的业务语义另见文末，不算确定缺陷。 |
| WebKit feature 字段被 R8 删除 | **这部分已修复**。两个 feature 字段现均保留，剩余方法签名问题见问题 3。 |
| TCL 单测不能编译 | **已修复**。本轮 HQ008 的 240 次单测执行全部通过。 |
| Debug patch 夹具默认关闭 patch | **已修复该源码缺口**。两处均显式 enabled=true；patch 专项 4 项单测通过。本轮没有重新执行其真机矩阵。 |
| SDK IMA 跨 manager 重入 | 维持关闭，本轮 12 项正向回归通过。 |
| independent 启动 timer 截断正常播放 | 维持关闭：开始后 timer 为空，30 秒不再终止已开始的 60 秒素材；未开始请求仍超时。 |

## 剩余问题

### 1. [P2] 宿主取消仍按异常文本识别，正常正文取消也会漏出旧错误

位置：[GoogleGamClient.java:97–100](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleGamClient.java#L97)，错误分派在 59、87 行，取消入口在 94 行。

当前 `isHostCancellation` 只将 `call.isCanceled && error.message == "Canceled"` 视为宿主取消。但主动 cancel 并不保证这个异常文本：

- 本轮先确认响应头已到达，并观察真实 OkHttp 工作线程栈阻塞在 `ResponseBody.string → socket read`，随后宿主 cancel。实际异常为 `java.net.SocketException: Socket closed`，SDK 仍发 `onError(900)`。
- 原 timeout 交付竞争也仍存在：timeout 已产生，SDK onFailure 尚未执行；宿主 cancel 返回后再放行回调，仍发 `onError(301)`。

```text
case=explicit-cancel-while-blocked-in-body-socket-read
transportFailure=java.net.SocketException:Socket closed
sdkCallback=error:900

case=explicit-cancel-before-sdk-failure-delivery
transportFailure=java.io.InterruptedIOException:timeout
sdkCallback=error:301
```

影响：已取消/替换请求仍发旧业务错误。两种场景归为同一取消来源识别缺陷，不重复计两项。应独立记录宿主取消，而不是从底层异常字符串推断。

正文 20 秒总超时则已正确回调 error:900；本轮未观测到重复终态，宿主回调中 cancel 并再次 resolve 也无死锁。该问题仍仅指 `com.smart.android.tclad.GoogleGamClient`，不是 GoogleGamPatchClient/TclBackendPatch。

证据：[SDK core 说明](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-core/README.md)、[实际日志](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-core/timeout-result.log)。

### 2. [P2] CMP 的 NOT_REQUIRED 快速返回仍不消费待补报告

位置：[Google Hq008CmpManager.kt:98–106](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt#L98)。

新增 flush 只接在 accepted-action 分支。上轮成功执行 REJECT、报告失败且保存 pending=REJECT 后，若恢复时 UMP 返回 `canRequestAds=true`、`privacyOptionsStatus=NOT_REQUIRED`，不会命中 accepted 分支，而在另一条快速路径直接 onComplete/return。该状态持续时，每轮都继续跳过待补报告。

本轮实编译当前完整 Google manager，保存 preferences 后以新 Context 恢复，结果为 `reports=1, pending=REJECT`，没有产生重试。不是假定原厂 HQ008 CMP 缺少队列，也不涉及 TCString。

需在可能提前返回的正式路径中统一处理独立的待补报告，而非只补 accepted 分支。

证据：[App flow 说明](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/app-flow/README.md)、[实际日志](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/app-flow/result.log)。恢复测试为冻结 preferences 创建新 Context，不等同于真机进程重启。

### 3. [P2] 新增 R8 keep 规则的方法签名不匹配真实 API

位置：[consumer-rules.pro:14–16](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/consumer-rules.pro#L14)，生产反射调用位于 [GoogleImaFirmwareBridge.kt:340–349](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaFirmwareBridge.kt#L340)。

当前新增规则匹配的是：

```text
boolean addDocumentStartJavaScript(WebView, String)
```

实际 WebKit 1.9.0 API 和生产反射对应的是：

```text
androidx.webkit.ScriptHandler addDocumentStartJavaScript(WebView, String, Set)
```

返回类型和参数数量均不同。当前规则的最小 R8 闭环明确输出 `Proguard configuration rule does not match anything`；压缩后该真实反射入口仍为 `MISSING`。WebViewFeature 两个字段已保住，不再按字段缺失报原缺陷；metadata 类的保留也已生效，剩下的是 document-start 方法入口。

需按真实反射 ABI 保留正确方法。证据使用当前 production helper、当前规则、实际 WebKit AAR 和本机 R8，不是历史 APK；这是最小 classfile 压缩验证，不声称新的完整 Release 真机验证。

证据：[SDK runtime 说明](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/README.md)、[本轮 R8 日志](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/webkit/result.log)。

### 4. [P2，首次并发边界] commit 后重读仍不是原子 get-or-create

位置：

- [ExecutionConfig.java:69–80](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java#L69)
- [GoogleImaExecutionConfig.java:71–78](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java#L71)
- [Hq008ApiRequestFields.kt:29–56](/Users/zy/Documents/Codex/ad-app/app/src/main/java/com/smart/android/ad_app/Hq008ApiRequestFields.kt#L29) 及 shared identity 的另一临界区。

同步 commit 保证本次写入完成，但没有把不同调用方的“读空 → 生成 → 写入 → 返回”合并到共同临界区。合法顺序仍可以是：

```text
两边均读到空 aid
TCL/app 写入 A，重读 A，返回 A
IMA/shared 随后写入 B，返回 B
最终持久值为 B，但前一份配置已经冻结 A
```

本轮有两组当前生产源码直编验证：SDK 两份 config 得到 `Same persistent identity=false`；当前 app Kotlin helper 与 shared Java identity 即使都 commit+reread，也得到 `App/shared same identity=false`。两组证据合并为同一原子初始化问题，不重复计数。

已有 aid 或串行调用通常不触发。需要共享同一原子初始化边界；额外 reread 无法收回已经返回给另一份配置的 ID。

证据：[SDK identity 日志](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/identity/result.log)、[app/shared identity 日志](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/app-shared-identity/result.log)。

## 不作为确定缺陷重复计数的边界

flush ACK 当前仅比较 action 字符串，不区分两个相同 action 的轮次。已能复现“旧 ACCEPT_ALL ACK 清掉新 ACCEPT_ALL pending”的本地行为；但当前接口只同步 action/元数据，没有 cycle/decision ID。若允许相同最终状态合并，旧成功 ACK 可以是等价确认。因此本轮不据此断言后端必然丢失可区分事件，也不把它算成第五项 P2；逐轮审计语义若另有明确要求，则需要记录身份。

MAYBE_LATER 仍是直接发送、失败不持久化的既有路径。本轮未以未明确的“所有动作必须完整可靠补报”要求新增一项缺陷。

## 本轮验证结果

| 现有套件 | 执行 | 失败/错误/跳过 |
|---|---:|---:|
| ad-sdk-tcl | 71 | 0/0/0 |
| ad-sdk-tcl:google-ima | 66 | 0/0/0 |
| ad-sdk-google-ima | 32 | 0/0/0 |
| app HQ008 TCL | 240 | 0/0/0 |
| app Google IMA / ambientlight | 225 | 0/0/0 |
| app Google independent / ambientlight | 199 | 0/0/0 |
| **合计** | **833** | **0/0/0** |

两个相关 Gradle 命令均 BUILD SUCCESSFUL。833 是模块级单测执行数，存在共享用例重复，并非 833 个独立场景。

新增定向验证另计：SDK timeout/cancel 探针 21 项断言通过、2 项失败（均属问题 1）；IMA 所有权 12 项正向断言全部通过。CMP、R8 和 identity 使用上文所述独立探针。本轮没有新的设备播放、真实 CMP UI 或生产后台请求。

## 可重跑入口

证据目录：[build/review-20261107-round3](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round3)，含源码 SHA-256、测试 XML 快照、构建日志、探针源码和 provenance。

```bash
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/zy/Documents/Codex/ad-app

# 当前返回非零：两条正向取消断言失败，日志明确标注。
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-core/run_timeout.py --default
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-core/run_ima.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/app-flow/run.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/run_webkit.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/run_identity.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round3/sdk-runtime/run_app_shared_identity.py
```
