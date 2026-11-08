# 2026-11-06 二合一 SDK 客户交付验证

交付版本 `com.smart.android:ad-sdk-tcl-combined:2026.11.06`，包含 TCL 原厂 2.8.02 执行链路与 Google 纯 IMA 3.35.1 / Media3 1.8.0。未迁入自拼接方案。全部模块、flavor 和六份自有 SDK AAR 的最低版本统一为 API 24。

## 结论与实际覆盖

Google 37 项场景及 TCL 34 项场景全部通过；另完成异常恢复、真实默认后台 patch、响应体下载取消、动态音量和本地点击验证，以及同一进程 9 轮交接的 34 项断言。失败的旧候选、原始崩溃和测试传输积压记录均保留，未覆盖成成功记录。

实体设备为 `192.168.0.124:5555`（mstar）和 `192.168.0.125:5555`（WS），两台均 Android API 29、WebView 74.0.3729.186。Google 主矩阵在 WS，TCL 最终矩阵在 mstar；补充测试令两台均执行两套引擎。API 24 经过配置、Manifest、编译及 lint 核验，没有 API 24 实机结果。

测试 APK 通过客户本地 Maven 仓库的**一个坐标**消费六份 SDK AAR 和五份受控 Google AAR，开启 R8，非 debuggable。独立 example 也仅使用该坐标，从包内仓库加 Google/Maven Central/JitPack 完成 Release R8 构建。公共三方依赖首次构建需要对应仓库，交付不是单个 fat AAR。

TCL 主矩阵使用修复原厂空素材异常后的二合一 APK。之后修改 Google 的会话连接取消和测试宿主音量探针，交付 AAR 中仅 Google SDK AAR 与受控 IMA AAR 改变，TCL 与三份运行时 AAR 保持相同。最终 APK 重新执行 Google 全部 37 项及 TCL 正常/空素材/真实后台/取消/音量/交接等补充场景；两台均有最终 APK 的 TCL 播放。主矩阵 APK 与最终 APK 哈希分别列在下文，不把两个 APK 表述为相同文件。

## 实际素材与观测方法

使用本机已有 `build/device-ima-fixture/vast.xml` 及 20/30/15 秒的三个 MP4。原件未修改；TLS 本地服务仅将原有本地 URL 映射到 ADB reverse 地址并附加轮次标识。原始三段 VAST 不添加测量节点；OMID/ActiveView 使用另外明确加入验证节点的独立用例。

判定同时核对公共 SDK 业务回调、终态审计、原厂播放器快照、本地服务实际 VAST/素材/tracking HTTP，以及 Google 独立验证脚本实际收到的 OMID 回调和 ActiveView HTTP。测试采集器的批量上传仅传输真实记录，不补造 SDK 或测量事件。R8 APK 不使用 run-as 日志作为必需证据。

原始三段用例要求恰好三个广告实例/片段、逐段完成、全部素材实际读取、impression/start/四分位/complete 跟踪存在。TCL 原厂可能向同一跟踪地址重复发送；保留原厂 tracking 语义，不把“完整生命周期存在”误称为所有原厂 HTTP 都恰好一次。宿主 start/load/终态按轮次去重。

Google 音量四种组合核对实际播放器音量，以及独立 OMID start 的 deviceVolume/mediaPlayerVolume/videoPlayerVolume 和 ActiveView a/mv/nv；上报音量与物理播放音量分别执行配置。OMID 测试检查 sessionStart、loaded、impression、start、firstQuartile、midpoint、thirdQuartile、complete、sessionFinish。Wrapper、WebView fallback、提前 release 和回调内重入均有实际运行记录。

原始文件 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `ad-1.mp4` | `3c2115832ac7865a628a2f5ac30055f63d5e077d6fb21981c1948bf1b01f470a` |
| `ad-2.mp4` | `ae908e05dd7e9d686269e67818f20eab99041d8ef975c8542a006019d8e836f3` |
| `ad-3.mp4` | `49cb8e7caadc57391dfa86354cc6d89f0f21e333cb8f3bc553d9a5ae4edbe32f` |
| `vast.xml` | `393c80b32d67db8451d492b6628e92507b875fbdd45ba65e4cc8195045be98aa` |

## 本轮发现并修复的问题

1. TCL 完成后的迟到播放器快照会重新创建已结束片段，三段广告可能记成五段。已按完成实例标识隔离，最终三段用例恰好三段。
2. TCL 取链 callTimeout 同样令 OkHttp isCanceled 为 true，曾被误当主动取消而吞掉错误。已独立记录宿主取消，超时正常回调错误，主动取消仍不误报失败。
3. TCL 空 InLine 在原厂素材构建方法内对 null Creative 列表调用 size，导致真实进程崩溃。SDK 仅在 `c/d.a(VastRequest, Ad, AdMaxDuration)` 边界捕获 RuntimeException，保留参数身份、记录 vast_parse_failed 并返回 null，进入原厂调用者已有的素材失败分支。正常解析/选材/tracking 不替换，Error 仍传播；本次二合一交付验证时 app 的观察补丁尚未启用此恢复，后续已按用户要求同步，详见 [app 与流程 SDK 回同步验证](2026-11-06-app-flow-backport-validation.md)。ASM 执行测试同时覆盖混淆代码复用参数局部槽位，R8 实际构建通过。
4. Google 原先将 Wrapper/空 InLine 误作有效填充；现需 InLine 且包含非空 MediaFile。两种空响应在真机均无虚假 FILLED。
5. Google 完成后立即 release 或完成回调内请求下一条，可能截断测量收尾。结算期调整为 1500ms，先安装结算任务再发宿主完成报告；受控 IMA 的实际 WebView.destroy 经过有上限的延后销毁。独立验证脚本实际收到 sessionFinish。[IAB Android OM SDK 文档](https://interactiveadvertisingbureau.github.io/Open-Measurement-SDKAndroid/) 说明结束后需要给验证脚本处理事件的时间；实现保留至少 1 秒所需窗口，本轮使用 1500ms，不伪造测量事件。
6. Google 宿主取消后，原生 XML 连接仍在等待响应体。现用会话归属的连接登记和线程作用域，在结束时关闭实际 HttpURLConnection；正常/异常返回都恢复线程作用域，迟到新连接立即拒绝，已完成连接不长期保留。WebView patch 取回同样登记。最终真机在 270KB 部分 XML 后取消，服务端观察 peer close。
7. 二合一消费时共享依赖的原厂严格约束与新 IMA 库冲突，已将对外约束改为最低要求；原厂自身构建仍校验固定基线。109 个模块的客户依赖图成功解析，缺失可选类使用明确 consumer rules，Release R8 通过。

原厂崩溃记录：`network-closure/native-crash-evidence.txt`。REA 静态证据位于审计归档 `rea/`：c/d.a 第 5 重载说明空列表访问；c/g.a 第 1 重载说明原厂已有 null 素材失败分支。静态分析只用于修复依据，真机异常回调与同进程后续播放才是恢复证明。

测试工具曾因串行上传大量观察事件造成终态采集积压，已改为业务事件即时上传、观察日志批量传输及四工作线程；原始积压失败保留。此类传输失败与上述真实产品崩溃分别记录。

## 场景逐项结果

| 引擎 | 场景 | 结果 | 证据轮次 |
|---|---|---|---|
| Google | original-pod | 通过 | `google-final-transport-original-pod` |
| Google | wrapper | 通过 | `google-final-transport-wrapper` |
| Google | wrapper-empty | 通过 | `google-final-transport-wrapper-empty` |
| Google | inline-empty | 通过 | `google-final-transport-inline-empty` |
| Google | empty | 通过 | `google-final-transport-empty` |
| Google | invalid | 通过 | `google-final-transport-invalid` |
| Google | patch-http | 通过 | `google-final-transport-patch-http` |
| Google | patch-json | 通过 | `google-final-transport-patch-json` |
| Google | patch-missing | 通过 | `google-final-transport-patch-missing` |
| Google | patch-rejected | 通过 | `google-final-transport-patch-rejected` |
| Google | patch-redirect | 通过 | `google-final-transport-patch-redirect` |
| Google | media404 | 通过 | `google-final-transport-media404` |
| Google | media-invalid | 通过 | `google-final-transport-media-invalid` |
| Google | pause-resume | 通过 | `google-final-transport-pause-resume` |
| Google | pause-timeout | 通过 | `google-final-transport-pause-timeout` |
| Google | buffer-resume | 通过 | `google-final-transport-buffer-resume` |
| Google | buffer-timeout | 通过 | `google-final-transport-buffer-timeout` |
| Google | cancel-patch | 通过 | `google-final-transport-cancel-patch` |
| Google | cancel-playing | 通过 | `google-final-transport-cancel-playing` |
| Google | audit-off | 通过 | `google-final-transport-audit-off` |
| Google | completion-release | 通过 | `google-final-transport-completion-release` |
| Google | patch-delay | 通过 | `google-final-transport-patch-delay` |
| Google | patch-timeout | 通过 | `google-final-transport-patch-timeout` |
| Google | long210 | 通过 | `google-final-transport-long210` |
| Google | sound00 | 通过 | `google-final-transport-sound00` |
| Google | sound01 | 通过 | `google-final-transport-sound01` |
| Google | sound10 | 通过 | `google-final-transport-sound10` |
| Google | sound11 | 通过 | `google-final-transport-sound11` |
| Google | startup-timeout | 通过 | `google-final-transport-startup-timeout` |
| Google | vast-timeout | 通过 | `google-final-transport-vast-timeout` |
| Google | hidden | 通过 | `google-final-transport-hidden` |
| Google | sound-missing | 通过 | `google-final-transport-sound-missing` |
| Google | sound-string0 | 通过 | `google-final-transport-sound-string0` |
| Google | sound-invalid | 通过 | `google-final-transport-sound-invalid` |
| Google | fallback | 通过 | `google-final-transport-fallback` |
| Google | missing-config | 通过 | `google-final-transport-missing-config` |
| Google | denied | 通过 | `google-final-transport-denied` |
| TCL | original-pod | 通过 | `tcl-final-guard-original-pod` |
| TCL | wrapper | 通过 | `tcl-final-guard-wrapper` |
| TCL | wrapper-empty | 通过 | `tcl-final-guard-wrapper-empty` |
| TCL | inline-empty | 通过 | `tcl-final-guard-inline-empty` |
| TCL | empty | 通过 | `tcl-final-guard-empty` |
| TCL | invalid | 通过 | `tcl-final-guard-invalid` |
| TCL | patch-http | 通过 | `tcl-final-guard-patch-http` |
| TCL | patch-json | 通过 | `tcl-final-guard-patch-json` |
| TCL | patch-missing | 通过 | `tcl-final-guard-patch-missing` |
| TCL | patch-rejected | 通过 | `tcl-final-guard-patch-rejected` |
| TCL | patch-redirect | 通过 | `tcl-final-guard-patch-redirect` |
| TCL | media404 | 通过 | `tcl-final-guard-media404` |
| TCL | media-invalid | 通过 | `tcl-final-guard-media-invalid` |
| TCL | pause-resume | 通过 | `tcl-final-guard-pause-resume` |
| TCL | pause-timeout | 通过 | `tcl-final-guard-pause-timeout` |
| TCL | buffer-resume | 通过 | `tcl-final-guard-buffer-resume` |
| TCL | buffer-timeout | 通过 | `tcl-final-guard-buffer-timeout` |
| TCL | cancel-patch | 通过 | `tcl-final-guard-cancel-patch` |
| TCL | cancel-playing | 通过 | `tcl-final-guard-cancel-playing` |
| TCL | audit-off | 通过 | `tcl-final-guard-audit-off` |
| TCL | completion-release | 通过 | `tcl-final-guard-completion-release` |
| TCL | patch-delay | 通过 | `tcl-final-guard-patch-delay` |
| TCL | patch-timeout | 通过 | `tcl-final-guard-patch-timeout` |
| TCL | long210 | 通过 | `tcl-final-guard-long210` |
| TCL | sound0 | 通过 | `tcl-final-guard-sound0` |
| TCL | sound1 | 通过 | `tcl-final-guard-sound1` |
| TCL | guarantee | 通过 | `tcl-final-guard-guarantee` |
| TCL | address-http | 通过 | `tcl-final-guard-address-http` |
| TCL | address-invalid | 通过 | `tcl-final-guard-address-invalid` |
| TCL | address-rejected | 通过 | `tcl-final-guard-address-rejected` |
| TCL | address-timeout | 通过 | `tcl-final-guard-address-timeout` |
| TCL | cancel-address | 通过 | `tcl-final-guard-cancel-address` |
| TCL | direct | 通过 | `tcl-final-guard-direct` |
| TCL | patch-disabled | 通过 | `tcl-final-guard-patch-disabled` |

## 补充闭环

| 轮次 | 结果 |
|---|---|
| `delivery-final-google-body-cancel` | 通过 |
| `delivery-final-tcl-unmute` | 通过 |
| `delivery-final-google-audit-off-body` | 通过 |
| `delivery-final-tcl-cross-empty` | 通过 |
| `delivery-final-tcl-cross-real-pod` | 通过 |
| `delivery-final-tcl-empty` | 通过 |
| `delivery-final-tcl-real-pod` | 通过 |
| `delivery-final-google-real-pod` | 通过 |
| `delivery-final-google-body-cancel-cross` | 通过 |
| `delivery-final-tcl-body-cancel-cross` | 通过 |
| `delivery-final-tcl-unmute-repeat` | 通过 |
| `delivery-final-tcl-mute` | 通过 |
| `delivery-final-google-click-1` | 通过 |
| `delivery-final-google-click-2` | 通过 |
| `delivery-final-google-click-3` | 通过 |
| `delivery-final-google-click-4` | 通过 |
| `delivery-final-google-click-5` | 通过 |

同进程交接 `delivery-final-transitions`：9 轮、34 项断言全部通过。包含 Google/TCL 各自替换、旧 patch 迟到、旧实例 release 不影响新轮、完成回调重入、TCL 取消后 Google 完成再 TCL 完成；主进程 PID `9341` 保持不变。Google 重入的下一轮 requestCreatedAt 比上一轮 finishedAt 晚 至少 1400ms，且旧终态回调先于新请求。

TCL 空 InLine 后紧接真实默认 patch 的原始三段广告，前后 PID 均 `9341`，后续完整播放通过。响应体取消用例先实际接收超过 256KB 的未完成 XML，再释放，服务端观察到 peer close；无 FILLED、完成或错误误报。

长广告设置 180 秒请求预算、210 秒素材，Google 实际审计最大位置 `200863`ms，TCL `209007`ms，均超出请求期限而完整结束。TCL 的 configured_volume_percentage 是原厂初始配置，不是动态音量。动态静音/恢复另通过测试宿主直接读取实际 UniVideoPlayerImpl 的原厂播放器委托 getVolume，核对 0→1 与 1→0；原厂委托 setVolume 继续转发底层播放器。此结果验证播放器状态和调用链，没有录制扬声器声压。旧测试误用初始配置字段造成的失败保留。

暂停超时与缓冲停滞为独立失败用例，恢复用例要求实际恢复后完成。

本地点击在 5 次自然概率试验后选中 `delivery-final-google-click-5`，仅使用本地测试广告与本地落地页；不强制随机命中。证据要求 20–80% 目标进度、实际进度达到目标、source=ui_route、一个 AD_FAKE_CTR 和一个实际 /pixel/click，并保持完整测量生命周期。命中目标 41.19%，实际触发 41.93%。详见内部 `delivery-final-click-verification.json`。

## 构建与二进制对应

195 项 JVM 测试通过：buildSrc 32、TCL 65、迁入 Google 66、共享原 IMA 核心 32；无失败、错误或跳过。TCL lint 0 错误/11 警告，Google lint 0 错误/21 警告。受控依赖 --check、原厂隔离与资源核验、app 纯 IMA Kotlin 编译、git diff --check 通过。lint 警告没有被表述为零警告。

TCL 主矩阵 APK SHA-256：`c66855d7b6b2e7149c842e5fbed7aaa4d21c096a34a905e2eb6bec5b75a1d4ff`。

最终二合一 R8 测试 APK SHA-256：`a82b84d010e7fb3d1863fdcf76a136ccb6c253f02201db2181d56b7001972c7b`。各普通测试开始时由 APK 自算 SHA 并上传，已逐项与上述预期核对。最终 SDK 文件冻结后打包，校验与测试所用 Maven 文件一致。

| 最终文件 | SHA-256 |
|---|---|
| `build/customer-sdk/repository/com/smart/android/tclad/ima/controlled/play-services-ads-identifier/18.0.1/play-services-ads-identifier-18.0.1.aar` | `01e5bff6933f461899c5d4da4005f15c8345d80e727c730bf6edb2bbd5289721` |
| `build/customer-sdk/repository/com/smart/android/tclad/ima/controlled/play-services-appset/16.0.2/play-services-appset-16.0.2.aar` | `268751b0285c397ca49936a298b58e4e8aa2748e2f1110e2daf4ac97bd43903b` |
| `build/customer-sdk/repository/com/smart/android/tclad/ima/controlled/media3-exoplayer-ima/1.8.0/media3-exoplayer-ima-1.8.0.aar` | `5ebf67ce8fb61fba5d8e906a98a9f2f3a7c7806f6a1b097e8d16ef30550df35a` |
| `build/customer-sdk/repository/com/smart/android/tclad/ima/controlled/tv-ads/1.0.0/tv-ads-1.0.0.aar` | `c7b169c8b3ae2d823d75a57f7a31908ea7568b6a6a5af752640e05d7cc315e0e` |
| `build/customer-sdk/repository/com/smart/android/tclad/ima/controlled/interactivemedia/3.35.1/interactivemedia-3.35.1.aar` | `87d3add223dfb0a4b222e4dc59e98d33e6d6a0b6c0b9643219d0500c8765dbd3` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl-google-ima/2026.11.06/ad-sdk-tcl-google-ima-2026.11.06.aar` | `c95fcbc6bf56138b7b6c8519f5595ff6f9c5eb4c34160393214a649e0664d2b3` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl/2026.11.06/ad-sdk-tcl-2026.11.06.aar` | `25ce486f2f9410aa0905f31df60ac7bb8b1369a85bb9a5713e5ee282a63f5c94` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl-runtime-base/2026.11.06/ad-sdk-tcl-runtime-base-2026.11.06.aar` | `c858cb7185d165d325776fefac0034dedf04d481303c740dd93043dc4ecbd353` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl-combined/2026.11.06/ad-sdk-tcl-combined-2026.11.06.aar` | `7271177b116307ff50746c34b63c3cdde7f3ecb58ca055ec7f174edf4a6b0d56` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl-runtime-media/2026.11.06/ad-sdk-tcl-runtime-media-2026.11.06.aar` | `a836923fae3b6036a4f0e3b4723e31c403a06cffc2f06c7b93e5e85578819df6` |
| `build/customer-sdk/repository/com/smart/android/ad-sdk-tcl-runtime-player/2026.11.06/ad-sdk-tcl-runtime-player-2026.11.06.aar` | `010db71c0a8d0c42c0e7eebe8cb087ee4f6a860fb31219e7281c8f3e4a120c32` |
| `ad-sdk-tcl/google-ima/src/test/consumer-smoke/build/outputs/apk/deviceRelease/google-ima-consumer-smoke-deviceRelease.apk` | `a82b84d010e7fb3d1863fdcf76a136ccb6c253f02201db2181d56b7001972c7b` |

客户 ZIP 包含六份自有 AAR、五份受控 Google AAR及各自 POM/Gradle metadata、文档、独立 Java/R8 示例和 SHA256SUMS。测试 CA、测试网络配置、私钥、原始采集记录、本机 local.properties 和示例构建目录不在客户 ZIP。客户交付包不包含该测试 APK。

## 外部条件与未声明覆盖

Google 官方远程示例的 asset.gvt1-cn.com 素材曾实际返回 HTTP 404，旧记录保留在此前 minSdk24 验证归档；未宣称这些远程素材已恢复或通过。本轮用现成的本地 VAST 及真实默认 patch 继续验证了完整 SDK 流程。生产 authorize、CMP/UMP、实际竞价填充、收益结果以及客户生产报告服务的入库不由本地测试证明；宿主继续负责业务授权、调度、报告持久化/HTTP 发送与重试。

TCL 可选缓存 Proxy 插件未在原厂输入内提供，不声明已测试该插件。Google 专有 OMID/ActiveView 和上报音量不是 TCL API 能力，不将其作为 TCL 测试结果。

## 测试环境恢复与证据位置

只卸载本任务测试包 com.smart.smoke.ima.devicevalidation / com.smart.smoke.ima.releasevalidation，移除本任务 tcp:19443/19444/19445 reverse，停止本任务 TLS 服务；两台原代理和 sample 属性与初始记录一致，恢复各自原启动界面。恢复后核验通过，见内部 restoration.json。

完整审计归档：`/Users/zy/Documents/Codex/ad-app-research-archive/2026-11-06-combined-sdk-delivery`。主场景的 matrix.json、每轮 verification.json/server-events.jsonl/server-runtime-audit.json、补充交接及失败历史都保存在此；私钥和设备细节留在内部归档，不随客户 ZIP 发出。客户包通过本文的用例编号、版本及二进制 SHA 保留验收对应关系。
