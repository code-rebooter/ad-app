# KaneShort：独立拼链接，TCL 执行

构建 flavor：google_ad_tv_desktop_kaneshort。
CHANNEL / C_TYPE / MODEL：GOOGLE_AD_TV_DESKTOP_KANESHORT。
包名 com.adsflare.kaneshort，versionCode 1 / versionName 1.0.1，minSdk 23，targetSdk 36。
按用户 2026-10-19 的明确要求新建独立 kaneShortRelease 签名；此签名不是原商店应用的签名。原项目未找到该包名源码，本渠道按独立新签名版本生成，不宣称可覆盖商店安装版或适合作为原商店应用的升级版本。

2026-10-26：与全部 Google 渠道统一复用 `app/src/google_ad_tv_desktop` 的源码、Manifest、资源、混淆规则、公共依赖和 independent ASM 处理器；旧纯 TCL 模板已删除。

流程：sdk/authorize.data.gam_config → independent 本地生成完整 URL → 公共修正及空 rdid 兜底 → google-gam/patch（携带本地 UID）→ 后台 URL 原样交 TCL → 原厂请求、解析、播放与监测。旧 ima_tcl_url_patch 开关已移除。

最终完整 URL 加入 AD_SDK_REQUEST 的 adLog.final_url，随广告终态日志一次汇总上传。实际获取到 InLine 视频填充后、TCL 解析前，通过原状态接口报告 AD_PROGRESS / FILLED。沿用 Google 监测宏桥接。

私有签名材料保存在 app/signing_files/kaneshort-release.jks 和 kaneshort-release.properties，均应忽略 Git 并保持 0600 权限。后续本渠道版本沿用该密钥，不重新生成。

构建：./gradlew :app:assembleGoogle_ad_tv_desktop_kaneshortRelease

按要求不运行回归测试或设备播放验证。
