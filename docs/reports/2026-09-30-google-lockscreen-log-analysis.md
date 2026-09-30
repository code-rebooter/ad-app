# GOOGLE_AD_TV_LOCKSCREEN 日志核查与客户回复

分析日期：2026-09-30。依据：用户提供的 CSV、当前工作区代码、本地历史版本 9 安装包、Google 官方文档。本次仅分析，没有修改投放代码或线上配置。

## 结论与边界

这份样本不支持“广告都完整播放，只因 IMA 默认 8 秒超时、402 先到达 GAM，导致请求和曝光一起被撤销”的完整因果链。

可以确认：样本中存在大量 VAST 303 空返回和 1005 请求失败；唯一一条 402 的实际超时阈值是 20 秒，未记录广告开始或完成。内部流程结束、IMA LOADED、IMA COMPLETED、用户实际看到广告、GAM 最终认可的曝光，是不同口径。

优先核查两条独立线索：一是填充和请求网络问题；二是隐藏投放与 Google 最终统计的差异。500 条均标记隐藏模式，当前代码和本地历史包均存在隐藏展示逻辑。这是有具体证据的异常线索，但尚不能证明 Google 已因此把这些请求判为无效流量，更不能证明两天内的回减全部由它造成。

没有取得 GAM 原始报表、回减前后快照、实际网络回传或 Google 的过滤原因，故尚不能定案“倒扣”的后台原因。

## 样本范围与计算口径

- 文件：`GOOGLE_AD_TV_LOCKSCREEN的日志记录_20260930032208.csv`。
- SHA-256：`8aeee870c9d2be361dc0ea8286da3be54fe277d3555a7ff923750e4a4b0a7e9a`。
- 500 条 CSV 数据记录，500 个不同 MAC；广告版本全部为 9。
- 时间列为 `2026-09-30 11:21:41` 至 `11:21:45`，覆盖 5 个秒刻度。每条记录内部包含更早开始的流程，不代表所有播放都发生在这 5 秒内。
- 473 个可提取的本地请求 ID，无跨行重复；其余 27 条在门禁阶段结束。
- 这是终态日志样本，不是两天全量日志或同一批请求的完整追踪，不能外推两天错误率、设备重试率或扣量比例。
- 统计按每行 JSON 中的 `steps` 和 `finalEventMessage` 计算，避免将同一错误在摘要中的重复出现累计多次。

| 最终结果 | 条数 | 解释 |
|---|---:|---|
| AD_PHASE_COMPLETED | 97 | 均有 AD_LOADED、AD_STARTED，且 imaTerminalEvent=COMPLETED |
| VAST 303 | 199 | VAST_NO_ADS_AFTER_WRAPPER；wrapper 后无可用广告 |
| IMA 1005 | 52 | FAILED_TO_REQUEST_ADS；请求失败 |
| VAST 402 | 1 | VAST_MEDIA_LOAD_TIMEOUT；20 秒媒体加载超时 |
| VAST 403 | 1 | VAST_LINEAR_ASSET_MISMATCH；素材与播放器能力不匹配 |
| 应用层请求超时 | 2 | callbackTimeoutMs=180000；不是 VAST 402 |
| FLOW_GUARD_FINISH | 116 | 均未记录 AD_REQUESTED、AD_LOADED、AD_STARTED，不是广告播放完成 |
| CMP_GATE_STOP | 27 | 门禁结束 |
| AUTHORIZE_DENIED | 4 | 自有授权拒绝 |
| AUTHORIZE_CALLBACK_FAIL | 1 | 自有授权请求失败 |
| 合计 | 500 | |

352 条具有 AD_REQUESTED 和 AD_SDK_REQUEST，其中 97 条完成，占这一样本子集 27.6%；255 条失败。402 占这一子集 0.28%。这些比例仅描述导出样本，不能作为全量投放指标。

1005 中，43 条含 `Caused by: 7 / HTTP status -1`，8 条含 `Caused by: 6 / HTTP status -1`，1 条含 `Caused by: 6 / HTTP status 0`。这些不是正常 HTTP 4xx/5xx 响应，需进一步采集网络异常；仅凭数字不能确定 DNS、TLS、连接失败中的哪一种。

## 唯一 402 的完整关键时序

CSV 第 333 行（含表头），时间列 `2026-09-30 11:21:42`，本地请求 ID：`client-1790738473354-270bc9ad`。

| 相对流程起点 | 事件 |
|---:|---|
| 1,565 ms | AD_REQUESTED |
| 2,373 ms | AD_SDK_REQUEST |
| 8,373 ms | AD_LOADED |
| 31,780 ms | AD_PHASE_ERROR，VAST_MEDIA_LOAD_TIMEOUT / 402 |

原文：`VAST media file loading reached a timeout of 20 seconds.`

该记录没有 AD_STARTED、AD_PHASE_COMPLETED，也没有“402 后仍成功播完”的证据。8,373 到 31,780 ms 是应用事件间隔，不能直接当成 SDK 内部计时器启动时刻；实际 20 秒阈值由错误原文确认。

内部摘要将 AD_LOADED 翻译为“素材加载=已完成”，但该事件来自 IMA LOADED 回调，不能证明视频文件已完整下载或可播放。参见 [播放器事件映射](../../app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdVastPlayerView.kt#L103) 和 [摘要生成](../../app/src/hq008/java/com/smart/android/ad_app/Hq008ConsentLogReporter.kt#L295)。这很容易造成“素材明明已加载完成，为何还有媒体加载超时”的误读。

## 客户三个问题的直接回答

1. **是否有大量 AdBreakCancelled / AdsCancelled？**

   本 CSV 没有记录这两个事件，也没有 AD_PHASE_CANCELLED。116 条 FLOW_GUARD_FINISH 是应用流程收口，不能当成 IMA 取消事件。CSV 不是底层所有 IMA 事件和网络 ping 的完整日志，因此只能回答“样本未见”，不能回答“线上绝对没有”。

2. **请求后留给广告播放的窗口是多少？**

   日志记录的应用层回调保护时间是 180 秒；唯一 402 记录的 IMA 媒体加载阈值是 20 秒。这两者不同。当前代码通过 Media3 的 setMediaLoadTimeoutMs 设置 IMA 媒体超时，服务端正数配置可覆盖默认 20,000ms。没有证据支持“默认 8 秒”或“直播插片窗口耗尽”。当前实现使用 SilenceMediaSource 作为内容占位，不能把占位时长或应用超时等同于真实直播插片窗口。

3. **是否在收到 402 后取消广告？**

   代码会把包括 402 在内的 IMA 错误作为失败处理，随后释放播放器和 AdsLoader。播放器 hasFinished 和请求终态保护会阻止同一应用请求再次报成功。这个行为可以表述为“错误后结束当前广告播放流程”，但不能进一步推导为“发送了名为 AdBreakCancelled 的事件”，更不能推导为“GAM 会因此删除已统计请求”。

## 隐藏投放的具体证据

500 条记录全部含“隐藏模式=是”。352 条 AD_SDK_REQUEST 全部为静音。352 条 AD_REQUESTED 的初始容器尺寸为 0×0，但这是创建阶段尺寸，可能尚未布局，不能据此认定实际播放期间一直是 0×0。静音本身也不能作为无效流量证据。

更直接的证据是：

- 当前 [GoogleAdTvDesktopAdManager.kt](../../app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt#L216) 在准备广告容器时设置 alpha=0。
- 当前 [首帧显示判断](../../app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt#L343) 在隐藏模式下直接返回，不把容器恢复为可见。
- 本地历史安装包 `app/build/outputs/apk/google_ad_tv_lockscreen/release/app-google_ad_tv_lockscreen-release-20260831_1607.apk` 的 metadata 标记 versionCode=9、versionName=1.0.5。APK SHA-256：`0c5bb3ed40edbab8b05948440cde4f32c328ca6eab3b8e0ce51cf139f6e9b929`。
- 对该历史包的反编译显示：隐藏渲染配置为 320×180，坐标 (-4000,-4000)，位置 LEFT_TOP；因此本地历史包也存在屏幕外投放逻辑。

当前工作区存在未提交变更，且没有核对线上设备实际 APK 哈希；不能以版本号相同就保证线上每台设备和本地历史包完全一致。需要在实际问题设备验证最终窗口坐标、alpha、可见区域、首帧和前后台状态。

Google 视频广告政策要求广告内容与控件不得隐藏，发布商展示位置政策也限制后台应用或屏幕外广告。若线上确实为隐藏/屏幕外播放，应先停止这种投放方式并恢复真实可见的展示场景。即使本地 IMA 回调完成，也不能据此保证有效曝光或收入。

还须核对这一模式是否近期改变。即使隐藏方式确实存在，也不能单靠长期存在的配置解释“为什么恰好这两天开始回减”。

## 对客户技术判断的校正

| 客户说法 | 核查结论 |
|---|---|
| 402 表示媒体加载超时/失败 | 基本正确；不是 HTTP 402 |
| 本案因默认 8 秒太短，改 14 秒 | 不适用；本案实际报 20 秒，改 14 秒反而缩短 |
| 402 先到 GAM 后会锁死会话，后续成功不再采信 | 未找到可支持这一通用规则的官方依据；本样本也未提供同会话回传时序 |
| 402 加会话取消，会撤销整条 Ad Requests | 未证实；不能把播放器失败、曝光是否计数和报表历史修订串成必然机制 |
| 正常 402 只扣预估曝光 | 不严谨；402 是播放错误定义，不是官方“扣量算法”定义 |
| 多 MediaFile 时应关闭 fallback | 未证明本案涉及多媒体源；GAM 官方对多类 VAST 错误反而建议评估 video fallback。服务端 video fallback 与播放器选 MediaFile 也不能混为一谈 |
| wrapper 上游超时就是 402 | 需区分 wrapper URI 超时与媒体素材超时；GAM 将前者通常列为 301，后者为 402 |
| 在 GAM 常规报表按本地 session ID 查即可 | 本地 client-... 请求 ID 不自动等于 GAM 可查询键；需记录实际广告请求关联参数并确认账号可用的诊断能力 |

Google 官方确实说明收益会经历实时无效流量过滤、月末校正及支付后扣减。这支持“内部回调统计和最终 Google 收益可能不同”，但并未证明本案的请求数回减就是无效流量，更未证明由 402 或播放器释放直接触发。

## 建议的核查顺序

1. **先对齐“倒扣”的定义。** 收集同一账号、日期、小时、广告单元、相同时区与过滤条件下的前后两份报表，保留精确指标名称。区分总请求、匹配请求、曝光、可见曝光和预估收益；确认是同一历史时间段确实变小，而非报表刷新或口径切换。
2. **验证真实可见性。** 在线上问题包核对隐藏开关、窗口最终位置与透明度、实际可见画面，并记录包哈希和近期配置变更。若证实隐藏投放，先纠正展示方式。
3. **定位样本中的主要失败。** 对 303 获取完整 VAST/wrapper 链、广告源填充与投放匹配结果；对 1005 获取请求域名、连接耗时、DNS/TLS/网络错误和影响地区。303 的“无广告”可能来自填充不足或投放条件，不能直接认定 wrapper 解析故障或违规。
4. **分别核对本地统计与 Google 回传。** 本地授权成功、流程结束、LOADED、STARTED、COMPLETED 分列计数；检查万博报表实际聚合哪类事件，不能仅由本 CSV 推断万博统计 SQL 存在错误。对诊断样本采集 IMA 原始事件、广告标识、实际请求关联参数、曝光/进度/完成/错误回传的发起时间与网络结果。HTTP 成功也不保证 Google 最终认定可计费。
5. **用两天全量数据与正常日作对照。** 按版本、设备类型、地区、网络、广告单元、素材和配置变更分组，区分长期低填充与新出现的历史报表回减。让 Google/渠道提供错误维度、过滤或收益调整说明。

本样本的 352 条 SDK 请求使用同一广告链接哈希，这可能仅代表共用广告位模板，不能证明最终请求参数完全相同或 GAM 已去重。500 台设备各一条日志也不足以验证单设备短时间重试。

## 可直接发给客户的回复

> 我们核查了此次导出的 500 条日志。上报时间集中在 9 月 30 日 11:21:41–11:21:45，仅能代表这一小段终态样本。352 条记录实际进入广告 SDK 请求，其中 97 条收到开始播放及 COMPLETED；失败主要为 303 空广告返回 199 条、1005 请求失败 52 条，402 仅 1 条。另有 116 条只是流程结束，没有广告请求及播放开始记录。
>
> 唯一的 402 明确为媒体加载超过 20 秒，而且没有开始播放/完成记录。因此目前没有证据支持“同一广告已播完，但默认 8 秒超时先报 402”的判断，直接改成 14 秒会缩短现有阈值。
>
> 对您提出的三个问题：①这份样本未见 AdBreakCancelled、AdsCancelled 或应用取消记录；②日志中的应用回调保护为 180 秒，402 对应的 IMA 媒体加载阈值为 20 秒，尚无直播窗口耗尽证据；③代码在 IMA 错误后结束并释放当前播放器，但这不等于 GAM 会撤销该次广告请求。
>
> 另一个需要共同确认的重点是，样本全部标记隐藏模式，代码及本地历史包存在透明或屏幕外展示逻辑。我们需要核实线上广告是否真实可见；播放器完成回调不等于 Google 最终认可的有效曝光。请协助提供同一日期、小时与广告单元回减前后的 GAM 报表、精确指标名称和错误/过滤明细，以便将播放故障、展示问题和后台数据修订分别对齐。

## 官方参考

- [GAM：VAST 错误 301、303、402、403 的定义与排查](https://support.google.com/admanager/answer/4442429?hl=en)
- [Android IMA：AdsRenderingSettings.setLoadVideoTimeout](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/reference/com/google/ads/interactivemedia/v3/api/AdsRenderingSettings)
- [Media3：ImaAdsLoader.Builder.setMediaLoadTimeoutMs](https://developer.android.com/reference/androidx/media3/exoplayer/ima/ImaAdsLoader.Builder)
- [GAM：无效流量与收益校正](https://support.google.com/admanager/answer/6053295?hl=en)
- [Google 视频广告资源政策](https://support.google.com/publisherpolicies/answer/15208072?hl=en)
- [Google：后台或屏幕外广告展示限制](https://support.google.com/publisherpolicies/answer/11190357?hl=en)
