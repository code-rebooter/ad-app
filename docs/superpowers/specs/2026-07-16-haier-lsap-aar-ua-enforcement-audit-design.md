# 海尔 LSAP AAR UA 强制与全量请求审计设计

日期：2026-07-16

## 背景

`haier_lsap`、`addy_hq1002`、`addy_jams` 三个渠道已经在 `Application.attachBaseContext()` 阶段规范化 Java 系统属性 `http.agent`，并通过授权接口上报 `ua_original`、`ua_effective` 和 `webview_ua`。

静态分析 LSAP 1.1.12 AAR 后确认，实际广告请求并不总是直接使用当前系统属性。`/rtb/bid` 的请求头和 JSON `device.ua` 都通过 AAR 内部方法 `d.b.e.b.a(Context)` 取值；该方法优先返回持久化在 `lsapdata` 中的 `LSADWEBUA`，否则才返回 AAR 首次读取并缓存的系统 UA。因此，我们后台看到的 `ua_effective` 不能证明客户服务器最终收到的 UA。

AAR 还同时使用 `HttpURLConnection`、重命名后的 `com.spctv.utils.okhttp3`、普通 `okhttp3`、WebView、运行时下载的 Titan native 库和动态 Dex。单个应用层 OkHttp 拦截器不能完整覆盖这些路径。

## 目标

- 对三个 LSAP 渠道中 AAR 已知 Java 请求统一使用经过确认的合规 UA。
- 保证 `/rtb/bid` 的 `User-Agent` 请求头与 JSON `device.ua` 完全一致。
- 防止远程配置、WebView JS 或 AAR 缓存重新引入异常 `LSADWEBUA`。
- 在不关闭 Titan native 和远程 Dex 更新的前提下，记录其下载、加载和调用边界。
- 将 AAR Java 层请求的完整 URL、请求头和请求参数原始值同步到我方服务器，不进行字段脱敏。
- 记录每条请求的实际 UA、修正前 UA、AAR 缓存 UA、修正动作和可验证覆盖级别。
- 补丁必须可重复生成、可校验，并在 AAR 版本或字节码结构变化时使构建明确失败。

## 渠道范围

| Flavor | applicationId | LSAP appKey | LSAP tagId | projectId |
|---|---|---|---|---:|
| `haier_lsap` | `com.google.android.adhaierlsap` | `com.atv.chhlauncher` | `510000001301` | 205 |
| `addy_hq1002` | `com.google.android.addyhq1002` | `com.dy.chhaddyhq1002` | `510000001501` | 217 |
| `addy_jams` | `com.google.android.addyjams` | `com.dy.chhaddyjams` | `510000001401` | 218 |

其他 flavor 不使用补丁 AAR，也不启用全量 AAR 请求审计。

## 已确认的设计决策

- 使用可重复生成的补丁 AAR，不采用只依赖反射注入的方案。
- 保留原始 AAR 作为补丁输入，构建产物使用单独生成的补丁 AAR。
- 不硬关闭 Titan native。
- 不硬关闭远程 Dex 热更新。
- 请求采集不对 Cookie、Authorization、Token、签名、IMEI、IMSI、Android ID、MAC 或其他字段做脱敏。
- 采集完整请求侧数据；普通接口响应仅记录状态码、响应头和异常信息。
- Titan/Dex 更新配置响应属于动态代码加载依据，需要完整记录响应正文。
- 自有审计上报请求必须排除在采集范围之外，防止递归。

## 总体架构

### 原始 AAR 与补丁产物

三个原始 AAR 保存在不参与应用依赖解析的位置。构建工具读取原始 AAR，校验输入 SHA-256、目标类和目标方法指纹，然后生成带补丁标识的新 AAR。应用三个 flavor 只依赖生成后的补丁 AAR。

生成后的 AAR 增加补丁元数据，至少包含：

```text
patchVersion
originalAarSha256
patchedClassesJarSha256
generatedAt
targetFlavor
lsapSdkVersion=1.1.12
```

如果输入 SHA-256、类名、方法描述符或关键字节码序列与预期不一致，构建任务直接失败，不允许使用未验证补丁继续打包。

### 运行时桥接组件

应用提供公开且保留混淆名称的 Java 兼容桥接类，供补丁字节码调用。桥接组件拆分为：

- `HaierUaRuntimeGuard`：生成、校验、安装并回读合规 UA。
- `HaierAarUaStoreGuard`：读取和强制维护 `lsapdata/LSADWEBUA`。
- `HaierAarNetworkAuditBridge`：接收补丁网络出口产生的请求和响应事件。
- `HaierAarAuditUploader`：异步持久化、压缩、分片并上传审计数据。
- `HaierAarRequestContext`：关联当前广告 `request_id`、渠道和广告位。

桥接类不得依赖 AAR 的混淆类名。AAR 补丁可以依赖桥接类，从而将版本敏感性限制在补丁生成器内。

## UA 强制规则

### 系统属性

继续在 `APP.attachBaseContext()` 中、`super.attachBaseContext()` 之前安装初始合规 UA。

以下边界每次执行重新校验，不复用上一次 `ua_effective` 快照：

1. 我方 `authorize` 请求参数生成前。
2. `UnifiedAdSdk.init()` 前。
3. 每次 `UnifiedAdSdk.requestAd()` 前。
4. 补丁 AAR 每次读取 `http.agent` 时。
5. 补丁网络出口生成最终请求前。

处理顺序：

```text
读取当前 http.agent
-> 使用当前 SDK_INT 规范化
-> 必要时 System.setProperty
-> 再次读取并验证
-> 同步写入 LSADWEBUA
-> 返回本次确认后的 UA
```

### AAR UA 解析器

补丁修改 `d.b.e.b.a(Context)` 的最终返回值。无论原方法选择了 `LSADWEBUA` 还是静态缓存系统 UA，返回前都必须经过 `HaierUaRuntimeGuard.enforceResolvedUa()`。

这保证以下路径使用同一个 UA：

- `/rtb/bid` 的 `User-Agent` 请求头。
- `/rtb/bid` JSON 的 `device.ua`。
- Titan 动态库下载请求。
- WebAD、素材和监测请求中调用该解析器的路径。
- AAR 创建 WebView 时设置的 UA。

### `LSADWEBUA`

补丁拦截 AAR 对 `lsapdata` 中 `LSADWEBUA` 的写入。远程配置或 WebView JS 提供的值先经过规范化，再允许持久化。

应用注册 `SharedPreferences.OnSharedPreferenceChangeListener`。如果运行期间发现 `LSADWEBUA` 与当前合规 UA 不一致，立即记录漂移事件并写回合规值。监听器必须使用防重入标记，避免自身写回导致递归。

### WebView

补丁修改 AAR 内所有 `WebSettings.setUserAgentString()` 调用的入参，使其先经过运行时守卫。WebView JS 的 `setUserAgent()` 即使传入异常值，也不能让当前 WebView 或持久化缓存使用异常 UA。

## AAR 网络出口补丁

### 重命名 OkHttp

在 `com.spctv.utils.okhttp3` 最终网络拦截层注入：

- 请求发送前强制替换 `User-Agent` 为本次确认后的 UA。
- 在发送前读取完整 method、URL、Headers 和 Body。
- 对 `/rtb/bid` JSON 再次解析并强制替换 `device.ua`。
- 记录请求成功状态、响应 Headers、状态码、耗时和异常。
- 不消费或关闭业务响应体。

对于 one-shot、duplex 或不能安全重复读取的 Body，补丁必须在对应请求参数生产位置提前采集，不能通过重复写 Body 的方式破坏请求。

### `HttpURLConnection`

补丁覆盖 AAR 的两组集中帮助类：

- `d.b.e.h`：LSAP GET/POST，包括 IDC 和 WebAD。
- `d.a.a.d`：Hezi POST、动态任务 GET。

连接发出前显式设置合规 `User-Agent`，并向审计桥接组件提交完整 URL、Headers 和 Body。读取响应后提交状态码、响应 Headers、耗时和异常。

### 普通 OkHttp / `HttpRequester`

补丁在 AAR 的 `HttpRequester` 请求构建位置覆盖 `User-Agent` 并采集完整请求。该路径覆盖 Unified 广告配置、VAST 等普通 OkHttp 请求。

BI 或其他直接创建独立 OkHttpClient 的 AAR 类，按已识别调用点补丁。补丁只处理 AAR 目标类，不全局修改应用中其他 SDK 的 OkHttp 行为。

### WebView 网络

所有 AAR WebView 强制使用合规 UA。通过可用的 `WebViewClient.shouldInterceptRequest()` 信息记录 URL、method 和可见请求 Headers。

Android WebView 不向 `shouldInterceptRequest()` 暴露所有 POST Body，因此 WebView 内部不可见的请求体标记为 `webview_body_unavailable`，不能伪造为已完整采集。

## 请求参数全量采集

### 通用审计结构

```text
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
system_ua_before
system_ua_after
aar_cached_ua_before
aar_effective_ua
header_ua_final
parameter_ua_final
ua_drift_detected
ua_repaired
response_code
response_headers_raw
error_type
error_message
```

`headers_raw`、`query_raw` 和 `body_raw` 保留原始字段和值，不做字段脱敏。文本 Body 以原文保存；二进制 Body 使用 Base64，并记录原始字节长度和 SHA-256。

### `/rtb/bid`

每次广告请求立即生成审计事件，记录：

- 最终完整 URL。
- 完整请求 Headers。
- 完整 JSON Body。
- `device.ua` 修正前值与最终值。
- Header UA 修正前值与最终值。
- 两处最终值是否一致。
- 服务端状态码、响应 Headers 和异常。

普通 RTB 响应正文不纳入全量请求参数要求，只记录长度和 SHA-256，避免复制完整 VAST/广告响应。现有广告调试链路需要响应预览时继续使用其现有上限。

### Hezi

在加密之前采集完整原始字段串，包含两次 UA、设备标识、网络信息、屏幕信息和版本信息；同时记录最终 `p`、`uuid`、完整 Headers、配置 URL 和任务 URL。

远程返回的动态任务完整记录 `l`、`u`、`accept`、`s` 以及流量任务字段。动态任务实际发出时再次记录最终 URL、Headers 和状态。

### IDC、WebAD 与配置接口

完整记录 IDC JSON、WebAD 查询参数、远程配置查询参数和请求 Headers。远程配置响应需要完整记录，因为它可以改变 `IDC_URL`、`RES_URL`、`LSADWEBUA`、Titan、Hezi 和 Dex 更新行为。

## Titan native 观察边界

三个 AAR 中不包含 `libtitan.so`；该文件由 `TITANLIBURL_<ABI>` 运行时下载。

完整记录：

- `TITANOPEN_<appkey>`。
- ABI、下载 URL、服务端 MD5、实际 SHA-256、文件大小和保存路径。
- `token`、Base64 `tag`、`listen_port`、`quota`、workspace。
- `System.load()` 结果。
- `nativeStart(workspace, configJson)` 完整参数和返回值。
- `setNetwork()` 参数和网络变化事件。
- native 启动和停止异常。

保留下载后的 SO 文件用于离线分析，但不通过普通请求审计 JSON 上传整个二进制文件。若后续需要上传二进制，使用独立文件上传接口。

native 加载后直接通过 socket、OpenSSL 或自带网络库产生的请求不会经过 Java 网络出口。当前设计不实现 native socket/OpenSSL hook，也不承诺取得这些内部请求的目标地址、Header 或 Body；只记录 Java 侧可见的下载、加载、配置和 JNI 调用边界，并将覆盖级别标记为 `native_unverified`。

## 动态 Dex 观察边界

完整记录更新检查请求和动态配置响应：

```text
ISDK_UPDATE_URL
devid
packageName
version
channel
ret
retInfo
updateurl
md5
releaseVersion
method.classname
method.method
method.paramvalue
```

下载后记录文件路径、大小、服务端 MD5、实际 SHA-256、DexClassLoader 创建结果、反射类名、方法名、完整参数、返回值和异常。保留下载后的 Dex 文件用于离线反编译和 URL/类名扫描。

动态 Dex 自己创建的未知网络栈不保证经过补丁 AAR 的 Java 出口，覆盖级别标记为 `dynamic_dex_unverified`。如果下载的 Dex 使用已被补丁覆盖的共享网络类，则对应请求仍会正常进入审计。

## 上传策略

### 实时事件

以下事件立即入队并触发上传：

- `/rtb/bid` 请求完成或失败。
- 系统 UA 发生漂移。
- `LSADWEBUA` 发生漂移。
- UA 被重新修正。
- Titan 或 Dex 下载、加载、调用失败。

### 批量事件

其他 AAR 请求最多等待 30 秒批量上传。批次使用 gzip 压缩。单条记录超过服务端单请求限制时按字节分片，所有分片携带同一 `audit_id`、分片序号、总分片数、原始长度和 SHA-256，服务端可以无损重组；不得截断原始值。

审计事件先写入应用私有目录的持久化队列，再异步上传。广告线程、AAR 回调线程和网络拦截线程不能同步等待审计服务器响应。

上传成功后删除本地记录。失败时指数退避重试。队列写入失败、磁盘空间不足或序列化失败只能影响审计，不允许阻断广告请求；相应错误写入本地诊断日志。

### 防递归

`HaierAarAuditUploader` 使用我方现有网络层上传至 `api/v2/ad/report`。补丁出口必须通过目标 host/path、内部请求标记和线程级防重入标记排除该上传请求。审计上传本身不得产生新的 AAR 审计事件。

### `api/v2/ad/report` 上传契约

审计继续复用现有上报接口的公共字段：

```text
request_id
event_type
uuid
channel_id
ad_version
mac
app_id
make
model
message
diagnostic_info
```

新增事件类型：

```text
AAR_HTTP_AUDIT
AAR_HTTP_AUDIT_CHUNK
AAR_UA_DRIFT
AAR_UA_REPAIRED
AAR_TITAN_EVENT
AAR_DEX_EVENT
```

未分片事件的完整审计 JSON 放入 `diagnostic_info`。分片事件的 `diagnostic_info` 使用 JSON 包装 Base64 后的 gzip 数据，并包含：

```text
audit_id
chunk_index
chunk_count
original_length
original_sha256
encoding=gzip+base64
chunk_data
```

服务端按 `audit_id` 和分片序号重组，并用长度和 SHA-256 校验。应用端只负责发送和失败重试，不同步等待服务端完成重组。

## 我方授权接口字段

保留：

```text
ua_original
ua_effective
webview_ua
```

新增：

```text
ua_observed
ua_aar_cached
ua_aar_effective
ua_drift_detected
ua_aar_drift_detected
ua_repaired
ua_checked_at_ms
```

每次构建授权请求时实时采集。`webview_ua` 不再永久缓存；获取失败时上报空字符串和错误状态，但不影响授权请求。

## 关联与状态

我方开始一次广告流程时创建 `request_id` 并设置当前 `HaierAarRequestContext`。AAR 请求无论在哪个线程执行，都读取当前活动上下文并附带该 `request_id`。广告流程终止后清理上下文。

没有活动广告流程的 AAR 定时任务仍生成独立 `audit_id`，`request_id` 为空，并标记 `background=true`。

## 错误处理

- UA 规范化失败：保留当前值、记录失败原因，不构造未经配置支持的猜测 UA。
- `System.setProperty()` 后回读不一致：记录高优先级漂移事件，并继续在最终请求出口覆盖 Header 和已识别 UA 参数。
- `LSADWEBUA` 写回失败：记录错误，最终请求出口继续强制覆盖。
- 请求 Body 无法安全读取：在生产位置提前采集；仍不可见时明确记录 `body_unavailable_reason`。
- 审计桥接异常：捕获所有异常，不能向 AAR 传播。
- 补丁方法指纹不匹配：构建失败。
- 补丁 AAR 元数据缺失：三个目标 flavor 的 release 构建失败。

## 测试设计

### 补丁生成器测试

- 三个 1.1.12 原始 AAR 的输入 SHA-256 与登记值一致。
- 所有目标类和方法存在且描述符一致。
- 生成补丁后目标方法包含桥接调用。
- 非目标类字节码保持不变。
- 对错误版本 AAR 执行补丁时明确失败。
- 输出 AAR 可重复生成；排除时间戳元数据后内容确定。

### JVM/Android 单元测试

- 正常系统 UA 不被改变。
- 异常系统 UA 被修正并回读成功。
- 异常 `LSADWEBUA` 被修正。
- AAR 解析器返回值始终为本次确认 UA。
- `/rtb/bid` Header UA 和 `device.ua` 一致。
- AAR 在应用启动后修改系统 UA时，下一次关键边界可检测并修正。
- WebView UA 不复用永久缓存。
- 全量审计保留所有 Header、Query 和 Body 原始值。
- 大 Body 压缩分片后能够无损重组并通过 SHA-256 校验。
- one-shot/duplex Body 不会因审计被重复消费。
- 自有上传请求不会递归产生审计。
- 审计上传失败不影响原始广告请求结果。

### 设备验证

- 覆盖安装与卸载重装分别验证。
- 主动写入异常 `http.agent` 后触发广告请求，确认发生漂移记录并自动修正。
- 主动写入异常 `LSADWEBUA` 后触发广告请求，确认缓存、Header 和 `device.ua` 都被修正。
- 抓取 `/rtb/bid` 最终请求，逐字比较 Header UA、JSON UA 和我方审计记录。
- 验证 IDC、WebAD、远程配置、Hezi、Titan 下载和 Dex 更新的可见参数都进入审计。
- 验证广告播放、回调、音量和超时行为不因审计发生变化。

## 验收标准

- 三个目标 flavor 只能打入经过指纹校验的补丁 AAR。
- 每次 `/rtb/bid` 的 Header UA 与 `device.ua` 完全相同，且等于当前合规 UA。
- AAR 或远程页面重新写入异常 `LSADWEBUA` 后不能影响下一次广告请求。
- AAR 已知 Java 网络出口的 URL、请求 Headers 和请求参数原始值能够在我方服务器还原。
- 大请求通过压缩分片无损上传，不截断。
- 审计上报失败不阻断广告请求。
- Titan 和 Dex 保持原有启用逻辑，不被硬关闭。
- Titan native 和动态 Dex 未知内部网络明确标记为未验证，不做虚假完整性声明。
- `haier_lsap`、`addy_hq1002`、`addy_jams` 三个正式包分别完成真实设备验证。

## 非目标

- 不修改其他渠道的 UA 或网络行为。
- 不通过 MITM 解密 Titan native 或动态 Dex 的未知 HTTPS 流量。
- 不在本阶段实现 native socket/OpenSSL hook。
- 不改变 Titan、Hezi、WebAD、IDC 或动态 Dex 的服务端开关逻辑。
- 不改变广告请求、播放、回调和音量业务规则。
