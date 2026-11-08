# Device 124 当前源码全量回归与新增 UMP 边界

更新时间：2026-11-07 22:57（Asia/Shanghai）。设备仅 `192.168.0.124:5555`，Android API 29。

## 结论

`app`、`ad-sdk-tcl`、`hq008-flow-sdk` 的 TCL / Google 主清单已补测完成；**不能把本轮写成全部无问题**：真实 Google UMP 的冷启动改选又复现了一个独立问题，尚未修改，已发起确认。

本轮未修改生产逻辑。冻结的 503 个生产输入文件哈希无变化；临时宿主、夹具、观察器、录屏与脚本均在 `/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/`。保留原静默 UMP、自动动作、真假音量映射、TCL REJECT 后继续广告、tracking/BI 与单返回 AdsEvent 等定制合同。

## 已通过的有限清单

| 清单 | 实际结果 |
|---|---|
| 三层 TCL / Google 主矩阵 | **120/120**，包含普通、pod/wrapper、空填充、错误、取消、同步替换/重启、长片、网络边界及上报；SDK/Flow 使用本轮按当前源码重建的 AAR，不再采用早期旧包 |
| 启动 / 播放后缓冲 guard | **12/12**，确实建立首帧/进度/停滞条件 |
| 音量矩阵 | **24/24**；包含 6 条真实 Google OM 动态转换；App/Flow 的 4 条 TCL 动态转换已补实际 API 触发，不再把没执行动作的捕获算通过 |
| patch 异常响应 | **24/24** |
| 精确渠道门禁 | **5/5**，使用对应 flavor 与真实 signal，不以非门禁 flavor 替代 |
| 非 debuggable R8 消费宿主 | SDK / Flow × TCL / Google **4/4** |
| 完成上报持续 503 → 冷进程恢复 | app / Flow × TCL / Google **4/4**；完整原 payload 真正入 SQLite、真实换 PID、**不发新广告**单独 resume 补发 200、条件 ACK 清理，再验证健康新播放 |
| 当前 RDID 双进程 | 四入口组合 **16/16**，包含实际 peer 重启；测试包使用生产相同 prefs/锁文件名，未用不相容的 prefs 前缀 |
| app RDID 失败与正常恢复 | **12/12** |
| app independent Google URL→TCL | **6/6**，按原独立路径合同，不强套纯 IMA 能力 |
| 真正 Google UMP 服务主链 | **4/4**：EEA ACCEPT、已加载后的 REJECT、真实换 PID 后 CHECK/拒绝 TC 一致、非 EEA；SDK 结果、HTML 均未注入 |
| 环境恢复 | **10/10**；代理、音量/静音、signal、原前台、原 reverse 恢复，移除本轮 reverse/forward，停止本轮服务与测试进程；未卸载、未清应用数据 |

主矩阵中 12 个 app 核心行复用上一轮**完全同 SHA APK**的原始通过捕获；不是声称 120 行全部本次新播放。相同生产源码的 886 项 JVM 测试归档仍为 0 failure/error/skipped，本轮离线重算，不冒称重新执行了这些单测。

补丁独立 deadline 用例用了明确标记的测试专用 vendor watchdog，以免其他超时先终结请求；生产请求预算未改。静态视频夹具早期发送超时曾截断 24MiB 文件，已在安全串行边界修正服务端发送条件后重跑；原失败留存。

## 发现但未改：Google UMP 冷启动改选未即时执行

**发生位置和渠道：** app 的 Google UMP 路径，测试 flavor `google_ad_tv_desktop_ambientlight`、`googleAdRuntime=ima`，原 APK 测试包 `com.smart.device124.batchgoogle20261107`。不是 TCL CMP，也没有证据推及 Flow 的另一套 consent 门禁。

复现使用与主链完全相同的最终 app APK（SHA `151ec67de4519a5a3c6adab3138cda440e93ed482a16d3fe2d9402a3d14aca94`），**没有加入视图观察器或录屏**：

1. 真正 Google EEA 表单静默 ACCEPT 成功，purpose-consent 位为 `11111111111`。
2. force-stop 后新 PID，立即执行 REJECT。
3. SDK 的 privacy options 仍在预加载；原逻辑 750ms × 最多 5 次重试耗尽，返回 `Privacy options form is being loading. Please try again later.`。
4. 回调一次、宿主和 pending callbacks 清理正常，**但本次 REJECT 尚未执行，TC 仍是旧 ACCEPT**。
5. 保持同一 PID 等 20 秒后重新执行 REJECT，真正表单成功，purpose-consent 位为 `00000000000`。

两轮独立测试均复现：`14072 → 14676`、`15355 → 15833`；两次延后重试均成功。共 6 次调用均有终态/清理，不是漏回调或永久等待；问题是动作就绪等待不足，不能以 `canRequestAds=true` 认定本次 REJECT 成功。

证据：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/live-ump/cold-options-exact-apk/summary.json`，各子目录有原事件、prefs、PID 与 logcat。

代码位置：

- `/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpSilentConsentFormRunner.kt:217-241`：loading 的有限重试及错误结束。
- `/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt:250-275`：生产集成以“可请求广告且无错误”判断动作成功。**源码推断**：此错误不会清掉 pending remote action，后续 gate 可重试；广告继续与否仍沿用既有 `canRequestAds` 定制。当前 6 次实测直接调用真实 UMP manager，不冒称同时走过生产远端决策/待补上报全链。

**未修改**等待策略、静默脚本、同意/拒绝语义或广告放行策略；修复需用户确认。

## 静默表面与额外错误：已观测和未证实分别记录

1. 被动视图观察器在 privacy options 初次挂载时抓到短暂 `web/root/window alpha=1`，后续被现有轮询隐藏。因此原“所有视图样本始终为 0”的严格断言仍 FAIL，原记录未改。
2. 追加 4 段设备原生录屏及截图；解码后蓝色宿主基线之后的 **179 个实际视频帧**均保持纯背景，**未观察到表单或遮暗**。录屏设备的色彩标记与实际矩阵不一致；v1 色差误判留存，v2 用 BT.601 转换对齐同场截图。该结果不把视图属性瞬态直接说成肉眼闪窗，也不保证未捕获时刻。
3. 录屏/观察器宿主另有一次 warm REJECT 返回 `UMP silent privacy options auto-click timed out`，当时 purpose-consent 位已经变为 0；错误回调/清理正常。未确认唯一原因，不能当正常成功，也未据此擅自改生产超时或自动点击。

证据：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/ump-surface/summary.json`、`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/ump-video/evidence-v1/summary.json`、`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/ump-video/video-summary-v2.json`。

## 交付和复核

- 主清单逐项结果：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/ACCEPTANCE.json`。原总结果保留 **false**，没有把静默表面严格失败擦掉。
- 最终状态、原始证据完整性、源码/产物哈希：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/FINAL-STATUS.json`。
- 当前 SDK/Flow 消费身份：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/current-artifacts.json`。
- 环境恢复原始命令与结果：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/cleanup.json`。
- 离线复核：`/Users/zy/Documents/Codex/ad-app/build/full-closure-20261107-r2/verify-final.sh`。仅重读证据，不使用 ADB、不重启服务、不修改生产代码。证据完整与业务全通过是分开的两个字段。
