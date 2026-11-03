# TCL 流程同步验证（2026-11-02）

已同步原厂取链后的后台 patch、按素材时长计算的长广告预算、广告串状态、请求归属与 TCL 运行时汇总日志。实现及字段说明见 [流程说明](../tcl-runtime-flow-sync.md)。当前全部 10 个 TCL flavor 共用实现。

## 编译与单测

- `testHq008XHSXDebugUnitTest`：214 项通过。包含长广告越过原请求期限、动态时长更新不重置已消耗时间、缓冲软预算/硬期限、重复回调、串播切换、patch 参数与取消、完整 XML 和超长汇总保留，以及无填充/网络/解析/播放错误分类。
- Google independent 的定点回归 28 项通过；LSAP 共享 trace 定点回归 14 项通过。
- `-PgoogleAdRuntime=ima` 下纯 IMA、patch 及 UMP 会话门禁的定点回归通过。
- XHSX Debug APK、TCL 爱尚 Release APK 构建通过；Google independent 和海尔 LSAP 的编译检查通过。
- Google UMP 专用测试从全渠道公共测试目录移入 Google 测试源集，避免 TCL/LSAP 测试编译引用 Google 独有类。

四份原厂 AAR 直接使用项目内 vendor 输入并检查固定 SHA-256，不依赖旧 demo 目录或跳过补丁任务。SDK 观察点在构建时检查存在，异步 HTTP 的会话 tag 在已有 UA bridge 之前注入。

## 取消边界修复补充验证

修复拦截器返回响应时过早移除下载 Call 的问题。下载 Call 现在保留到响应体 EOF、关闭或读取失败；取消后才进入拦截器的下载会直接取消，不再发起下载。

新增 `TclDownloadCallTrackerTest` 共 7 项回归通过。其中用真实本地 HTTP 连接发送超过 256 KiB 的 XML 前半段并暂停尾部，按运行时顺序完成 256 KiB 抓包、返回响应和开始读体，再取消下载；验证 Call 被取消、读取在 2 秒内以 IOException 结束，服务端无需发送尾部，客户端读取超时设置为 15 秒。其余覆盖完整 XML/EOF、提前关闭、读体与关闭异常、响应前异常或无响应体、交接时取消和取消后进入的请求。

修复后 `testHq008XHSXDebugUnitTest` 全量 221 项通过，XHSX Debug APK 重新构建通过。该补充验证使用 JVM 上的真实 OkHttp/Socket，不是模拟器或生产后台联调；下文 Release 产物及 TV 模拟器结果对应初次同步验证。补充日志、单测 XML 和 Debug APK SHA-256 位于原归档目录的 `cancellation-boundary/`。

## TV 模拟器

环境：独立的 `Ad_TV_API_36`，Android 16 / API 36 / arm64，使用只读、无音频的模拟器实例。未安装或启动测试到原先正在验证 IMA 的实体设备。

测试使用明确的假取链/patch 响应和本地 HTTP 媒体，广告请求、VAST/Wrapper 解析、原厂播放和跟踪均通过真实 TCL 2.8.02 SDK 执行。

| 场景 | 结果 |
| --- | --- |
| 210 秒长广告 | 180 秒宿主请求期限下正常继续播放，记录到 200 秒后的推进，素材最大观测位置 209079ms，收到原厂完成并清理窗口；仅一次 patch 和一次终态汇总 |
| 两条 20 秒广告 | 两个独立条目，creative-1 / creative-2 分别保留位置、时长与完成态；一次 patch、一次 FILLED、一次终态汇总；14 次原厂 HTTP 结果被捕获 |
| Wrapper → InLine | 两份 XML，Wrapper 阶段不误报填充，最终 InLine 报一次 FILLED；不重复 patch，跟踪 HTTP 被观测 |
| 空 VAST | 不报 FILLED，原厂错误收口为 `no_fill`，一次终态日志 |
| 保底 | 主链接空响应后使用原厂保底；主链与保底分别 patch，总计两次；XML 记录保底真正请求的后台链接，不把原模型旧 URL 当作实际请求 |
| patch 失败 | `gam_patch_failed`，仅发生取链 HTTP，未回退原链接请求广告；一次终态日志 |
| 生命周期 | 取消排队请求、初始化期限、旧加载/开始/完成/错误回调、共享底层控制器、下一轮期限和 trace 开关隔离全部通过 |

最终六组设备回归合计 39 项断言通过；长广告另有 5 项断言通过。成功场景验证了实际跟踪 HTTP、逐条元数据以及审计在终态之前入批。终态日志采集在观察到汇总请求体时验证，不把这一检查描述为线上服务端已成功入库。

首轮模拟器检查发现并修正 Android DOM 不支持部分安全 feature 的问题，以及跟踪执行器切线程后丢失归属的问题；后续设备回归确认 FILLED 与跟踪记录均正常。XML 仍拒绝 DTD/entity，禁止外部实体解析。

## 范围和产物

离线验证会在 Debug 测试进程中注入固定原厂配置快照，不代表正式配置服务初始化、CMP 弹窗或真实广告填充已经在本轮联调。生产 patch 的真实后台调用仍需按渠道正式联调；本轮验证其请求协议、取消和后台返回链接的使用方式。

当前应用声音策略继续使用授权配置值，没有新增真实系统音量采集，也没有把 IMA/OMID 字段填成 TCL 的执行结果。

构建产物：

- `app/build/outputs/apk/hq008XHSX/debug/app-hq008XHSX-debug.apk`。
- `app/build/outputs/apk/tcl_aishang/release/app-tcl_aishang-release-20261102_1904.apk`。

原始 JSONL、构建日志、TCL 单测 XML、运行脚本和 APK SHA-256 保存在仓库外的 `../ad-app-research-archive/2026-11-02-tcl-runtime-sync/`。
