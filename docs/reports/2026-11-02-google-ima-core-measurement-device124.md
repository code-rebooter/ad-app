# Google 纯 IMA：远程 Core 3.538.0 / 3.539.0 实机测量对照

2026-11-02 在 `192.168.0.124:5555` 的 mstar 实机上完成对照。两个有效样本的播放、IMA 起播测量、OMID 会话及测量请求均完成，主要上报值一致：OMID 可见比例 **100%**，三个音量字段均为 **1**。实际播放器音量和设备音量均为 **0**，因此这里的结论是当前受控纯 IMA 链路的上报结果。

**范围更正：本报告验证的是官方样本下的 Core 播放与测量层，不能作为我方完整生产业务链路通过的依据。** 后续对照自拼接实现确认：本次测试基线 `185b657` 中，纯 IMA 的 Debug 和 Release 均未接入 `POST /api/v2/ad/google-gam/patch`，构建脚本还明确排除了 `GoogleGamPatchClient.kt`；该基线纯 IMA 上报 `LOADED`，没有自拼接方案中独立的 `FILLED` 步骤。此外，纯 IMA 的 Debug 分支始终将授权返回的广告 URL 替换为官方 OMID 样本 URL，因此本次虽然经过真实授权接口与 Provider 请求入口，仍未验证后台实际下发的广告地址。上一轮验证遗漏了这些业务差异。

对应代码：[纯 IMA 构建排除 patch 客户端](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-integration/baseline-google-url-tcl.gradle:31)、[自拼接调用后台 patch](/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt:124)、[纯 IMA Debug 广告地址替换](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-integration/baseline-GoogleImaAdManager.kt:114)。

后续已补齐上述业务缺口并完成实机验证，见[纯 IMA patch 接入与验证](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-02-google-ima-patch-integration-device124.md)。本报告原始数据仍描述修复前基线的 Core 测量样本。

使用 `GOOGLE_AD_TV_DESKTOP_AMBIENTLIGHT` 渠道，包名 `com.ambientlight.tv`，版本 `1.0.6 (6)`，代码基线 `185b657`，原生 IMA `3.35.1`、Media3 `1.8.0`、WebView `74.0.3729.186`。两版各执行一次有效样本，均为正式 Provider `debug/requestFloating` 入口、隐藏模式、静音条件，同一官方 OMID 测试广告：广告 ID `5940439870`，创意 ID `138383994985`，Universal Ad ID `googlevideo:7PmBItJiJIM`，声明时长 10 秒。

| 检查项 | Core 3.538.0 | Core 3.539.0 |
|---|---|---|
| 测试开始时间（上海） | 16:48:35 | 16:49:23 |
| 我方 request_id | `client-1793609323493-3e242620` | `client-1793609372415-aceb01ee` |
| 执行脚本与官方同版本脚本的 SHA-256 | 一致 | 一致 |
| IMA `STARTED` / `COMPLETED` | 各 1 次 | 各 1 次 |
| IMA 25% / 50% / 75% 事件 | 齐全 | 齐全 |
| 终态 `measurement_complete` | `true` | `true` |
| `measurement_incomplete` | `false` | `false` |
| OMID `sessionStart` / `sessionFinish` | 齐全 | 齐全 |
| OMID loaded / start / impression / 三个四分位 / complete | 齐全 | 齐全 |
| OMID `percentageInView` | `100` | `100` |
| OMID 可见性 `reasons` | `[]` | `[]` |
| OMID `mediaPlayerVolume` / `deviceVolume` / `videoPlayerVolume` | `1 / 1 / 1` | `1 / 1 / 1` |
| 本地审计：实际播放器 / 设备音量 | `0 / 0` | `0 / 0` |
| Active View `/pcs/activeview` | 3 条，全部 HTTP 200 | 3 条，全部 HTTP 200 |
| OMID 测试 `ima-omid-script-test` | 11 条，全部 HTTP 204 | 11 条，全部 HTTP 204 |
| 我方 consent-log-report | `AD_PHASE_COMPLETED`，上报成功 | `AD_PHASE_COMPLETED`，上报成功 |

两版最后一条捕获的 Active View 请求均为 `sv=971&cb=nima&e=14&sdk=n`，共同字段为 `mut=0&a=1&c=1&mc=1&nc=0&mv=1&nv=1`，`ps=211,131`、`scs=962,541`、`bs=211,131`、`dur=10007`、`avms=nis`。时间累计值如下：

| 原始字段 | Core 3.538.0 | Core 3.539.0 |
|---|---|---|
| `tos` | `6535,0,0,0,0` | `6012,0,0,0,0` |
| `mtos` | `6535,6535,6535,6535,6535` | `6012,6012,6012,6012,6012` |
| `mcvt` | `6535` | `6012` |
| `vmtime` | `5155` | `5192` |

这些时间值来自最后捕获的一次测量发送，不能视为广告完整时长或 Google 后台最终汇总值。单次对照中的时间差不足以归因于 Core 版本；本次未观察到可见比例、音量信号、事件完整性或 HTTP 接收状态的差异。HTTP 成功和我方上报成功说明请求已被接收，未查询 Google 后台计费或最终可见率。

按用户要求，临时测试包仍调用我方真实授权接口，并将返回的 `authorized=false` 在客户端转换为 `true`，日志标记为 `IMA_CORE_MEASUREMENT_TEMP_AUTH_ALLOW original=false effective=true`，其他返回字段沿用接口值。测试结束后已恢复源码和普通调试包，设备 APK 的 SHA-256 与放行前普通包一致。

测试中通过 CDP 将 Core 脚本请求指向对应的官方版本。3.538.0 轮次的调试器 URL 可能仍显示原请求中的 `3.539.0`；实际执行脚本由 `Debugger.getScriptSource` 导出并与官方 3.538.0 文件逐字节核验，OMID `partnerVersion` 和上报 `sdkv` 也对应 3.538.0。两版官方执行脚本 SHA-256：

- 3.538.0：`07d2011cad5ee4797502291000e79b17d2cb4efdbb9003db9e0b9948e4b2255c`
- 3.539.0：`1c4b95dae73dc379b8fdae008d803eae21d32fdbd3d342e6f220c513e9d7c751`

默认代理含 `doubleclick.net → REJECT` 规则，视频直连还会进入返回 404 的国内节点。有效对照使用独立临时代理，保留 Google 官方广告、视频、IMA 和 OMID 地址。原设备代理 `192.168.0.141:7897` 已恢复，临时代理已停止，原 WebView 缓存已恢复。前置排障样本不计入以上结果。

证据文件：

- [结构化对照结果](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/comparison.json)
- [3.538.0 我方实际上报审计](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/core-3.538.0-official1/runtime-audit-actual.json)、[测量请求明细](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/core-3.538.0-official1/network-decoded.json)
- [3.539.0 我方实际上报审计](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/core-3.539.0-official1/runtime-audit-actual.json)、[测量请求明细](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/core-3.539.0-official1/network-decoded.json)
- [环境恢复核验](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/restoration.json)
- [官方 IMA OMID 样本说明](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/tags)
