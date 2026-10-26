# Local Calendar：独立拼链接，TCL 执行

构建 flavor：google_ad_tv_desktop_localcalendar。
CHANNEL / C_TYPE / MODEL：GOOGLE_AD_TV_DESKTOP_LOCALCALENDAR。
包名 com.localcalendar.tv，versionCode 5 / versionName 1.0.3，minSdk 24，localCalendarRelease 签名。签名沿用 googleapps 的本地发布密钥；尚未核对 Play 应用签名证书。

2026-10-26：与全部 Google 渠道统一复用 `app/src/google_ad_tv_desktop` 的业务源码、Manifest、资源、混淆规则、公共依赖和 independent ASM 处理器。此前按纯 TCL 模板生成的同渠道包已作废，旧模板代码已删除。

流程：sdk/authorize.data.gam_config → independent 本地生成完整 URL → 公共修正及空 rdid 兜底 → google-gam/patch（携带本地 UID）→ 后台 URL 原样交 TCL → 原厂请求、解析、播放与监测。旧 ima_tcl_url_patch 开关已移除。

完整最终 URL 写入现有汇总日志 ad_log.steps[eventType=AD_SDK_REQUEST].adLog.final_url，广告流程结束后一次上传。实际获取到 InLine 视频填充后、TCL 解析前，通过原状态接口报告 AD_PROGRESS / FILLED。监测宏桥接沿用最新实现。

构建：./gradlew :app:assembleGoogle_ad_tv_desktop_localcalendarRelease

本次不运行回归测试或设备播放验证。

## 2026-10-23 flymobi independent 交付

用户确认 flymobi 对应本 flavor。包名、签名和后台渠道沿用既有配置，版本递增至 `1.0.2 / versionCode 4`。该版本开始使用完整独立 URL AAR，仍走公共参数修正 → 后台 patch → TCL。

## 2026-10-24 flymobi independent 更新

版本递增至 `1.0.3 / versionCode 5`，继续使用独立 URL 引擎。当前正式构建已统一升级为 `independent-google-tv-url-local-identity-20261025.aar`；包含固件字段统一、完整指纹恢复以及本地广告 ID/LAT 和 AppSet 规则。包名、渠道和签名沿用上一正式包。
