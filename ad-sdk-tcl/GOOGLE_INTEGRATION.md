# Google 链接接入原厂 TCL 处理引擎

Google 渠道保留自己的 `google-gam/resolve` 及 Google 广告位配置。公开 API 的设计与取链接方式可以独立；取得 adurl 之后执行 TCL 2.8.02 的原厂广告链路。

## 取链接与入参

`GoogleGamClient` 保留现有 `channel_id`、`mac` 取链接格式。后台返回 `ad_tag_url` 即可进入直接标签适配器；不要求照搬 TCL 的 58 字段。后台返回的旧超时字段仍可读取，但不再覆盖原厂播放器的超时机制。

Google 的 correlator、应用标识、音量/自动播放与实际授权状态在进入原厂链路前准备：

```java
GoogleAdTagParameters.Builder parameters = new GoogleAdTagParameters.Builder()
    .setApplication(context.getPackageName(), appName)
    .setAutoplay(true)
    .setMuted(!soundEnabled);
if (advertisingIdAvailable) parameters.setAndroidAdvertisingId(advertisingId, limitAdTracking);
else parameters.clearAdvertisingId();
if (gdprApplicabilityKnown) parameters.setGdprConsent(gdprApplies, currentTcString);

String adurl = parameters.build().applyTo(config.getAdTagUrl());
Ad.get().begin(context)
    .lazyLoad().setAdType(AdType.WATERFALL)
    .setPlayerType(0).setPlayerViewType(1)
    .setVolume(soundEnabled ? 1f : 0f)
    .setVastUrl(adurl)
    .listen(listener)
    .start();
```

库存参数 `iu`、`sz`、`description_url`、`cust_params` 等继续使用 Google 标签原值。广告标识、授权和音量必须反映本次实际输入。`GoogleAdTagParameters` 属于链接准备工具，不参与原厂的 XML 解析或回报。

## adurl 后的处理

原厂 `VastRequest` 会为实际请求追加/替换 UUID `requestId`。这一步对 Google 也保留，这是按本轮“adurl 之后执行原厂流程”的要求恢复的行为。

直接链接适配器固定选择已实测的 TCL 客户端 VAST 分支：`beaconingType=2`、`adType=1`。提供完整原厂格式响应时可用 `ResolvedAd.fromTclResponse(json)`；保底 URL、广告位等字段原样传入原厂模型。

HTTP、XStream 模型与转换器、Wrapper、素材选择、播放状态、错误码、回调与跟踪由原厂代码执行。没有自写的事件去重或标准补报。例如此前自写版本增加的 creativeView 回报已随自写 Tracking 移除，当前以原厂实际触发的事件为准。

初始化默认使用随 SDK 保存的 2026-10-08 原厂配置快照；要调整配置，初始化前显式提供原厂格式 JSON。宿主销毁时调用 Controller.release，回调与控制器复用遵循原厂行为。

## 验证与正式切换

独立 demo 使用提供的 Google 测试标签和雷鸟标签验证。另有“原厂公开入口”和“宿主 adurl 入口”的同输入对照，见 [迁移核验记录](../artifacts/tcl-original-engine-migration-20261009/report.md)。旧的 29 项测试、9/9 或 78/78 回报记录属于自写兼容版本，不作为本版结果。

正式 Google 渠道仍使用现有 IMA 入口。本轮交付新 SDK 和独立 demo；业务接入时需按原厂容器、生命周期、回调及单进程控制器复用规则接入，并避免两套广告引擎同时运行。
