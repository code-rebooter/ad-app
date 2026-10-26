# X98K：独立拼链接，TCL 执行

构建 flavor：google_ad_tv_desktop_v260904_x98k。
CHANNEL / C_TYPE / MODEL：GOOGLE_AD_TV_DESKTOP_V260904_X98K。
包名 com.localvideoplayer.tv，versionCode 1 / versionName 1.0.1，localVideoPlayerRelease 签名。

2026-10-26：与全部 Google 渠道统一复用 `app/src/google_ad_tv_desktop` 的业务源码、Manifest、资源、混淆规则、公共依赖和 independent ASM 处理器。此前按纯 TCL 模板生成的同渠道包已作废，旧模板代码已删除。

流程：sdk/authorize.data.gam_config → independent 本地生成完整 URL → 公共修正及空 rdid 兜底 → google-gam/patch（携带本地 UID）→ 后台 URL 原样交 TCL → 原厂请求、解析、播放与监测。旧 ima_tcl_url_patch 开关已移除。

完整最终 URL 写入现有汇总日志 ad_log.steps[eventType=AD_SDK_REQUEST].adLog.final_url，广告流程结束后一次上传。实际获取到 InLine 视频填充后、TCL 解析前，通过原状态接口报告 AD_PROGRESS / FILLED。监测宏桥接沿用最新实现。

构建：./gradlew :app:assembleGoogle_ad_tv_desktop_v260904_x98kRelease

本次不运行回归测试或设备播放验证。
