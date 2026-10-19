# Local Calendar：IMA 拼链接，TCL 执行

构建 flavor：google_ad_tv_desktop_localcalendar。
CHANNEL / C_TYPE / MODEL：GOOGLE_AD_TV_DESKTOP_LOCALCALENDAR。
包名 com.localcalendar.tv，versionCode 3 / versionName 1.0.1，minSdk 24，localCalendarRelease 签名。签名沿用 googleapps 的本地发布密钥；尚未核对 Play 应用签名证书。

2026-10-19 修正：复用最新 google_ad_tv_desktop_v260904_1 的业务源码、Manifest、资源、混淆规则、完整依赖和四个 ASM 处理器。此前按纯 TCL 模板生成的同渠道包已作废。

流程：google-gam/resolve → Google IMA Core 生成完整 URL → ima_tcl_url_patch 开关（缺省/0 保留，1 适配 TCL）→ 公共参数修正 → 空 rdid 持久 UID + aaid 兜底 → 释放 IMA → TCL 请求、解析、播放和监测。IMA 广告请求在发出前截获，广告响应仅交给 TCL。

完整最终 URL 写入现有汇总日志 ad_log.steps[eventType=AD_SDK_REQUEST].adLog.final_url，广告流程结束后一次上传。实际获取到 InLine 视频填充后、TCL 解析前，通过原状态接口报告 AD_PROGRESS / FILLED。监测宏桥接沿用最新实现。

构建：./gradlew :app:assembleGoogle_ad_tv_desktop_localcalendarRelease

本次不运行回归测试或设备播放验证。
