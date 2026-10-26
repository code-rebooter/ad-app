# V260904_1：独立生成 URL，后台 patch，TCL 请求与播放

2026-10-23：执行代码已迁入 `app/src/google_ad_tv_desktop/java`，全部 Google flavor 共用同一套最新链路。旧 IMA 完整执行与纯 TCL 实验方案已删除；本目录仅保留 V260904_1 渠道说明。完整清单见 `app/src/google_ad_tv_desktop/README.md`。

当前流程（2026-10-26）：

`业务开关 / CMP / sdk/authorize.data.gam_config → independent 本地生成 URL → 公共参数修正及空 rdid 兜底 → google-gam/patch → 后台最终 URL → TCL 请求 VAST / Wrapper → UniPlayer 播放 → 原厂跟踪`

## URL 交接

独立 AAR 在本地生成完整 URL，不加载 IMA loader 或远程 Core。完整 URL 经过原公共修正及已有空 ID 兜底，携带本轮 request_id、授权时的 channel_id、mac、androidid 和本地持久 UID rdid 请求后台 patch。

patch 只有 HTTP 200、code=200 且返回非空 data.ad_tag_url 才进入 TCL。后台结果原样传给 setVastUrl，此后不再执行客户端公共 URL 修正或额外适配。已删除 ima_tcl_url_patch 开关及对应的本地 TCL 字段清理代码。接口错误和空链接结束本轮，不绕过后台播放本地链接。

请求 ID 和渠道都随本轮授权传递；宿主总超时包含独立 URL 生成、patch 和 TCL，取消/销毁同时取消在途生成任务、patch 和 TCL。详细请求字段、同一持久 UID 的来源和错误约定见 `docs/google_gam_patch_api.md`。

## 日志与状态

完整最终 URL 仍写入原 AD_SDK_REQUEST 步骤的 adLog.final_url，值为后台返回后真正交给 TCL 的 URL；终态一次汇总上报，不新增单独日志请求。consent-log-report 顶层 request_id 来自本轮快照，FILLED 仍在原响应正式解析前报告。

监测宏 AD_MT/UACH、原厂 VAST/Wrapper/UniPlayer 和已有跟踪行为保持原路径。公共参数、UA 和设备固件修正规则在交给后台前执行，未在此变更中重写信号计算。

2026-10-26：Google 渠道固定使用完整独立 AAR，APK 不包含 IMA SDK、PAL 或 tv-ads；IMA 反编译和对照代码仅保存在仓库外研究工作区。当前构建说明见 `app/src/google_ad_tv_desktop/README.md`，后台返回示例见 `docs/google_gam_patch_api.md`。

## 历史记录

以下为旧交付与验证记录，旧开关行为已由上面的 patch 流程替代，不能作为当前接口配置说明。

## 2026-10-17 原固定调整方案验证（开关接入前）

Android TV 16 / API 36 ARM64 模拟器收到官方 10 秒测试广告的 TCL 完成回调；1 条广告请求与 6 条监测请求均 HTTP 200，监测宏已展开。`AD_MT` 使用 TCL 实际播放进度，`uach_m` 与广告 URL 的 `uach` 相同。真实后台连续生成两次 URL，交接与原厂 HTTP 请求之间仅新增 `requestId`。

真实管理器入口已由 TCL 发出广告请求，返回 HTTP 200、156 字节空 VAST，未发生正式填充。4 项 URL 策略测试、Debug 和 R8 Release 构建通过；Release 中拦截与监测钩子保留，Debug 验证页不存在。Release APK 未进行运行验证；正式业务总开关、CMP、授权整条流程未在本轮重新覆盖。HTTP 200 不代表平台计费或测量认证。

完整参数清单、实际 URL、原始 JSONL、VAST、构建与验证证据已迁移至仓库外研究归档。2026-10-16 的旧验证只作历史记录，其中监测请求当时尚有宏未展开，不应据此宣称测量验证通过。


## 0/1 开关与公共修正的本次验证

2026-10-17：10 项针对性测试通过，Debug / R8 Release 构建成功。TV 模拟器核对了 0、1 和字符串 "0" 切回：关闭分支保留 IMA URL 后仍修正已有 UA/型号/系统/SDK/Build 字段；开启分支先适配 TCL，再做同一公共修正。开启分支官方测试广告收到 TCL 完成回调，6 条原厂监测均 HTTP 200，实际请求只由 TCL 新增 requestId。当前真实后台配置生成 URL 后解析开关为 0。关闭分支未做完整播放/监测验证，Release 未安装运行，正式填充/频控/计费不由本次结果保证。证据已迁移至仓库外研究归档。


## 最终 URL 日志与 FILLED 状态（2026-10-17）

交给 setVastUrl 的完整最终 URL 已添加到原 AD_SDK_REQUEST 步骤的 adLog.final_url，仍在流程终态一次汇总上传至 consent-log-report。新加的 FILLED 是状态接口 /api/v2/ad/report 的 message，event_type 沿用 AD_PROGRESS；在 TCL 正式转换 XML 前观察已有响应，识别到 InLine 视频素材后每次宿主请求最多报告一次。Wrapper/空响应不报填充，原厂请求、解析、播放器和监测逻辑不被替代。后续错误诊断保留 filled 与 filledAtMs。协议与交测信息见 docs/google_ima_tcl_reporting.md；验证证据已迁移至仓库外研究归档。


## 1.0.2 / versionCode 2：空 rdid 兜底

最终公共修正后，缺失/空白 rdid 按 TCL 原厂读取本地值、UUID 生成、持久保存的方式补齐。本应用复用 google_tcl_ad/aid 存储，不恢复 TCL 被隔离的业务取链/统计。已有 rdid 和 idtype 不改；补自建 UID 时使用 idtype=dpid。明确 is_lat/ltd/tfcd/tfua/dnt 限制标识的请求不补，全零标识不替换。最终 URL 日志会含兜底结果，仍只终态一次上传。

2026-10-17 早前模拟器实际 rdid 为 69d1f33b-6b82-4188-9608-98c471ce1d4b（idtype=adid, is_lat=0）。这只能证明模拟器当时有值，测试盒子为空的原因尚未验证。


## 1.0.3 / versionCode 3：兜底类型对齐 TCL

空 rdid 仍使用相同的本地持久 UID，补值时 idtype 从 1.0.2 的 dpid 改为 TCL 原厂 getDeviceType() 返回的 aaid。已有非空 rdid 及其类型保持原值；不重新生成已保存的 UID。公共修正顺序、限制标记处理、最终 URL 的终态汇总日志以及 FILLED 状态流程沿用现有逻辑。


## 正式版本基准修正：1.0.11 / versionCode 12

构建 flavor 仍为 google_ad_tv_desktop_v260904_1，包名 com.localvideoplayer.tv，后台渠道 GOOGLE_AD_TV_DESKTOP_V260904_1。此前 google_ad_tv_desktop_tcl_test 已于 2026-10-16 交付同包名、同后台渠道和同正式签名的 1.0.10 / versionCode 11。因此本渠道接续到 1.0.11 / versionCode 12；本次生成的 1.0.1～1.0.3 / code 1～3 版本过低，不应作为覆盖 code 11 的交测包。当前代码沿用相同 TCL 执行、空 rdid 的持久 UID + aaid 兜底及日志/FILLED 处理，URL 生成已统一切换为 independent。

## 2026-10-23 授权合并取链

基础链接、加载超时和开关从同一次授权响应传至本次播放器入口；兼容授权响应平铺格式，显式 gam_config 优先。无可用链接时跳过，不请求旧 resolve，不沿用上轮值。8 项授权配置与 3 项开关定点检查通过；本次未做设备或线上请求验证。完整后台示例见 docs/google_ima_tcl_authorize_api.md。

## 2026-10-23 请求字段补充

六个广告流程接口补 androidid、ad_version_name 和实际 TCL SDK 版本 sdk_version=2.8.02；授权另带复用 google_tcl_ad/aid 的 rdid。日志顶层携带本轮快照 request_id，仍一次终态汇总。接口清单和字段含义见 docs/google-ad-backend-fields-20261023.md。

## 1.0.12 / versionCode 13：固定独立 URL 引擎

全部 Google flavor 固定使用 `independent-google-tv-url-local-identity-20261025.aar`，不再提供 `googleUrlEngine` 选择参数。正式 APK 排除 IMA SDK 与桥接源码，不改包名、签名或后台渠道，并继续沿用 `localVideoPlayerRelease` 签名。APK 文件名固定标明 `independent`。
