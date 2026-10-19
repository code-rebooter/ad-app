# V260904_1：IMA 拼接参数，TCL 请求与播放

适用于 `google_ad_tv_desktop_v260904_1`、`google_ad_tv_desktop_v260904_x98k`、`google_ad_tv_desktop_localcalendar` 和 `google_ad_tv_desktop_kaneshort`，四个渠道共用本目录实现。业务链路为：

`业务开关 / CMP / sdk/authorize → google-gam/resolve → IMA 3.35.1 Core 生成 URL → 参数适配 → 释放 IMA → TCL 请求 VAST / 解析 Wrapper → UniPlayer 播放 → TCL 监测上报`。

本渠道独立生成业务源码，替换原 Media3 播放入口；使用与 TCL 实验渠道相同的隔离运行时及公共参数补丁。包名、版本、签名、后台渠道标识及现有 UMP/CMP 流程沿用渠道配置。

## 接口开关（2026-10-17 更新）

现有 `google-gam/resolve` 返回 `data.ima_tcl_url_patch`，后台约定整数 0/1：缺失或 0 默认保留 IMA 原始 URL，1 执行之前那套 TCL 调整。客户端同时兼容字符串 0/1 与布尔值；无效值按 0，不会让整份广告响应解析失败。每次广告独立使用本次接口值，不沿用上一轮开关。

两个分支均在交给 TCL 前运行现有 `normalizeAarUrlUa` 公共修正。该函数由原 `HaierAarUaPayloadNormalizer.kt` 生成，只替换为 TCL 使用的运行时 bridge，避免两套参数修正规则漂移。会修正它已识别的 UA/设备/系统/Build 字段，未识别的信号、重复参数与编码保留。`ms/uach` 不拆改。

顺序固定为：`IMA URL → [开关控制的旧 TCL 调整] → 公共参数修正 → 释放 IMA → TCL`。开关不能关闭最后的公共修正。后台协议见工作区 `docs/google_ima_tcl_url_patch_api.md`；本仓库不含后端页面/服务，未修改线上接口配置。

## URL 交接

`GoogleImaUrlBridge.buildFinalUrl` 返回 `FinalUrl.url`（交给 TCL 的 URL）、`FinalUrl.imaUrl`（清理前的 IMA URL）和可为空的原生请求 UA。IMA 初始化、信号收集及 Core 页面加载仍然执行，才能生成本次请求的信号。

渠道专用 ASM 在 `zzy.zzp` 使用 AdsRequest 的 userRequestContext 绑定会话，在 `zzba.zza` 分派 `nativeXhr/nativeRequest` 前消耗广告请求命令，阻止进入 IMA 的 GKS 或本地 HTTP 请求。只接受与输入广告端点匹配的 GET 请求（兼容 pubads / securepubads 别名）；需要请求体时返回错误。不把 VAST 响应交给 IMA，不进入 IMA 的解析、播放或监测。

`ima_tcl_url_patch=1` 时，`GoogleImaTclUrlPolicy` 保留后台业务字段和本次 IMA 生成的数据，过滤 IMA 的播放器身份、未接入的测量能力与内部执行字段，例如 `sdkv/js/sdk_apis/omid_p/osd`、`atv_*`、`impl`。新增未知字段不会自动传给 TCL。`ms/uach` 保持原始编码，不编造信号，也不宣称 Google 已认证混合接入。

开关为 1 时，`output` 恢复后台格式（本次为 `vast`）；`vpa/vpmute/ctv/msid` 按实际自动播放、声音、电视能力和包名校准。不会从后台旧值补回 Core 本次缺失的广告 ID 或信号；`is_lat=1` 时移除 `rdid`。两个分支在公共修正后都会对空 `rdid` 使用本地持久 UID 兜底并设置 `idtype=aaid`（1.0.3 起对齐 TCL 原厂）；明确限制标识的请求不补，规则见 URL 开关文档。后台已有业务字段的 query 片段及重复项保持原始编码，不统一解码再编码。

两条路径经过公共修正后，先释放 IMA，再把最终 URL 交给 TCL `setVastUrl`。TCL 原厂随后仍会加入自己的 `requestId`。原生 UA 作为桥接结果保留，本渠道不覆盖 TCL 的 HTTP UA。

## TCL 监测宏

渠道专用 `GoogleTclTrackingVisitorFactory` 为 TCL 原厂监测 URL 格式化补上两个 Google 宏：`AD_MT` 使用 TCL 实际读取的播放器进度（毫秒），`UACH` 使用本次广告 URL 的 `uach`。控制器、引擎、监测对象按会话关联，入队前固定参数，防止下一条广告污染前一条监测。

仅处理 doubleclick.net 的 `/pagead/interaction/` 与 `/pcs/view`。事件触发、XML 解析、线程执行器及 HTTP 客户端仍使用 TCL 原厂实现；不增加监测事件，不合成可见率或 OMID。未读到播放进度时保留不可用值 `-1`。钩子固定于本次 TCL 2.8.02 ABI，并由渠道规则保留相应类。

宿主回调超时覆盖 resolve、IMA 拼接和 TCL；后台加载超时约束 IMA 参数阶段，TCL 内部超时沿用原厂配置。取消、替换或销毁时同时取消 IMA/TCL，过期回调不进入新会话。业务日志只记录 URL 哈希和长度；完整 URL 只由桥接回调交付及 Debug 验证页记录。

## 2026-10-17 原固定调整方案验证（开关接入前）

Android TV 16 / API 36 ARM64 模拟器收到官方 10 秒测试广告的 TCL 完成回调；1 条广告请求与 6 条监测请求均 HTTP 200，监测宏已展开。`AD_MT` 使用 TCL 实际播放进度，`uach_m` 与广告 URL 的 `uach` 相同。真实后台连续生成两次 URL，交接与原厂 HTTP 请求之间仅新增 `requestId`。

真实管理器入口已由 TCL 发出广告请求，返回 HTTP 200、156 字节空 VAST，未发生正式填充。4 项 URL 策略测试、Debug 和 R8 Release 构建通过；Release 中拦截与监测钩子保留，Debug 验证页不存在。Release APK 未进行运行验证；正式业务总开关、CMP、授权整条流程未在本轮重新覆盖。HTTP 200 不代表平台计费或测量认证。

完整参数清单、实际 URL、原始 JSONL、VAST、构建与验证证据见工作区 `artifacts/google-ima-tcl-adaptation-20261017/`。2026-10-16 的旧验证只作历史记录，其中监测请求当时尚有宏未展开，不应据此宣称测量验证通过。


## 0/1 开关与公共修正的本次验证

2026-10-17：10 项针对性测试通过，Debug / R8 Release 构建成功。TV 模拟器核对了 0、1 和字符串 "0" 切回：关闭分支保留 IMA URL 后仍修正已有 UA/型号/系统/SDK/Build 字段；开启分支先适配 TCL，再做同一公共修正。开启分支官方测试广告收到 TCL 完成回调，6 条原厂监测均 HTTP 200，实际请求只由 TCL 新增 requestId。当前真实后台配置生成 URL 后解析开关为 0。关闭分支未做完整播放/监测验证，Release 未安装运行，正式填充/频控/计费不由本次结果保证。证据位于 `artifacts/google-ima-tcl-switch-20261017/`。


## 最终 URL 日志与 FILLED 状态（2026-10-17）

交给 setVastUrl 的完整最终 URL 已添加到原 AD_SDK_REQUEST 步骤的 adLog.final_url，仍在流程终态一次汇总上传至 consent-log-report。新加的 FILLED 是状态接口 /api/v2/ad/report 的 message，event_type 沿用 AD_PROGRESS；在 TCL 正式转换 XML 前观察已有响应，识别到 InLine 视频素材后每次宿主请求最多报告一次。Wrapper/空响应不报填充，原厂请求、解析、播放器和监测逻辑不被替代。后续错误诊断保留 filled 与 filledAtMs。协议与交测信息见 docs/google_ima_tcl_reporting.md；证据位于 artifacts/google-ima-tcl-filled-20261017。


## 1.0.2 / versionCode 2：空 rdid 兜底

最终公共修正后，缺失/空白 rdid 按 TCL 原厂读取本地值、UUID 生成、持久保存的方式补齐。本应用复用 google_tcl_ad/aid 存储，不恢复 TCL 被隔离的业务取链/统计。已有 rdid 和 idtype 不改；补自建 UID 时使用 idtype=dpid。明确 is_lat/ltd/tfcd/tfua/dnt 限制标识的请求不补，全零标识不替换。最终 URL 日志会含兜底结果，仍只终态一次上传。

2026-10-17 早前模拟器实际 rdid 为 69d1f33b-6b82-4188-9608-98c471ce1d4b（idtype=adid, is_lat=0）。这只能证明模拟器当时有值，测试盒子为空的原因尚未验证。


## 1.0.3 / versionCode 3：兜底类型对齐 TCL

空 rdid 仍使用相同的本地持久 UID，补值时 idtype 从 1.0.2 的 dpid 改为 TCL 原厂 getDeviceType() 返回的 aaid。已有非空 rdid 及其类型保持原值；不重新生成已保存的 UID。公共修正顺序、限制标记处理、最终 URL 的终态汇总日志以及 FILLED 状态流程沿用现有逻辑。


## 正式版本基准修正：1.0.11 / versionCode 12

构建 flavor 仍为 google_ad_tv_desktop_v260904_1，包名 com.localvideoplayer.tv，后台渠道 GOOGLE_AD_TV_DESKTOP_V260904_1。此前 google_ad_tv_desktop_tcl_test 已于 2026-10-16 交付同包名、同后台渠道和同正式签名的 1.0.10 / versionCode 11。因此本渠道接续到 1.0.11 / versionCode 12；本次生成的 1.0.1～1.0.3 / code 1～3 版本过低，不应作为覆盖 code 11 的交测包。代码沿用已完成的 IMA URL 拼接、TCL 执行、空 rdid 的持久 UID + aaid 兜底及日志/FILLED 处理。
