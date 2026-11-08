# 纯 IMA / minSdk 24 真机验证（2026-11-06）

项目最低 Android 版本已统一为 API 24（Android 7.0）。两台实体设备上的最终受控场景为 **30/30 通过，共 472 个断言**，包括真实 IMA Core、Media3 播放、独立 OMID 验证脚本和 ActiveView HTTP 采集。真实默认后台 patch 与 Google VAST 获取、FILLED/LOADED 也通过，但两个官方样本的 Google 素材 CDN 返回 HTTP 404，真实远端视频完整播放仍未通过。

## 代码与构建

- 19 份 Gradle 文件（含独立测试宿主）统一 minSdk 24，app 的配置覆盖项也为 24；Gradle 实际配置检查覆盖 17 个 Android 模块及所有 flavor。接入文档同步到 24；历史设计/检查文档保留原日期语境。
- 纯 IMA 仍位于 `:ad-sdk-tcl:google-ima`，未迁移自拼接方案。本轮未重置原 TCL/HQ008 的既有工作区改动。
- app 的 `-PgoogleAdRuntime=ima :app:compileGoogle_ad_tv_desktop_ambientlightDebugKotlin` 通过。
- SDK Debug/Release AAR、普通消费宿主 R8 Release APK 均构建成功。迁移模块 JVM 60 项、原 IMA 模块 28 项通过，无失败/错误/跳过。
- 最终 Release Lint：0 错误、21 警告。未关闭 Lint 或增加 baseline。产物合并 Manifest 的 minSdkVersion 为 24。

最终构建命令：

```sh
./gradlew -I ad-sdk-tcl/google-ima/tools/consumer-smoke.init.gradle \
  :ad-sdk-tcl:google-ima:assembleDebug \
  :ad-sdk-tcl:google-ima:assembleRelease \
  :ad-sdk-tcl:google-ima:testDebugUnitTest \
  :ad-sdk-tcl:google-ima:lintRelease \
  :ad-sdk-google-ima:testDebugUnitTest \
  :google-ima-consumer-smoke:assembleRelease --offline --console=plain
```

## 设备与判定范围

| ADB 设备 | 型号 | Android API | WebView |
| --- | --- | --- | --- |
| 192.168.0.124:5555 | mstar | 29 | 74.0.3729.186 |
| 192.168.0.125:5555 | WS | 29 | 74.0.3729.186 |

运行版本为 IMA 3.35.1、Media3 1.8.0，实际观察到 Core `h.3.539.0`、`h.3.540.0`。这两台设备验证 API 29 的运行结果；API 24 最低版本由配置、Manifest、编译和 Lint 检查确认，本轮没有 API 24 实体设备结果。

受控测试使用官方测试 tag 作为 Core 参数生成种子，在广告请求发出前由本地 HTTPS patch fixture 替换为本地 VAST、MP4、独立测量脚本和追踪地址。未使用商业库存；点击场景仅命中本地追踪。真实后台试验使用 Google 官方测试 tag 且关闭模拟点击。

## 最终受控用例

“通过”包含错误和取消用例产生预期终态、状态去重、阻止错误后继续请求等断言，并非要求每个场景播放完成。

| 场景 | 设备尾号 | 预期终态 | 断言 | Run ID |
| --- | --- | --- | ---: | --- |
| 125 上报0/实际0 | 125 | completed | 20 | `device125-gam-probe` |
| 125 上报1/实际0 | 125 | completed | 20 | `device125-report1-physical0` |
| 125 上报0/实际1 | 125 | completed | 20 | `device125-report0-physical1` |
| 125 上报1/实际1 | 125 | completed | 20 | `device125-report1-physical1` |
| 124 上报0/实际0 | 124 | completed | 20 | `device124-r3-report0-physical0` |
| 124 上报1/实际0 | 124 | completed | 20 | `device124-r3-report1-physical0` |
| 124 上报0/实际1 | 124 | completed | 20 | `device124-r3-report0-physical1` |
| 124 上报1/实际1 | 124 | completed | 20 | `device124-r3-report1-physical1` |
| 双广告串 | 125 | completed | 18 | `device125-pod` |
| Wrapper 跳转 | 125 | completed | 21 | `device125-r2-wrapper` |
| 空 VAST | 125 | failed | 10 | `device125-empty` |
| 无效 XML | 125 | failed | 10 | `device125-r2-invalid` |
| patch HTTP 400 | 125 | failed | 10 | `device125-r4-patch-error` |
| 媒体 HTTP 404 保留填充 | 125 | failed | 10 | `device125-media-error` |
| 缺失授权配置 | 125 | completed | 6 | `device125-missing-config` |
| 外部门禁拒绝 | 125 | completed | 6 | `device125-external-denied` |
| 关闭审计保留状态上报 | 125 | completed | 14 | `device125-audit-disabled` |
| 隐藏播放 | 125 | completed | 17 | `device125-r4-hidden` |
| 缺省上报音量 | 125 | completed | 17 | `device125-r3-reported-missing` |
| 无效上报音量 | 125 | completed | 20 | `device125-r3-reported-invalid` |
| patch 延迟6秒 | 125 | completed | 17 | `device125-r3-delayed-patch` |
| 取消在途 patch | 125 | cancelled:window_destroy | 9 | `device125-r2-cancel-inflight` |
| patch 网络超时 | 125 | failed | 9 | `device125-r2-request-timeout` |
| 210秒长广告 | 125 | completed | 21 | `device125-r2-long-210s` |
| 原生失败转WebView | 125 | completed | 21 | `device125-r3-fallback` |
| 播放中销毁窗口 | 125 | cancelled:window_destroy | 10 | `device125-r4-finish-playback` |
| 新请求取消旧轮 | 125 | cancelled:replaced_by_new_request | 9 | `device125-r4-replaced-old` |
| 替换后新轮完整播放 | 125 | completed | 17 | `device125-r4-replaced-new` |
| R8 Release完整播放 | 124 | completed | 20 | `device124-r8-report0-physical1` |
| R8 点击确认与完整测量 | 124 | completed | 20 | `device124-r8-r4-click-5` |

关键证据：

- 两台设备分别覆盖上报音量/实际音量 0/0、1/0、0/1、1/1；音量结论同时来自播放器、独立 OMID 会话与 ActiveView HTTP。另覆盖缺省及无效配置、隐藏模式、关闭审计和播放门禁。
- 210 秒素材完整结束，整轮 219.67 秒；原始播放器快照推进至 `positionMs=200539`，超过原 180 秒请求期限，最终正常完成。
- 原生网络失败后真实进入 WebView 回退，整个会话仅一次 patch；迟到旧 patch 被隔离，不产生旧 READY/旧 VAST，新轮正常播放。
- R8 非 debuggable 的 deviceRelease 包实际播放并完成测量。点击确认场景采用原生产概率与窗口，未强制命中：目标进度 0.73252445，实际 0.7400599，`source=ui_route`，确认耗时 30ms；SDK 点击报告和本地追踪各 1 次。
- 单轮请求/终态去重、广告串双实例、Wrapper、空/无效 VAST、patch 错误/超时/取消、媒体失败保留填充、窗口销毁均有对应原始证据。

## 保留的失败尝试与测试设施修正

30 个用例是修正测试设施及判定后选定的最终验证集，不代表首次尝试全部成功。归档中仍保留 17 个断言失败的尝试，以及服务重启时被中断的运行，不计入通过分母。逐项失败检查见 `final-verification.json` 的 `retained_failed_attempts`。

修正包括：TLS 握手移至连接工作线程，避免阻塞 accept；HTTP/1.0 显式关闭连接以避免旧池化连接 EOF；播放中取消改为观察本地 started 回调后等待 2 秒；短素材进度同时读取真实 IMA AD_COMPLETED 诊断，避免仅每 20 秒播放器快照漏采。没有伪造进度、修改生产 SDK 的测量结果或放宽 1 秒推进要求。

早期部分尝试缺少独立 OMID 生命周期/测量信号，已保留其失败记录；最终选定的 Wrapper、声音矩阵和 R8 点击场景均收到对应测量。此结果证明这些场景可完成，不证明任意网络环境下测量无丢失。

## 默认真实后台与外部未通过项

通过 SDK 默认客户端调用 `https://api.kytira.cc/api/v2/ad/google-gam/patch`，观察到 `AD_GAM_PATCH_REQUEST → READY`，之后取得 Google VAST，产生 FILLED 和 LOADED。分别使用 OMID 和 Single Inline Linear 两种[Google 官方测试标签](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/tags)。

两种样本在 `redirector.gvt1.com` 跳转到 `asset.gvt1-cn.com` 后，Media3 下载 MP4 连续收到 HTTP 404。测试宿主被动 AnalyticsListener 捕获到：

```text
androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException:
Response code: 404
```

失败发生在媒体 HTTP 加载阶段，当前证据不指向解码问题。未改写 Google CDN 地址或通过兜底素材掩盖失败。真实远端广告的开始、完整播放及完整测量，仍需在该素材能够正常返回的网络环境下复验。

对应运行：`client-1793930190548-sdk-real-backend`、`client-1793930580321-sdk-real-probe`、`client-1793930741018-sdk-real-linear`。原始审计、事件和 Media3 错误日志均已归档。桌面 Python 直接请求曾遇到 Cloudflare 403，该结果与实际 SDK 默认客户端的成功 patch 分开记录。

CMP/UMP、authorize、业务调度、正式报告 HTTP 发送/重试继续由宿主负责。本轮验证 SDK 回调及测试收集器收到报告，不代表生产 ad/report 或 consent-log-report 已收报。

## 复测入口

测试宿主仅通过 init script 加入构建；Debug 包和 deviceRelease 包使用独立 applicationId，不覆盖业务 app。deviceRelease 继承 release、启用 R8，消费 SDK release variant。测试代码和 localhost 信任配置不进入 SDK AAR。

```sh
./gradlew -I ad-sdk-tcl/google-ima/tools/consumer-smoke.init.gradle \
  :google-ima-consumer-smoke:assembleDebug \
  :google-ima-consumer-smoke:assembleDeviceRelease --offline
python3 ad-sdk-tcl/google-ima/tools/device-fixture-server.py --help
python3 ad-sdk-tcl/google-ima/tools/run-device-case.py --help
```

服务器需要显式输出目录、TLS 证书/私钥、视频、独立 verifier 和 ActiveView 模板，参数见 `--help`。测试只反向映射 `tcp:19443`；fixture 私钥放在研究归档中，未放入项目。服务和 runner 的原始日志不丢弃失败尝试。

## 设备恢复

本次创建的 Debug/deviceRelease 测试包已卸载，两台设备的 `tcp:19443` reverse 已移除，fixture 服务已停止。124 返回原 launcher，125 返回原应用商店页面。未修改系统音量或全局代理，保留原代理 `192.168.0.132:7897`；124 原有 `debug.google.ima.sample=omid`、125 空值均保留。恢复后的包列表、reverse、前台和设置证据见 `restoration.json`。

## 归档与产物

证据目录：`/Users/zy/Documents/Codex/ad-app-research-archive/2026-11-06-pure-ima-min24`。包含 `final-verification.json`、每轮 verification/events/audit/VAST、服务端 HTTP/独立测量事件、测试 XML、Lint、构建和真实媒体错误日志、恢复记录及 APK/AAR 副本。

| 产物 | SHA-256 |
| --- | --- |
| `ad-sdk-tcl/google-ima/build/outputs/aar/google-ima-debug.aar` | `4e4962fcd0f087e636c638b166ac1f7f4c3f24328e9efe25e8204343c1354880` |
| `ad-sdk-tcl/google-ima/build/outputs/aar/google-ima-release.aar` | `38ff14108c1093f3593a51ba0d124f02cba34330bf759045a9915cd26601167f` |
| `ad-sdk-tcl/google-ima/src/test/consumer-smoke/build/outputs/apk/debug/google-ima-consumer-smoke-debug.apk` | `818e6c74f28fdf7e4c4281e793922c1ca4b99b49f6a47fdcc9e694c0d4ee16a5` |
| `ad-sdk-tcl/google-ima/src/test/consumer-smoke/build/outputs/apk/deviceRelease/google-ima-consumer-smoke-deviceRelease.apk` | `6532507ce47771b2b6e1d897f7e0985826dacb15e7cc168afb38db1bb43a8ac1` |
| `ad-sdk-tcl/google-ima/src/test/consumer-smoke/build/outputs/apk/release/google-ima-consumer-smoke-release-unsigned.apk` | `962ad904864a70b8f88b79cefa5d60a9e97c53e206ab4e1460edb271dbf9f8a5` |

后续 Google 与 TCL 二合一交付及缺陷修复结果见 [2026-11-06 二合一客户验证](2026-11-06-combined-sdk-customer-validation.md)。本文保留当时产物和外部素材失败记录。
