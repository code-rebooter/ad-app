# KaneShort：IMA 拼链接，TCL 执行

构建 flavor：google_ad_tv_desktop_kaneshort。
CHANNEL / C_TYPE / MODEL：GOOGLE_AD_TV_DESKTOP_KANESHORT。
包名 com.adsflare.kaneshort，versionCode 1 / versionName 1.0.1，minSdk 23，targetSdk 36。
按用户 2026-10-19 的明确要求新建独立 kaneShortRelease 签名；此签名不是原商店应用的签名。原项目未找到该包名源码，本渠道按独立新签名版本生成，不宣称可覆盖商店安装版或适合作为原商店应用的升级版本。

直接复用最新 google_ad_tv_desktop_v260904_1 的源码、Manifest、资源、混淆规则、完整依赖和四个 ASM 处理器。不要使用 google_ad_tv_desktop_tcl_test 的纯 TCL 模板。

流程：google-gam/resolve → IMA Core 拼完整 URL → ima_tcl_url_patch（缺省/0 保留，1 适配 TCL）→ 公共参数修正 → 空 rdid 使用持久 UID / aaid 兜底 → 释放 IMA → TCL 请求、解析、播放与监测。真实广告拉取请求在 IMA 发出前截获。

最终完整 URL 加入 AD_SDK_REQUEST 的 adLog.final_url，随广告终态日志一次汇总上传。实际获取到 InLine 视频填充后、TCL 解析前，通过原状态接口报告 AD_PROGRESS / FILLED。沿用 Google 监测宏桥接。

私有签名材料保存在 app/signing_files/kaneshort-release.jks 和 kaneshort-release.properties，均应忽略 Git 并保持 0600 权限。后续本渠道版本沿用该密钥，不重新生成。

构建：./gradlew :app:assembleGoogle_ad_tv_desktop_kaneshortRelease

按要求不运行回归测试或设备播放验证。
