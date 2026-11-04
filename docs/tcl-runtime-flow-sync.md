# TCL 流程、长广告与运行时日志同步

适用于 `AD_CHANNEL_TYPE=TCL` 的全部应用 flavor。TCL 原厂版本仍为 2.8.02；Google independent、纯 IMA 和 LSAP 各自的播放器入口不改变。

当前覆盖 hq008、hq008XHSX、tcl_aishang、ad_ytx01、ad_ytx01_sxk、ad_ytx01_jx、ad_album_101_001、hq008Noneu、hq008Noneuc2、tcl_poly。构建按渠道类型选择源码，不按 flavor 前缀猜测。

## 请求与 patch

正式链路：调度及播放门禁 → flow-control → 按 skip_cmp 执行 TCL CMP → authorize → TCL 原厂取链 → google-gam/patch → TCL 原厂广告请求、解析、播放与跟踪。

沿用 [patch 协议](google_gam_patch_api.md)，后台按 channel_id 区分渠道。TCL 提交原厂取链工作线程实际取得的 `adsUrl`，携带本轮固定的 request_id/channel_id、本地持久 rdid、mac、androidid、宿主版本和 sdk_version=2.8.02。这里只复用 HTTP 协议客户端，不编译 Google URL 生成器或 IMA 播放器。

patch 成功只替换原厂响应对象的 adsUrl，其余广告位、平台、beaconingType、保底及原厂控制信息保持原值。原厂此后仍执行自身的 requestId 拼接等请求行为。VAST Wrapper 跳转不重新 patch；保底链接在实际请求时 patch。每个不同的原始链接在本轮至多发起一次 patch，相同链接共享结果。错误、空链接、取消或超时都不能退回原链接发广告请求。

patch 等待只发生在工作线程。请求阶段使用本轮剩余期限；取消、替换、销毁窗口会取消在途 patch 和所属下载请求。下载 Call 在响应体读到 EOF、关闭或读取失败后才退出取消集合；收到响应头或抓包读满 256 KiB 都不代表下载结束，因此大 XML 的慢速尾部仍可取消。关闭后才进入拦截器的下载也会直接取消。SDK 请求在创建 Call 前绑定会话 tag，使异步 HTTP 回调不依赖“当前全局请求”。原厂请求 ID、控制器、配置参数及跟踪对象分别绑定所属会话，关闭时只移除该会话的映射。

## 长广告与广告串

- 请求、初始化和 patch 共用原始请求期限，默认 180 秒，可由授权配置覆盖；重试初始化不重置期限。
- 开始播放后切换为每条广告的播放预算：素材时长 + 60 秒，限制在 90 秒至 15 分钟；未知时长采用 10 分钟。
- 缓冲暂停软预算，硬期限在该条开始时固定为播放预算 + 120 秒。连续缓冲、就绪不播放或位置停滞达到 60 秒分别报告对应超时。
- 每条广告等待首帧或位置推进证据的上限为 30 秒。后续时长更新扣除已消耗时间，不重新计满；重复回调不延长期限。
- 下一条广告使用独立预算。条目切换等待 30 秒并加 5 秒完成容差；已知最后一条等待整轮完成的容差为 5 秒。
- 进度从真实 IPlayer 的位置、时长、播放/准备状态、媒体索引读取。TCL `onAdStartPlay(double)` 的值来自 AdExtra.getMaxDuration，记录为广告串时长信息，不当作播放进度。
- 完成、错误、取消与超时只收口一次。成功完成时先采集播放器快照并释放解码资源，留 500ms 收集已排队的原厂跟踪结果，再结束汇总；销毁或新请求到来可立即结算已完成会话。

## 状态与日志

`ad/report` 保留 REQUESTED、LOADED、STARTED、COMPLETED、ERROR/TIMEOUT，并增加本轮一次 FILLED。自 2026-11-03 起，填充使用独立 `event_type=AD_FILLED`、`message=FILLED`；REQUESTED、LOADED、STARTED 仍使用 `AD_PROGRESS`。FILLED 在原厂响应交给 XmlConverter 前识别：仅有效 VAST InLine 线性视频中存在 HTTP(S) MediaFile 时触发，Wrapper、空 VAST、无效 XML 不算填充。填充不等于成功播放或曝光。

`consent-log-report` 继续由 popup_log_enabled 控制，在终态一次汇总。新增结构化 step `TCL_RUNTIME_AUDIT`，schema_version=1：

- 请求 ID、冻结的渠道、原厂请求 ID、取链/patch/SDK 事件时间线。
- 实际 VAST/Wrapper XML、请求链接、字节长度、SHA-256、InLine/Wrapper/素材信息。XML 使用 gzip+base64 保存。
- 原厂 HTTP 请求结果、HTTP 状态/网络错误、耗时及取链响应；跟踪请求沿用原厂执行器，不额外补发请求。
- 每条广告的 creative ID、位置/总条数、真实媒体时长、首帧/推进证据、最大位置、播放比例、缓冲及终态。
- 释放前播放器状态和原厂错误信息。

诊断的 reason 区分 tag_request_failed、gam_patch_failed、vast_request_failed、vast_parse_failed、no_fill、media_load_failed、playback_start_failed、playback_failed，原厂 errorCode 同时保留。空响应与网络/解析错误分别判断；取得填充后的失败仍保留 filled=true。

XML 的 sdk_request_url 是原厂模型中的链接，request_url 是实际交给 HTTP 客户端的链接；HTTP 记录另有 response_url 表示重定向后的响应链接。保底经过 patch 后也按此规则关联，不能把原厂模型里的旧链接当作实际请求地址。

原始 XML 最多保存 64 份、合计 4MiB gzip 数据；截取或省略会显式计数。事件保留首项及最近 399 项，并报告省略数量。TCL 审计与 IMA 审计使用相同的关键内容保留策略，避免原有 256,000 字符裁剪流程把完整审计静默丢弃。普通消息记录 URL 的哈希和长度；完整后台链接保存在 AD_SDK_REQUEST.adLog.final_url 和审计结构中。

仅记录广告配置音量及 SDK 配置查询值，未增加系统音量采集。IMA 事件标记 not_applicable；当前 TCL VAST 引擎的 OMID 能力标记 not_available。XML 中声明验证脚本，只记录声明与 not_observed，不当作已执行验证。

## 构建与验证入口

四份原厂 AAR 从 `ad-sdk-fusion/vendor/{base,cmp,media,player}/sdk.aar` 读取，继续检查原来的 SHA-256。新增原厂观察点只进入 TCL 应用使用的补丁 AAR；构建会校验关键入口存在。原始输入文件不修改。

```sh
./gradlew :app:testHq008XHSXDebugUnitTest :app:assembleHq008XHSXDebug --offline
./gradlew :app:assembleTcl_aishangRelease :app:compileHaier_lsapDebugKotlin --offline
./gradlew -PgoogleAdRuntime=ima :app:testGoogle_ad_tv_desktop_ambientlightDebugUnitTest --offline
```

XHSX Debug 验证页支持 `mode=fixture`、`fixture_case=single|pod|wrapper|empty|long|guarantee|patch_error`，通过明确的假取链、假 patch 响应和本地媒体验证真实原厂 SDK。long 使用 210 秒测试视频，宿主请求期限为 180 秒。`mode=lifecycle` 验证取消、初始化期限及旧回调隔离，使用待取消的假 patch 阻止商业广告请求。离线设备可在测试进程中注入仓库固定的原厂配置快照；测试后应重启进程再做正式联调。这不构成线上初始化或真实广告填充验证。Release 不包含验证 Activity、假接口或配置快照。

设备输出在 `files/device-validation/<run_id>.jsonl`。原始运行文件、模拟器媒体与构建日志保存在项目外临时目录，验证结果另记报告。
