
# LSAP AAR 补丁维护指南

本文档用于供应商发布新版 LSAP AAR 后，重新生成 patched AAR、重新构建 APK，并验证 UA、设备型号、网络审计和运行时动态停用能力仍然生效。

适用渠道：

- haier_lsap
- addy_hq1002
- addy_jams

当前补丁版本：lsap-full-network-audit-3
当前 SDK 版本：1.1.12

## 1. 架构概览

当前方案由“构建期字节码补丁”和“APK 运行时桥接”两部分组成：

~~~text
原始 AAR
  └─ classes.jar
       └─ ASM 修改 Java 方法调用
             ↓
patched AAR
  ├─ 修改后的 classes.jar
  ├─ 原始 so、资源和配置
  └─ META-INF/lsap-ua-audit.properties
             ↓
APK
  └─ 同时包含 HaierAarRuntimeBridge 及其修正/审计实现
~~~

重要结论：

1. 构建任务修改的是 AAR 内的 classes.jar 字节码。
2. 原始 AAR 的 so、资源和其他文件不会被删除或替换。
3. patched AAR 中的 SDK class 会调用 APK 内的 HaierAarRuntimeBridge。
4. patched AAR 不能脱离当前 APK 的桥接类单独使用。
5. Titan native、so 和远程 Dex 的 Java 入口会进入桥接层；运行时总闸关闭时这些 Java 入口会被 no-op 或直接阻断。native/Dex 内部自行创建的网络仍标记为未验证。
6. 版本名字段会和 UA 一起按 `SDK_INT` 规范化，避免 ROM 伪装导致的 `Build.VERSION.RELEASE` 误报。

## 2. 代码位置

### 构建期补丁

| 文件 | 作用 |
| --- | --- |
| buildSrc/src/main/groovy/com/smart/lsap/LsapAarPatchTask.groovy | 校验 SHA、读取 classes.jar、调用 patcher、残留检查、重新打包 |
| buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy | ASM 字节码替换规则 |
| app/build.gradle | 三个渠道的输入 AAR、SHA、输出 AAR 和依赖绑定 |

### APK 运行时

| 文件 | 作用 |
| --- | --- |
| app/src/haier_lsap/java/com/smart/android/ad_app/HaierAarRuntimeBridge.kt | AAR 调用的总桥接入口，维护运行时总闸 |
| HaierAarOkHttpAudit.kt | 标准 OkHttp 最终请求边界 |
| HaierAarUrlConnectionAudit.kt | URLConnection 请求生命周期 |
| HaierAarWebViewAudit.kt | WebView UA、URL、POST 和资源请求 |
| HaierAarUaPayloadNormalizer.kt | JSON、表单、Query 中的 UA/型号/Android 版本名 |
| app/src/hq008/java/com/smart/android/ad_app/Hq008SdkFlowControlClient.kt | 将后台 flow-control 的 enabled 结果同步给 LSAP 运行时总闸 |
| app/src/main/java/com/smart/android/ad_app/HaierUserAgentInstaller.kt | http.agent 安装和运行时复核 |
| app/src/main/java/com/smart/android/ad_app/HaierUserAgentNormalizer.kt | SDK_INT 对应的规范 UA |
| app/src/main/java/com/smart/android/ad_app/HaierDeviceModelNormalizer.kt | 通用占位型号判定 |

### 审计上报

| 文件 | 作用 |
| --- | --- |
| HaierAarNetworkAudit.kt | 单条网络事件字段 |
| HaierAarAuditUploader.kt | 按一次广告流程聚合事件 |
| Hq008ConsentLogReporter.kt | 发送一次 consent-log-report |

## 3. 当前三个原始 AAR

配置位置：app/build.gradle 的 lsapPatchedAarSpecs。

| 渠道 | 原始 AAR | 当前 SHA-256 |
| --- | --- | --- |
| haier_lsap | app/libs/haier_lsap/lsapsdk-com.google.android.adengine-270729.aar | 233ea5eea8862f71e2af086f1bd71944852941485b3319476d2df0582dc31cea |
| addy_hq1002 | app/libs/addy_hq1002/lsapsdk-combine-com.google.android.addyhq1002-1.1.12.aar | b7800b13463999ffaf7651e0c8e9c80590bed1dc644178feb774b2f5c626a78d |
| addy_jams | app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar | b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30 |

输出目录：app/build/generated/lsap-patched/<flavor>/。

SHA-256 是完整输入 AAR 的 SHA，不是 classes.jar 的 SHA。

`addy_jams` 供应商在 SDK 版本号仍为 1.1.12 时重新发布了播放器释放修订包，仓库用本地 `-r2` 后缀区分不同字节码。旧输入 `lsapsdk-combine-com.google.android.addyjams-1.1.12.aar` 保留用于回滚，其 SHA-256 为 `24e3651af8b1eb8e6f8313ad9225d6725cd64f24915430de07a89686bdeaee88`。

`haier_lsap` 供应商在 2026-07-29 提供了 `adengine-270729` AAR，仓库保留旧输入 `lsapsdk-com.google.android.adhaierlsap-1.1.12.aar` 用于回滚，其 SHA-256 为 `2c405af3f7ba41c78baa8213992040f835db998a877c83cc0be64cc648d4ee6c`。

## 4. 构建期 AAR 补丁流程

LsapAarPatchTask 的流程：

1. 读取 inputAar。
2. 计算 SHA-256，与 expectedSha256 比较。
3. SHA 不匹配立即失败，禁止对未知 SDK 直接打补丁。
4. 解压 AAR，提取 classes.jar，其他文件原样缓存。
5. 用 ASM ClassReader/ClassNode 遍历所有 class 和方法调用。
6. 调用 LsapClassPatcher.patch 修改调用点。
7. 对修改后 class 再扫描，检查关键调用是否残留。
8. 检查所有必需 networkSurface 类别。
9. 检查修改 class 数量不低于 20。
10. 重新生成排序后的 classes.jar，ZipEntry 时间统一为 0。
11. 原始 so、资源、配置等文件复制回新的 AAR。
12. 写入 META-INF/lsap-ua-audit.properties。

构建失败条件：

- 输入 SHA-256 不匹配。
- AAR 缺少 classes.jar。
- 缺少任意必需网络类别。
- 修改后仍有可识别的未补丁调用。
- 修改 class 数量少于 20。

## 5. 字节码替换点

| 原始调用或位置 | 桥接入口 | 作用 |
| --- | --- | --- |
| System.getProperty | getSystemProperty | http.agent 读取时返回有效 UA |
| System.load | systemLoad | 记录 so 加载后继续原始加载 |
| System.loadLibrary | systemLoadLibrary | 记录 library 加载后继续原始加载 |
| TitanSDK.nativeStart | nativeStart | 记录 Titan 入口后继续执行 |
| 指定动态 Dex 类中的 Method.invoke | invokeDynamicMethod | 记录动态 Dex Java 入口 |
| WebSettings.setUserAgentString | setWebViewUserAgent | 强制 WebView UA |
| WebSettings.getDefaultUserAgent | getDefaultWebViewUserAgent | 返回有效 WebView UA |
| WebSettings.getUserAgentString | getWebViewUserAgent | 返回有效 WebView UA |
| WebView.setWebViewClient | setAuditedWebViewClient | 包装 WebViewClient |
| WebView.loadUrl/postUrl | loadWebViewUrl/loadWebViewUrlWithHeaders/postWebViewUrl | 修正 URL、Header、POST Body |
| URL.openConnection | openUrlConnection/openUrlConnectionWithProxy | URLConnection 入口 |
| URLConnection 生命周期 | 对应 connect/getStream/getResponseCode/disconnect 桥接 | 请求、响应、错误审计 |
| OkHttpClient.newCall | newOkHttpCall | 创建带最终拦截器的 OkHttp |
| DatagramSocket.send | sendDatagram | 记录 UDP，不改变发送 |
| Handler.post/postDelayed | postHandler/postDelayedHandler | 运行时总闸关闭后不再入队新 Handler 任务 |
| Timer.schedule/scheduleAtFixedRate | scheduleTimer* | 运行时总闸关闭后不再入队新 Timer 任务 |
| ScheduledExecutorService.schedule/scheduleAtFixedRate/scheduleWithFixedDelay | scheduleExecutor* | 运行时总闸关闭后不再入队新 ScheduledExecutor 任务 |
| AlarmManager.set/setExact/setRepeating 等 | set*Alarm | 运行时总闸关闭后不再注册新 Alarm |
| JobScheduler.schedule | scheduleJob | 运行时总闸关闭后不再注册新 Job |
| d/b/e/n.b(String,String) | normalizeStoredValue | 修正 LSADWEBUA 等缓存值 |
| shaded OkHttp Header builder | normalizeHeaderValue | 修正 shaded 请求头 |
| shaded OkHttp 最终 chain | executeShadedRequest | shaded OkHttp 最终请求边界 |
| d/b/e/b.class UA resolver 返回点 | enforceResolvedUa | 修正 SDK 内部解析 UA |
| d/b/b/b.class RTB Body 返回点 | rewriteRtbBody | 修正 device.ua/device.model |
| d/a/a/a.class Hezi 加密输入 | captureHeziEncryptionInput | 记录加密前输入 |
| 指定配置回调 onResponse | captureAarCallbackResponse | 记录配置/动态动作回调 |

当前 SDK 1.1.12 依赖的混淆类名和 descriptor 包括：

~~~text
d/b/d/a.class
d/b/e/b.class
d/b/b/b.class
d/a/a/a.class
com/spctv/utils/okhttp3/b0/e/a.class
~~~

如果新 AAR 的混淆类名、方法 descriptor 或网络库包名变化，必须更新 LsapClassPatcher，不能只改 SHA。

## 6. 运行时 UA 和型号修正

三个渠道的 APP.attachBaseContext 阶段调用：

~~~kotlin
HaierUserAgentInstaller.installForCurrentProcess(BuildConfig.FLAVOR)
~~~

在 AAR 初始化、统一请求前、UA resolver 和最终请求边界再次执行：

~~~kotlin
HaierUserAgentInstaller.ensureEffectiveForCurrentProcess()
~~~

支持 flavor：

~~~text
haier_lsap
addy_hq1002
addy_jams
~~~

### UA 模板

异常 UA 会按 Build.VERSION.SDK_INT 生成：

~~~text
Dalvik/2.1.0 (Linux; U; Android <规范版本>; X96_NEXT Build/<规范 Build ID>)
~~~

当前 API 映射：

| SDK_INT | Android | Build ID |
| ---: | --- | --- |
| 23 | 6.0 | MRA58K |
| 24 | 7.0 | NRD90M |
| 25 | 7.1 | NDE63H |
| 26 | 8.0 | OPR6.170623.010 |
| 27 | 8.1 | OPM1.171019.011 |
| 28 | 9 | PPR1.180610.009 |
| 29 | 10 | QP1A.190711.019 |
| 30 | 11 | RP1A.200720.009 |
| 31 | 12 | SP1A.210812.015 |
| 32 | 12L | SP2A.220305.012 |
| 33 | 13 | TP1A.220624.014 |
| 34 | 14 | UP1A.231005.007 |
| 35 | 15 | AP3A.240905.015.A2 |
| 36 | 16 | BP2A.250605.031.A2 |

会触发替换：

- UA 为空或不可解析。
- UA 含控制字符或换行。
- Android 版本与 SDK_INT 不匹配。
- Build ID 与 SDK_INT 不匹配。
- UA 型号是通用占位型号。

正常 UA 保持原值。

### 通用型号规则

HaierDeviceModelNormalizer 会忽略大小写、空格、横线和其他分隔符。

会替换为 X96_NEXT：

~~~text
TV BOX
TVBOX
TV Box 4K
QTC TVBOX
Android TV
Android TV Box
Smart TV
TV Stick
TV Dongle
Set Top Box
AOSP
generic
unknown
mstar
walley
walleye
~~~

会保持不变：

~~~text
TX9 PRO
TV98 Pro
X96Q PRO
Z8 PRO
ATB-01
Sony BRAVIA
~~~

处理字段：

- UA 中的型号段。
- JSON deviceModel。
- Query device_model。
- RTB JSON device.model。

普通对象里的无关 model 字段不会全局替换。

## 7. 请求最终边界

### 标准 OkHttp

HaierAarOkHttpAudit 会在真正发送前：

1. 修正 URL Query。
2. 修正 JSON/form Body。
3. 强制设置 User-Agent。
4. 添加最终网络拦截器。
5. 记录请求。
6. 记录响应或错误。

/api/v2/ad/report 会跳过审计拦截器，避免审计递归。

### shaded spctv OkHttp

executeShadedRequest 会：

- 修正 URL Query。
- 修正 /rtb/bid Body。
- 修正 UA 和设备型号字段。
- 强制写入 User-Agent。
- 记录请求、响应和异常。
- 继续执行原始请求，不因审计失败阻塞广告。

### URLConnection

URLConnection 桥接覆盖 openConnection、setRequestProperty、addRequestProperty、connect、getOutputStream、getInputStream、getResponseCode、getErrorStream 和 disconnect。

请求头 User-Agent 会在打开和发送前再次强制设置。GET Query 会在连接打开时修正。

### WebView

WebView 桥接覆盖默认 UA、WebSettings UA、loadUrl、带 Header 的 loadUrl、postUrl 和 WebViewClient 资源请求。postUrl 的表单 Body 会被修正。

## 8. 运行时总闸和动态停用

LSAP SDK 仍然按正常流程初始化。运行中是否允许它继续产生有效行为，由 `HaierAarRuntimeBridge` 内的运行时总闸决定。

总闸状态来源：

- `Hq008SdkFlowControlClient` 请求 `api/v2/ad/sdk/flow-control` 成功且 `enabled=true`：打开总闸。
- `Hq008SdkFlowControlClient` 请求成功且 `enabled=false`：关闭总闸。
- `Hq008SdkFlowControlClient` 请求失败：沿用现有业务策略按关闭处理，也会关闭总闸。

总闸状态会写入 `lsapdata/CHIHIM_SDK_RUNTIME_ENABLED`。进程内使用 `AtomicBoolean` 保存当前状态，下一次 flow-control 成功返回 `enabled=true` 后会重新打开，因此这是可恢复的动态开关。

总闸关闭后的行为：

- OkHttp、shaded OkHttp、URLConnection、WebView、UDP 等网络出口会直接失败、返回空响应或 no-op，不再向第三方服务器发出有效请求。
- Titan `System.load`、`System.loadLibrary`、`TitanSDK.nativeStart` 和指定动态 Dex `Method.invoke` 会在 Java 入口被拦截。
- Handler、Timer、ScheduledExecutorService、AlarmManager、JobScheduler 的新调度会被 no-op 或返回已完成的占位结果。
- 已经排队或已经启动的线程不会被物理取消；这些任务醒来后只要再次经过上述出口，就会被继续拦截。
- 我方自己的 flow-control、authorize、consent-log-report、ad-report 不通过 AAR patched 出口，不会因为 LSAP 总闸关闭而被拦。

审计事件：

~~~text
AAR_SDK_GATE_CHANGED
AAR_SDK_GATE_BLOCKED
AAR_HTTP_BLOCKED
~~~

`AAR_SDK_GATE_BLOCKED` 带 60 秒限流，避免后台关闸后定时任务密集醒来导致本地审计缓存被刷爆。

## 9. 网络审计上报

单条 HaierAarAuditEvent 的主要字段：

~~~text
audit_id
request_id
channel_id
application_id
app_version_code
app_version_name
lsap_sdk_version
patch_version
timestamp_ms
duration_ms
source_stack
coverage
method
url_raw
query_raw
headers_raw
content_type
body_encoding
body_raw
body_length
body_sha256
system_ua_before
system_ua_after
aar_effective_ua
ua_drift_detected
ua_repaired
response_code
response_headers_raw
error_message
background
~~~

判断最终 UA 时优先看 headers_raw 中的 User-Agent、header_ua_final、query_raw 中的 ua/af_ua、body_raw 中的 userAgent/ua，以及 deviceModel/device_model。

aar_effective_ua 是桥接层计算的目标值，不能单独证明服务器已收到该值；最终请求头、Query、Body 才是发送边界证据。

### 一次流程只上报一次

HaierAarAuditUploader 的流程：

1. beginFlow(requestId) 开始关联一次广告流程。
2. 启动阶段事件进入待关联缓存。
3. 网络事件进入当前 flow。
4. 终态调用 appendFlowCaptureToConsentLog。
5. 组装一个 AD_SDK_HTTP_CAPTURE。
6. Hq008ConsentLogReporter 发送一次 consent-log-report。

接口：

~~~text
https://api.kytira.cc/api/v2/ad/consent-log-report
~~~

请求关键字段：

~~~text
channel_id
mac
ad_version
event_type=AD_SDK_HTTP_CAPTURE
event_message
ad_log
~~~

普通 Payload：

~~~text
ad_log.steps[].adLog.sdk_network_logs[]
~~~

Payload 超过 120000 字符时：

~~~text
adLog.encoding=gzip+base64
adLog.original_length
adLog.original_sha256
adLog.sdk_network_logs_compressed
~~~

当前限制：

- 启动阶段待关联事件最多 160 条。
- trace 最多 80 个步骤。
- 单次 trace 最大 256000 字符。
- 普通 SDK 网络 Payload 超过 120000 字符时 gzip + Base64。

## 10. 新 AAR 的重新打补丁流程

### 10.1 替换原始 AAR

新版 AAR 必须放在 app/libs/<flavor>/ 下。不要把新版 AAR 直接放进 build/generated/lsap-patched；那里是构建产物。

### 10.2 更新输入路径和 SHA

~~~bash
shasum -a 256 app/libs/<flavor>/<new-aar>.aar
~~~

把输出 SHA 更新到 app/build.gradle 对应 flavor 的 sha256，同时更新 input 文件名。

### 10.3 更新 SDK 版本元数据

如果 SDK 从 1.1.12 变更，检查并同步：

- app/build.gradle 的输入文件名。
- LsapAarPatchTask.groovy 的 lsapSdkVersion。
- HaierAarNetworkAudit.kt 的 lsap_sdk_version。
- 相关测试和交付说明。

如果三个渠道 SDK 版本不同，不要继续使用全局硬编码版本。

### 10.4 执行 patch task

~~~bash
./gradlew :app:patchHaierLsapAar
./gradlew :app:patchAddyHq1002LsapAar
./gradlew :app:patchAddyJamsLsapAar
~~~

只更新一个渠道时，只执行对应任务。

### 10.5 检查 metadata

~~~bash
for aar in app/build/generated/lsap-patched/haier_lsap/*.aar app/build/generated/lsap-patched/addy_hq1002/*.aar app/build/generated/lsap-patched/addy_jams/*.aar; do
  echo "===== $aar ====="
  unzip -p "$aar" META-INF/lsap-ua-audit.properties
done
~~~

必须确认：

- patchVersion 是 lsap-full-network-audit-3。
- originalAarSha256 等于新 AAR SHA。
- targetFlavor 正确。
- lsapSdkVersion 正确。
- modifiedClasses 不低于当前基线。
- 基础 11 个 networkSurface 类别全部存在。
- 如果当前 AAR 使用运行时调度 API，metadata 还应能看到 handlerSchedule、timerSchedule、scheduledExecutor、alarmSchedule 或 jobSchedule 等类别。

基础 11 个类别：

~~~text
systemProperty
nativeLoad
titanStart
dynamicDexInvoke
webViewUa
webViewNetwork
urlOpenConnection
urlConnectionLifecycle
okhttp3Call
udpSend
spctvOkHttpFinal
~~~

当前 `haier_lsap` `adengine-270729` AAR 的补丁输出基线：

~~~text
urlConnectionLifecycle:70
urlOpenConnection:13
okhttp3Call:5
handlerSchedule:141
jobSchedule:1
udpSend:2
webViewUa:4
systemProperty:18
scheduledExecutor:2
timerSchedule:2
spctvOkHttpFinal:1
webViewNetwork:11
dynamicDexInvoke:1
alarmSchedule:3
nativeLoad:1
titanStart:1
~~~

### 10.6 处理 patch 失败

#### SHA mismatch

说明文件内容不是配置中的 AAR。重新计算 SHA，不要删除校验。

#### missing network surface

说明新版 AAR 删除、移动或重命名了某类调用。先检查：

~~~bash
mkdir -p /tmp/lsap-aar-check
unzip -p app/libs/<flavor>/<new-aar>.aar classes.jar > /tmp/lsap-aar-check/classes.jar
jar tf /tmp/lsap-aar-check/classes.jar | sort
~~~

再用 JADX、javap 或 ASM 确认新类名、方法名和 descriptor，更新 LsapClassPatcher。

#### residual network calls remain

重点检查 owner、opcode、descriptor、shaded OkHttp 包名和混淆类名是否变化。不要删除 residual 检查来放行未知调用。

#### modified classes too few

先比较新旧 classes.jar 的 class 列表和调用面，再决定是否更新规则。

### 10.7 构建正式 APK

~~~bash
./gradlew :app:assembleHaier_lsapRelease :app:assembleAddy_hq1002Release :app:assembleAddy_jamsRelease
~~~

当前版本基线：

| 渠道 | versionCode | versionName |
| --- | ---: | --- |
| haier_lsap | 6 | 1.0.6 |
| addy_hq1002 | 4 | 1.0.4 |
| addy_jams | 8 | 1.0.8 |

新版 AAR 必须进入新 APK；只生成 patched AAR 但不重打 APK，设备不会使用新逻辑。

## 11. APK 和真机验证

用 JADX 检查 APK 内桥接类：

~~~bash
jadx --single-class com.smart.android.ad_app.HaierAarRuntimeBridge -d /tmp/jadx-check app/build/outputs/apk/<flavor>/release/<apk>.apk
~~~

至少确认：

~~~text
PATCH_VERSION = lsap-full-network-audit-3
executeShadedRequest
newOkHttpCall
openUrlConnection
updateSdkEnabled
postDelayedHandler
scheduleExecutorAtFixedRate
device model normalization
~~~

旧版 lsap-ua-audit-1 APK 不能继续交付，即使当前 generated 目录已经有新版 patched AAR。

真机建议卸载后安装，避免旧进程和旧缓存干扰：

~~~bash
adb uninstall <applicationId>
adb install <release-apk>
~~~

抓取关键日志：

~~~bash
adb logcat -c
adb logcat -v threadtime -s HaierUaNormalizer HaierAarBridge HaierAarAudit HaierAarOkHttp Hq008ConsentLog
~~~

一轮广告至少检查：

1. system_ua_before。
2. system_ua_after。
3. aar_effective_ua。
4. 最终请求头 User-Agent。
5. Query 的 ua/af_ua。
6. Body 的 userAgent/ua。
7. Body/Query 的 deviceModel/device_model。
8. 通用型号是否变成 X96_NEXT。
9. 一次流程是否只发送一次 consent-log-report。
10. 是否仍有独立 AAR /api/v2/ad/report 审计上报。

动态停用建议额外检查：

1. 后台 flow-control 返回 `enabled=false` 后，日志出现 `AAR_SDK_GATE_CHANGED enabled=false`。
2. LSAP AAR 后续请求出现 `AAR_SDK_GATE_BLOCKED` 或 `AAR_HTTP_BLOCKED`。
3. 后台 flow-control 下一次返回 `enabled=true` 后，日志出现 `AAR_SDK_GATE_CHANGED enabled=true`。
4. gate 重新打开后，新的 LSAP 网络请求和新调度可以继续执行。

## 12. 已知限制

### native 和远程 Dex

当前只包裹 native/Dex 的 Java 入口：

~~~text
coverage=native_unverified
coverage=dynamic_dex_unverified
~~~

这不等于 native so 或远程 Dex 内部每条网络请求都经过 Java 最终拦截。

### 已经排队的定时任务

运行时总闸关闭后，不会物理清理第三方 SDK 已经排队的 Handler 消息、TimerTask、Executor 任务、Alarm 或 Job。当前策略是拦截后续新调度，并在任务醒来执行网络、WebView、native 或动态 Dex 等出口时继续阻断。

### URLConnection 流式 Body

URLConnection 请求头和生命周期已覆盖，但不可重放的自定义流或 native Body 无法安全地整体读出再改写，需要结合新版字节码和真机抓包确认。

### WebView 资源 Body

WebView 资源回调一般能获得 URL、方法和请求头，但不一定能获得 Body；显式 postUrl Body 可以修正和捕获。

### 客户后台历史值

设备授权拒绝、流控关闭或没有进入广告 SDK 时，不会刷新客户侧 UA。分析异常必须结合 MAC、请求时间、应用版本和具体 URL。

## 13. 回滚

1. 保留旧原始 AAR 和 SHA。
2. 恢复 app/build.gradle 中旧 input 和 sha256。
3. 重新运行对应 patch task，不直接复用旧 generated 文件。
4. 重新构建 release APK。
5. 用 AAR metadata 和 APK 中 PATCH_VERSION 确认回滚版本。

## 14. 最短维护命令

~~~bash
# 1. 替换 app/libs/<flavor>/ 下原始 AAR
shasum -a 256 app/libs/<flavor>/<new-aar>.aar

# 2. 更新 app/build.gradle 的 input 和 sha256 后执行
./gradlew :app:patch<FlavorPatchTask>

# 3. 检查补丁元数据
unzip -p app/build/generated/lsap-patched/<flavor>/<patched-aar>.aar META-INF/lsap-ua-audit.properties

# 4. 打正式包
./gradlew :app:assemble<Flavor>Release
~~~

如果新 AAR 的混淆类名、方法 descriptor、网络库实现或动态加载方式变化，必须更新 LsapClassPatcher，并重新做静态检查和真机验证。
