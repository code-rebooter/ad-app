# TCL 抓包字段参考与 Google resolve 职责

2026-10-09 修订：Google 渠道继续返回自己的 `pubads.g.doubleclick.net/gampad/ads` 标签。复用 TCL 播放器并不要求把 `google-gam/resolve` 改成 TCL 的 58 字段协议，也不要求新增 TCL 配置接口。

后台保留 Google 库存与广告位配置，按实际业务决定是否接收设备、授权、内容等额外上下文。客户端在每次新机会补齐 Google 的 correlator 和真实播放/设备状态，再把完整 URL 交给通用 VAST 加载器。字段由哪一侧提供应避免重复覆盖；具体调用见 [Google 接入说明](GOOGLE_INTEGRATION.md)。当前两字段 resolve 适配器可以继续用于获取标签。

本轮固定链接验证绕过 resolve，不以此要求后端改造。下面的 58 字段表和 33 项 URL 表仅用于解释 TCL/雷鸟抓包；只有主动对接同类 TCL 后台协议时才参考它们。旧 60 字段示例/Schema 是历史提案，不是 Google 的必填契约。

## 请求示例及完整类型

[完整 60 字段示例](../artifacts/tcl-resolve-contract-20261009/resolve-request-example.json)；[历史提案 JSON Schema](../artifacts/tcl-resolve-contract-20261009/resolve-request.schema.json)。这是历史 TCL 风格提案，未在 Google 调用端启用，也不是本轮改造要求。

示例中的设备/授权/账号值必须换成本次真实来源；空值表示该示例未提供，不表示可将所有真实请求留空。字段完整传输也不等于内容有效，后端需校验实际语义。

以下表格的“抓包值”仅为 TCL 证据，尤其 MovieArk、项目 212、伙伴标识、内容 Suga Babies 不可作为自有 Google 渠道身份硬编码。

## POST 字段（完整清单）

| 字段 | JSON 类型 | 抓包值 | 值来源 |
| --- | --- | --- | --- |
| appPackage | string | `"com.google.android.adais"` | 应用/平台登记身份，BasicParameters |
| appVersion | string | `"1.0.3"` | 应用/平台登记身份，BasicParameters |
| contentSeason | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| dnum | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| appCat | string | `"app"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| maxAdDuration | number | `60` | getALLConfig 返回的广告时长配置 |
| appKey | string | `<已脱敏>` | 应用/平台登记身份，BasicParameters |
| area | string | `"US"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| gdprConsent | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| minAdDuration | number | `5` | getALLConfig 返回的广告时长配置 |
| dnt | number | `0` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| contentTitle | string | `"App Content"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| gdpr | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| ap | number | `1` | 当前非直播 WATERFALL 配置/常量 |
| contentGenre | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| playerHeight | number | `0` | 当前非直播 WATERFALL 配置/常量 |
| podDuration | number | `120` | getALLConfig 返回的广告时长配置 |
| contentLanguage | string | `"en"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| sdkVersion | string | `"2.8.02"` | 当前非直播 WATERFALL 配置/常量 |
| contentRating | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| placement | number | `1` | 当前非直播 WATERFALL 配置/常量 |
| device | string | `"android"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| appDomain | string | `"com.google.android.adais"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| DevicePartner | string | `"chhkj_1"` | 应用/平台登记身份，BasicParameters |
| contentId | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| IFAType | string | `"aaid"` | SDK DeviceInfoGeneratorImpl 采集 |
| flags | number | `0` | 当前非直播 WATERFALL 配置/常量 |
| skip | number | `0` | 当前非直播 WATERFALL 配置/常量 |
| podSequence | number | `0` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| contentProducerName | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| schain | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| coppa | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| adType | number | `2` | 当前非直播 WATERFALL 配置/常量 |
| asfm | string | `<已脱敏>` | 应用/平台登记身份，BasicParameters |
| contentCat | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| deviceLanguage | string | `"en-US"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| playerWidth | number | `0` | 当前非直播 WATERFALL 配置/常量 |
| contentEpisode | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| gdprSource | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| cb | number | `1791454746014` | 每次请求生成的毫秒时间戳 |
| timestamp | number | `1791454746020` | 每次请求生成的毫秒时间戳 |
| appStoreUrl | string | `"https://play.google.com/store/apps/details?id=com.tcl.movieark"` | SDK 默认值；宿主非空值可覆盖 |
| mediaCp | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| appName | string | `"MovieArk"` | 原厂固定身份（appBundle 在内部接入分支另有处理） |
| contentKeywords | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| deviceMake | string | `"RockChip"` | SDK 默认值；宿主非空值可覆盖 |
| contentSeries | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| userAgent | string | `"Dalvik/2.1.0 (Linux; U; Android 13; X88Pro13.D1010.FE Build/TQ3C.230805.001.B2)"` | SDK DeviceInfoGeneratorImpl 采集 |
| appBundle | string | `"com.tcl.movieark"` | 原厂固定身份（appBundle 在内部接入分支另有处理） |
| application | number | `212` | 应用/平台登记身份，BasicParameters |
| contentLivestream | number | `0` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| deviceModel | string | `"X88Pro13.D1010.FE"` | SDK 默认值；宿主非空值可覆盖 |
| channelName | string | `"TCL_AISHANG"` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| contentLength | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| ProductionQuality | number | `0` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| TUID | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| contentNetwork | string | `""` | 宿主 RequestParams；原厂默认空字符串或数字 0 |
| did | string | `<已脱敏>` | SDK DeviceInfoGeneratorImpl 采集 |

## 完整返回链接参数及来源

这里“对应输入/候选输入”是基于一次样本建立的对照；名称和值相同不能独立证明服务端内部函数。客户端实际拿到这些参数时它们已经在 URL 中。

## 后台返回 URL（完整清单）

| 查询参数 | 对应输入/候选输入 | 抓包值 | 核验结论 |
| --- | --- | --- | --- |
| device_model | deviceModel | `"X88Pro13.D1010.FE"` | 设备型号 |
| rating | contentRating | `"R"` | 后台改写为空值以外的节目分级 |
| skip | skip | `""` | 后台将数字 0 变为空字符串 |
| gdpr_consent | gdprConsent | `""` | 同为空；仅凭本次样本不能证明非空转换 |
| ua | userAgent | `"Dalvik/2.1.0 (Linux; U; Android 13; X88Pro13.D1010.FE Build/TQ3C.230805.001.B2)"` | 设备 HTTP User-Agent |
| content_title | contentTitle | `"Suga Babies"` | 后台将 App Content 改为 Suga Babies |
| coppa | coppa | `"0"` | 后台将空字符串变为 0 |
| ic | 后台提供，未证明客户端映射 | `"IAB1"` | 后台补入 IAB1；与 contentCat 的映射未证实 |
| cb | cb | `"1791454746014"` | 沿用本次请求的毫秒时间戳 |
| channel_name | channelName | `"Suga Babies"` | 后台将 TCL_AISHANG 改为 Suga Babies |
| content_id | contentId | `"63c6852e1e97ec56ca93df9e"` | 后台补入节目 ID |
| ip | 后台提供，未证明客户端映射 | `<已脱敏>` | 后台提供的出口 IP；不是设备局域网 IP |
| h | playerHeight | `"0"` | 加载阶段为 0 |
| network_name | 后台提供，未证明客户端映射 | `"Maverick Entertainment"` | 后台提供节目网络名；与 contentNetwork 的映射未证实 |
| pod_max_dur | podDuration | `"120"` | 广告组时长上限，秒 |
| dnt | dnt | `""` | 后台将数字 0 变为空字符串 |
| TDID | 后台提供，未证明客户端映射 | `""` | 后台保留空值；不能据此将 TUID 改名为 TDID |
| url | appDomain | `"com.google.android.adais"` | 本次与宿主传入域信息相同 |
| gdpr | gdpr | `"0"` | 后台将空字符串变为 0；客户端应传实际授权状态 |
| app_name | appName | `"MovieArk"` | 本次原厂固定 MovieArk |
| device_make | deviceMake | `"RockChip"` | 设备厂商 |
| max_dur | maxAdDuration | `"60"` | 单条广告时长上限，秒 |
| content_genre | contentGenre | `"Drama,Thriller,Crime"` | 后台补入 Drama,Thriller,Crime |
| app_bundle | appBundle | `"com.tcl.movieark"` | 本次原厂外部接入固定 com.tcl.movieark |
| w | playerWidth | `"0"` | 加载阶段为 0 |
| app_store_url | appStoreUrl | `"https://play.google.com/store/apps/details?id=com.tcl.movieark"` | 商店 URL 作为一个查询值进行编码 |
| us_privacy | 后台提供，未证明客户端映射 | `"1---"` | 后台补入 1---；POST 中没有同名字段 |
| min_dur | minAdDuration | `"5"` | 单条广告时长下限，秒 |
| placement | placement | `"1"` | 本次为 1 |
| ifa_type | IFAType | `"aaid"` | 本次为 aaid |
| did | did | `<已脱敏>` | 设备广告标识 |
| vt | 后台提供，未证明客户端映射 | `"Suga Babies"` | 后台节目标题别名，与 content_title 相同 |
| cid | 后台提供，未证明客户端映射 | `"63c6852e1e97ec56ca93df9e"` | 后台节目 ID 别名，与 content_id 相同 |
| requestId | SDK 每次生成 | UUID | 原厂 VastRequest.getVastUrl 替换或追加；不在该 POST 请求体内 |

## 抓包证明服务端不只是原样转发

| 字段 | 发给 TCL 服务端 | TCL 返回链接 |
| --- | --- | --- |
| 内容标题 | `contentTitle="App Content"` | `content_title="Suga Babies"` |
| 频道名称 | `channelName="TCL_AISHANG"` | `channel_name="Suga Babies"` |
| 内容 ID | `contentId=""` | `content_id="63c6852e1e97ec56ca93df9e"` |
| 内容分类 | `contentGenre=""` | `content_genre="Drama,Thriller,Crime"` |
| 内容分级 | `contentRating=""` | `rating="R"` |
| 公网 IP | POST 无 `ip` 字段 | 服务端加入 `ip`，与本次代理出口一致 |

服务端如何挑选该内容的内部算法没有抓到。可确认的是这些值在服务端返回阶段已存在，客户端没有补齐它们。如果自有后台缺少这层内容/广告位配置，客户端补几个查询参数无法补出同样的信息。

## TCL 具体如何处理 adurl

根据原厂 2.8.02 字节码和 2026-10-08 HTTP 记录，主路径如下：

1. `getWaterfall/ad` 返回 JSON；读取 `data.adsUrl`（同时读取平台、广告位及保底等字段）。这时 URL 已带 33 项查询参数。
2. `VodAdsLoaderImp` 对应实现调用 `UUID.randomUUID().toString()`，将原 URL 和这个 requestId 放进 `VastRequest.Builder`。
3. `VastRequest` 构造时用 `Uri.parse(url).getPath()` 另存 `vastTag`。实际请求通过 `getVastUrl()` 调用 `StringUtils.replaceOrAddParameter(url, "requestId", id)` 得到链接。本次抓包实际查询参数是原 33 项加 requestId，共 34 项；其余参数值和顺序保持一致。
4. VAST 加载器调用 `HttpRequester.getXmlSync(request.getVastUrl())` 获取 XML，随后 `XmlConverter.convert(xml, VAST.class)`；原厂转换器使用 XStream 的注解映射和 `fromXML`。
5. 得到 VAST 对象后处理 Ad/Creative/Linear。Wrapper 分支会读取 `VASTAdTagURI` 并继续请求、合并相关跟踪；昨天这份响应没有 Wrapper，是三个 InLine。
6. 播放时长取 XML 的 `Duration`，素材取 `MediaFiles`，曝光与 start/quartile/complete 等地址取 XML 的 `Impression`、`TrackingEvents`。此次日志实际选择三条 640×360、640 kbps MP4，时长 20 / 30 / 15 秒，交给 UniPlayer 播放。

所以 adurl 是“拿 VAST XML 的请求地址”。TCL 不靠拆开 `content_title`、`did`、`pod_max_dur` 等 query 来解析视频；这些 query 交给广告服务端处理。播放器播放的 MP4 URL 是请求 adurl 后，再从 XML 读出的另一层地址。记录 getPath 不等于把整个 URL 截成 path 发送。

昨天样本的主路径可以用以下伪代码表示；省略了容错、保底、Wrapper 递归及跟踪：

```java
String tag = response.data.adsUrl;             // 服务端已拼好 33 项
String requestId = UUID.randomUUID().toString();
String requestUrl = replaceOrAddParameter(tag, "requestId", requestId);
String xml = httpRequester.getXmlSync(requestUrl);
VAST vast = xmlConverter.convert(xml, VAST.class);
// 从 vast 的 Linear 中读取 Duration、MediaFiles、TrackingEvents 等
```

## 同类 TCL 协议的对照验收参考

- 同一请求快照中，设备 UA、型号、厂商、广告标识类型/值和授权状态有明确真实来源，MAC 不当作 AAID。
- 逐项核对 58 个输入：标记值来自设备、宿主业务、平台登记或渠道配置；未知值明确为空/缺省，不重用原厂样本身份。
- 对同类 TCL 标签逐项核对 33 个输出，明确哪些透传、哪些配置补充、哪些改写；没有数据源的项明确为未实现。Google 上游另附语义到其字段的映射及实际请求证据。
- 返回 `ad_tag_url` 与实际 VAST 请求链接比较：除明确约定的动态请求参数外，客户端不能覆盖后台生成的内容/广告位/授权上下文。
- 发起两次新的广告机会：cb/requestId 等按约定更新；同一次加载及其必要重试不能无意重建另一套业务上下文。
- 不同应用、设备、授权、内容和时长配置能产生对应变化；正确编码空格、逗号和嵌套 URL；空广告与错误场景保持现有约定。
- 拿实际返回链接获取并解析 VAST，再验播放与跟踪。仅通过本地三条视频不能代替前面的参数/生成逻辑验收。

## 证据与当前实现缺口

[抓包比对摘要](../artifacts/tcl-resolve-contract-20261009/capture-comparison.json)；[完整请求/响应映射 JSON](../artifacts/tcl-resolve-contract-20261009/tcl-query-mapping.json)；[原厂加载器字节码](../artifacts/tcl-resolve-contract-20261009/original-loader-bytecode.txt)；[原厂 VastRequest 字节码](../artifacts/tcl-request-alignment-20261009/original-vast-request-bytecode.txt)；[原始抓包报告](../artifacts/tcl-vast-inspection-20261008/report.md)。

当前 `GoogleGamClient.resolve(channelId, mac, callback)` 保留现有两字段取链接协议。Google 是否需要增加业务字段应根据自己的标签生成需求决定，不能由这份 TCL 抓包表推导出 58 字段必填。
