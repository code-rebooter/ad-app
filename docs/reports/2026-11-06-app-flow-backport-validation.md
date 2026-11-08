# 2026-11-06 app 与 HQ008 Flow SDK 回同步修复验证

本轮已将二合一 SDK 测试中确认的修复同步到 app module，并补齐 `hq008-flow-sdk` 的会话接入和终态收尾。Google 使用纯 IMA；未迁移自拼接方案。最低 Android API 24。

## 同步内容

- app TCL：在原厂素材构建边界恢复空 Creative/InLine 触发的 RuntimeException，返回原厂已有的素材失败分支并给出 `vast_parse_failed`。这包含实际崩溃恢复，并非只改日志。参数槽位复用由 ASM 执行测试覆盖；Error 仍传播。
- app TCL：拒绝已结束广告的迟到快照，三段广告保持三个片段，避免重复创建和预算续期。
- app 渠道归属：补齐 TCL LOADED 与 IMA 阶段报告的 requestChannelId，正常、失败和下载取消三种结果均额外核对实际 HTTP 载荷保留绑定的渠道。
- app 纯 IMA：Wrapper/空 InLine 不再造成虚假填充；完成结算保留至少 1500ms 窗口，先安装结算任务再通知业务，支持完成报告内 release/重入；会话结束关闭在途 XML 响应体连接。受控 IMA WebView 收尾由共享 runtime 补丁统一处理。
- Flow SDK：授权快照、CMP 门禁、动态回调时限、播放期限延长、真实 REQUESTED、阶段诊断和原始运行时审计均绑定 session；停止、超时、完成时释放对应广告；释放回调中启动下一轮或替换 runtime 时保留新轮所有权，旧诊断和审计仍归旧轮。
- Flow SDK：REQUESTED/ERROR 先持久化，再由 WorkManager 投递和重试；流控连接取消与上报使用独立 client，停止流程不会取消自己的错误上报。发布版本为 2.1.4 / versionCode 14。

Flow SDK 本身负责流控和报告，TCL 与 IMA 播放由二合一 SDK 执行。接入示例补充了两套引擎的 session 转发；Google 必须在完成测量结算后的 `onAdCompleted()` 中完成 session。

## 本轮真机结果

实体设备为 mstar 和 WS，均 API 29。复用本机已有 `build/device-ima-fixture/vast.xml` 与三段 MP4，通过 TLS fixture 和 ADB reverse 提供可重现响应，未等待远端随机填充。

| 路径 | 用例数 | 结果 |
|---|---|---|
| app 纯 IMA | 17 | 全部通过 |
| app TCL | 11 | 全部通过 |
| Flow SDK + 纯 IMA | 17 | 全部通过 |
| Flow SDK + TCL | 13 | 全部通过 |
| app 最终渠道回归 IMA | 3 | 全部通过 |
| app 最终渠道回归 TCL | 3 | 全部通过 |

合计 58 个主用例及 6 个最终渠道回归用例通过。覆盖原始三段播放、Wrapper、空 InLine/空 Wrapper、非法响应、patch/素材错误、XML 响应体下载中取消、播放中取消、210 秒长广告；app IMA 另测完成时 release、完成报告内重入和四种音量组合；Flow SDK 另测停止后重启、完成时重启、initialize 重入替换、回调超时，以及流控/CMP 拒绝和网络超时。

附加独立核验：

- 四条原始三段路径均有恰好三个完成片段、三份 MP4 实际请求，逐段 impression/start/四分位/complete 跟踪齐全。TCL 原厂跟踪可能重复，本轮核对生命周期存在，不将所有原厂 HTTP 表述为恰好一次。
- app Google 四种音量组合同时核对实际播放器音量、独立 OMID start 三个音量字段和 request vpmute。
- app Google 完成报告内重入，两轮各有一次终态和审计，独立 OMID 收到 sessionFinish；下一轮请求在旧完成报告后 3564ms、旧完成回调后 35ms 开始。
- Flow SDK 的 Google 审计在流程业务终态前保留完整 `IMA_RUNTIME_AUDIT`；重入用例核对旧轮释放诊断和新轮 request 身份。
- 持久化恢复：使 REQUESTED/ERROR 首次真实 HTTP 均失败，再 force-stop 测试进程；启动新进程后，两条旧报告补发成功，保留旧 request/channel 和取消时诊断；恢复轮流控拒绝，未授权或发起广告。

主用例使用真实回调、审计和 fixture HTTP 交叉判定。主动调用测试命令产生的取消标记与 SDK 业务终态分别核对，不以测试命令返回作为播放完成证明。

## 测试接入问题与历史记录

TCL app 主动取消按现有协议上报一次 `AD_ERROR`、message=`CANCELLED`。最初断言错误地要求完全没有 ERROR，修正测试协议后以新用例名复测通过，产品取消协议未为此修改。

app IMA 重入测试最初未开启下一轮采集，导致第二轮审计未被测试宿主上传；修正测试宿主，在新轮真实 REQUESTED 时开启采集后复测通过。此变更只涉及测试 sourceSet。

首次 Flow AAR 消费工程漏加 WorkManager，真实设备出现 NoClassDefFoundError。已按集成文档显式声明依赖，重新 R8 构建并完成全部 Flow 场景；失败日志保留。直接 `files(AAR)` 不携带传递依赖，客户宿主必须声明 README 中依赖。

未授权轮次使用 SDK 生成的 client requestId，审计本来按此身份上传；最初门禁测试错误地在测试轮次名目录中查找。修正采集断言为按 channel 查找并核对 client requestId、trace requestId 一致后，四种门禁用例重新验证。

原始失败、修正后的 accepted matrix 和补充验证分开保存，未将旧失败结果覆盖成成功。

## 构建与交付对应

506 次相关 JVM 单测执行全部通过：补丁层 33、app 纯 IMA 225、app TCL 240、Flow SDK 8，无失败、错误或跳过。默认 Google independent 路径另外 199 次回归执行通过。Flow release lint 0 错误 / 8 警告；AAR release、消费 APK Release R8 构建及 `git diff --check` 通过。

Flow 真机矩阵通过客户 Maven 二合一坐标与本地 Flow AAR 消费，非源码项目依赖，开启 R8、非 debuggable；测试 APK 显式声明 WorkManager。冻结交付 AAR 为 `hq008-flow-sdk-v2.1.4-20261106_1429-release.aar`，与测试消费依赖一致；后续自动时间戳构建不作为替换交付物。

| 验证文件 | SHA-256 |
|---|---|
| `app-final-google.apk` | `620e088be87a302170e6449bd8bd682bf578256aa647bea99f72a2a12aa963bd` |
| `app-final-tcl.apk` | `ebc41331501463db0a3f5a4b1e97382c8e266cdd817c485b90fa6dfafd209647` |
| `app-google-trace.apk` | `92237d9708ccf9955119e042de55ea811d3afad904cf3384f0546305baffe352` |
| `app-tcl.apk` | `0b23c49749790d578d11f62051e136bd96f7d583901d717fad2ad0ebaac73f20` |
| `flow-consumer.apk` | `b3066dde5132d2324986e39f4695caee049d194a1cc2c9e15abf3f6684b0e642` |
| `hq008-flow-sdk-v2.1.4-20261106_1429-release.aar` | `97f6005578688377ef57fb74344393df5fa635b4656dbc9165d165d4b6614efd` |

app Google 主矩阵原 APK SHA-256：`050158c07aaac9b05dd05e60ac45d608ed40159174871042e7adafc958fec200`。重入采集修正后的 APK 为上表 `app-google-trace.apk`，该轮仅测试宿主变化；随后补齐渠道报告字段并生成最终两份 app APK，见上表 app-final-google.apk / app-final-tcl.apk。完整播放矩阵在渠道字段补齐前执行；最终 APK 对正常、空素材失败和响应体取消分别复测，并核对全部阶段实际 HTTP 渠道。渠道补齐不修改播放器或补丁执行路径；未将这些 APK 称为相同二进制。Flow 有授权回调的播放用例核对 APK 自算 SHA 与上表 `flow-consumer.apk` 一致；未授权门禁用例在同一份已安装测试 APK 中运行。

可交付 Flow 包位于 `build/customer-flow-sdk-2.1.4-20261106.zip`，含冻结 AAR、两套引擎接入说明、本报告和校验文件；二合一 SDK 继续使用此前交付包。测试 APK、测试 CA/私钥、设备信息与原始日志不放入 Flow 客户包。

## 覆盖边界与环境恢复

API 24 经过配置和 Manifest 检查，本轮真机为 API 29，不声明 API 24 实机通过。生产 CMP UI/UMP、实际竞价收益与生产后端入库不由本地 fixture 证明；本轮验证流控/CMP gate 与报告 HTTP 协议闭环。

已卸载本任务测试包、移除本任务 reverse、停止本任务 TLS 服务，并恢复初始代理、sample 属性和前台页面，核验见内部 restoration.json。

完整内部证据：`/Users/zy/Documents/Codex/ad-app-research-archive/2026-11-06-app-flow-backport`，含 tools、unit-results、build-logs、binaries、fixture、fixture-durable 与逐轮 verification.json。

## 主用例索引

| 路径 | 证据轮次 | 结果 |
|---|---|---|
| app 纯 IMA | `backport-app-google-empty-inline` | 通过 |
| app 纯 IMA | `backport-app-google-original-pod` | 通过 |
| app 纯 IMA | `backport-app-google-wrapper-empty` | 通过 |
| app 纯 IMA | `backport-app-google-wrapper` | 通过 |
| app 纯 IMA | `backport-app-google-empty` | 通过 |
| app 纯 IMA | `backport-app-google-invalid` | 通过 |
| app 纯 IMA | `backport-app-google-patch-error` | 通过 |
| app 纯 IMA | `backport-app-google-media-error` | 通过 |
| app 纯 IMA | `backport-app-google-body-cancel` | 通过 |
| app 纯 IMA | `backport-app-google-playing-cancel` | 通过 |
| app 纯 IMA | `backport-app-google-long210` | 通过 |
| app 纯 IMA | `backport-app-google-completion-release` | 通过 |
| app 纯 IMA | `backport-app-google-reentrant-trace` | 通过 |
| app 纯 IMA | `backport-app-google-sound00` | 通过 |
| app 纯 IMA | `backport-app-google-sound01` | 通过 |
| app 纯 IMA | `backport-app-google-sound10` | 通过 |
| app 纯 IMA | `backport-app-google-sound11` | 通过 |
| app TCL | `backport-app-tcl-empty-inline` | 通过 |
| app TCL | `backport-app-tcl-original-pod` | 通过 |
| app TCL | `backport-app-tcl-wrapper-empty` | 通过 |
| app TCL | `backport-app-tcl-wrapper` | 通过 |
| app TCL | `backport-app-tcl-empty` | 通过 |
| app TCL | `backport-app-tcl-invalid` | 通过 |
| app TCL | `backport-app-tcl-patch-error` | 通过 |
| app TCL | `backport-app-tcl-media-error` | 通过 |
| app TCL | `backport-app-tcl-body-cancel-protocol` | 通过 |
| app TCL | `backport-app-tcl-playing-cancel-protocol` | 通过 |
| app TCL | `backport-app-tcl-long210` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-single` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-original-pod` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-inline-empty` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-wrapper-empty` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-wrapper` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-patch-error` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-body-cancel` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-stop-playing` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-restart-stop` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-restart-complete` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-replace-runtime` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-callback-timeout` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v2-google-long210` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v3-google-control_reject` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v3-google-cmp_reject` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v3-google-control_timeout` | 通过 |
| Flow SDK + 纯 IMA | `backport-flow-v3-google-authorize_timeout` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-single` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-original-pod` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-inline-empty` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-wrapper-empty` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-wrapper` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-patch-error` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-body-cancel` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-stop-playing` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-restart-stop` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-restart-complete` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-replace-runtime` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-callback-timeout` | 通过 |
| Flow SDK + TCL | `backport-flow-v2-tcl-long210` | 通过 |
| app 最终渠道回归 IMA | `backport-app-final-channel-google-normal` | 通过 |
| app 最终渠道回归 IMA | `backport-app-final-channel-google-empty` | 通过 |
| app 最终渠道回归 IMA | `backport-app-final-channel-google-body-cancel` | 通过 |
| app 最终渠道回归 TCL | `backport-app-final-channel-tcl-normal` | 通过 |
| app 最终渠道回归 TCL | `backport-app-final-channel-tcl-empty` | 通过 |
| app 最终渠道回归 TCL | `backport-app-final-channel-tcl-body-cancel` | 通过 |
