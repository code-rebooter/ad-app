# 静默 UMP 与 A/B 问题修复、124 回归

完成时间：2026-11-07 17:49（Asia/Shanghai）。工程 `/Users/zy/Documents/Codex/ad-app`；设备仅 `192.168.0.124:5555`。

本轮代码、脚本、完整原始证据及构建产物：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/`。最终验收 `DELIVERY.json` 为 PASS，`failed_checks=[]`；环境恢复 10 项 PASS。

## 1. 本轮修改了什么

相对于本轮开始时的 `source-before.json`，只有 **8 个生产文件**有新增/修改；另有 3 个定向测试文件。未重置、提交或清理用户已有改动。

| 对应项 | 改动 | 实测/验证 |
|---|---|---|
| A1 SDK 版本字段 | app 的 `Hq008AdReporter` 根据正在执行的 SDK 标识填写 `diagnostic_info.sdkVersion`，不按 Google flavor 一刀切 | 纯 IMA 五类事件的实际 HTTP 同时为 `sdk=google_ima_3.35.1`、`sdkVersion=google_ima_3.35.1`；TCL 与独立 Google URL→TCL 保持 `2.8.02` |
| A2 TCL CMP 待补快照 | 保存首次完整 consent-report 请求体，失败/重启后重放原体；legacy action-only 记录首次重试时升级；保留 generation 条件 ACK | 151 条确定性断言；14 个真实上报、503、重启、旧 ACK 与恢复捕获通过 |
| B1 app Worker 收尾窗口 | `KEEP` 改为 `APPEND_OR_REPLACE`，确保同键新入队有后继 work；保留条件 ACK | 同键替换单测、新队列条件 ACK、两引擎实际持续 503→重启→补发通过 |
| B4 app / Flow 双进程队列 | 原 SharedPreferences 整文件并发写改为 SQLite 行级事务存储；旧数据一次性导入，导入标记与记录同事务提交；ACK 为单条 `key AND payload` 条件删除 | 修前每引擎每轮写 40 条只留 20 条，三轮复现；修后三轮各引擎均 40/40；迁移/ACK 8 个真实 Android 断言通过；四组实际失败重启补报全部通过 |
| TC REJECT 校正 | SDK 反射轮询不再因“同 campaign”或“新元数据配旧 TC”立即接受旧结果；沿用原有轮询、原生生成和 fallback | 22 条确定性断言；真实 ACCEPT→REJECT 后 manager/default prefs/SDK processor/SDK 文件四份 TC 完全一致且 purpose-consent 位为 0；换 PID 正常 init 后仍一致 |

生产文件完整路径：

- `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt`
- `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt`
- `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt`
- `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008SdkRequestReports.kt`
- `/Users/zy/Documents/Codex/ad-app/app/src/main/java/com/smart/android/ad_app/Hq008ReportQueueStore.java`
- `/Users/zy/Documents/Codex/ad-app/hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/internal/DurableReports.java`
- `/Users/zy/Documents/Codex/ad-app/hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/internal/ReportWorker.java`
- `/Users/zy/Documents/Codex/ad-app/hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/internal/ReportQueueStore.java`

新队列数据库是各包私有的 `hq008_sdk_request_reports.db` / `hq008_flow_sdk_reports.db`；原请求 key、payload、事件种类和网络上报协议不变。旧 XML 不再作为新队列的权威存储。已保存的旧 payload 按原体补发，不回写历史字段。

### A1 最终发生位置

本轮实际请求：`POST /api/v2/ad/report`，JSON-string 字段 `diagnostic_info` 内。

- 纯 IMA：flavor `google_ad_tv_desktop_ambientlightDebug`、`googleAdRuntime=ima`；测试包 `com.smart.device124.batchgoogle20261107`；实际请求渠道 **BACKPORT_APP**；request_id `batch-d124-app-google-single-final1`。
- TCL：测试包 `com.smart.device124.batchtcl20261107`；request_id `batch-d124-app-tcl-single-final1`。
- 独立 Google URL→TCL：测试包 `com.smart.device124.batchindependent20261107`；request_id `batch-app-independent-single-final1`。它实际执行 TCL，版本仍为 `2.8.02`。

原始 HTTP 与逐事件校验在对应 `playback-verification/<run>/version-wire.json`、`fixture/<run>/live-server-events.jsonl`。没有修改 Google 广告 URL 的版本参数，也没有改 Flow 的另一套 SDK 配置字段。

### A2 没有扩展同意语义

原 consent-report 首次请求本来不带 TC 字符串；本轮没有凭空添加 TC 字段。保存的是原有 action、channel、MAC、Android ID、app/SDK 版本等完整请求参数，而非修改 consent 决策或生成内容。

### TC 修复边界

修复的是执行新动作时读取陈旧异步结果的窗口。没有扫描、清空或强制重算历史 TC；没有改 REJECT 后继续广告，也没有替换原厂 TC / LI / custom purpose 生成规则。

## 2. 明确保留的定制逻辑

`custom-contract-audit.json` 的逐项比对全部通过：

- **Google UMP 生产源码完全未改**：静默宿主、表单、privacy options、自动动作与远端选择逻辑原样保留，不新增可见弹窗。
- TC 原生成器 `generateSilentTcString`、`buildSilentConsentJson`、原持久化 `persistSilentConsentState`、原 seed 应用及 `executeSilentDecision` 内容与本轮开始前一致。
- TCL REJECT 后继续广告不变。
- 原生 SDK、音量实现、真假音量上报映射、模拟点击、渠道门禁均未改。
- A3 原生 tracking HTTP / BI 补齐保持；**单个 AdsEvent 返回边界不改**，不人工补发一组外部枚举回调。
- 不同包名的私有 RDID / 文件锁不共享是正常作用域；没有改成公共全局存储。

## 3. 最新实际通过的验证

| 套件 | 本轮结果 |
|---|---|
| 八项重新执行的 JVM/单测任务 | **886 tests / 0 failures / 0 errors / 0 skipped**；归档 XML 独立重算 |
| 最终 app / SDK / Flow 主矩阵 | **41 个不同场景最新捕获全部 PASS**，含 TCL / Google 双引擎、独立 Google URL→TCL、wrapper、空填充、媒体错误、取消、正常播放 |
| 真实 210 秒素材 | app / SDK / Flow × TCL / Google，**6 次完成、实际进度、tracking、终态及资源关闭通过** |
| 非 debuggable R8 消费包 | SDK / Flow × TCL / Google，**4/4 PASS**；设备原始 ledger 经 SHA 绑定 HTTP export 取得，不把 run-as 失败当作通过 |
| 完成上报故障闭环 | app / Flow × TCL / Google **4 组 / 8 次播放 PASS**：持续 503、实际 SQL 中完整原体、真实换 PID、原体补发 200、对应 key 清除、健康新请求完成 |
| 双进程队列 | 3 轮，每引擎每轮 40 条，全部保留；旧 XML 导入与条件 ACK 实机通过 |
| TCL CMP | 14 个真实 HTTP/补报/旧 ACK 样本；真实 ACCEPT→REJECT→重启四状态及位级校验通过 |
| 静默 UMP | 真正 UMP SDK 的请求解析、WebView 加载、静默自动 ACCEPT / REJECT、privacy options 改选、CHECK / DEFER、无表单、503、加载超时、自动动作超时、合并回调、宿主实际销毁与迟到回调隔离闭环；控制的是外部 HTTP HTML，未注入 ConsentInformation 结果 |
| 真实 Google UMP 服务 | 正常系统代理下实际返回 **NOT_REQUIRED**、canRequestAds=true、无错误，回调及宿主清理通过 |
| B3 原厂 IMA 最终 GAM RDID | 独立未打补丁的官方 IMA 3.35.1 / GMS 18.0.1，两个实际 PID 的 AdsRequest 经真实 JS 产生最终 GAM HTTP URL；**两个非空 RDID 相同**。原始 CDP 网络事件保留，无 ID 注入 |
| 环境恢复 | 测试包停止、代理、信号、音量/静音、原前台组件、原 reverse、临时 reverse/forward 及本轮服务清理，**10/10 PASS**；没有卸载包或清数据 |

B3 的新证据是最终真实 GAM 请求，不再仅引用先前的公共 GMS getter。它证明本次 124 同包换 PID 的原厂结果一致；没有把广告 ID 重置、账号/系统配置变化等未测条件写成结论。

## 4. 保留的历史失败与本轮范围

原始 FAIL / 中断记录未覆盖或改成 PASS：

1. 执行期间记录到一次设备重启/ADB offline，重启原因属性为 `reboot,adb`；本轮没有发出 reboot 命令。恢复本轮 reverse 后，用新 run_id 重跑受影响场景。
2. SDK TCL 长播的一次请求真实发生 VAST HTTP timeout，正常返回错误并清理；健康网络下新的 210 秒捕获最终完成。没有为此改变业务 timeout。
3. 非 debuggable 宿主最初只等待 8 秒，早于原有 12 秒原始 ledger export；新捕获保留 17 秒窗口后四项严格通过。没有伪造终态或放宽资源断言。
4. CMP 旧校验器仍固定要求 r7 宿主版本，初版仅该项失败；新 v2 校验精确绑定本轮编译版本，其余断言未放宽，原 FAIL 留存。
5. 独立 Google URL→TCL 用原有独立能力合同验收，不套用纯 IMA 的 OMID / runtime-audit 能力，也未为通过测试修改集成逻辑。

本轮有限清单为上表 41 场景及专项套件；上轮 120 主矩阵、所有 guard / 音量 / 精确 flavor gate 并未逐行全部重跑，仍按各自冻结版本保留。真实 Google EEA/各地区线上表单 HTML 未在本轮验证；静默表单机制已通过真正 SDK 的受控 HTTP 夹具验证，真实服务本次为 NOT_REQUIRED。

## 5. 交付入口

- 实现与运行命令：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/README.md`
- 最终机器可读验收：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/DELIVERY.json`
- 精确源码改动/冻结：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/source-final.json`
- 最终场景逐项结果：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/playback-verification/summary.json`
- 新 Flow release AAR：`/Users/zy/Documents/Codex/ad-app/build/batch-custom-preserving-20261107/binaries/hq008-flow-sdk.aar`

41 项引用各 case 的最新已完成捕获；所有更早尝试单独保留，详见 `historical_failed_attempts`。本轮生产修改均已落在工程源文件中；临时宿主、响应夹具、CDP 和设备脚本仅在 build 测试目录。
