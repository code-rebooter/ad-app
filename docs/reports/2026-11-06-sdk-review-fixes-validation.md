# 2026-11-06 SDK 复审修复交付验证

七项复审问题已修复，并同步对应 app 逻辑。二合一 SDK 交付版本为 `com.smart.android:ad-sdk-tcl-combined:2026.11.06.1`，Flow SDK 为 2.1.5 / versionCode 15。Google 保持纯 IMA，未迁入自拼接方案。最低 Android API 24。

## 修复与对应验证

| 复审问题 | 修复 | 本轮验证 |
|---|---|---|
| IMA 延迟请求在释放后复活 | 全局排队所有权、入口代次与可取消 Runnable；app 按 requestId 撤销；完成/失败收尾期间的新请求延后，旧轮清理只操作原容器归属 | SDK 排队取消、只保留最新请求、释放旧实例不取消新轮；app 定向排队取消和重入 |
| 跳过/拒播被 Flow 示例记为成功 | 缓存 AD_ERROR / AD_COMPLETED，到最终回调且收到审计后再决定 session.failed/completed；处理 null 配置 | 空配置、缺失配置、拒播均一个 AD_ERROR，无 AD_COMPLETED/假填充，日统计为 FAILED，审计先于终态 |
| Google 取消后补发开始/加载 | 同步 report、trace、授权判断和期限回调后复查活性；加载通知移至 manager 复查之后 | AD_STARTED/AD_LOADED 报告内取消，无后续宿主开始/加载回调 |
| TCL 取消后补发填充/期限 | 执行事件、start 回调及 watchdog 返回后检查 closed；app 宿主 start 后检查 request 归属 | filled 回调内取消/替换，旧轮无 FILLED；新控制器正常完成 |
| Flow init 重入覆盖新回调 | init 捕获本次 runtime，主线程激活时核对 owner，操作捕获实例 | 内层 initialize 未被外层自动 start，显式 start 后调用正确内层回调，新轮正常完成 |
| 隐藏模式未透传 | 示例把 auth.isHiddenMode() 传入 execution | hidden 授权实际容器 alpha=0，业务终态 hiddenMode=true |
| Google 上报仍用 TCL 版本 | 初始化示例明确 Google/TCL 版本，说明动态二合一的产品版本约定 | Google 实际业务 HTTP 的 sdk_version 为 3.35.1；精确 README Java 示例对本轮 AAR 编译通过 |

Flow API 的 adSdkVersion 仍是初始化配置；没有引入逐 session 版本覆盖接口。动态切换二合一引擎时应使用与后台约定的产品版本，实际引擎诊断保留。Google/TCL 固定渠道示例分别明确 3.35.1 / 2.8.02。

## 构建与 JVM 回归

681 次 JVM 单测执行全部通过，0 失败、错误或跳过：补丁 33、TCL 71、迁入 IMA 66、共享 IMA core 32、Flow 14、app IMA 225、app TCL 240。共享用例在多个模块重复执行，不将该数字称为 681 个互不重复的场景。

另直接执行本轮编译后的类：12 项 SDK 生命周期回归及 5 项 app 排队回归全部通过。这些测试运行真实 manager 方法，使用确定性 Android 队列/时钟桩与反射构造前置状态；不代替播放器、网络或 OMID 真机验证。TCL 新增 6 项真实 Controller/TclExecution 重入测试；Flow 新增 6 项初始化行为测试，均包含在上方单测执行数内。

统一 AAR 发布构建、app 编译与 APK 构建通过。客户消费工程以 6 份 SDK AAR、5 份受控 Google AAR及 Flow AAR构建非 debuggable R8 APK，依赖图解析 118 个模块，未直接依赖 SDK 源码项目。独立客户 example 通过一个二合一 Maven 坐标离线完成 Release R8 构建。Flow release lint 0 错误 / 8 警告。`git diff --check` 通过。

## 真机结果

41 个最终接受场景全部通过：Google 路径 27 项，TCL 路径 14 项。包括直接 SDK 的真实宿主回调重入、Flow 实际 HTTP 与审计、以及 app module 的同步路径。覆盖原始三段 VAST、Wrapper、空素材、取消、停止后重启、完成时重启、回调超时、门禁拒绝/超时和两种引擎的 210 秒广告。

实际设备为 WS 与 mstar，两台 API 29。本轮复用本机已有 `build/device-ima-fixture/vast.xml`、MP4 及 TLS fixture，不等待远端随机填充。Google 排队用例同时核对旧轮独立 OMID 的 complete/sessionFinish，不以宿主完成回调代替测量收尾。

API 24 已核对配置与交付 AAR Manifest，本轮没有 API 24 实机结果；不以 API 29 结果代称 API 24 实测。生产竞价收益、生产报告入库和生产 CMP UI/UMP 不由本地 fixture 证明。

## 采集失败、隔离与冻结基线

首次 TCL 用例的原始播放器和本地业务记录实际完成，但 ADB 重连后 reverse 消失，测试采集器的 NO_PROXY 上传得到 ConnectException。已保留原失败结果和本地记录，恢复路由后以新用例名重新执行；此项没有修改 SDK 或补造事件。临时日志属性已恢复。

app Google 首次手工会话被测试 APK 的自动 AdProvider 调度在约 30 秒时替换，原失败记录保留。最终使用已冻结 Debug APK，由测试包自己的 run-as 身份停用其 AdProvider，force-stop 后重跑；仅隔离测试自动调度，不改变生产 provider。测试 sourceSet 也已加入移除该 provider 的 manifest 供以后构建使用，但最终已测 app APK 使用运行时停用方式。

工作区并行任务在本轮统一构建后修改了受控 IMA 音量桥接，触发后续测试配置重建的 hash 校验失败。该后续变更没有混入本交付：本轮 SDK 仓库、Flow AAR 和测试 APK 在 15:53–15:55 构建后冻结，交付的 11 份 AAR 与该冻结清单逐一相同。打包使用 `--frozen-repository --skip-build`，避免从共享源码目录刷新未经本轮验证的依赖。七项修复的源码已落盘；本报告的验证结论严格绑定下列二进制，不覆盖并行的其他改动。

## 二进制校验

| 已验证文件 | SHA-256 |
|---|---|
| `app-google-review.apk` | `e36ff02244b63a6c05604a5d6148e8ea841e9f25a13c10cd195f0730e558749b` |
| `app-tcl-review.apk` | `947a170179290f52ab63eb82b9c54367df578392ae19797fbff15c7d9988566e` |
| `consumer-review-r8.apk` | `e93145ac175b289a51b5bf599b25d4ea43b5dabff7394839d108a3b69f234bba` |
| `hq008-flow-sdk-v2.1.5-release.aar` | `68b0e21b79336e0f95b23b0afa87fa572c15f9d0fbbc5a80fe470f5bb08087e8` |
| `repository/com/smart/android/ad-sdk-tcl/2026.11.06.1/ad-sdk-tcl-2026.11.06.1.aar` | `bc3b5cce4531be52810f029ffa2ce31941fabd1aba6044cf510ba6925b7236c0` |
| `repository/com/smart/android/ad-sdk-tcl-combined/2026.11.06.1/ad-sdk-tcl-combined-2026.11.06.1.aar` | `7271177b116307ff50746c34b63c3cdde7f3ecb58ca055ec7f174edf4a6b0d56` |
| `repository/com/smart/android/ad-sdk-tcl-google-ima/2026.11.06.1/ad-sdk-tcl-google-ima-2026.11.06.1.aar` | `4eee5cd626cf2e20842e6410fd82c684fa030a65ac419305f5a1fb71b8fd4b49` |
| `repository/com/smart/android/ad-sdk-tcl-runtime-base/2026.11.06.1/ad-sdk-tcl-runtime-base-2026.11.06.1.aar` | `c858cb7185d165d325776fefac0034dedf04d481303c740dd93043dc4ecbd353` |
| `repository/com/smart/android/ad-sdk-tcl-runtime-media/2026.11.06.1/ad-sdk-tcl-runtime-media-2026.11.06.1.aar` | `a836923fae3b6036a4f0e3b4723e31c403a06cffc2f06c7b93e5e85578819df6` |
| `repository/com/smart/android/ad-sdk-tcl-runtime-player/2026.11.06.1/ad-sdk-tcl-runtime-player-2026.11.06.1.aar` | `010db71c0a8d0c42c0e7eebe8cb087ee4f6a860fb31219e7281c8f3e4a120c32` |
| `repository/com/smart/android/tclad/ima/controlled/interactivemedia/3.35.1/interactivemedia-3.35.1.aar` | `87d3add223dfb0a4b222e4dc59e98d33e6d6a0b6c0b9643219d0500c8765dbd3` |
| `repository/com/smart/android/tclad/ima/controlled/media3-exoplayer-ima/1.8.0/media3-exoplayer-ima-1.8.0.aar` | `5ebf67ce8fb61fba5d8e906a98a9f2f3a7c7806f6a1b097e8d16ef30550df35a` |
| `repository/com/smart/android/tclad/ima/controlled/play-services-ads-identifier/18.0.1/play-services-ads-identifier-18.0.1.aar` | `01e5bff6933f461899c5d4da4005f15c8345d80e727c730bf6edb2bbd5289721` |
| `repository/com/smart/android/tclad/ima/controlled/play-services-appset/16.0.2/play-services-appset-16.0.2.aar` | `268751b0285c397ca49936a298b58e4e8aa2748e2f1110e2daf4ac97bd43903b` |
| `repository/com/smart/android/tclad/ima/controlled/tv-ads/1.0.0/tv-ads-1.0.0.aar` | `c7b169c8b3ae2d823d75a57f7a31908ea7568b6a6a5af752640e05d7cc315e0e` |

客户二合一包：`build/ad-sdk-tcl-combined-2026.11.06.1.zip`。Flow 包：`build/hq008-flow-sdk-2.1.5.zip`。包内含各自接入文档、验证报告及 SHA256SUMS。Flow AAR 使用固定交付名 `hq008-flow-sdk-v2.1.5-release.aar`，内容与真机消费的冻结 AAR 完全相同。测试 APK、CA/私钥、local.properties、设备原始日志与 example/build 不包含在客户包内。

复现打包：

```sh
python3 ad-sdk-tcl/tools/package_customer_sdk.py --version 2026.11.06.1 \
  --output build/customer-sdk-review-fixes --skip-build --frozen-repository \
  --validation-report docs/reports/2026-11-06-sdk-review-fixes-validation.md
```

两个设备的本任务测试包、reverse 19446 和 fixture 服务已清理，初始代理、sample 和临时日志属性已恢复。WS 已按 initial-state 恢复原始前台；mstar 当前由并行任务的 devicevalidation 占用前台及 reverse 19447，已保留，没有强行中断，因此不声明该设备的初始前台恢复完成。首轮清理脚本的前台校验失败与后续精确复核均保留在恢复记录中。内部完整证据位于 `/Users/zy/Documents/Codex/ad-app-research-archive/2026-11-06-sdk-review-fixes`，包含逐轮 verification、HTTP、审计、单测、冻结二进制与恢复记录。

## 最终场景索引

| 路径 | 证据轮次 | 结果 |
|---|---|---|
| Google SDK / Flow / app | `review-sdk-google-original-pod` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-wrapper` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-inline-empty` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-cancel_started_report` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-cancel_loaded_report` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-pending_cancel` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-pending_latest` | 通过 |
| Google SDK / Flow / app | `review-sdk-google-pending_release_previous` | 通过 |
| Google SDK / Flow / app | `review-flow-google-missing-config` | 通过 |
| Google SDK / Flow / app | `review-flow-google-null-config` | 通过 |
| Google SDK / Flow / app | `review-flow-google-denied` | 通过 |
| Google SDK / Flow / app | `review-flow-google-hidden` | 通过 |
| Google SDK / Flow / app | `review-flow-google-normal` | 通过 |
| Google SDK / Flow / app | `review-flow-google-body-cancel` | 通过 |
| Google SDK / Flow / app | `review-flow-google-restart-stop` | 通过 |
| Google SDK / Flow / app | `review-flow-google-restart-complete` | 通过 |
| Google SDK / Flow / app | `review-flow-google-callback-timeout` | 通过 |
| Google SDK / Flow / app | `review-flow-google-long210` | 通过 |
| Google SDK / Flow / app | `review-flow-google-control_reject` | 通过 |
| Google SDK / Flow / app | `review-flow-google-cmp_reject` | 通过 |
| Google SDK / Flow / app | `review-flow-google-control_timeout` | 通过 |
| Google SDK / Flow / app | `review-flow-google-authorize_timeout` | 通过 |
| Google SDK / Flow / app | `review-r2-app-google-original-pod` | 通过 |
| Google SDK / Flow / app | `review-r2-app-google-empty` | 通过 |
| Google SDK / Flow / app | `review-r2-app-google-pending-cancel` | 通过 |
| Google SDK / Flow / app | `review-r2-app-google-cancel-started` | 通过 |
| Google SDK / Flow / app | `review-r2-app-google-reentrant` | 通过 |
| TCL SDK / Flow / app | `review-r2-sdk-tcl-original-pod` | 通过 |
| TCL SDK / Flow / app | `review-r2-sdk-tcl-wrapper` | 通过 |
| TCL SDK / Flow / app | `review-r2-sdk-tcl-inline-empty` | 通过 |
| TCL SDK / Flow / app | `review-r2-sdk-tcl-cancel_filled` | 通过 |
| TCL SDK / Flow / app | `review-r2-sdk-tcl-replace_filled` | 通过 |
| TCL SDK / Flow / app | `review-flow-tcl-init-reentry` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-normal` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-body-cancel` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-restart-stop` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-restart-complete` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-callback-timeout` | 通过 |
| TCL SDK / Flow / app | `review-r2-flow-tcl-long210` | 通过 |
| TCL SDK / Flow / app | `review-r2-app-tcl-original-pod` | 通过 |
| TCL SDK / Flow / app | `review-r2-app-tcl-empty` | 通过 |
