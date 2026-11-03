# 纯 IMA 后台 patch 接入与实机验证

已补齐纯 IMA 的 `POST /api/v2/ad/google-gam/patch`、`AD_GAM_PATCH_REQUEST/READY`、`AD_SDK_REQUEST.adLog.final_url` 和独立 `FILLED` 上报。Debug、Release 均接入；自拼接/TCL 原有调用流程保持原实现。

当前顺序为：授权取得基础 URL → IMA Core 生成完整 URL → 后台 patch → 后台 URL 原样进入广告 HTTP → VAST 填充、加载、播放和测量。拦截发生在 Core 拼参之后，覆盖原生网络及 WebView 回退。主请求与其回退共享一次 patch；跟踪请求、脚本和后续 Wrapper 不作为新的主请求重复 patch。

复用现有 `GoogleGamPatchClient` 和成功/错误协议。请求使用本轮冻结的 request_id、授权渠道、独立本地 rdid、MAC、ANDROID_ID、宿主版本和原生 SDK 版本 `3.35.1`。后台返回 URL 不再追加参数或本地修正。失败不回退原 URL，不自动重试 patch POST；销毁、替换、总超时会取消在途请求，过期结果不能送入下一轮。

另补齐两处相关行为：

- `ad_load_timeout_ms` 同时传入 VAST 和素材加载超时，patch 等待受本轮总时限约束。
- 普通 Debug 默认使用后台广告 URL，并尊重流控/CMP/授权结果。官方 OMID 样本和原有 Debug 放行仅由 `debug.google.ima.sample=omid` 显式开启，Release 不启用。测试结束已清空该属性。

在 `192.168.0.124:5555` 的 mstar 实机，使用 AmbientLight `1.0.6 (6)`、原生 IMA `3.35.1`、Media3 `1.8.0` 和官方 OMID 样本，完成以下验证。授权、patch 和我方报告使用真实后台接口。

| 场景 | request_id | patch 请求 / 就绪 | FILLED | 播放完成及终态上传 |
|---|---|---|---|---|
| Core 3.538.0 正常原生网络 | `client-1793612975464-c89f0ff0` | 各 1 次 | 已确认 | 成功 |
| Core 3.539.0 正常原生网络 | `client-1793612876383-fd69b002` | 各 1 次 | 已确认 | 成功 |
| patch 交付额外延迟 6.5 秒 | `client-1793613663123-4d4a4b85` | 各 1 次 | 已确认 | 成功 |
| 模拟原生网络失败，进入 WebView 回退 | `client-1793613941296-b2e32cad` | 各 1 次 | `webview_patched_http`，已确认 | 成功 |

每轮均捕获到 IMA STARTED、COMPLETED 和 Google 测量请求。两个 Core 的执行脚本 SHA-256 均与官方对应版本一致。3.539.0 正常样本还逐字核对了 `final_url` 与原生 HTTP 实际请求 URL，两者一致。不同返回域名、重复参数和百分号编码的保留由定点单元测试覆盖。

原生网络回退及延时通过临时测试构建触发；故障与延迟注入已移除，实机已安装最终普通调试包。测试期间发现并修正了 Core 原生请求允许省略 User-Agent、而公开构造器拒绝空值的问题，现在仅代理 URL 字段，其余字段保留原值。

252 项单元检查全部通过，包含并发回退仅一次 POST、失败不回退、取消及过期响应、冻结身份、返回 URL 原值和原生请求可空字段。最终 Debug 和 Release 构建通过，受控 IMA AAR 重新生成并通过本地 Maven 副本 SHA 校验。

设备代理恢复为 `192.168.0.141:7897`，样本属性已清空，原 WebView 缓存已恢复，独立测试代理已停止。源码与安装包均已核验不包含临时故障、延迟或源码授权放行注入。实机验证使用官方测试广告，生产广告单元未纳入本次样本。

证据与实现：

- [结构化验证记录](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-integration/verification.json)
- [后台最终 URL 与实际原生 HTTP URL 对照](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-3.539.0-verified/url-proof.json)
- [3.539.0 关键阶段日志](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-3.539.0-verified/trace-steps-extracted.json)
- [WebView 回退实机原始日志](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-forced-webview-verified/logcat.txt)
- [最终环境恢复记录](/Users/zy/Documents/Codex/ad-app/outputs/ima-core-measurement-20261102/patch-integration/restoration.json)
- [宿主 patch 会话](/Users/zy/Documents/Codex/ad-app/app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaBackendPatch.kt)
- [宿主播放与业务日志](/Users/zy/Documents/Codex/ad-app/app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaAdManager.kt)
- [SDK 网络拦截入口](/Users/zy/Documents/Codex/ad-app/ad-sdk-google-ima/src/main/java/com/smart/android/googleima/internal/GoogleImaRuntimeBridge.java)

Android 原始日志对重复行有折叠，部分网络报告的整段 JSON 无法从 logcat 完整重组；原始 CDP、独立可解析的阶段日志和上报成功确认均已保留。
