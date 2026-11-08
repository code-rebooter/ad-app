# Google 纯 IMA 接入

2026-11-06：按本轮要求，app Google 渠道的纯 IMA 方案迁入 `:ad-sdk-tcl:google-ima`。

```groovy
implementation project(':ad-sdk-tcl:google-ima')
```

通过 `com.smart.android.tclad.ima.GoogleImaAdManager` 传入本轮授权的 `gam_config` 和实际声音配置。模块执行 Media3 + IMA 3.35.1、Core 最终链接后台 patch、逐广告播放预算、广告串生命周期、独立音量上报和运行测量审计。

完整示例、宿主回调、依赖与更新方式见 [纯 IMA 接入说明](google-ima/README.md)。本轮新增的 independent 生成链接专用入口已撤回；原 TCL 引擎及其历史直接标签 API 保留。
