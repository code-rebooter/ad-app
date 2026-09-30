客户 ADHQ1001 APK 广告逻辑核查（2026-09-14）

可直接转发的简短说明：

> 已核查客户 2.0.10 安装包：广告在屏保场景触发，经我方流控、授权后，由客户轮流请求 TCL 和 Whale 两路广告。授权次数包含两路尝试，不能直接等同于 TCL 展示量。TCL 会回传加载、开始、完成；Whale 没有回传加载和开始，结束回调却按失败上报，因此按全量广告对账时，会出现展示/完成数偏少、失败数偏多。另外，安装包内置 Flow SDK 2.1.2，仍存在日统计请求 ID 与原广告请求不一致的问题。以上逻辑已从 APK 确认，近期差额的具体占比还需结合后台明细核对。

核查对象与范围

- 文件：`Adhq1001HISIA9_V2.0.10_20260807.apk`，19,601,913 字节。
- 包名：`com.google.android.adhq1001`；应用版本：`2.0.10` / versionCode `10`；渠道：`ADHQ1001`。
- APK 内置 Flow SDK：`2.1.2` / versionCode `12`，构建时间 `20260806_1731`。本地 SDK 源码当前为 `2.1.3`，不能用当前源码代替客户安装包作结论。
- SHA-256：`23b22825a03fdca1e912c87a1ce23c4cb48534f94b41284a77e76a2bca902801`。
- 方法：JADX 静态反编译，并用 apktool 输出的 smali 字节码交叉核对关键分支。JADX 全包有第三方依赖方法反编译错误；本报告涉及的客户屏保、初始化和 Flow SDK 文件未出现方法反编译失败标记，关键结论另经 smali 验证。未进行真机播放或访问线上接口；尚无后台对账明细。

实际处理流程

1. Application 只执行 `initialize(this, "ADHQ1001")`。屏保窗口挂载时调用 `start()`，屏保开始时注册授权回调并再次 `start()`；SDK 对重复启动有保护。屏保停止时将当前 session 记为 `SCREENSAVER_STOPPED`，然后停止流程、清除回调并释放广告。
2. 首次无历史记录时默认延迟 30 秒；有历史记录时会考虑上次触发和保存的间隔。每轮先请求 `flow-control`，通过后请求 `authorize`，授权通过才把 session 交给客户。
3. 客户在授权之后才检查屏保广告总开关、是否正在播放，以及当前广告源开关。关闭时回传 `NO_NEED_TWAD`、`NO_NEED_TAD` 或 `NO_NEED_WAD`；这些轮次有授权，但没有对应广告请求或展示。
4. 新建屏保服务默认先选 TCL，然后在 TCL / Whale 之间逐轮切换。选中广告源被关闭时，也会结束该轮并切换下一轮，没有立即尝试另一广告源。总开关关闭或“正在播放”提前返回时不切换。不能据此认定线上实际占比严格各 50%。
5. 下一轮在本轮结束后等待服务端 `next_request_seconds`；有效范围 10～86400 秒，缺失/无效时回退 1200 秒。未收到终态回调时，授权回调发出后默认 180 秒记 `TIMEOUT`。代码中的 `AD_INTERVAL_DELAY=10000` 未用于广告调度。

两路广告回传对比

| 回调阶段 | TCL | Whale |
| --- | --- | --- |
| 我方授权交付 | SDK 自动报 `AD_PROGRESS / REQUESTED` | 相同，计入同一渠道授权回调数 |
| 广告加载/填充 | `onAdLoaded → session.loaded()` | `onAdFilled` 只打日志，无 `loaded()` |
| 开始播放 | `onAdStartPlay → session.started()` | `onAdStart` 只打日志、隐藏时钟，无 `started()` |
| 结束回调 | `onAdFinished → session.completed()` | `onAdFinish → session.failed("OTHER_AD_FINISH")` |
| 错误 | `failed(错误码, "TCL_AD_ERROR_")`，错误码保留在诊断字段 | 统一 `failed("OTHER_AD_OTHER")`，原错误码和信息只留本地日志 |

因此，`OTHER_AD_FINISH` 不能直接解释为广告播放故障；它来自 Whale 的结束回调，也不能未经核实就等同于广告平台认可的完整播放。如果后台只统计 TCL，应明确授权分母包含 Whale 和业务跳过；如果要统计全量广告，应区分广告源并统一回调口径。

其他已确认的对账风险

- **旧版日统计请求 ID 不一致。** 2.1.2 的 `SDK_DAILY_METRIC.request_id` 是 `metric-` 加原请求 ID，普通事件使用原 ID。后台按原 ID 直接关联时会不匹配；是否因此拒收或漏计，需要检查接口响应和入库规则。本地提交 `65521a3`（2026-08-10）在 2.1.3 中改为直接使用原 ID；客户 APK 尚不含此修正。
- **日统计是累计快照。** 每个终态额外上传一次当天累计值，日期固定北京时间。不能把每条 `authorized_callback_total` 或 `final_status_totals[].total` 相加；应按设备、渠道、北京时间日期及本地计数连续性取最新有效快照。清数据/重装造成重置、跨日和最后一次上报丢失需另行处理。普通 `AD_PROGRESS` 也包含 REQUESTED、LOADED、STARTED 三种阶段，记录条数不是播放次数。
- **可能连续超时。** `loadTad()` 先把 `mIsAdPlaying` 置 true；若 TCL 未初始化，只上报 `TCL_SDK_NOT_INITIALIZED`，未调用恢复播放标志的 `handleAdFinished()`。同一次屏保后续授权会进入“正在播放”分支直接返回、不结束 session；没有其他清理/回调时会继续超时。两个广告容器为空的路径也直接返回、不结束 session。SDK 超时只结束流程，不会清理客户播放器或恢复客户标志；这些路径的线上发生频率尚未验证。
- **设备字段不能直接混用。** 授权接口的 `uuid` 使用由 Android ID 转换出的 UUID，事件/日统计接口的 `uuid` 使用原始 Android ID。跨接口若只按 `uuid` 等值关联，会不匹配；应核实后台关联规则，优先使用同轮 `request_id`。
- **上报失败没有业务补传队列。** `FlowApiClient.report()` 请求最终失败后只记录日志；本地累计成功不代表服务端已收到。OkHttp 自身的连接恢复不能替代持久化补传。是否影响本次差额需查日志。

建议先从异常日期的 `ADHQ1001`、应用 versionCode `10` 明细核对：`OTHER_AD_FINISH`、`OTHER_AD_OTHER`、`NO_NEED_*`、`TCL_SDK_NOT_INITIALIZED`、`TIMEOUT` 的数量及同设备时序；检查 `metric-` 日统计的接收/关联情况，并确认双方统计的是 TCL 还是全部广告、开始播放还是完成播放。升级 Flow SDK 可以带入日统计 ID 修正，但不会自动修正客户的 Whale 回调或播放标志处理。

证据定位（下列 Java 行号均为本次反编译文件行号）

- [客户屏保逻辑](/tmp/adhq1001-20260914-audit/sources/com/google/android/adhq1001/service/screensaver/SystemScreensaverService.java:51)：授权分流 51～94；TCL 171～246；Whale 250～299；生命周期 372～421。
- [客户初始化](/tmp/adhq1001-20260914-audit/sources/com/google/android/adhq1001/MainApplication.java:25)：渠道初始化及两路广告 SDK 初始化。
- [APK SDK 版本](/tmp/adhq1001-20260914-audit/sources/com/smart/android/hq008flow/BuildConfig.java:8)。
- [APK 事件上报](/tmp/adhq1001-20260914-audit/sources/com/smart/android/hq008flow/internal/AdEventReporter.java:34)：日统计 ID、事件类型、设备字段及累计快照内容。
- [APK 流程运行时](/tmp/adhq1001-20260914-audit/sources/com/smart/android/hq008flow/FlowRuntime.java:242)：授权交付、终态、超时、调度和授权设备字段。
- [Whale 回调 smali](/tmp/adhq1001-20260914-smali/smali_classes3/com/google/android/adhq1001/service/screensaver/SystemScreensaverService$4.smali:153)：`onAdFinish()` 中明确执行 `Hq008AdSession.failed(String)`；同文件 `onAdFilled()`、`onAdStart()` 无加载/开始回传。
- [当前源码日统计修正](/Users/zengyue/Documents/Chihi_Project/AD_APP/hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/internal/AdEventReporter.java:37)。

反编译产物位于 `/tmp/adhq1001-20260914-audit`，smali 位于 `/tmp/adhq1001-20260914-smali`，系统清理临时目录后可使用上述原 APK 重建。
