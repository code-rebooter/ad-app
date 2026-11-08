# 2026-11-07 app / ad-sdk-tcl 第二轮修复复审

> 后续状态：用户再次修改后的最新结论见[第三轮修复复审](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-app-tcl-round3-review.md)。本页保留第二轮证据，不再代表全部缺陷的当前状态。

## 结论

当前确认 **8 项尚未关闭的问题，均为 P2：6 项运行时/边界问题，2 项测试与验证工具问题**。没有沿用旧源码或旧 APK 代替本轮验证。本轮未修改生产代码或既有测试。

已确认有效的修复：

- SDK IMA 跨 manager 重入误取消：12 项正向断言全部通过，含同 manager、第三 manager、观察者回调重入和旧会话迟到事件。
- app Google independent：播放开始后会撤销请求启动 timer；30 秒启动预算不再截断第 5 秒开始的 60 秒广告，未开始的请求仍正常超时。底层 TclExecution 自有播放 watchdog，不因 manager 未另加 timer 而报缺陷。
- HQ008 Debug 主源码编译恢复；但其单测仍编译失败，见第 7 项。
- Google IMA / combined 两份 README 恢复，全新目录打包确认存在。
- TCL README 的默认 patch 说明已改为关闭，与实现一致。
- 两套不适用于当前实现的 GoogleImaVolumeAuditTest 已移除，原 18 次字段读取 NPE 不再出现。这是测试基线冲突消失，不是重新证明真实音量链路。

## 尚未关闭的发现

### 1. [P2] GoogleGamClient 仅修了响应头前超时，正文总超时仍漏回调

位置：[GoogleGamClient.java:85–86](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleGamClient.java#L85)。

第 56–61 行 onFailure 的新判断已生效：响应头前 callTimeout 现在能够回调 error:301。但响应头返回后，body.string() 触发总超时会进入另一处 catch，那里仍以 `!call.isCanceled()` 过滤 onError。

触发条件：服务器迅速返回响应头，正文缓慢返回，整次调用超过 callTimeout，宿主没有主动取消。真实默认构造器用“正文每 3 秒返回一个字节”避开默认 10 秒 readTimeout，本轮结果：

```text
configuredCallTimeoutMs=20000
elapsedMs=20242
transportIsCanceledAtEnd=true
sdkCallback=NONE
runningCalls=0
```

影响：底层请求已结束，resolve 宿主却没有成功或错误终态。此处仍仅指 `com.smart.android.tclad.GoogleGamClient`，不是 GoogleGamPatchClient / TclBackendPatch，也不泛指 app patch 主路径。

修复应统一 onFailure 与正文 catch 的取消/错误分派，而不是只改 onFailure。

### 2. [P2] timeout 类型豁免会放行显式取消后的晚错误回调

位置：[GoogleGamClient.java:58–61](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleGamClient.java#L58)，取消入口为 92 行。

当前通过异常类型放行 timeout，但没有独立记录宿主是否主动 cancel。网络已产生 timeout、SDK onFailure 尚未交付时，宿主取消旧请求；随后旧 onFailure 仍因为 timeout 豁免调用 onError。

本轮通过 OkHttp EventListener.callFailed 的 latch 固定合法交错，确认宿主 cancel 已返回才放行 SDK 回调：

```text
case=explicit-cancel-before-sdk-failure-delivery
transportFailure=java.io.InterruptedIOException:timeout
sdkCallback=error:301
FAIL explicit host cancellation before onFailure suppresses late timeout callback
```

影响：已取消/替换的旧 resolve 仍发失败，破坏既有“显式取消抑制后续业务回调”语义。普通显式取消对照仍正常，不将此描述为所有取消均失效。

应以独立宿主取消标志区分两种取消来源，不能仅依赖 Call.isCanceled 或异常类型。

### 3. [P2] Google CMP 已保存待补报告，但正式入口仍不消费

位置：[Hq008CmpManager.kt:85–94](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt#L85)；唯一 flush 调用在 180 行的带 decision 重载，init 仍为空。

成功 ACCEPT_ALL/SAVE_SETTINGS 后报告失败，确实保存了新的 pending。但下一轮正式入口 CHECK_ONLY 允许请求广告时，会直接命中 accepted-action 提前返回，不进入带 decision 重载，因此没有补报机会。

当前完整 production manager 实编译结果：

```text
reports=1, popup=1, pending=ACCEPT_ALL
```

用持久状态重新创建 Context，再调用 init/正式入口也未补报。显式调用带 decision 重载能够补报，说明保存和发送函数本身能工作，漏的是正式流程挂接。该恢复夹具不是 Android 真机进程重启测试。

需要在正式入口的跳过/阻断分支之前可靠消费待补报告；不能只在拿到新 decision 后执行。

### 4. [P2] 旧报告 ACK 无条件清槽，会删除新一轮待补报告

位置：[Hq008CmpManager.kt:440–442](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt#L440)；265–267 行的本轮 ACK 也无记录身份校验。

第 180 行异步 flush 后立即继续处理新动作，未等待旧报告结束。正式流程可触发：

```text
上轮 REJECT 报告失败，pending=REJECT
下一轮补发 REJECT，同时执行新的 ACCEPT_ALL
新报告保存 pending=ACCEPT_ALL
旧 REJECT 的成功 ACK 无条件清空 pending
新 ACCEPT_ALL 报告失败，但 pending 已为空
```

本轮手动控制真实 production manager 的网络回调顺序，请求为 `[REJECT, REJECT(retry), ACCEPT_ALL]`，最后 `pending=null`；下一轮也不再补报。

ACK 应绑定本次发送的报告 ID/代次，只确认对应记录。仅比较 action 也不能区分连续两次相同 action。该缺陷不涉及 TCString：当前 consent-report 协议仍不发送它。

### 5. [P2] SDK WebKit R8 规则只修到 metadata，feature 入口仍被删除

- 规则：[consumer-rules.pro:9–12](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/consumer-rules.pro#L9)
- 反射：[GoogleImaFirmwareBridge.kt:353–365](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaFirmwareBridge.kt#L353)，document-start 入口在 340–349 行。

新增 keep 的三类已经生效。但 WebViewFeature 的 USER_AGENT_METADATA / DOCUMENT_START_SCRIPT 动态字段，以及 WebViewCompat.addDocumentStartJavaScript 仍未受保护。

本轮使用当前真实 helper、当前 consumer rules、实际 WebKit 1.9.0 和 R8 8.10.9 完成最小 classfile 压缩闭环：

```text
压缩前：两个 feature 字段均存在
压缩后：featureField.USER_AGENT_METADATA=MISSING
压缩后：featureField.DOCUMENT_START_SCRIPT=MISSING
```

usage 也确认 addDocumentStartJavaScript 被删除；mapping 确认新增 keep 的 metadata 三类保留，排除“未使用新规则”的解释。生产 helper 在动态字段不存在时 catch-return-false，相关兼容能力被静默跳过。

这是当前反射 ABI 的最小 R8 验证，不是历史客户 APK，也不声称完整新 Release APK 或真机已经验证。

### 6. [P2，首次并发边界] 双引擎 RDID 初始化仍使用不同锁

- [ExecutionConfig.java:69–76](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java#L69)
- [GoogleImaExecutionConfig.java:71–78](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java#L71)

两份当前源码仍分别锁不同 Class，读写同一个 google_tcl_ad/aid。首次没有 aid 时，两个线程预建两份 execution 可同时读空、分别生成 UUID，使一份本轮配置的 RDID 与最终持久值不同。

本轮重新编译两份生产 Java 源码，固定合法线程交错再次得到 `Same persistent identity=false`。已有 aid 或串行初始化通常不触发。需要共用同一初始化临界区。

### 7. [P2，测试阻断] TCL 单测旧位置参数仍未同步，无法编译

位置：[TclBackendPatchTest.kt:43](/Users/zy/Documents/Codex/ad-app/app/src/testTclRuntime/java/com/smart/android/ad_app/TclBackendPatchTest.kt#L43)，另有 57、64、77 行。

Debug Activity 已改为命名参数，主源码编译恢复；但这四处测试仍把 lambda 传给新增 Boolean 参数。实际 `:app:compileHq008DebugUnitTestKotlin` 编译失败：

```text
Argument type mismatch: actual type is 'Function0<Unit>', but 'Boolean' was expected.
No value passed for parameter 'onReady'.
```

这是 app TCL 单测未执行的原因。修复调用时还必须明确开启这些 patch 专项测试的 enabled，而非仅补齐名称、继续用 false 默认值。

### 8. [P2，验证工具] Debug patch 夹具改为命名参数后，实际默认禁用了 patch

位置：[Hq008DeviceValidationActivity.kt:276–278](/Users/zy/Documents/Codex/ad-app/app/src/hq008XHSXDebug/java/com/smart/android/ad_app/Hq008DeviceValidationActivity.kt#L276)、363–371 行。

这两处现在能编译，但没有传 enabled=true。TclBackendPatch 默认 false，因此直接返回源 URL，不调用夹具的 patch client，也不触发 onReady。pending 生命周期夹具不再模拟等待中的 patch，patch_error/guarantee/patch 次数等验证也失去原先前提。

本轮实编译当前 production TclBackendPatch，以相同命名参数调用，对照结果：

```text
current named-argument fixture: result=https://fixture/source patchRequests=0 onReady=0
explicit enabled control: result=https://fixture/patched patchRequests=1 onReady=1
```

客户端为确定性桩，证明是否调用 client，不是设备实测。原 Activity 第 411 行仍要求选中 root 的 patch 次数为 1/2，因此该夹具的当前行为与自身断言直接冲突。这是 Debug 验证工具问题，不泛化为生产默认应启用 patch。

## 本轮构建与回归

| 套件 | 已执行 | 失败/错误/跳过 |
|---|---:|---:|
| ad-sdk-tcl | 71 | 0/0/0 |
| ad-sdk-tcl:google-ima | 66 | 0/0/0 |
| ad-sdk-google-ima | 32 | 0/0/0 |
| app Google IMA / ambientlight | 225 | 0/0/0 |
| app Google independent / ambientlight | 199 | 0/0/0 |
| **合计** | **593** | **0/0/0** |

593 是模块级测试执行数，存在共享用例重复。**整体回归不能称为全通过**：HQ008 TCL 单测被第 7 项编译错误挡住，未运行。SDK core 新增的超时正向探针另外有 3 个失败断言，分别是短正文总超时、默认 20 秒正文总超时、显式取消后的晚 timeout 回调；前两者是第 1 项同一问题的两个场景。IMA 12 项正向生命周期断言全部通过。

原厂隔离清单、五份受控 Google runtime 校验、git diff --check -- app ad-sdk-tcl 均通过。本轮没有运行新的真机播放/真实 CMP UI/生产后台请求。

## 复现与证据

证据根目录：[build/review-20261107-round2](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round2)。包含本轮源码 SHA-256、测试 XML 快照、构建日志和各项可执行探针。

```bash
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/zy/Documents/Codex/ad-app

# 正确性断言：当前会返回非零，日志明确记录剩余的3条失败
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/sdk-core/run_timeout.py --default

python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/sdk-core/run_ima.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/app-flow/run.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/sdk-runtime/run_webkit.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/sdk-runtime/run_identity.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round2/fixture-patch/run.py
```

MAYBE_LATER 当前仍是直接发送、失败不持久化的旧路径。此处仅记录其覆盖范围，不将“所有动作均必须可靠补报”的未明确要求额外算成第九项缺陷。
