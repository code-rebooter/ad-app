# 124 UMP 剩余问题修复与最终验收

完成时间：2026-11-08 07:09:30（Asia/Shanghai）。设备 `192.168.0.124:5555`，API 29。

本轮剩余的 **privacy options 首帧可见瞬态**、**15 秒终态后旧排队 JavaScript 仍执行**，已完成修复与实际对照验证。当前两个运行渠道的真实同意／拒绝、冷进程读回、错误回调和恢复路径已完成下列有限清单验收。

## 1. 生产修改边界

只修改：`/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpSilentConsentFormRunner.kt`。

1. SDK 异步挂载时在 Choreographer 首帧隐藏对应 consent 根视图；保留原 120ms 扫描，已隐藏时不每帧更新窗口/Binder，不触碰无关根视图。
2. initial form / privacy options 每次动作独立 token、host/WebView 引用与终态 fence。DOM 副作用前同步验证当前动作仍有效且在原 15 秒 deadline 内；终态后旧排队 JS 与回调失效。
3. 错误/完成/超时/宿主关闭撤销本次 retry、扫描、frame callback 和计时任务；错误时只关闭对应 consent Dialog。私有 prompt 在 Native 直接确认，不出现 prompt UI。

**原定制选择规则不变**：ACCEPT/REJECT、Manage、78 项偏好与 Confirm、评分阈值、fallback、滚动顺序、250ms 初次点击、350ms 间隔、静默行为、拒绝后的广告放行，以及原单 AdsEvent 边界均保留。未延长 15 秒预算、未加入 ACK grace。

503 个冻结生产输入中只有上述 runner 改变，其余 502 个完全一致。选择模板去掉新增 fence 标记整行后与原模板逐字节相同，二者 SHA256 均为 `6e5e0f2d8100378419fbf3afd80f5063870260fe27be6d68291c28be3869fd85`。本轮没有修改 TCL TC、RDID、队列、上报、音量或广告业务源码，没有 reset/revert/commit 用户改动。

## 2. 当前代码实际验收

| 套件 | 结果 |
|---|---|
| 当前生产 runner 确定性执行 | 24/24 PASS；30 秒 readiness、原 15 秒动作预算、旧 token、排队 JS、初始表单、首帧与终态清理 |
| 实际输出 JavaScript | 14/14 PASS；原选择结果/动作序列/偏好位/滚动等价，取消/过期/prompt 延迟后停止副作用 |
| 纯 IMA app JVM | 226 tests，0 failure/error/skipped |
| 独立 Google URL→TCL app JVM | 200 tests，0 failure/error/skipped |
| 纯 IMA 真实 Google 服务 | ACCEPT、warm REJECT、warm ACCEPT、cold REJECT、cold CHECK、非 EEA CHECK 六阶段通过；冷读回 TC 一致 |
| 独立 Google URL→TCL 真实 Google 服务 | 五条健康路径均有当前 APK 的实际成功捕获；另外验证 preload 错误终态、同 PID CHECK/新 ACCEPT 恢复及 cold REJECT/CHECK；详见独立聚合审计 |
| 当前真实 initial form | 在单独的临时测试包调用 SDK reset 制造初始状态；真实 Google initial ACCEPT、initial REJECT、cold CHECK 和原表单分支日志通过；不注入 TC/结果、不 clear 应用数据 |
| 当前 SDK HTTP 故障链 | 真正 SDK 加载超时、HTTP 503 均返回一次错误并清理，保留原 consent；恢复原 Google HTTP 后同动作真实成功，cold CHECK 通过 |
| renderer 18 秒强制延迟 | 修前/修后实际对照通过，见下节 |
| 画面验收 | 34 份实际 screenrecord、1581 个解码帧逐帧核对；marker 后无可见表单、无遮罩/变暗，原始 PNG 同验 |
| APK/源码绑定 | 三个当前 APK 源码与完整 runner class family 一致，实际 DEX 含 fence；每次设备 ledger APK SHA 与安装包匹配 |
| 环境恢复 | 五个测试包仅 force-stop；原代理/signal、7/15 音量且非静音、原前台、空 reverse/forward 全恢复，boot ID 未变；未卸载、未清数据或清 logcat |

真实 Google 服务案例使用 SDK test-device/debug geography 参数，HTML、结果、存储和回调均来自原服务，`external_ump_fixture=false`。HTTP 错误/renderer 条件只在临时测试包路由外部 HTTP 响应，明确标为 `external_ump_fixture=true`；没有向生产添加 routing，也没有伪造 ConsentInformation。

独立渠道成功项来自**同一当前 APK/源码**的多次捕获，不宣称它的一次完整连续流程零失败。`independent-aggregate.json` 逐项绑定每条健康成功与两次错误闭环；原失败套件仍保持原 FAIL。

## 3. 超时后排队 JS 的实机对照

先通过真实 Google ACCEPT 得到实际 TC，再冷启同包，以受控 HTTP 让真正 UMP renderer 忙 18 秒，保留原 15 秒动作预算。按钮只写测试 marker，不生成或替换 TC。

- 修前 `fault-v2.apk`：15 秒返回一次 auto-click timeout 并释放宿主后，renderer 恢复，旧排队脚本仍点击 Reject，真实 SDK storage 写入 marker。
- 当前 `fault-checked.apk`：同样在原预算超时；renderer 恢复后真实 SDK 写入 `_busy_end`，但旧按钮 marker 为 **0**，无第二次结果，原真实 ACCEPT 位保留；表单始终静默。
- epoch 对齐验收 `late-timeline.json`：修前动作起点到 timeout 为 15.043 秒、marker 晚于终态；当前为 15.010 秒，renderer 晚于终态恢复且无晚期按钮写入。没有用杀进程、延长预算或放松断言绕过条件。

首次 `device-late` 的 warm 夹具命中了已缓存 Google 表单，没有让目标表单进入强制延迟，原 FAIL 保留；后改用冷进程消除缓存，仅修正测试条件。

## 4. 原始错误与恢复均留证

本轮有 9 次健康意图请求原记录为 FAIL，以下原文未改写为 PASS：

- `late-v2/before-seed-accept`：`Web view timed out.`。
- `late-v2/after-seed-accept`：`Web view timed out.`。
- `ima-final/warm-accept`：`Error making request.`。
- `independent-final/warm-accept`：`UMP privacy options readiness timed out`。
- `independent-recovery/warm-reject`：`UMP privacy options readiness timed out`。
- `fault-final/seed-accept`：`Web view timed out.`。
- `initial-final/initial-accept`：`Web view timed out.`。
- `initial-final/initial-accept-retry2`：`Web view timed out.`。
- `initial-final/initial-reject`：`Web view timed out.`。

直接 SDK `Web view timed out.` / `Error making request.` 的后续普通新请求成功。独立渠道两次 readiness 错误，各自请求时间窗口内均明确记录原 SDK 连续 `Failed to load and cache a form, error=Web view timed out.`：无挂载表单，30 秒有界返回一次错误，TC 与上一笔真实状态完全相同，host/callbacks 释放。没有把 readiness 错误放进健康阶段的自动重试名单，也没有扩大预算。另一次同 PID CHECK→新 ACCEPT 已真实成功，后续冷进程 REJECT/CHECK 也已完成。

这是“错误请求正常终态＋新请求恢复”的验证，不把失败请求描述成已执行同意/拒绝。原 ledger、prefs、视频、日志和所有更早 B/F55/错误 ASI 候选均保留。当前验收只使用三个 `-checked.apk`，不用旧 F55 通过替代当前 fence。

## 5. 成品入口

- 完整当前源快照：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/GoogleUmpSilentConsentFormRunner.final.kt`
- 相对本轮起点的精确补丁：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/fix-from-round-baseline.patch`（已落地，无需重复应用）
- 源码/选择模板审计：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/source-audit.json`
- 源码→class→APK→ledger：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/artifact-audit.json`
- 独立渠道逐条覆盖与错误闭环：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/independent-aggregate.json`
- 当前 initial 分支验证：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/initial-branch-audit.json`
- 原始证据 SHA：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/evidence-sha256.json`
- 最终机器验收：`/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence/DELIVERY.json`

```sh
cd /Users/zy/Documents/Codex/ad-app
python3 app/src/test/ump-privacy-options/run.py
npm install --prefix app/src/test/ump-privacy-options --ignore-scripts
node app/src/test/ump-privacy-options/decision-script.cjs
```

测试依赖不进入生产应用。实际设备 driver、视频验收器及专项审计脚本均在 `/Users/zy/Documents/Codex/ad-app/build/ump-silent-final-20261108/terminal-fence`，新跑使用新的 suite/run_id，不覆盖原证据。

此前 app / ad-sdk-tcl / hq008-flow-sdk 双引擎矩阵继续引用 `/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-custom-preserving-batch-validation.md` 及其冻结交付。本轮只关闭当前明确列出的 UMP 剩余项，没有把历史 120 个非 UMP 场景冒充为都重跑过当前 fence APK。
