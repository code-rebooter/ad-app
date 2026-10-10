# WATERFALL 请求参数与后台接口约定

2026-10-09。依据 X88 上 TCL 2.8.02 的实际 HTTP 记录和原厂请求类字节码。

POST 请求实际包含 **58 个字段**；后台返回的 adsUrl 含 **33 个查询参数**；SDK 仅补充 requestId，实际请求含 **34 个参数**。保留空值后统计，不把嵌套编码的商店 URL 拆开。

单次抓包可以证明实际发送字段，不能证明每个字段均为服务端必填，也不能证明所有国家、授权状态及配置使用相同值。

## 当前 Google 渠道如何使用

Google 保留原有 `google-gam/resolve` 和 Google 标签。客户端补齐 Google 运行参数后，Google 与雷鸟链接均进入通用 VAST 请求、解析、原厂播放及跟踪流程。复用此流程不要求 Google 复制 TCL 的 58 字段协议。

以下是 TCL 专有请求格式的抓包参考；不是 Google 必传清单。后端职责的修订说明见 [RESOLVE_TCL_CONTRACT.md](RESOLVE_TCL_CONTRACT.md)。

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

## 可选 TCL 兼容适配器的接口约定

1. 接收上述 58 字段的 JSON，保持大小写、数字类型及空字符串。DevicePartner、IFAType、ProductionQuality、TUID 不改名。
2. 返回 code=100000、data.adsUrl；该 URL 包含平台所需全部查询值，客户端保留后台生成和改写后的值，仅替换或追加 requestId。
3. 实际应用、登记身份、AAID、授权状态、节目元数据和时长配置由对应数据源提供；不能把 X88 的一次请求值用于其他设备或每次请求。
4. area=US 是本次宿主 Locale 值，而后台配置 countryCode=hk、实际出口 IP 属于香港，三者不可混为一个字段。
5. 5/60/120 秒来自本次 getALLConfig 配置；新适配器允许显式配置这些值，尚未自动实现原厂 getALLConfig 调度。

## 已实现与边界

新模块 RequestParams 补齐原厂 34 个宿主字段；WaterfallRequest 生成 58 字段请求；WaterfallAdTagProvider 异步 POST 到宿主配置的接口，解析 adsUrl 并补 requestId。停止或释放 Controller 时取消配置请求。

Builder(context) 读取真实宿主包名、版本、应用名、签名 MD5、设备厂商/型号及 User-Agent；appBundle 默认宿主包名，商店地址和平台登记项由宿主填写。AAID 与类型由宿主提供，未提供时明确为空。原厂固定 MovieArk/com.tcl.movieark 和已抓到的项目身份只作为核验依据，需对接该登记身份时通过配置显式传入。

本文的可选后台适配器未联调真实取链接接口；独立 demo 后续已完成直接 Google/雷鸟真实 VAST 请求和播放，见 [双平台实测](../artifacts/live-google-leiniao-20261009/report.md)。此可选适配器请求宿主传入的接口地址，尚未接入真实后台；不内置原厂地址、HTTP 鉴权或签名拦截器。尚未覆盖原厂配置缓存/调度、CMP 自动取值及真机全流程；不能据此宣称整个 TCL SDK 已完成 1:1 验证。

原始证据：项目根目录 artifacts/tcl-vast-inspection-20261008/tcl-http-capture.jsonl（第 3、4 条）；artifacts/tcl-request-alignment-20261009 下的 original-request-bytecode.txt、original-waterfall-bytecode.txt、original-vast-request-bytecode.txt。
