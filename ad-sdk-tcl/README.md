# TCL 原厂 adurl 处理引擎

二合一版本 2026.11.06.1 的复审修复、验证结果和产物哈希见 [完整验证报告](../docs/reports/2026-11-06-sdk-review-fixes-validation.md)。最低 API 24；Google 为纯 IMA 路径。

## 客户二合一接入

同时提供 TCL 原厂和 Google 纯 IMA 时，使用一个聚合依赖：

```groovy
implementation project(':ad-sdk-tcl:combined')
// 客户 Maven 包：com.smart.android:ad-sdk-tcl-combined:2026.11.06.1
```

TCL 使用本页的 `Ad` / `Controller`；Google 使用 [GoogleImaAdManager](google-ima/README.md)。两套入口保留各自的播放器和会话，跨引擎切换前释放上一轮，不同时开启两个广告窗口。聚合依赖会传递带入 SDK、三份 TCL 运行时和五份受控 Google AAR；不能仅复制聚合 AAR。

客户包只在本机生成：

```sh
python3 ad-sdk-tcl/tools/package_customer_sdk.py
```

交付包包含 Maven 仓库、接入文档、独立 R8 示例、验证报告和校验和。

> 2026-11-06：Google 纯 IMA 路径已迁入独立子模块 `:ad-sdk-tcl:google-ima`，包含 app 同版的播放管理、后台 patch、广告串预算、音量上报和测量审计。接入使用 [纯 IMA 说明](google-ima/README.md)。原 TCL 模块继续保留既有入口。

> 2026-11-05：已同步 app 的 TCL 运行时：后台 patch、请求响应确认、逐条广告播放预算、下载取消、有效 FILLED 和终态审计。当前宿主回调会去重，首帧或位置推进可以确认 STARTED；完成后释放原厂控制器并保留 500ms 跟踪结算窗口；新请求到来时先结算上一轮，终态审计保留释放前的最后播放器快照。下方 10 月记录描述历史隔离基线，不代表新增宿主生命周期与原厂逐事件一致。
> 2026-10-12：当前为 `adurl-only-v4`。已复核全部隔离规则，恢复通用媒体缓存、索引数据库、文件工具、播放器配置/预加载及通用 POST，Cookie/TLS 延续 v3 恢复结果。TCL 业务取链、配置、BI、统计、aid 等入口继续隔离。当前验证及完整清单见 [全部规则复核](../artifacts/tcl-isolation-scope-review-20261012/report.md)；此前交测包未覆盖。v1 的 Google/雷鸟实播、v2 补漏和 v3 Cookie/TLS 修正记录分别保留，不能当作 v4 真实 HTTPS 验证。

本模块的边界是“宿主已经取得 adurl”。公开 API 接收链接，原厂 TCL 2.8.02 执行请求、VAST 解析、选材、播放与跟踪；宿主侧生命周期使用 app 同版状态和审计逻辑。

原先的自写 `VastLoader`、`VastParser`、`Session`、`Tracking` 已移出生产源码，保存在迁移记录的 `legacy-src/`。当前 SDK 不再执行这套兼容实现。

## 执行链路

`宿主 adurl → 原厂 Ad/Config.start → 原厂 AddressLoader 收到链接响应 → VodAdsLoaderImp → VastRequest → DevelopedVastAdsLoader / HttpRequester / XmlConverter → 原厂 LazyLoadAdController → UniPlayer → 原厂事件跟踪`

适配层仅为原厂“取得广告地址”的 JSON 请求提供宿主已经取得的结果。原厂 AddressLoader 的线程、取消与回调，以及后面的请求参数、XML 模型、Wrapper、素材选择、播放状态、回报、超时和保底全部继续使用原厂实现。原厂广告资源保留。与广告执行无关的统计、配置服务及持久化入口按 [隔离清单](ISOLATION.md) 阻断。

## 版本和配置

| 部分 | 版本 / 内容 |
| --- | --- |
| 原厂 base | 2.8.02，278 个 class |
| 原厂 media_ad | 2.8.02，169 个 class |
| 原厂 player | 2.8.02，1,958 个 class；UniPlayer 4.0.6 + 定制 ExoPlayer 2.19.1 |
| XML 解析依赖 | XStream 1.4.18，原厂模型/转换，仅限制非广告 Java 类型 |
| 网络依赖 | OkHttp 4.12.0 / Okio 3.6.0；通用 GET/POST 保留，TCL 取链使用本地输入边界 |
| 外部依赖 | 当前解析为 66 项，均锁定到已抓取 TCL 宿主的对应版本 |

隔离参考产物 `original/classes.jar` 以 2,405 个原厂 class 为输入：移除 190 个 BI/数据库类，13 个类做列明的边界改动，其余 2,202 个类逐字节一致；构建同时检查修改类中的非隔离方法未改变。媒体模块对基础资源的引用也逐项核对，580 个带限定条件的基础资源值完整保留。

默认初始化读取 `assets/tcl-original-waterfall-2.8.02.json`，这是 2026-10-08 TCL 实测使用的完整配置快照：包括 5/60/120 秒的广告时长配置与 10,000 ms Wrapper 超时。固定版本和配置共同作为对照基准；不是通过新的后台接口重新获取配置。宿主可在初始化前调用 `Initialization.setOriginalConfiguration(json)` 明确提供另一份原厂格式配置。

## 使用

```groovy
implementation project(':ad-sdk-tcl')
```

```java
// 建议在 Application 中初始化一次；加载会等待初始化完成。
Initialization.init(context);

ResolvedAd input = new ResolvedAd.Builder(adurl)
    .setAdUnitId(reportingAdUnitId)
    .setGuaranteeLink(guaranteeUrl) // 没有保底时传空字符串或省略
    .build();

Controller controller = Ad.get().begin(context)
    .lazyLoad()
    .setAdType(AdType.WATERFALL)
    .setPlayerType(0)
    .setPlayerViewType(1)
    .setVolume(0f)
    .setResolvedAd(input)
    .listen(new AdStatusListener() {
        @Override public void onAdLoaded(Controller loaded) {
            loaded.start(adContainer);
        }
        @Override public void onAdStartPlay(double seconds) { /* 原厂事件 */ }
        @Override public void onAdFinished() { /* 原厂事件 */ }
        @Override public void onAdError(int code) { /* 原厂错误码 */ }
    }).start();
```

仅有链接时也可用 `.setVastUrl(adurl)`，它选择已核验的 TCL 客户端 VAST 引擎：`beaconingType=2`、`adType=1`、`adPlatformCode="0"`。有完整 TCL 取链接响应时使用 `ResolvedAd.fromTclResponse(json)`，保留 `adUnitID`、`adPlatformCode`、`guaranteeLink` 等数据，由原厂模型解释。

未调用 `.setRequestParams()` 时保留原厂 Config 默认值；自定义取链接 provider 仍接收兼容的默认参数对象。只有宿主明确提供的 RequestParams 才传入原厂 Config，SDK 不额外给 adurl 拼接这些默认空字段。

原厂会在实际请求 adurl 时替换/追加本次 `requestId`，Google 链接也走这一原厂步骤。Google 的库存和本次设备/授权参数在进入这里之前准备，详见 [GOOGLE_INTEGRATION.md](GOOGLE_INTEGRATION.md)。

`Controller` 的 start/preparePlayer/pause/resume/setVolume/lifeCycle 转发给原厂；stop/release 同时关闭本轮网络与生命周期。加载、开始及终态按本轮去重，旧回调无法影响新请求。播放预算、30 秒首帧期限、60 秒停滞/缓冲期限、广告串切换及实际 IPlayer 快照与 app 同步。

## 本轮执行配置

```java
ExecutionConfig execution = ExecutionConfig.builder(context, requestId, channelId)
    .callbackTimeoutSeconds(callbackTimeoutSeconds) // 有效范围 30..600 秒，缺省 180 秒
    .auditEnabled(popupLogEnabled)
    .build();

Ad.get().begin(context).lazyLoad().setPlayerType(0).setPlayerViewType(1)
    .setExecutionConfig(execution)
    .setVolume(soundEnabled ? 1f : 0f)
    .setResolvedAd(input)
    .listen(listener).start();
```

配置会冻结 request_id/channel_id、androidid、宿主版本、sdk_version=2.8.02、MAC 和持久 rdid。后台 `google-gam/patch` 默认**不启用**；需要时显式调用 `backendPatchEnabled(true)` 开启，再用 `patchEndpoint()` 指定接口、`patchParameters()` 补充冻结身份。未提供 ExecutionConfig 的既有 adurl 入口不调用后台 patch。

RDID 保留宿主既有的 `google_tcl_ad/aid`。同一宿主进程内，TCL、IMA、app 与共享 core 使用同一 canonical-path 进程锁，再获取文件锁；不改变标识格式、接口字段或跨应用隔离。存储不可用或首次持久化失败时，配置构建抛出 `IllegalStateException`，不会无锁生成一个未确认的新 ID；宿主应将本轮配置准备失败转为自己的失败回调。此机制不承诺 Android SharedPreferences 跨进程缓存一致性。

并发、失败后重试及 API 24 字节码回归入口见 [identity-lock 测试](src/test/identity-lock/README.md)。

`AdStatusListener` 新增 onAdRequested、onAdFilled、onAdDiagnostics、onAdExecutionEvent、onAdAudit、onPlaybackDeadline 和 onAdCancelled。请求结果只在真实地址 HTTP 收到响应后确认，包括非成功状态、重定向和无效正文；NO_RESPONSE/NOT_SENT 保留在终态诊断。WaterfallAdTagProvider 会提供实际 HTTP 证据并保留完整取链响应的广告位、平台与保底信息；自定义 provider 通过 Callback.onHttpRequest/onHttpResponse 提供证据。宿主直接提供 adurl 时，本地地址注入不算真实取链请求，不额外补报 REQUESTED。

审计开关由宿主本轮 popup_log_enabled 传入；审计保留完整的有界 VAST XML、HTTP、素材与逐广告快照。配置、解析及跟踪仍执行隔离后的原厂逻辑。构建复用 app 的 TclRuntimeAuditPatcher，观察点归属 SDK 内部包；实际交付使用 original/audited-classes.jar，original/classes.jar 与 isolation-manifest.json 保留为未加观察点的隔离参考。

与 HQ008 Flow SDK 的逐轮配置及完整回调接线见 [Flow 接入说明](../hq008-flow-sdk/README.md#与-ad-sdk-tcl-同步接入)。


## 验证与交付

```bash
./gradlew :ad-sdk-tcl:testDebugUnitTest :ad-sdk-tcl:assembleRelease \
  :ad-sdk-tcl:runtime-base:assembleRelease :ad-sdk-tcl:runtime-player:assembleRelease \
  :ad-sdk-tcl:runtime-media:assembleRelease :ad-sdk-tcl-demo:assembleDebug \
  :ad-sdk-tcl:lintDebug :ad-sdk-tcl-demo:lintDebug
python3 ad-sdk-tcl/tools/verify_isolated_runtime.py
python3 ad-sdk-tcl/tools/verify_original_resources.py
```

AAR 方式接入时需要 SDK AAR、三个运行时 AAR 和锁定的外部依赖；SDK AAR 不是内嵌全部依赖的单文件包。原厂 AAR 的来源见 [vendor/README.md](vendor/README.md)。

[严格对照要求](PARITY_REQUIREMENTS.md)与[迁移核验记录](../artifacts/tcl-original-engine-migration-20261009/report.md)区分原厂代码复用、同输入对照及真实广告测试。正式 Google 业务渠道的 IMA 调用尚未在本轮切换。
