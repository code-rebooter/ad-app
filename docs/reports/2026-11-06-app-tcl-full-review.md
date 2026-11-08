# 2026-11-06 app / ad-sdk-tcl 全链路代码审查

> 2026-11-07 补充复核：网络问题限定为 GoogleGamClient 的 callTimeout（不是 GoogleGamPatchClient/TclBackendPatch，也不是所有网络超时）；CMP 丢报限定 Google UMP sourceSet，原厂 HQ008 有补报且当前协议不发送 TCString。两项及 IMA 同步重入的当前源码复现见[三项争议复核](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-review-disputes-verification.md)。

## 结论

按当前工作树确认 **10 项问题：1 项 P1、8 项 P2、1 项 P3**。本轮仅审查和验证，未修改生产代码、配置或既有测试，也未提交代码。

覆盖 app 的启动/Provider/调度/授权/CMP/悬浮窗口、TCL/Google independent/纯 IMA 三条播放路径及 Haier 接入；覆盖 ad-sdk-tcl facade、执行生命周期、HTTP/patch、逐段预算、Google IMA 迁入模块、combined 入口、依赖隔离和客户打包。历史七项修复未重复算作新问题。

## 发现

### 1. [P1] TclBackendPatch 参数变更未同步调用点，HQ008 Debug 已不能编译

- 声明：[TclBackendPatch.kt:14](/Users/zy/Documents/Codex/ad-app/app/src/tclRuntime/java/com/smart/android/ad_app/TclBackendPatch.kt#L14)
- 调用：[Hq008DeviceValidationActivity.kt:276–278](/Users/zy/Documents/Codex/ad-app/app/src/hq008XHSXDebug/java/com/smart/android/ad_app/Hq008DeviceValidationActivity.kt#L276)、同文件 363–370 行。
- 测试调用：[TclBackendPatchTest.kt:43](/Users/zy/Documents/Codex/ad-app/app/src/testTclRuntime/java/com/smart/android/ad_app/TclBackendPatchTest.kt#L43)，另有 57、64、77 行。

构造器在 client 后插入 `enabled: Boolean = false`，旧位置参数仍把 lambda 传给它。已观测 `:app:compileHq008DebugKotlin` 失败；同一 Activity 属于 hq008XHSXDebug，且通过 sourceSet 复用到 hq008Debug。改用另一 TCL flavor 运行测试后，`compileTcl_aishangDebugUnitTestKotlin` 又在四个旧调用点失败。因此不是某一单测断言不通过，而是 Debug 构建及 TCL 回归入口被阻断。

修复应同步所有调用并明确传入开关，优先使用命名参数；这些 patch 专项夹具需要启用 patch，不能只补一个默认 false 使其勉强编译。

### 2. [P2] SDK IMA 跨 manager 替换时，旧调用栈会取消重入的新会话

位置：[GoogleImaAdManager.kt:81–83](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaAdManager.kt#L81)。

触发顺序：A 活跃 → B.showAd 替换 A → A.onAdCancelled 同步调用 B.showAd → 内层创建新 B 会话 → 外层返回后立即执行自身 destroyAd → 新 B 被取消 → 此后才检查 generation。较新请求虽然已经发出 REQUESTED，却没有留下活动会话。

已观测真实编译后 production manager 的 JVM 夹具输出：

```text
events=[a:cancelled, b:AD_REQUESTED, b:cancelled]
b.current=null
```

需在跨 owner.destroyAd 返回后、调用自身 destroyAd 前复核 generation，不能只在两个 destroy 之后检查。此项是 SDK 多 manager 边界，不套用于 app 单例 manager。

### 3. [P2] GoogleGamClient 把网络总超时当作主动取消，漏掉所有终态回调

位置：[GoogleGamClient.java:56](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleGamClient.java#L56)，80、83 行也使用相同判断。

默认 client 的 callTimeout 为 20 秒。OkHttp 总超时内部也会使 Call.isCanceled() 为 true；当前 onFailure 以此抑制 onError，因而服务无响应或正文读到总超时时，底层请求结束，宿主却收不到 onResolved/onError。依靠 resolve 回调推进的接入会一直等待，除非宿主另有 watchdog。

已用当前生产 Java 源码、MockWebServer NO_RESPONSE 和 500ms callTimeout 复现：

```text
requestReceived=true
callbackAfterCallTimeout=NONE
runningCalls=0
```

应将显式宿主取消与网络超时分开判断，保持超时的错误回调。

### 4. [P2] app 默认 Google independent 的固定回调计时会截断正常播放

位置：[GoogleAdTvDesktopAdManager.kt:80–81](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt#L80)，关联 nativeStarted 的 233–245 行。

showAd 从入口启动固定 timeoutMs 倒计时。素材开始后既不撤销该任务，也不切换到按实际素材时长计算的播放预算；onAdStartPlay(seconds) 的时长也没有用于预算。后台允许 callback timeout=30 秒，因此 5 秒加载后开始的 60 秒素材仍会在请求第 30 秒 fail(host_callback_timeout)，释放控制器并报告错误。默认 180 秒时，正常 210 秒素材也会受到影响。

已实编译当前 production manager、调用真实 showAd/nativeStarted 并执行确定性队列：

```text
after start at 5s, duration=60s; remaining timers=[30000]
at 30s while 60s ad is playing: hostError=true
```

应与 app TCL/IMA 路径一致，区分请求启动预算和播放预算。HaierLsapAdManager 也有固定会话计时的同类源码，但未另做执行复现，不重复计一项。

### 5. [P2] Google UMP 提前清除待处理状态，consent-report 失败后不再补发

位置：[Hq008CmpManager.kt:254–260](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt#L254)，关联 84–93 行。

UMP 本地操作成功后，代码先保存 last_applied_remote_action、清除 pending_remote_action，再请求 consent-report；报告回调忽略错误。若 ACCEPT_ALL/SAVE_SETTINGS 执行成功、但上报遇到短时断网，下轮 CHECK_ONLY 可请求广告时会因 accepted action 跳过远端流程，既不重报也没有持久待报记录。重启不能恢复已删除的状态。

已用当前生产 gate 实编译，令 report 失败后再次运行 gate：

```text
first pass: popupCalls=1 reports=1
retry pass: popupCalls=1 reports=1
```

需分别保存“本地操作已应用”和“结果待上报”状态；上报成功才确认后者，失败可重试，无需重复操作 UMP。

### 6. [P2] SDK Release 缺少 WebKit 反射 ABI 保护，相关能力静默失效

- 反射：[GoogleImaFirmwareBridge.kt:353–365](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaFirmwareBridge.kt#L353)，其他 metadata 反射在 306–349 行。
- 规则：[consumer-rules.pro](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/consumer-rules.pro)。

动态 getField 读取 DOCUMENT_START_SCRIPT / USER_AGENT_METADATA，再按名称反射调用 WebKit 方法，但 SDK consumer rules 未保护这些 AndroidX 成员。app 自身的 proguard-rules.pro:278–282 有对应保留，独立 SDK 没有。

已观测现有客户 example 的真实 R8 usage/mapping：两个 feature 字段、metadata getter/setter 被移除或改名。当前编译版本与该客户输入 AAR 的 AndroidXWebKitIdentityCompat 方法 `javap -c -p` 输出完全一致；完整 class 的字节哈希不同，不将它们称为同一二进制。对应证据已复制到本轮目录。

据此推断：同规则下 SDK Release 的反射会失败，getField 的 NoSuchFieldException 被吞掉后返回 false，跳过 metadata 和 document-start 能力。此项没有新跑一次 Release 设备验证；R8 构建成功、Debug 正常均不能证明反射能力存在。修复需要将反射 ABI 纳入 SDK 自身的消费者规则或使用可被静态分析的调用。

### 7. [P2，首次初始化并发边界] 双引擎可生成不同的持久 RDID

- [ExecutionConfig.java:69–76](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java#L69)
- [GoogleImaExecutionConfig.java:71–78](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java#L71)

二者初始化同一个 google_tcl_ad/aid，却锁不同 Class。首次安装、两个线程并行预构建 execution 时，可同时读空并分别生成 UUID；最终一份本轮配置的 RDID 与持久值不同。不要求两套引擎同时播放，也不影响已存在 aid 的通常路径。

已用两份当前生产 Java 源码及合法线程交错的 barrier 夹具复现 `Same persistent identity=false`。应共用同一初始化器/锁。

### 8. [P2，回归基线] 两套 IMA 音量审计测试与当前实现不一致，稳定产生 18 次失败

- [app GoogleImaVolumeAuditTest.kt:15](/Users/zy/Documents/Codex/ad-app/app/src/testGoogleImaRuntime/java/com/smart/android/ad_app/GoogleImaVolumeAuditTest.kt#L15)
- [SDK GoogleImaVolumeAuditTest.kt:15](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/test/java/com/smart/android/tclad/ima/GoogleImaVolumeAuditTest.kt#L15)

每套 9 条测试均要求 buildSummary 输出 volume_chain_audit，但当前两份 reporter 均无该字段，测试全部在读取字段处 NPE，尚未执行到各自断言。已观测 SDK IMA 75 条中 9 失败、app IMA 234 条中 9 失败。

工作区已有音量策略撤回/恢复记录。因此这里确认的是测试与当前源码基线未同步，不据此认定原有独立音量策略是业务 Bug，也不应为使测试变绿而擅自恢复已撤回的策略变更。

### 9. [P2，客户交付] 新目录打包成功，但缺少两份核心接入文档

位置：[package_customer_sdk.py:117–126](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/tools/package_customer_sdk.py#L117)，生成 README 的 160–161 行引用缺失文档。

当前工作树没有 ad-sdk-tcl/google-ima/README.md、ad-sdk-tcl/combined/README.md。脚本遇到缺文件直接 continue，仍成功产出 ZIP，并要求客户先阅读不存在的 Google-IMA.md / Combined-integration.md。旧输出目录的残留文档会掩盖问题。

已用全新临时输出目录、冻结现有仓库实际打包，退出码 0；检查 ZIP 确认两份文档均不存在。需补齐必需文档并在打包前校验缺失，不应依赖上一次输出目录。

### 10. [P3，接入说明] TCL patch 默认值说明与实现相反

- 说明：[README.md:109](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/README.md#L109)
- 实现：[ExecutionConfig.java:49](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java#L49)

文档写设置 execution 后默认启用 patch，实际 backendPatchEnabled=false；示例也未开启。照抄文档不会发 patch。应按产品当前约定同步说明和示例，不直接假定必须改回默认值。

## 本轮实际验证

| 套件 | 执行 | 通过 | 失败 |
|---|---:|---:|---:|
| ad-sdk-tcl | 71 | 71 | 0 |
| ad-sdk-tcl:google-ima（含共享 core 测试） | 75 | 66 | 9 |
| ad-sdk-google-ima | 32 | 32 | 0 |
| app Google independent / ambientlight | 199 | 199 | 0 |
| app Google IMA / ambientlight | 234 | 225 | 9 |
| **合计** | **611** | **593** | **18** |

以上为模块测试执行数，存在共享用例重复，不称为 611 个独立场景。app HQ008 Debug 构建及 TCL aishang 单测编译失败，未将它们表述为已通过测试。

另执行现有真实编译类生命周期夹具：SDK 12/12、app 排队 5/5 通过。这些历史覆盖仍通过，但未覆盖本轮发现的跨 manager 重入组合。新增 5 份定向探针分别确认 resolve 总超时、跨 manager 重入、independent 固定计时、CMP 上报丢失和 RDID 竞争。

原厂隔离校验及受控 Google runtime 同步校验通过；git diff --check -- app ad-sdk-tcl 通过。使用本机 JBR 21；首次 JBR 25 的 Groovy classfile 69 失败属于执行环境不兼容，不算产品发现。

## 可重跑入口与证据

证据目录：[/Users/zy/Documents/Codex/ad-app/build/review-20261106](/Users/zy/Documents/Codex/ad-app/build/review-20261106)。包含源码 SHA-256 快照、测试 XML 快照、构建日志、R8 证据、隔离校验、ZIP 内容列表和定向复现工具。

```sh
cd /Users/zy/Documents/Codex/ad-app
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

python3 /Users/zy/Documents/Codex/ad-app/build/review-20261106/sdk-core-repros/run.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261106/app-flow-repros/repro_timeout.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261106/app-flow-repros/repro_cmp.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261106/identity-repro/run.py

./gradlew --offline --continue -PgoogleAdRuntime=ima \
  :ad-sdk-tcl:testDebugUnitTest :ad-sdk-tcl:google-ima:testDebugUnitTest \
  :ad-sdk-google-ima:testDebugUnitTest :app:testHq008DebugUnitTest \
  :app:testGoogle_ad_tv_desktop_ambientlightDebugUnitTest

./gradlew --offline --continue \
  :app:testTcl_aishangDebugUnitTest \
  :app:testGoogle_ad_tv_desktop_ambientlightDebugUnitTest
```

## 覆盖边界

本轮没有连接设备、发生产竞价请求、操作真实 CMP UI、重新执行真实视频或 OMID 测量矩阵。JVM 夹具调用生产源码/编译类，但 Android、网络等边界按每份 README 所述使用桩或本地服务；不把这些结果代称真机播放通过。原厂二进制采用已有隔离清单校验，并非重新逆向每个原厂类。

未确认其他新增 TCL runtime 终态、下载响应体取消或逐段预算缺陷，不等于证明所有线上和设备组合无问题。建议修复顺序为构建阻断 → 回调/重入与正常播放被截断 → 上报重试和 Release 差异 → 并发身份与交付文档/测试基线。
