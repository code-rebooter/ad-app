# TCL 原厂 adurl 处理引擎
> 2026-10-12：当前为 `adurl-only-v4`。已复核全部隔离规则，恢复通用媒体缓存、索引数据库、文件工具、播放器配置/预加载及通用 POST，Cookie/TLS 延续 v3 恢复结果。TCL 业务取链、配置、BI、统计、aid 等入口继续隔离。当前验证及完整清单见 [全部规则复核](../artifacts/tcl-isolation-scope-review-20261012/report.md)；此前交测包未覆盖。v1 的 Google/雷鸟实播、v2 补漏和 v3 Cookie/TLS 修正记录分别保留，不能当作 v4 真实 HTTPS 验证。

本模块的边界是“宿主已经取得 adurl”。公开 API 负责接收链接并转发调用；进入广告处理后执行 TCL 2.8.02 原厂代码。

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

以 2,405 个原厂 class 为输入：移除 190 个 BI/数据库类，13 个类做列明的边界改动，其余 2,202 个类逐字节一致；构建同时检查修改类中的非隔离方法未改变。媒体模块对基础资源的引用也逐项核对，580 个带限定条件的基础资源值完整保留。

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

`Controller` 的 start/preparePlayer/pause/resume/setVolume/stop/release/lifeCycle 均转发至原厂 Controller。回调原样转发，不做“一次完成”去重，不主动补发原厂未发送的事件。Controller 的同进程复用也遵循原厂工厂。两次独立广告机会之间，宿主应在上一轮完成后调用原厂 `release()`，再开始下一轮；SDK 不隐式重建原厂单例或补发广告事件。手机补测中，未 release 直接复用曾出现第二轮无画面/回报的间歇停播，日志包含 Surface 切换失败，重跑又完成；该分支仍保留失败记录，不能以成功重跑声称已经解决。

不再提供自写 XML 入口、独立的播放超时或重定向规则。需要测试 XML 时通过本地 HTTP 标签返回 XML，仍由原厂请求和解析。调试 HTTP 抓取仅存在于 demo，不替换 SDK 的网络客户端。

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
