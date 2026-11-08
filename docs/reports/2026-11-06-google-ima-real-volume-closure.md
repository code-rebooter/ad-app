> **范围纠正（2026-11-06）**：本报告测试的是被我擅自修改音量策略后的版本，不是用户原有策略的闭环验证。不能据此判定原有独立上报策略是 Bug，也不能据此宣称原逻辑已通过。未经确认而关闭预加载的改动已撤销并重建受控 AAR。其余音量策略变更尚未完整恢复；具体状态见 `outputs/ima-volume-scope-recovery-20261106/STATUS.md`。下文保留为历史证据，不覆盖失败记录。

# Google IMA 真实音量修复与验证记录

日期：2026-11-06。设备：`192.168.0.124:5555`，Android 29。运行时：IMA 3.35.1、Media3 1.8.0；独立 OM 接收端实际记录的 Core 为 3.539.0。

## 结论

已修复 App 与独立 SDK 的实际音量传递、声明一致性和观察范围；**没有将整项验证标记为全部通过**。严格矩阵额外发现有声连续三广告的 ActiveView 首次测量音量异常，保留失败，不改写 SDK 测量值，也没有删除这项断言。

## 实现

1. 初始 `vpmute`、`adWillPlayMuted` 和 backend patch 请求按实际 `soundEnabled` 生成。弃用的 `setReportedSoundEnabled` 保留 ABI，但不能与实际播放状态矛盾。
2. 固定版本 Media3 适配器在真实广告进度入口及实际音量变化时，调用 IMA 的真实 `onVolumeChanged(AdMediaInfo, percentage)`。按广告实例去重，新广告重新发送初始值；没有活动广告、已释放、非法值不发送。失败的调用可重试。
3. 系统音量通过真实 AudioManager 读取；播放器音量和系统音量保持独立。OM 参数原样传递。原先独立覆盖 OM / ActiveView 音量的逻辑已移除。
4. 观察脚本只读记录 OM start / volumeChange，保留原消息、参数、对象和返回值。业务审计明确区分 IMA 输入、真实回调、OM 服务输入与独立接收端。
5. `volume_chain_audit` 对实际播放器、IMA 输入、回调和 OM 媒体末值分别比较；任一已观测矛盾为 `observed_mismatch`；缺少 OM 观察保持 `unknown_missing_observation`。末值一致不代表整段历史一致，变化另有时间线。
6. 媒体预加载改为串行加载。静音三广告原本出现的 ActiveView 非零值和重复 OM loaded 已在对照中消失；不是通过改造接收值实现的。切段等待真实素材加载。

核心代码：

- `/Users/zy/Documents/Codex/ad-app/ad-sdk-google-ima/src/main/java/com/smart/android/googleima/internal/GoogleImaRuntimeBridge.java`
- `/Users/zy/Documents/Codex/ad-app/ad-sdk-google-ima/src/main/java/com/smart/android/googleima/internal/GoogleImaVolumeEvents.java`
- `/Users/zy/Documents/Codex/ad-app/buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy`
- `/Users/zy/Documents/Codex/ad-app/app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaRuntimeAuditReporter.kt`
- `/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaRuntimeAuditReporter.kt`

## 已观测验证

### 自动化与构建

| 套件 | 执行数 | 失败 / 错误 / 跳过 |
|---|---:|---:|
| core | 41 | 0 / 0 / 0 |
| 独立 Google SDK（包含 core 测试） | 84 | 0 / 0 / 0 |
| App pure IMA | 234 | 0 / 0 / 0 |
| buildSrc 定点字节码测试 | 8 | 0 / 0 / 0 |
| 只读 JS 观察协议 | 11 | 0 / 0 / 0 |

共 367 次模块测试执行，不代表 367 项互不重复的测试。Debug 和 R8 deviceRelease 均构建成功。core lint：0 errors、14 warnings。五份独立运行时 AAR 同步检查通过。

### 真机全量矩阵：final3

| 用例 | 实际结果 |
|---|---|
| 播放器静音 / 系统有声 | 通过 |
| 播放器有声 / 系统有声 | 通过 |
| 播放器有声 / 系统静音 | 通过 |
| 播放器静音 / 系统静音 | 通过 |
| 播放器 0 → .35 → 1 → 0 | 通过 |
| 系统音量变化，播放器保持 1 | 通过 |
| 暂停时改音量再恢复 | 通过 |
| 静音三广告 | 通过，OM loaded 严格唯一 |
| 有声三广告 | **失败：ActiveView_actual_audio_value** |
| 跨三广告切换音量 | 通过 |
| 中途取消 | 通过，无伪造 complete |
| 空 VAST | 通过，缺观察不报成功 |
| 无 verification 脚本 | 失败：新增跨层比较把缺 OM 情况分类为 consistent_but_incomplete；已修复并用 final4 复测通过 |
| 关闭业务审计 | 通过，独立接收端仍可验证实际 OM |

该轮实际 12 / 14，通过结果和失败结果原样保留。最终的缺 OM 修正是审计分类和测试宿主 JSON null 保留，不修改播放器、IMA 输入或 OM 消息。

### 最新 R8 回归：release-final4

实际 **6 / 7 通过，1 项失败**。静音、动态播放器音量、暂停改音量、静音三广告、取消、无 verifier 均通过；有声三广告仍失败 `ActiveView_actual_audio_value`。当前 Debug 的缺 OM 专项复测 1 / 1 通过。两种最新 APK 的全部测试启动摘要均与构建 SHA-256 一致。

最新两轮合计 7 / 8 通过；这不是“完整 14 项已在最新 APK 全部重跑”的结论。媒体传递路径没有在 final3 后修改，后续修正仅为审计分类和测试宿主 JSON null 保留。独立 OM 实收、严格 loaded 唯一性和实际音量序列仍作为独立断言，未为首条 ActiveView 异常放宽。


## 尚未解决：有声三广告 ActiveView 首值

`final3-three_ads_audible` 的实际播放器为 1，系统为 4 / 15；独立 OM 三段 start 均实收 `mediaPlayerVolume=1, deviceVolume=.27, videoPlayerVolume=1`。同轮第一段 ActiveView 的首条 `e=15, vpt=0` 为 `a=1,mv=1,nv=1`，之后为约 `.266`；第二、三段均约 `.266`。

本地 Core 参考代码显示 native viewability 回调异步填充 volume，存在初始数据未到达的时间窗；**尚未证明这一次首值的完整上游时序因果，也没有把它描述成已修复**。严格断言继续失败。OM 接收正确不能抵消 ActiveView 的这项不一致。

## 证据与覆盖边界

证据根目录：`/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-closure-20261106`。

- `device/final3-matrix.json`：14 项完整矩阵及逐断言结果。
- `device/final4-no_verifier/volume-verification.json`：缺 OM 修正的实际复测。
- `device/*/live-server-events.jsonl`：本地独立验证脚本实际收件，非客户端“已发送”推断。
- `device/*/server-runtime-audit.json`：客户端审计，明确不是独立接收端确认。
- `volume-pod-rea-evidence.json`：固定 APK 字节码方法和串行加载设置的静态证据。
- `final-unit-test-summary.json`、`final-observer-tests.log`、`final-missing-om-build.log`：构建及测试。
- `final4-apk-manifest.json`、`apks/`：最新验证 APK 和摘要。
- `baseline/`、`task-only-source.diff`：任务前快照与独立差异；没有重置其他未提交工作。

仅使用本地 VAST、素材、tracking 和 verification 接收端，`enable_fake_ctr=0`，可见播放。没有用业务后台成功上传替代独立 OM 收件证据；没有把 getter 的静态存在当作执行覆盖。DAI getter / 消息路径不是客户端 VAST 的必经路线，列为不适用。未据此断言线上 Google 匹配率下降原因。

## 运行入口

```sh
cd /Users/zy/Documents/Codex/ad-app
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew -PgoogleAdRuntime=ima \
  -I ad-sdk-tcl/google-ima/tools/consumer-smoke.init.gradle \
  -I ad-sdk-google-ima/src/test/device/volume-validation.init.gradle \
  :ad-sdk-google-ima:testDebugUnitTest \
  :ad-sdk-tcl:google-ima:testDebugUnitTest \
  :app:testGoogle_ad_tv_desktop_ambientlightDebugUnitTest \
  :ad-sdk-google-ima:lintDebug \
  :google-ima-consumer-smoke:assembleDebug \
  :google-ima-consumer-smoke:assembleDeviceRelease

python3 ad-sdk-tcl/google-ima/tools/prepare_controlled_runtime.py --check
node ad-sdk-google-ima/src/test/js/omid-volume-observer.test.cjs

# 已启动本地 TLS fixture server 并配置 adb reverse 19447 时：
python3 ad-sdk-google-ima/src/test/device/run-volume-matrix.py \
  --serial 192.168.0.124:5555 \
  --output outputs/ima-volume-closure-20261106/device \
  --prefix NEW_UNIQUE_PREFIX
```

测试前缀必须唯一；脚本拒绝覆盖旧尝试。设备重启、命令失败和恢复失败均保留失败记录。未提交、推送或发布代码。

## 清理结果

已验证：系统媒体音量恢复 0，代理保持原值，移除本次 19447 reverse，原有 19446 reverse 已恢复；两个本次安装的验证包已卸载，本次 fixture server 已停止。未修改客户 App，未提交或推送代码。
